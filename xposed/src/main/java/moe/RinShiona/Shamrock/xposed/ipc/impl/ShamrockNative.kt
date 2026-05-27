package moe.RinShiona.Shamrock.xposed.ipc.impl

import de.robv.android.xposed.XposedBridge

/**
 * Kotlin <-> native bridge for libshamrock.so.
 *
 * Architecture
 * ============
 * Shamrock now layers two hook engines:
 *
 *  1. **Java-level Xposed hooks** (XposedHelpers / XposedBridge) handle the
 *     framework-level checks (PackageManager, SystemProperties, File.exists,
 *     etc.). See `AntiDetection.kt`.
 *
 *  2. **Native inline hooks** (ShadowHook, this file's `nativeInit()`)
 *     handle the hot-path security probes that QQ runs from inside its own
 *     libfekit.so / libQSec.so:
 *       - /proc/self/maps reads via fopen / openat
 *       - dlopen() of libxposed_* / libsandhook / libsubstrate
 *       - readlink() of /proc/self/exe (mask Shamrock APK path)
 *
 * The native side also exposes `nativeGetSign()` — a direct call into
 * QQ's native sign function, bypassing the Java-level
 * QQSecuritySign.getSign trampoline that the anti-hook scanner watches.
 *
 * Loading
 * =======
 * Called once, as early as possible during process startup, from
 * `XposedEntry.execStartupInit`. Failure to load (e.g. on x86_64 emulator
 * without prebuilt shadowhook) is non-fatal — we fall through to the
 * Java-only hook path.
 */
internal object ShamrockNative {

    /** Whether System.loadLibrary("shamrock") succeeded. */
    @JvmStatic
    var libraryLoaded: Boolean = false
        private set

    /** Whether nativeInit() reported success. */
    @JvmStatic
    var initialized: Boolean = false
        private set

    /**
     * Load and initialize the native subsystem.
     *
     * Safe to call multiple times — only the first invocation does work,
     * subsequent calls are no-ops.
     */
    @Synchronized
    fun bootstrap(): Boolean {
        if (initialized) return true
        if (!libraryLoaded) {
            try {
                // Library name "shamrocknt" must match CMakeLists.txt
                // project("shamrocknt") in xposed/src/main/cpp/.
                // The app/ module ships its own libshamrock.so for utility
                // functions (MD5, silk, CQ codec); ours is separate.
                System.loadLibrary("shamrocknt")
                libraryLoaded = true
                XposedBridge.log("[ShamrockNative] libshamrocknt.so loaded")
            } catch (e: UnsatisfiedLinkError) {
                XposedBridge.log("[ShamrockNative] libshamrocknt.so load failed: ${e.message}")
                return false
            } catch (e: Throwable) {
                XposedBridge.log("[ShamrockNative] unexpected load error: ${e.javaClass.simpleName}: ${e.message}")
                return false
            }
        }
        return try {
            initialized = nativeInit()
            XposedBridge.log("[ShamrockNative] nativeInit() => $initialized")
            initialized
        } catch (e: Throwable) {
            XposedBridge.log("[ShamrockNative] nativeInit threw: ${e.message}")
            false
        }
    }

    /**
     * Fetch a diagnostic status string from the native side. Useful for
     * debugging via the /shamrock/status HTTP endpoint.
     */
    fun status(): String {
        if (!libraryLoaded) return "library not loaded"
        return try {
            nativeCheckStatus()
        } catch (e: Throwable) {
            "native error: ${e.message}"
        }
    }

    /**
     * Native fast-path getSign. Returns a SignResult object as produced by
     * QQ's own libfekit.so getSign function — NOT a Shamrock synthesis.
     *
     * Returns null if the native fast path is unavailable (e.g. the library
     * isn't loaded, or QQ hasn't yet bound the native method). In that case
     * the caller should fall back to reflective FEKit.getSign in
     * `QSignerImpl`.
     */
    fun getSign(
        qua: String,
        cmd: String,
        buffer: ByteArray,
        seqBytes: ByteArray,
        uin: String
    ): Any? {
        if (!initialized) return null
        return try {
            nativeGetSign(qua, cmd, buffer, seqBytes, uin)
        } catch (e: Throwable) {
            XposedBridge.log("[ShamrockNative] nativeGetSign threw: ${e.message}")
            null
        }
    }

    fun energy(data: String, salt: ByteArray): ByteArray? {
        if (!initialized) return null
        return try {
            nativeEnergy(data, salt)
        } catch (e: Throwable) {
            XposedBridge.log("[ShamrockNative] nativeEnergy threw: ${e.message}")
            null
        }
    }

    // ---------- JNI declarations ----------
    // Resolved by libshamrock.so:JNI_OnLoad via RegisterNatives. The
    // signatures here MUST match shamrock_native.cpp:kNativeMethods.

    @JvmStatic external fun nativeInit(): Boolean
    @JvmStatic external fun nativeCheckStatus(): String

    @JvmStatic external fun nativeGetSign(
        qua: String,
        cmd: String,
        buffer: ByteArray,
        seqBytes: ByteArray,
        uin: String
    ): Any?

    @JvmStatic external fun nativeEnergy(
        data: String,
        salt: ByteArray
    ): ByteArray?
}
