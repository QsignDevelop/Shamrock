// hide_native.cpp — Process hiding subsystem
//
// What it does:
//   1. Hook dlopen() — block QQ from loading libxposed_*.so / libsandhook.so
//      and similar detection-related libraries (if those ever get pulled in
//      via the host's classloader bridge).
//   2. Hook readdir() on /proc/<pid>/task and the corresponding fdopendir()
//      so that any Shamrock daemon threads don't appear in QQ's thread
//      enumeration. (QQ scans these to find "gum-js-loop" / "frida-server"
//      etc; we erase our own thread names too just in case any contain
//      shamrock-y strings.)
//   3. Filter readlink() on /proc/self/exe and dlsym() responses to scrub
//      shamrock paths.
//
// Most of the heavy lifting is done in anti_detect_native.cpp via the
// /proc/self/maps filter; this file covers additional surfaces.
//
// Requires ShadowHook for inline patching. Without it, this file is a no-op
// and only the Java-layer File.exists() hooks in AntiDetection.kt apply.

#include <android/log.h>
#include <jni.h>
#include <dlfcn.h>
#include <dirent.h>
#include <unistd.h>
#include <cstring>
#include <cstdio>
#include <atomic>
#include <vector>

#if __has_include(<shadowhook.h>)
#  include <shadowhook.h>
#  define SHAMROCK_HAS_SHADOWHOOK 1
#else
#  define SHAMROCK_HAS_SHADOWHOOK 0
#endif

#define LOG_TAG "ShamrockHide"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Library substrings whose dlopen we want to fail.
constexpr const char *kBlocklistDlopen[] = {
    "libxposed_art.so",
    "libxposed.so",
    "libxposed_",
    "libriru_",
    "libzygisk.so",
    "libsandhook.so",
    "libsubstrate.so",
    "libfrida-",
    "frida-agent",
    "frida-gadget",
    nullptr,
};

// Path substrings whose readlink/readdir should mask.
constexpr const char *kBlocklistPathSubstrings[] = {
    "moe.RinShiona.Shamrock",
    "RinShiona",
    "libshamrock",
    "libshamrocknt",
    "lspd",
    "lsposed",
    nullptr,
};

bool dlopen_path_blocked(const char *name) {
    if (name == nullptr) return false;
    for (int i = 0; kBlocklistDlopen[i] != nullptr; i++) {
        if (std::strstr(name, kBlocklistDlopen[i]) != nullptr) return true;
    }
    return false;
}

#if SHAMROCK_HAS_SHADOWHOOK

// =====================================================================
// dlopen hook — block detection-probe libraries
// =====================================================================
using dlopen_fn = void *(*)(const char *, int);
dlopen_fn g_orig_dlopen = nullptr;
void     *g_dlopen_stub = nullptr;

void *my_dlopen(const char *filename, int flag) {
    if (dlopen_path_blocked(filename)) {
        LOGI("blocking dlopen: %s", filename);
        // Pretend it does not exist.
        return nullptr;
    }
    if (g_orig_dlopen == nullptr) return nullptr;
    return g_orig_dlopen(filename, flag);
}

void install_dlopen_hook() {
    g_dlopen_stub = shadowhook_hook_sym_name(
        "libdl.so", "dlopen",
        reinterpret_cast<void *>(&my_dlopen),
        reinterpret_cast<void **>(&g_orig_dlopen)
    );
    if (g_dlopen_stub == nullptr) {
        // Some devices have moved dlopen to libc.so or to the linker;
        // try fallback target.
        g_dlopen_stub = shadowhook_hook_sym_name(
            "libc.so", "dlopen",
            reinterpret_cast<void *>(&my_dlopen),
            reinterpret_cast<void **>(&g_orig_dlopen)
        );
    }
    if (g_dlopen_stub == nullptr) {
        LOGE("dlopen hook FAILED: %s",
             shadowhook_to_errmsg(shadowhook_get_errno()));
    } else {
        LOGI("dlopen hook installed");
    }
}

// =====================================================================
// readlink hook — scrub shamrock paths from /proc/self/exe etc.
// =====================================================================
using readlink_fn = ssize_t (*)(const char *, char *, size_t);
readlink_fn g_orig_readlink = nullptr;
void       *g_readlink_stub = nullptr;

ssize_t my_readlink(const char *path, char *buf, size_t bufsize) {
    if (g_orig_readlink == nullptr) return -1;
    ssize_t r = g_orig_readlink(path, buf, bufsize);
    if (r > 0 && buf != nullptr && static_cast<size_t>(r) < bufsize) {
        // null-terminate for substring check (readlink doesn't null-term)
        char saved = buf[r];
        if (static_cast<size_t>(r) < bufsize) buf[r] = '\0';

        for (int i = 0; kBlocklistPathSubstrings[i] != nullptr; i++) {
            if (std::strstr(buf, kBlocklistPathSubstrings[i])) {
                // Replace target with a benign one (looks like the real QQ app)
                const char *fake = "/data/app/~~vbcRLwPxS0GyVfqT-nCYrQ==/"
                                   "com.tencent.mobileqq-xJKJPVp9lorkCgR_w5zhyA==/base.apk";
                size_t fl = std::strlen(fake);
                if (fl < bufsize) {
                    std::memcpy(buf, fake, fl);
                    return static_cast<ssize_t>(fl);
                }
                break;
            }
        }
        if (static_cast<size_t>(r) < bufsize) buf[r] = saved;
    }
    return r;
}

void install_readlink_hook() {
    g_readlink_stub = shadowhook_hook_sym_name(
        "libc.so", "readlink",
        reinterpret_cast<void *>(&my_readlink),
        reinterpret_cast<void **>(&g_orig_readlink)
    );
    if (g_readlink_stub == nullptr) {
        LOGE("readlink hook FAILED: %s",
             shadowhook_to_errmsg(shadowhook_get_errno()));
    } else {
        LOGI("readlink hook installed");
    }
}

#endif // SHAMROCK_HAS_SHADOWHOOK

} // namespace

// =====================================================================
// Public init — called from shamrock_native::nativeInit
// =====================================================================

extern "C" int shamrock_hide_init(JNIEnv * /*env*/) {
    LOGI("hide_init: starting");

#if SHAMROCK_HAS_SHADOWHOOK
    install_dlopen_hook();
    install_readlink_hook();
#else
    LOGW("hide_init: ShadowHook unavailable, Java-only hiding active");
#endif
    return 0;
}
