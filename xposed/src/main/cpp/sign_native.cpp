// sign_native.cpp — Native sign / energy extraction subsystem
//
// Uses runtime RegisterNatives capture (dynscan_native.cpp) to call QQ's
// real native getSign / Dandelion.energy without going through scanned
// Java trampolines.

#include <android/log.h>
#include <jni.h>
#include <dlfcn.h>
#include <unistd.h>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>

#if __has_include(<shadowhook.h>)
#  include <shadowhook.h>
#  define SHAMROCK_HAS_SHADOWHOOK 1
#else
#  define SHAMROCK_HAS_SHADOWHOOK 0
#endif

#include "shadowhook_bootstrap.h"

#define LOG_TAG "ShamrockSign"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {
    int  shamrock_dynscan_init(JNIEnv *env);
    void *shamrock_dynscan_find_fn(const char *class_substr, const char *method_name);
    int  shamrock_dynscan_binding_count();
    void shamrock_dynscan_format_status(char *buf, size_t buf_len);
}

namespace {

using GetSign_fn = jobject (*)(JNIEnv *, jobject,
                               jobject, jstring, jstring,
                               jbyteArray, jbyteArray, jstring);

// Dandelion.energy(Object, Object) -> byte[]
using Energy_fn = jbyteArray (*)(JNIEnv *, jobject, jobject, jobject);

// Dandelion.fly(String, byte[]) -> byte[]
using Fly_fn = jbyteArray (*)(JNIEnv *, jobject, jstring, jbyteArray);

std::atomic<GetSign_fn> g_orig_getSign{nullptr};
std::atomic<Energy_fn>  g_orig_energy{nullptr};
std::atomic<Fly_fn>     g_orig_fly{nullptr};

std::mutex g_cache_mutex;
jobject    g_sec_sign_singleton = nullptr;
jobject    g_dandelion_instance = nullptr;

void *find_libfekit_base() {
    FILE *fp = ::fopen("/proc/self/maps", "r");
    if (fp == nullptr) return nullptr;

    char line[1024];
    void *base = nullptr;
    while (::fgets(line, sizeof(line), fp) != nullptr) {
        if (std::strstr(line, "libfekit.so") == nullptr) continue;
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

void refresh_captured_fn_ptrs() {
    if (g_orig_getSign.load() == nullptr) {
        auto fn = reinterpret_cast<GetSign_fn>(
            shamrock_dynscan_find_fn("QQSecuritySign", "getSign"));
        if (fn != nullptr) {
            g_orig_getSign.store(fn);
            LOGI("getSign fn captured @ %p", fn);
        }
    }
    if (g_orig_energy.load() == nullptr) {
        auto fn = reinterpret_cast<Energy_fn>(
            shamrock_dynscan_find_fn("Dandelion", "energy"));
        if (fn != nullptr) {
            g_orig_energy.store(fn);
            LOGI("energy fn captured @ %p", fn);
        }
    }
    if (g_orig_fly.load() == nullptr) {
        auto fn = reinterpret_cast<Fly_fn>(
            shamrock_dynscan_find_fn("Dandelion", "fly"));
        if (fn != nullptr) {
            g_orig_fly.store(fn);
            LOGI("fly fn captured @ %p", fn);
        }
    }
}

jobject get_security_sign_singleton(JNIEnv *env) {
    std::lock_guard<std::mutex> lk(g_cache_mutex);
    if (g_sec_sign_singleton != nullptr) return g_sec_sign_singleton;

    jclass cls = env->FindClass("com/tencent/mobileqq/sign/QQSecuritySign");
    if (cls == nullptr) {
        env->ExceptionClear();
        LOGE("cannot find QQSecuritySign");
        return nullptr;
    }
    jmethodID mid = env->GetStaticMethodID(cls, "getInstance",
        "()Lcom/tencent/mobileqq/sign/QQSecuritySign;");
    if (mid == nullptr) {
        env->ExceptionClear();
        env->DeleteLocalRef(cls);
        LOGE("cannot find getInstance");
        return nullptr;
    }
    jobject local = env->CallStaticObjectMethod(cls, mid);
    env->DeleteLocalRef(cls);
    if (local == nullptr) {
        LOGE("getInstance returned null");
        return nullptr;
    }
    g_sec_sign_singleton = env->NewGlobalRef(local);
    env->DeleteLocalRef(local);
    return g_sec_sign_singleton;
}

jobject get_dandelion_instance(JNIEnv *env) {
    std::lock_guard<std::mutex> lk(g_cache_mutex);
    if (g_dandelion_instance != nullptr) return g_dandelion_instance;

    jclass cls = env->FindClass(
        "com/tencent/mobileqq/qsec/qsecdandelionsdk/Dandelion");
    if (cls == nullptr) {
        env->ExceptionClear();
        return nullptr;
    }
    jmethodID mid = env->GetStaticMethodID(cls, "getInstance",
        "()Lcom/tencent/mobileqq/qsec/qsecdandelionsdk/Dandelion;");
    if (mid == nullptr) {
        env->ExceptionClear();
        env->DeleteLocalRef(cls);
        return nullptr;
    }
    jobject local = env->CallStaticObjectMethod(cls, mid);
    env->DeleteLocalRef(cls);
    if (local == nullptr) return nullptr;
    g_dandelion_instance = env->NewGlobalRef(local);
    env->DeleteLocalRef(local);
    return g_dandelion_instance;
}

jobject invoke_getSign_reflect(JNIEnv *env, jobject thiz,
                               jstring qua, jstring cmd,
                               jbyteArray buffer, jbyteArray seq_bytes,
                               jstring uin) {
    jclass cls = env->FindClass("com/tencent/mobileqq/sign/QQSecuritySign");
    if (cls == nullptr) {
        env->ExceptionClear();
        return nullptr;
    }
    jmethodID mid = env->GetMethodID(cls, "getSign",
        "(Lcom/tencent/mobileqq/qsec/qsecurity/QSec;"
        "Ljava/lang/String;Ljava/lang/String;[B[BLjava/lang/String;)"
        "Lcom/tencent/mobileqq/sign/QQSecuritySign$SignResult;");
    env->DeleteLocalRef(cls);
    if (mid == nullptr) {
        env->ExceptionClear();
        LOGE("invoke_getSign_reflect: cannot find getSign method");
        return nullptr;
    }

    jobject result = env->CallObjectMethod(thiz, mid,
        static_cast<jobject>(nullptr),
        qua, cmd, buffer, seq_bytes, uin);

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return nullptr;
    }
    return result;
}

} // namespace

extern "C" int shamrock_sign_init(JNIEnv *env) {
    LOGI("sign_init: starting");

#if SHAMROCK_HAS_SHADOWHOOK
    shamrock_ensure_shadowhook_init();
#endif

    shamrock_dynscan_init(env);

    if (env->FindClass("com/tencent/mobileqq/sign/QQSecuritySign") != nullptr) {
        get_security_sign_singleton(env);
    } else {
        env->ExceptionClear();
        LOGW("QQSecuritySign not yet loaded — will retry on first sign call");
    }

    void *base = find_libfekit_base();
    if (base != nullptr) {
        LOGI("libfekit.so base = %p", base);
    } else {
        LOGW("libfekit.so not yet mapped");
    }

    refresh_captured_fn_ptrs();
    return 0;
}

extern "C" jobject shamrock_invoke_native_getSign(
        JNIEnv *env,
        jstring qua, jstring cmd,
        jbyteArray buffer, jbyteArray seq_bytes, jstring uin) {

    refresh_captured_fn_ptrs();

    jobject thiz = get_security_sign_singleton(env);
    if (thiz == nullptr) return nullptr;

    GetSign_fn fn = g_orig_getSign.load();
    if (fn != nullptr) {
        jobject result = fn(env, thiz,
                            static_cast<jobject>(nullptr),
                            qua, cmd, buffer, seq_bytes, uin);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            return nullptr;
        }
        return result;
    }

    return invoke_getSign_reflect(env, thiz, qua, cmd, buffer, seq_bytes, uin);
}

extern "C" jbyteArray shamrock_invoke_native_energy(
        JNIEnv *env, jstring data, jbyteArray salt) {

    refresh_captured_fn_ptrs();

    jobject thiz = get_dandelion_instance(env);
    if (thiz == nullptr) return nullptr;

    Fly_fn fly_fn = g_orig_fly.load();
    if (fly_fn != nullptr) {
        jbyteArray result = fly_fn(env, thiz, data, salt);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            return nullptr;
        }
        return result;
    }

    Energy_fn energy_fn = g_orig_energy.load();
    if (energy_fn != nullptr) {
        jbyteArray result = energy_fn(env, thiz,
                                      static_cast<jobject>(data),
                                      static_cast<jobject>(salt));
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            return nullptr;
        }
        return result;
    }

    jclass cls = env->FindClass(
        "com/tencent/mobileqq/qsec/qsecdandelionsdk/Dandelion");
    if (cls == nullptr) {
        env->ExceptionClear();
        return nullptr;
    }
    jmethodID mid = env->GetMethodID(cls, "fly",
        "(Ljava/lang/String;[B)[B");
    env->DeleteLocalRef(cls);
    if (mid == nullptr) {
        env->ExceptionClear();
        return nullptr;
    }

    jbyteArray result = static_cast<jbyteArray>(
        env->CallObjectMethod(thiz, mid, data, salt));
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return nullptr;
    }
    return result;
}

extern "C" void shamrock_sign_format_status(char *buf, size_t buf_len) {
    if (buf == nullptr || buf_len == 0) return;
    refresh_captured_fn_ptrs();
    std::snprintf(buf, buf_len,
        "getSign=%p energy=%p fly=%p",
        reinterpret_cast<void *>(g_orig_getSign.load()),
        reinterpret_cast<void *>(g_orig_energy.load()),
        reinterpret_cast<void *>(g_orig_fly.load()));
    shamrock_dynscan_format_status(buf + std::strlen(buf),
                                   buf_len - std::strlen(buf));
}
