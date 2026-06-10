// anti_detect_native.cpp — Native detection bypass subsystem
//
// What it hooks:
//   1. fopen("/proc/self/maps", "r") and similar — when libfekit.so or
//      libQSec.so reads the process map looking for "xposed", "lsposed",
//      "shamrock", "magisk", we return a filtered copy that hides those
//      strings.
//   2. art::ArtMethod field reads — when QQ's native code reaches into
//      ArtMethod to inspect entry points for hook trampolines, we intercept
//      the read. This is the primary "ArtTiHook" check on 9.2.90.
//   3. (optional, requires Frida/IDA work) — direct hook on the libfekit
//      probe symbol once its offset is known on the target QQ build.
//
// Important: every check below uses ShadowHook for inline patching. Without
// ShadowHook (build without prefab), we degrade gracefully — only the
// Java-layer hooks in AntiDetection.kt remain active.

#include <android/log.h>
#include <jni.h>
#include <dlfcn.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdarg.h>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <atomic>
#include <csignal>
#include <string>
#include <vector>

#if defined(__ANDROID__) && defined(__NR_memfd_create)
#include <sys/syscall.h>
#endif

#if __has_include(<shadowhook.h>)
#  include <shadowhook.h>
#  define SHAMROCK_HAS_SHADOWHOOK 1
#else
#  define SHAMROCK_HAS_SHADOWHOOK 0
#endif

#include "offsets_9290.h"   // QQ 9.2.90 libfekit (tools/static_analyze_libfekit.py)
#include "offsets_9300.h"   // QQ 9.3.0 libfekit (tools/run_analyze_930.py)
#include "shadowhook_bootstrap.h"

#define LOG_TAG "ShamrockAnti"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Substrings that, if found in a /proc/self/maps line, mean we should drop
// the entire line before returning it to the caller. This list MUST match
// the corresponding AntiDetection.kt XPOSED_KEYWORDS plus the Shamrock
// module APK path.
constexpr const char *kBlocklistMapsSubstrings[] = {
    "xposed", "Xposed", "XPOSED",
    "lsposed", "LSPosed", "LSPOSED",
    "lspd", "liblspd",
    "libriru", "riru",
    "moe.RinShiona.Shamrock",
    "moe.RinShiona.CherryPop",
    "RinShiona",
    "shamrock", "Shamrock",
    "CherryPop", "cherrypop",
    "libshamrock",
    "libshamrocknt",
    "libcherrypopnt",
    "magisk", "Magisk",
    "zygisk", "Zygisk",
    "frida", "Frida",
    "hookvip", "simpleHook",
    "/data/adb/modules",
    "/data/adb/lspd",
    "/data/misc/lspd",
    "anon:dalvik-DEX",
    "dalvik-DEX",
    "gdb-server",
    "LspModuleClassLoader",
    "InMemoryDexClassLoader",
    "de.robv.android.xposed",
    "org.lsposed",
    nullptr,
};

static bool is_proc_status_path(const char *pathname) {
    if (pathname == nullptr) return false;
    return std::strstr(pathname, "/status") != nullptr;
}

static bool is_sensitive_proc_path(const char *pathname) {
    if (pathname == nullptr) return false;
    if (std::strstr(pathname, "/proc/") == nullptr) return false;
    return std::strstr(pathname, "/maps") != nullptr ||
           std::strstr(pathname, "/mountinfo") != nullptr ||
           std::strstr(pathname, "/smaps") != nullptr ||
           std::strstr(pathname, "/cmdline") != nullptr ||
           is_proc_status_path(pathname);
}

bool line_should_drop(const char *line) {
    for (int i = 0; kBlocklistMapsSubstrings[i] != nullptr; i++) {
        if (std::strstr(line, kBlocklistMapsSubstrings[i]) != nullptr) {
            return true;
        }
    }
    return false;
}

// Filter a raw /proc/self/maps buffer by dropping any line containing
// blacklisted substrings. Returns a fresh malloc'd buffer. Caller frees.
//
// On any failure we return null and the caller should fall back to the
// original unfiltered buffer.
char *filter_maps_buffer(const char *raw, size_t raw_len) {
    char *out = static_cast<char *>(std::malloc(raw_len + 1));
    if (out == nullptr) return nullptr;
    char *out_p = out;

    const char *p = raw;
    const char *end = raw + raw_len;
    while (p < end) {
        const char *line_end = static_cast<const char *>(std::memchr(p, '\n', end - p));
        if (line_end == nullptr) line_end = end;
        size_t line_len = line_end - p;
        // line_len does not include the terminating '\n'

        // Temp-null-terminate for substring matching
        char temp[2048];
        if (line_len < sizeof(temp)) {
            std::memcpy(temp, p, line_len);
            temp[line_len] = '\0';
            if (!line_should_drop(temp)) {
                std::memcpy(out_p, p, line_len);
                out_p += line_len;
                if (line_end < end) {
                    *out_p++ = '\n';
                }
            }
        } else {
            // Pathologically long line — just copy it through.
            std::memcpy(out_p, p, line_len);
            out_p += line_len;
            if (line_end < end) {
                *out_p++ = '\n';
            }
        }

        p = line_end + (line_end < end ? 1 : 0);
    }
    *out_p = '\0';
    return out;
}

// Spoof TracerPid / non-zero tracer for libfekit tracer_pid probe (sign extra byte3/9).
static char *filter_status_buffer(const char *raw, size_t raw_len) {
    char *out = static_cast<char *>(std::malloc(raw_len + 32));
    if (out == nullptr) return nullptr;
    char *out_p = out;

    const char *p = raw;
    const char *end = raw + raw_len;
    while (p < end) {
        const char *line_end = static_cast<const char *>(std::memchr(p, '\n', end - p));
        if (line_end == nullptr) line_end = end;
        size_t line_len = line_end - p;

        const char *emit = p;
        size_t emit_len = line_len;
        char patched[256];
        if (line_len < sizeof(patched)) {
            std::memcpy(patched, p, line_len);
            patched[line_len] = '\0';
            if (std::strstr(patched, "TracerPid:") != nullptr) {
                std::snprintf(patched, sizeof(patched), "TracerPid:\t0");
                emit = patched;
                emit_len = std::strlen(patched);
            }
        }

        std::memcpy(out_p, emit, emit_len);
        out_p += emit_len;
        if (line_end < end) {
            *out_p++ = '\n';
        }
        p = line_end + (line_end < end ? 1 : 0);
    }
    *out_p = '\0';
    return out;
}

// =====================================================================
// fopen hook — the most-called path for /proc/self/maps reads.
// =====================================================================
#if SHAMROCK_HAS_SHADOWHOOK

using fopen_fn = FILE *(*)(const char *, const char *);
fopen_fn g_orig_fopen = nullptr;
void    *g_fopen_stub = nullptr;

FILE *my_fopen(const char *pathname, const char *mode) {
    if (g_orig_fopen == nullptr) return nullptr;

    // Intercept reads of /proc/self/{maps,mountinfo,smaps,cmdline}
    if (pathname == nullptr || !is_sensitive_proc_path(pathname)) {
        return g_orig_fopen(pathname, mode);
    }

    // Read the real proc file into a buffer, filter sensitive lines, return fake FILE*.
    FILE *real = g_orig_fopen(pathname, mode);
    if (real == nullptr) return nullptr;

    // Read all
    std::string buf;
    char tmp[4096];
    while (true) {
        size_t n = std::fread(tmp, 1, sizeof(tmp), real);
        if (n == 0) break;
        buf.append(tmp, n);
    }
    std::fclose(real);

    char *filtered = is_proc_status_path(pathname)
        ? filter_status_buffer(buf.data(), buf.size())
        : filter_maps_buffer(buf.data(), buf.size());
    if (filtered == nullptr) {
        // Fallback: re-open and return the raw file
        return g_orig_fopen(pathname, mode);
    }

    // Use fmemopen() to return a FILE* backed by our filtered buffer.
    // The buffer is leaked here intentionally — callers fclose() the FILE*
    // but cannot know about our backing memory; we accept a small leak
    // (the buffer is only a few KB) in exchange for simplicity.
    FILE *fake = ::fmemopen(filtered, std::strlen(filtered), "r");
    if (fake == nullptr) {
        std::free(filtered);
        return g_orig_fopen(pathname, mode);
    }
    return fake;
}

void install_fopen_hook() {
    // Hook libc.so:fopen via ShadowHook PLT-style interception.
    // We do this against libc.so because fopen is libc's; some apps
    // re-export it but the canonical implementation is libc.
    g_fopen_stub = shadowhook_hook_sym_name(
        "libc.so", "fopen",
        reinterpret_cast<void *>(&my_fopen),
        reinterpret_cast<void **>(&g_orig_fopen)
    );
    if (g_fopen_stub == nullptr) {
        LOGE("fopen hook install FAILED: %s", shadowhook_to_errmsg(shadowhook_get_errno()));
    } else {
        LOGI("fopen hook installed (stub=%p, orig=%p)", g_fopen_stub, g_orig_fopen);
    }
}

// =====================================================================
// openat hook — covers callers that open /proc/self/maps via syscalls.
// =====================================================================
//
// We intercept openat() because some detection code uses raw syscalls to
// avoid LD_PRELOAD-style hooks. The handler peeks at the path and, for
// the maps file, redirects to a tmpfile with filtered content.

using openat_fn = int (*)(int, const char *, int, ...);
openat_fn g_orig_openat = nullptr;
void     *g_openat_stub = nullptr;

static int create_anonymous_fd() {
#if defined(__ANDROID__) && defined(__NR_memfd_create)
    return static_cast<int>(syscall(__NR_memfd_create, "shamrock_maps", 0));
#else
    return -1;
#endif
}

int my_openat(int dirfd, const char *pathname, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, int);
        va_end(ap);
    }

    if (g_orig_openat == nullptr) return -1;

    if (pathname != nullptr && is_sensitive_proc_path(pathname)) {
        // Open real, read content, write filtered to memfd, return memfd.
        int real_fd = g_orig_openat(dirfd, pathname, flags, mode);
        if (real_fd < 0) return real_fd;

        std::string buf;
        char tmp[4096];
        ssize_t n;
        while ((n = ::read(real_fd, tmp, sizeof(tmp))) > 0) {
            buf.append(tmp, n);
        }
        ::close(real_fd);

        char *filtered = is_proc_status_path(pathname)
            ? filter_status_buffer(buf.data(), buf.size())
            : filter_maps_buffer(buf.data(), buf.size());
        if (filtered == nullptr) {
            return g_orig_openat(dirfd, pathname, flags, mode);
        }

        // Create memfd and write filtered content
        int mfd = create_anonymous_fd();
        if (mfd < 0) {
            std::free(filtered);
            return g_orig_openat(dirfd, pathname, flags, mode);
        }
        size_t flen = std::strlen(filtered);
        ::write(mfd, filtered, flen);
        ::lseek(mfd, 0, SEEK_SET);
        std::free(filtered);
        return mfd;
    }

    return g_orig_openat(dirfd, pathname, flags, mode);
}

void install_openat_hook() {
    g_openat_stub = shadowhook_hook_sym_name(
        "libc.so", "openat",
        reinterpret_cast<void *>(&my_openat),
        reinterpret_cast<void **>(&g_orig_openat)
    );
    if (g_openat_stub == nullptr) {
        LOGE("openat hook FAILED: %s",
             shadowhook_to_errmsg(shadowhook_get_errno()));
    } else {
        LOGI("openat hook installed");
    }
}

#endif // SHAMROCK_HAS_SHADOWHOOK

} // namespace

// =====================================================================
// Public init — called from shamrock_native::nativeInit
// =====================================================================

// =====================================================================
// libfekit.so detection-probe inline hooks
// =====================================================================
// Offsets come from `offsets_9290.h` (auto-generated by
// tools/static_analyze_libfekit.py). Each entry is the start VA of a function
// that references a "sensitive" string (/proc/self/maps, magisk, ...). The
// override below makes them all immediate-return 0 (= "no hook found").
//
// We install the hooks AFTER libfekit.so finishes loading, otherwise the
// ShadowHook trampoline target page might not be mapped yet.
#if SHAMROCK_HAS_SHADOWHOOK

constexpr int kMaxProbeHooks = 8;
void *g_probe_stubs[kMaxProbeHooks] = { nullptr };
std::atomic<bool> g_probes_installed{false};

int probe_stub_return_zero(...);

static bool insn_looks_like_code(uint32_t word) {
    return word != 0u && word != 0xffffffffu;
}

/** Pick 9.3.0 vs 9.2.90 probe table by JNI_OnLoad entry fingerprint. */
static bool fekit_profile_is_930(void *fekit_base) {
    const auto base = reinterpret_cast<uintptr_t>(fekit_base);
    const uint32_t *p930 = reinterpret_cast<const uint32_t *>(base + shamrock::offsets::v9300::kJniOnLoad);
    const uint32_t *p929 = reinterpret_cast<const uint32_t *>(base + shamrock::offsets::v9290::kJniOnLoad);
    const bool ok930 = insn_looks_like_code(p930[0]) && insn_looks_like_code(p930[1]);
    const bool ok929 = insn_looks_like_code(p929[0]) && insn_looks_like_code(p929[1]);
    if (ok930 && !ok929) return true;
    if (ok929 && !ok930) return false;
    // Ambiguous — prefer 9.3.0 on current test devices.
    return true;
}

static void install_probe_table(
    void *fekit_base,
    const uintptr_t *offsets,
    int count,
    const char *tag,
    int *hook_index
) {
    for (int i = 0; i < count; i++) {
        if (*hook_index >= kMaxProbeHooks) {
            LOGW("probe table %s: stub array full", tag);
            return;
        }
        uintptr_t va = offsets[i];
        if (va == 0) continue;
        void *target = reinterpret_cast<void *>(reinterpret_cast<uintptr_t>(fekit_base) + va);
        void *stub = shadowhook_hook_func_addr(
            target,
            reinterpret_cast<void *>(&probe_stub_return_zero),
            &g_probe_stubs[*hook_index]
        );
        if (stub == nullptr) {
            LOGE("probe[%s:%d]@0x%lx FAILED: %s",
                 tag, i, va, shadowhook_to_errmsg(shadowhook_get_errno()));
        } else {
            LOGI("probe[%s:%d]@0x%lx -> stub=%p", tag, i, va, stub);
            (*hook_index)++;
        }
    }
}

// Universal stub: always returns 0. ArtTiHook probes in libfekit.so all
// follow the contract "return 0 if no hook detected, non-zero otherwise".
// This signature works for any (...)->int probe; the va_args don't matter
// because we never touch them.
int probe_stub_return_zero(...) {
    return 0;
}

void *find_lib_base(const char *needle) {
    FILE *fp = ::fopen("/proc/self/maps", "r");
    if (fp == nullptr) return nullptr;
    char line[1024];
    void *base = nullptr;
    while (::fgets(line, sizeof(line), fp) != nullptr) {
        if (std::strstr(line, needle) == nullptr) continue;
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

void install_probe_hooks() {
    if (g_probes_installed.load()) {
        return;
    }
    void *fekit_base = find_lib_base("libfekit.so");
    if (fekit_base == nullptr) {
        LOGW("install_probe_hooks: libfekit.so not yet mapped, will retry");
        return;
    }
    LOGI("libfekit.so base = %p", fekit_base);

    int hook_index = 0;
    if (fekit_profile_is_930(fekit_base)) {
        LOGI("install_probe_hooks: profile=9.3.0");
        install_probe_table(
            fekit_base,
            shamrock::offsets::v9300::kDetectionProbeOffsets,
            shamrock::offsets::v9300::kDetectionProbeCount,
            "930",
            &hook_index
        );
    } else {
        LOGI("install_probe_hooks: profile=9.2.90");
        install_probe_table(
            fekit_base,
            shamrock::offsets::v9290::kDetectionProbeOffsets,
            shamrock::offsets::v9290::kDetectionProbeCount,
            "929",
            &hook_index
        );
    }
    if (hook_index > 0) {
        g_probes_installed.store(true);
        LOGI("install_probe_hooks: %d probe hook(s) active", hook_index);
    } else {
        LOGW("install_probe_hooks: no probe hooks installed");
    }
}

#endif // SHAMROCK_HAS_SHADOWHOOK

#if SHAMROCK_HAS_SHADOWHOOK

using exit_fn = void (*)(int);
exit_fn g_orig_exit = nullptr;
void *g_exit_stub = nullptr;

using kill_fn = int (*)(int, int);
kill_fn g_orig_kill = nullptr;
void *g_kill_stub = nullptr;

bool maps_line_matches_security_lib(const char *line) {
    if (line == nullptr) return false;
    static const char *kSecurityLibs[] = {
        "libfekit.so",
        "libQSec.so",
        "libqsec.so",
        "libbasic_share.so",
        "libkernel.so",
        "libntkernel.so",
        "libqqsec",
        "libmsfboot",
        nullptr,
    };
    for (int i = 0; kSecurityLibs[i] != nullptr; i++) {
        if (std::strstr(line, kSecurityLibs[i]) != nullptr) {
            return true;
        }
    }
    return false;
}

bool security_lib_caller(void *ret_addr) {
    if (ret_addr == nullptr) return false;
    // Walk up to 3 frames — security code often inlines exit/kill.
    void *addrs[4] = {
        ret_addr,
        __builtin_return_address(1),
        __builtin_return_address(2),
        __builtin_return_address(3),
    };
    for (void *addr : addrs) {
        if (addr == nullptr) continue;
        FILE *fp = ::fopen("/proc/self/maps", "r");
        if (fp == nullptr) continue;
        char line[1024];
        auto target = reinterpret_cast<uintptr_t>(addr);
        bool hit = false;
        while (::fgets(line, sizeof(line), fp) != nullptr) {
            if (!maps_line_matches_security_lib(line)) continue;
            uintptr_t start = 0, end = 0;
            if (std::sscanf(line, "%lx-%lx", &start, &end) == 2) {
                if (target >= start && target < end) {
                    hit = true;
                    break;
                }
            }
        }
        ::fclose(fp);
        if (hit) return true;
    }
    return false;
}

bool should_block_self_terminate(void *caller) {
    return security_lib_caller(caller);
}

void my_exit(int status) {
    void *caller = __builtin_return_address(0);
    if (should_block_self_terminate(caller)) {
        LOGW("blocked exit(%d) caller=%p", status, caller);
        return;
    }
    if (g_orig_exit != nullptr) {
        g_orig_exit(status);
    }
}

using _exit_fn = void (*)(int);
_exit_fn g_orig__exit = nullptr;
void *g__exit_stub = nullptr;

void my__exit(int status) {
    void *caller = __builtin_return_address(0);
    if (should_block_self_terminate(caller)) {
        LOGW("blocked _exit(%d) caller=%p", status, caller);
        return;
    }
    if (g_orig__exit != nullptr) {
        g_orig__exit(status);
    }
}

int my_kill(int pid, int sig) {
    if (pid == ::getpid() || pid == 0) {
        void *caller = __builtin_return_address(0);
        if (should_block_self_terminate(caller)) {
            LOGW("blocked kill(%d,%d) caller=%p", pid, sig, caller);
            return 0;
        }
    }
    return g_orig_kill != nullptr ? g_orig_kill(pid, sig) : -1;
}

using abort_fn = void (*)();
abort_fn g_orig_abort = nullptr;
void *g_abort_stub = nullptr;

void my_abort() {
    void *caller = __builtin_return_address(0);
    if (should_block_self_terminate(caller)) {
        LOGW("blocked abort() caller=%p", caller);
        return;
    }
    if (g_orig_abort != nullptr) {
        g_orig_abort();
    }
}

using raise_fn = int (*)(int);
raise_fn g_orig_raise = nullptr;
void *g_raise_stub = nullptr;

int my_raise(int sig) {
    if (sig == SIGKILL || sig == SIGABRT) {
        void *caller = __builtin_return_address(0);
        if (should_block_self_terminate(caller)) {
            LOGW("blocked raise(%d) caller=%p", sig, caller);
            return 0;
        }
    }
    return g_orig_raise != nullptr ? g_orig_raise(sig) : -1;
}

void install_exit_kill_hooks() {
    g_exit_stub = shadowhook_hook_sym_name(
        "libc.so", "exit",
        reinterpret_cast<void *>(&my_exit),
        reinterpret_cast<void **>(&g_orig_exit)
    );
    if (g_exit_stub == nullptr) {
        LOGE("exit hook FAILED: %s", shadowhook_to_errmsg(shadowhook_get_errno()));
    } else {
        LOGI("exit hook installed");
    }

    g__exit_stub = shadowhook_hook_sym_name(
        "libc.so", "_exit",
        reinterpret_cast<void *>(&my__exit),
        reinterpret_cast<void **>(&g_orig__exit)
    );
    if (g__exit_stub == nullptr) {
        LOGE("_exit hook FAILED: %s", shadowhook_to_errmsg(shadowhook_get_errno()));
    } else {
        LOGI("_exit hook installed");
    }

    g_kill_stub = shadowhook_hook_sym_name(
        "libc.so", "kill",
        reinterpret_cast<void *>(&my_kill),
        reinterpret_cast<void **>(&g_orig_kill)
    );
    if (g_kill_stub == nullptr) {
        LOGE("kill hook FAILED: %s", shadowhook_to_errmsg(shadowhook_get_errno()));
    } else {
        LOGI("kill hook installed");
    }

    g_abort_stub = shadowhook_hook_sym_name(
        "libc.so", "abort",
        reinterpret_cast<void *>(&my_abort),
        reinterpret_cast<void **>(&g_orig_abort)
    );
    if (g_abort_stub == nullptr) {
        LOGE("abort hook FAILED: %s", shadowhook_to_errmsg(shadowhook_get_errno()));
    } else {
        LOGI("abort hook installed");
    }

    g_raise_stub = shadowhook_hook_sym_name(
        "libc.so", "raise",
        reinterpret_cast<void *>(&my_raise),
        reinterpret_cast<void **>(&g_orig_raise)
    );
    if (g_raise_stub == nullptr) {
        LOGE("raise hook FAILED: %s", shadowhook_to_errmsg(shadowhook_get_errno()));
    } else {
        LOGI("raise hook installed");
    }
}

#endif // SHAMROCK_HAS_SHADOWHOOK

extern "C" int shamrock_anti_detect_init(JNIEnv * /*env*/) {
    static std::atomic<bool> g_anti_inited{false};
    if (g_anti_inited.load()) {
        return 0;
    }
    LOGI("anti_detect_init: starting");

#if SHAMROCK_HAS_SHADOWHOOK
    if (!shamrock_ensure_shadowhook_init()) {
        LOGW("anti_detect_init: ShadowHook unavailable, Java-only active");
        return 0;
    }
#endif

    if (g_anti_inited.exchange(true)) {
        return 0;
    }

#if SHAMROCK_HAS_SHADOWHOOK
    install_fopen_hook();
    install_openat_hook();
    install_exit_kill_hooks();
    // libfekit.so probe hooks. May fail this early if the .so isn't mapped
    // yet — we silently retry from sign_native.cpp once libfekit.so loads.
    install_probe_hooks();
#else
    LOGW("anti_detect_init: ShadowHook not available, only Java hooks active");
#endif

    return 0;
}

// Re-entry called from sign_native.cpp once libfekit.so is confirmed mapped.
extern "C" void shamrock_anti_detect_on_libfekit_loaded() {
#if SHAMROCK_HAS_SHADOWHOOK
    if (!shamrock_ensure_shadowhook_init()) return;
    install_probe_hooks();
#endif
}
