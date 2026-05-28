// shamrock_native.cpp - JNI library entry point
//
// This is loaded by Kotlin via System.loadLibrary("shamrock") on QQ process
// start (XposedEntry calls into here as early as possible).
//
// We expose three subsystems via JNI native methods registered on a single
// Kotlin object `moe.RinShiona.Shamrock.xposed.ipc.impl.ShamrockNative`:
//
//   nativeInit()        — initialize ShadowHook + all hooks
//   nativeGetSign(...)  — call FEKit native getSign directly, bypassing Java
//   nativeCheckStatus() — return diagnostic info string for debugging
//
// Each subsystem (sign / anti_detect / hide) lives in its own .cpp file.

#include <android/log.h>
#include <jni.h>
#include <atomic>
#include <cstdio>
#include <cstring>

#define LOG_TAG "ShamrockNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Forward declarations into each subsystem.
extern "C" {
    // Initialization (called once from nativeInit).
    int  shamrock_sign_init(JNIEnv *env);
    int  shamrock_anti_detect_init(JNIEnv *env);
    void shamrock_anti_detect_on_libfekit_loaded();
    int  shamrock_hide_init(JNIEnv *env);

    // JNI bridge for sign extraction.
    jobject shamrock_invoke_native_getSign(
        JNIEnv *env,
        jstring qua, jstring cmd,
        jbyteArray buffer, jbyteArray seq_bytes, jstring uin);

    jbyteArray shamrock_invoke_native_energy(
        JNIEnv *env, jstring data, jbyteArray salt);

    void shamrock_sign_format_status(char *buf, size_t buf_len);
}

namespace {

std::atomic<bool> g_initialized{false};

// --------- JNI native methods registered on ShamrockNative class ----------

jboolean JNICALL native_init(JNIEnv *env, jclass /*self*/) {
    if (g_initialized.exchange(true)) {
        LOGI("nativeInit: already initialized — noop");
        return JNI_TRUE;
    }
    LOGI("nativeInit: starting subsystems");

    int rc1 = shamrock_sign_init(env);
    int rc2 = shamrock_anti_detect_init(env);
    int rc3 = shamrock_hide_init(env);

    LOGI("nativeInit: sign=%d anti=%d hide=%d", rc1, rc2, rc3);
    return (rc1 == 0 && rc2 == 0 && rc3 == 0) ? JNI_TRUE : JNI_FALSE;
}

jstring JNICALL native_check_status(JNIEnv *env, jclass /*self*/) {
    char buf[1024];
    std::snprintf(buf, sizeof(buf),
        "Shamrock native: initialized=%s; ",
        g_initialized.load() ? "yes" : "no");
    shamrock_sign_format_status(buf + std::strlen(buf),
                                sizeof(buf) - std::strlen(buf));
    return env->NewStringUTF(buf);
}

jobject JNICALL native_get_sign(
        JNIEnv *env, jclass /*self*/,
        jstring qua, jstring cmd,
        jbyteArray buffer, jbyteArray seq_bytes, jstring uin) {
    if (!g_initialized.load()) {
        LOGE("nativeGetSign: subsystem not initialized");
        return nullptr;
    }
    return shamrock_invoke_native_getSign(env, qua, cmd, buffer, seq_bytes, uin);
}

jbyteArray JNICALL native_energy(
        JNIEnv *env, jclass /*self*/,
        jstring data, jbyteArray salt) {
    if (!g_initialized.load()) {
        LOGE("nativeEnergy: subsystem not initialized");
        return nullptr;
    }
    return shamrock_invoke_native_energy(env, data, salt);
}

void JNICALL native_on_libfekit_loaded(JNIEnv * /*env*/, jclass /*self*/) {
    shamrock_anti_detect_on_libfekit_loaded();
}

constexpr const char *kNativeClass =
    "moe/RinShiona/Shamrock/xposed/ipc/impl/ShamrockNative";

const JNINativeMethod kNativeMethods[] = {
    {"nativeInit",        "()Z",
     reinterpret_cast<void *>(native_init)},
    {"nativeCheckStatus", "()Ljava/lang/String;",
     reinterpret_cast<void *>(native_check_status)},
    {"nativeGetSign",
     "(Ljava/lang/String;Ljava/lang/String;[B[BLjava/lang/String;)"
     "Lcom/tencent/mobileqq/sign/QQSecuritySign$SignResult;",
     reinterpret_cast<void *>(native_get_sign)},
    {"nativeEnergy",
     "(Ljava/lang/String;[B)[B",
     reinterpret_cast<void *>(native_energy)},
    {"nativeOnLibFeKitLoaded", "()V",
     reinterpret_cast<void *>(native_on_libfekit_loaded)},
};

} // namespace

// =====================================================================
// JNI_OnLoad — registers native methods with the JVM.
// =====================================================================

extern "C" __attribute__((visibility("default")))
jint JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("JNI_OnLoad: GetEnv failed");
        return JNI_ERR;
    }

    jclass cls = env->FindClass(kNativeClass);
    if (cls == nullptr) {
        env->ExceptionClear();
        LOGE("JNI_OnLoad: cannot find class %s", kNativeClass);
        return JNI_ERR;
    }

    if (env->RegisterNatives(cls, kNativeMethods,
                              sizeof(kNativeMethods) / sizeof(kNativeMethods[0])) != JNI_OK) {
        LOGE("JNI_OnLoad: RegisterNatives failed");
        env->ExceptionClear();
        env->DeleteLocalRef(cls);
        return JNI_ERR;
    }
    env->DeleteLocalRef(cls);

    // Anti-detect hooks are installed from nativeInit() after shadowhook_init().

    LOGI("JNI_OnLoad: registered %zu native methods on %s",
         sizeof(kNativeMethods) / sizeof(kNativeMethods[0]), kNativeClass);

    return JNI_VERSION_1_6;
}
