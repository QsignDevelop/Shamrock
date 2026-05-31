#include "shadowhook_bootstrap.h"

#include <android/log.h>
#include <atomic>

#if __has_include(<shadowhook.h>)
#  include <shadowhook.h>
#  define SHAMROCK_HAS_SHADOWHOOK 1
#else
#  define SHAMROCK_HAS_SHADOWHOOK 0
#endif

#define LOG_TAG "ShamrockSH"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

bool shamrock_ensure_shadowhook_init() {
#if !SHAMROCK_HAS_SHADOWHOOK
    return false;
#else
    static std::atomic<bool> done{false};
    static std::atomic<bool> success{false};
    if (done.load(std::memory_order_acquire)) {
        return success.load(std::memory_order_acquire);
    }

    bool expected = false;
    if (!done.compare_exchange_strong(expected, true, std::memory_order_acq_rel)) {
        return success.load(std::memory_order_acquire);
    }

    const int rc = shadowhook_init(SHADOWHOOK_MODE_SHARED, false);
    if (rc != 0) {
        LOGE("shadowhook_init failed: %d (%s)", rc,
             shadowhook_to_errmsg(shadowhook_get_errno()));
        success.store(false, std::memory_order_release);
        return false;
    }

    LOGI("shadowhook initialized (SHARED mode)");
    success.store(true, std::memory_order_release);
    return true;
#endif
}
