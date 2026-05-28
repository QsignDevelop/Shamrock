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
} // namespace

// =====================================================================
// JNI methods exported by symbol name (no RegisterNatives required).
//
// We previously used JNI_OnLoad + RegisterNatives, but inside QQ the
// `ShamrockNative` Class instance reachable from FindClass (which queries
// the system ClassLoader at OnLoad time) does NOT match the class loaded
// via LSPosed's module ClassLoader, so the lookup table never applied to
// Kotlin's runtime calls. Plain symbol export sidesteps the issue.
// =====================================================================

extern "C" __attribute__((visibility("default"))) JNIEXPORT
jboolean JNICALL
Java_moe_RinShiona_Shamrock_xposed_ipc_impl_ShamrockNative_nativeInit(
        JNIEnv *env, jclass /*self*/) {
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

extern "C" __attribute__((visibility("default"))) JNIEXPORT
jstring JNICALL
Java_moe_RinShiona_Shamrock_xposed_ipc_impl_ShamrockNative_nativeCheckStatus(
        JNIEnv *env, jclass /*self*/) {
    char buf[1024];
    std::snprintf(buf, sizeof(buf),
        "Shamrock native: initialized=%s; ",
        g_initialized.load() ? "yes" : "no");
    shamrock_sign_format_status(buf + std::strlen(buf),
                                sizeof(buf) - std::strlen(buf));
    return env->NewStringUTF(buf);
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT
jobject JNICALL
Java_moe_RinShiona_Shamrock_xposed_ipc_impl_ShamrockNative_nativeGetSign(
        JNIEnv *env, jclass /*self*/,
        jstring qua, jstring cmd,
        jbyteArray buffer, jbyteArray seq_bytes, jstring uin) {
    if (!g_initialized.load()) {
        LOGE("nativeGetSign: subsystem not initialized");
        return nullptr;
    }
    return shamrock_invoke_native_getSign(env, qua, cmd, buffer, seq_bytes, uin);
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT
jbyteArray JNICALL
Java_moe_RinShiona_Shamrock_xposed_ipc_impl_ShamrockNative_nativeEnergy(
        JNIEnv *env, jclass /*self*/,
        jstring data, jbyteArray salt) {
    if (!g_initialized.load()) {
        LOGE("nativeEnergy: subsystem not initialized");
        return nullptr;
    }
    return shamrock_invoke_native_energy(env, data, salt);
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT
void JNICALL
Java_moe_RinShiona_Shamrock_xposed_ipc_impl_ShamrockNative_nativeOnLibFeKitLoaded(
        JNIEnv * /*env*/, jclass /*self*/) {
    shamrock_anti_detect_on_libfekit_loaded();
}

extern "C" __attribute__((visibility("default")))
jint JNI_OnLoad(JavaVM * /*vm*/, void * /*reserved*/) {
    LOGI("JNI_OnLoad: symbol-name binding (no RegisterNatives)");
    return JNI_VERSION_1_6;
}
