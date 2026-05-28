// dynscan_native.cpp — Runtime native binding discovery (built into Shamrock)
//
// Hooks JNIEnv->RegisterNatives to capture libfekit.so / libQSec.so native
// method entry points as QQ registers them. No external unidbg/Frida needed.

#include <android/log.h>
#include <jni.h>
#include <dlfcn.h>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#if __has_include(<shadowhook.h>)
#  include <shadowhook.h>
#  define SHAMROCK_HAS_SHADOWHOOK 1
#else
#  define SHAMROCK_HAS_SHADOWHOOK 0
#endif

#define LOG_TAG "ShamrockDynscan"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct NativeBinding {
    std::string class_name;
    std::string method_name;
    std::string signature;
    void       *fn_ptr = nullptr;
    void       *lib_base = nullptr;
    uintptr_t   offset = 0;
};

std::mutex g_bindings_mutex;
std::vector<NativeBinding> g_bindings;
std::atomic<bool> g_hook_installed{false};
std::atomic<bool> g_table_patched{false};

using RegisterNatives_t = jint (*)(JNIEnv *, jclass, const JNINativeMethod *, jint);
RegisterNatives_t g_orig_register_natives = nullptr;

void *find_module_base(const char *name_substr) {
    FILE *fp = ::fopen("/proc/self/maps", "r");
    if (fp == nullptr) return nullptr;

    char line[1024];
    void *base = nullptr;
    while (::fgets(line, sizeof(line), fp) != nullptr) {
        if (std::strstr(line, name_substr) == nullptr) continue;
        if (std::strstr(line, "r-xp") == nullptr) continue;
        uintptr_t start = 0;
        if (std::sscanf(line, "%lx-", &start) == 1) {
            base = reinterpret_cast<void *>(start);
            break;
        }
    }
    ::fclose(fp);
    return base;
}

void *guess_lib_base(void *fn_ptr) {
    if (fn_ptr == nullptr) return nullptr;
    void *fekit = find_module_base("libfekit.so");
    if (fekit != nullptr) return fekit;
    return find_module_base("libQSec.so");
}

bool is_interesting_binding(const char *class_name, const char *method_name) {
    if (class_name == nullptr || method_name == nullptr) return false;
    if (std::strstr(class_name, "QQSecuritySign") != nullptr &&
        std::strcmp(method_name, "getSign") == 0) {
        return true;
    }
    if (std::strstr(class_name, "Dandelion") != nullptr &&
        (std::strcmp(method_name, "energy") == 0 ||
         std::strcmp(method_name, "fly") == 0)) {
        return true;
    }
    if (std::strstr(class_name, "QSec") != nullptr &&
        std::strcmp(method_name, "getXwDebugID") == 0) {
        return true;
    }
    return false;
}

void record_binding(JNIEnv *env, jclass clazz,
                    const char *method_name, const char *signature,
                    void *fn_ptr) {
    if (fn_ptr == nullptr || method_name == nullptr) return;

    jclass cls_obj = env->FindClass("java/lang/Class");
    if (cls_obj == nullptr) {
        env->ExceptionClear();
        return;
    }
    jmethodID get_name = env->GetMethodID(cls_obj, "getName", "()Ljava/lang/String;");
    env->DeleteLocalRef(cls_obj);
    if (get_name == nullptr) {
        env->ExceptionClear();
        return;
    }

    auto name_obj = static_cast<jstring>(env->CallObjectMethod(clazz, get_name));
    if (name_obj == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        return;
    }

    const char *class_name = env->GetStringUTFChars(name_obj, nullptr);
    if (class_name == nullptr) {
        env->DeleteLocalRef(name_obj);
        return;
    }

    if (!is_interesting_binding(class_name, method_name)) {
        env->ReleaseStringUTFChars(name_obj, class_name);
        env->DeleteLocalRef(name_obj);
        return;
    }

    void *lib_base = guess_lib_base(fn_ptr);
    uintptr_t offset = 0;
    if (lib_base != nullptr) {
        offset = reinterpret_cast<uintptr_t>(fn_ptr) -
                 reinterpret_cast<uintptr_t>(lib_base);
    }

    {
        std::lock_guard<std::mutex> lk(g_bindings_mutex);
        for (const auto &b : g_bindings) {
            if (b.class_name == class_name &&
                b.method_name == method_name &&
                b.signature == (signature ? signature : "")) {
                env->ReleaseStringUTFChars(name_obj, class_name);
                env->DeleteLocalRef(name_obj);
                return;
            }
        }
        g_bindings.push_back(NativeBinding{
            class_name,
            method_name,
            signature ? signature : "",
            fn_ptr,
            lib_base,
            offset
        });
    }

    LOGI("captured %s.%s%s -> %p (base=%p off=0x%lx)",
         class_name, method_name, signature ? signature : "",
         fn_ptr, lib_base, static_cast<unsigned long>(offset));

    env->ReleaseStringUTFChars(name_obj, class_name);
    env->DeleteLocalRef(name_obj);
}

jint hook_register_natives(JNIEnv *env, jclass clazz,
                           const JNINativeMethod *methods, jint n_methods) {
    if (methods != nullptr && n_methods > 0) {
        for (jint i = 0; i < n_methods; ++i) {
            record_binding(env, clazz, methods[i].name, methods[i].signature,
                           methods[i].fnPtr);
        }
    }
    return g_orig_register_natives(env, clazz, methods, n_methods);
}

/**
 * Patching the JNIEnv vtable directly was the last-resort fallback, but on
 * Android 13/14 the table memory is mapped read-only and any write triggers
 * SIGSEGV which then bubbles into QQ's CrashDefend signal handler and tears
 * down the whole process. Disabled — if the libart symbol hook fails we just
 * degrade to Java-reflection sign (still functional, just no native fast-path).
 */
bool patch_jni_register_natives(JNIEnv * /*env*/) {
    LOGW("JNIEnv table patch disabled (read-only on Android 13+)");
    g_table_patched.store(true);
    return false;
}

#if SHAMROCK_HAS_SHADOWHOOK
bool hook_art_register_natives() {
    // libart mangled names across Android versions (10..14).
    static const char *kSymbols[] = {
        "_ZN3art3JNI15RegisterNativesEP7_JNIEnvP7_jclassPK15JNINativeMethodi",
        "_ZN3art3JNI15RegisterNativesEP7_JNIEnvP7_jclassPK15JNINativeMethodib",
        // Android 13+ moved RegisterNatives onto JniRuntime / JniInternal.
        "_ZN3art10JniRuntime15RegisterNativesEP7_JNIEnvP7_jclassPK15JNINativeMethodi",
        "_ZN3art10JniRuntime15RegisterNativesEP7_JNIEnvP7_jclassPK15JNINativeMethodib",
        nullptr
    };

    for (int i = 0; kSymbols[i] != nullptr; ++i) {
        void *stub = shadowhook_hook_sym_name(
            "libart.so", kSymbols[i],
            reinterpret_cast<void *>(hook_register_natives),
            reinterpret_cast<void **>(&g_orig_register_natives));
        if (stub != nullptr) {
            LOGI("RegisterNatives hooked via libart symbol %s", kSymbols[i]);
            return true;
        }
    }
    return false;
}
#endif

} // namespace

extern "C" {

int shamrock_dynscan_init(JNIEnv * /*env*/) {
    if (g_hook_installed.exchange(true)) {
        return 0;
    }

#if SHAMROCK_HAS_SHADOWHOOK
    if (hook_art_register_natives()) {
        LOGI("dynscan initialized, waiting for libfekit RegisterNatives...");
        return 0;
    }
    LOGW("libart RegisterNatives hook unavailable on this build — "
         "sign will fall back to Java reflection");
#else
    LOGW("ShadowHook not built in — dynscan disabled");
#endif
    // Successful "degraded" path: anti-detect + hide still work.
    return 0;
}

void *shamrock_dynscan_find_fn(const char *class_substr,
                               const char *method_name) {
    if (class_substr == nullptr || method_name == nullptr) return nullptr;
    std::lock_guard<std::mutex> lk(g_bindings_mutex);
    for (const auto &b : g_bindings) {
        if (b.class_name.find(class_substr) != std::string::npos &&
            b.method_name == method_name) {
            return b.fn_ptr;
        }
    }
    return nullptr;
}

int shamrock_dynscan_binding_count() {
    std::lock_guard<std::mutex> lk(g_bindings_mutex);
    return static_cast<int>(g_bindings.size());
}

void shamrock_dynscan_format_status(char *buf, size_t buf_len) {
    if (buf == nullptr || buf_len == 0) return;
    std::lock_guard<std::mutex> lk(g_bindings_mutex);
    size_t pos = 0;
    pos += static_cast<size_t>(std::snprintf(
        buf + pos, buf_len - pos, "bindings=%zu", g_bindings.size()));
    for (const auto &b : g_bindings) {
        if (pos + 1 >= buf_len) break;
        pos += static_cast<size_t>(std::snprintf(
            buf + pos, buf_len - pos,
            ";%s.%s=0x%lx",
            b.class_name.c_str(), b.method_name.c_str(),
            static_cast<unsigned long>(b.offset)));
    }
}

} // extern "C"
