package moe.RinShiona.Shamrock.xposed.ipc.impl

import android.content.Context
import android.os.Build
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.util.zip.ZipFile

/**
 * Kotlin <-> native bridge for libshamrocknt.so.
 *
 * LSPosed loads module code via LspModuleClassLoader; on some ROMs
 * System.loadLibrary("shamrocknt") cannot see libs inside the module APK.
 * We fall back to explicit paths / zip extraction (same pattern as NativeLoader).
 */
internal object ShamrockNative {

    private const val LIB_NAME = "shamrocknt"

    /** Whether libshamrocknt.so was loaded successfully. */
    @JvmStatic
    var libraryLoaded: Boolean = false
        private set

    /** Whether nativeInit() reported success. */
    @JvmStatic
    var initialized: Boolean = false
        private set

    @Synchronized
    fun bootstrap(hostCtx: Context? = null): Boolean {
        if (initialized) return true
        if (!libraryLoaded) {
            libraryLoaded = loadNativeLibrary(hostCtx)
            if (!libraryLoaded) return false
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

    private fun loadNativeLibrary(hostCtx: Context?): Boolean {
        try {
            System.loadLibrary(LIB_NAME)
            XposedBridge.log("[ShamrockNative] libshamrocknt.so loaded via loadLibrary")
            return true
        } catch (e: UnsatisfiedLinkError) {
            XposedBridge.log("[ShamrockNative] loadLibrary failed: ${e.message}")
        }

        val ctx = hostCtx ?: kotlin.runCatching {
            Class.forName("mqq.app.MobileQQ")
                .getMethod("getContext")
                .invoke(null) as Context
        }.getOrNull()

        if (ctx != null) {
            kotlin.runCatching {
                val ai = ctx.packageManager.getApplicationInfo("moe.RinShiona.Shamrock", 0)
                val fromNativeDir = File(ai.nativeLibraryDir, "lib$LIB_NAME.so")
                if (fromNativeDir.exists()) {
                    System.load(fromNativeDir.absolutePath)
                    XposedBridge.log("[ShamrockNative] loaded from nativeLibraryDir: $fromNativeDir")
                    return true
                }
                if (extractAndLoad(ai.sourceDir, ctx)) return true
            }.onFailure {
                XposedBridge.log("[ShamrockNative] ApplicationInfo load failed: ${it.message}")
            }
        }

        resolveModuleApkPath()?.let { apk ->
            if (extractAndLoad(apk, ctx)) return true
        }

        XposedBridge.log("[ShamrockNative] all load paths failed for lib$LIB_NAME.so")
        return false
    }

    private fun resolveModuleApkPath(): String? {
        val cl = ShamrockNative::class.java.classLoader ?: return null
        for (fieldName in listOf("apk", "modulePath", "moduleApkPath")) {
            kotlin.runCatching {
                val field = cl.javaClass.getDeclaredField(fieldName)
                field.isAccessible = true
                (field.get(cl) as? String)?.takeIf { it.endsWith(".apk") }?.let { return it }
            }
        }
        Regex("module=([^,\\]]+)").find(cl.toString())?.groupValues?.get(1)?.trim()?.let {
            if (it.endsWith(".apk")) return it
        }
        return null
    }

    private fun extractAndLoad(apkPath: String, hostCtx: Context?): Boolean {
        return kotlin.runCatching {
            ZipFile(apkPath).use { zip ->
                val abi = Build.SUPPORTED_ABIS.firstOrNull { candidate ->
                    zip.getEntry("lib/$candidate/lib$LIB_NAME.so") != null
                } ?: return false
                val entryName = "lib/$abi/lib$LIB_NAME.so"
                val cacheRoot = hostCtx?.cacheDir
                    ?: File("/data/data/com.tencent.mobileqq/cache")
                val out = File(cacheRoot, "shamrocknt/lib$LIB_NAME.so").apply {
                    parentFile?.mkdirs()
                }
                if (!out.exists() || out.length() <= 0L) {
                    zip.getInputStream(zip.getEntry(entryName)!!).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    out.setReadable(true, false)
                    out.setExecutable(true, false)
                }
                System.load(out.absolutePath)
                XposedBridge.log("[ShamrockNative] loaded from extracted $out (apk=$apkPath)")
                true
            }
        }.getOrElse {
            XposedBridge.log("[ShamrockNative] extractAndLoad failed: ${it.message}")
            false
        }
    }

    fun status(): String {
        if (!libraryLoaded) return "library not loaded"
        return try {
            nativeCheckStatus()
        } catch (e: Throwable) {
            "native error: ${e.message}"
        }
    }

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

    fun onLibFeKitLoaded() {
        if (!libraryLoaded) return
        runCatching {
            nativeOnLibFeKitLoaded()
            XposedBridge.log("[ShamrockNative] libfekit probe hooks refreshed")
        }.onFailure {
            XposedBridge.log("[ShamrockNative] onLibFeKitLoaded failed: ${it.message}")
        }
    }

    @JvmStatic external fun nativeInit(): Boolean
    @JvmStatic external fun nativeOnLibFeKitLoaded()
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
