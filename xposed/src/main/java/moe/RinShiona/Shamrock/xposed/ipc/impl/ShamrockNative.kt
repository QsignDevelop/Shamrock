package moe.RinShiona.Shamrock.xposed.ipc.impl

import android.content.Context
import android.os.Build
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.util.zip.ZipFile

/**
 * Kotlin <-> native bridge for libshamrocknt.so.
 *
 * libshamrocknt depends on libshadowhook.so. When injected into QQ we must load
 * shadowhook first (from the module APK / nativeLibraryDir), never a lone
 * libshamrocknt.so extracted into QQ cache.
 */
internal object ShamrockNative {

    private const val LIB_NT = "shamrocknt"
    private const val LIB_SHADOW = "shadowhook"
    private const val MODULE_PKG = "moe.RinShiona.Shamrock"

    @JvmStatic
    var libraryLoaded: Boolean = false
        private set

    @JvmStatic
    var initialized: Boolean = false
        private set

    @Synchronized
    fun bootstrap(hostCtx: Context? = null): Boolean {
        if (initialized) return true
        if (!libraryLoaded) {
            libraryLoaded = loadNativeLibraries(hostCtx)
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

    private fun loadNativeLibraries(hostCtx: Context?): Boolean {
        val ctx = hostCtx ?: currentHostContext()
        if (ctx != null && loadFromModuleNativeDir(ctx)) return true
        resolveModuleApkPath()?.let { apk ->
            if (ctx != null && extractAndLoadPair(apk, ctx)) return true
        }
        XposedBridge.log("[ShamrockNative] all load paths failed")
        return false
    }

    private fun currentHostContext(): Context? {
        return kotlin.runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as Context
        }.getOrElse {
            kotlin.runCatching {
                Class.forName("mqq.app.MobileQQ")
                    .getMethod("getContext")
                    .invoke(null) as Context
            }.getOrNull()
        }
    }

    /** Preferred path: both .so files from Shamrock module nativeLibraryDir. */
    private fun loadFromModuleNativeDir(hostCtx: Context): Boolean {
        return kotlin.runCatching {
            val ai = hostCtx.packageManager.getApplicationInfo(MODULE_PKG, 0)
            val dir = ai.nativeLibraryDir ?: return false
            val shadow = File(dir, "lib$LIB_SHADOW.so")
            val nt = File(dir, "lib$LIB_NT.so")
            if (!shadow.exists() || !nt.exists()) {
                XposedBridge.log("[ShamrockNative] module native dir missing so: shadow=${shadow.exists()} nt=${nt.exists()}")
                return false
            }
            System.load(shadow.absolutePath)
            XposedBridge.log("[ShamrockNative] loaded $shadow")
            System.load(nt.absolutePath)
            XposedBridge.log("[ShamrockNative] loaded $nt")
            true
        }.getOrElse {
            XposedBridge.log("[ShamrockNative] loadFromModuleNativeDir failed: ${it.message}")
            false
        }
    }

    private fun extractAndLoadPair(apkPath: String, hostCtx: Context): Boolean {
        return kotlin.runCatching {
            ZipFile(apkPath).use { zip ->
                val abi = Build.SUPPORTED_ABIS.firstOrNull { candidate ->
                    zip.getEntry("lib/$candidate/lib$LIB_NT.so") != null &&
                        zip.getEntry("lib/$candidate/lib$LIB_SHADOW.so") != null
                } ?: return false

                val cacheRoot = File(hostCtx.cacheDir, "shamrocknt").apply { mkdirs() }
                val shadowOut = File(cacheRoot, "lib$LIB_SHADOW.so")
                val ntOut = File(cacheRoot, "lib$LIB_NT.so")

                fun extract(entryName: String, out: File) {
                    if (!out.exists() || out.length() <= 0L) {
                        zip.getInputStream(zip.getEntry(entryName)!!).use { input ->
                            out.outputStream().use { output -> input.copyTo(output) }
                        }
                        out.setReadable(true, false)
                        out.setExecutable(true, false)
                    }
                }

                extract("lib/$abi/lib$LIB_SHADOW.so", shadowOut)
                extract("lib/$abi/lib$LIB_NT.so", ntOut)

                System.load(shadowOut.absolutePath)
                XposedBridge.log("[ShamrockNative] loaded extracted $shadowOut")
                System.load(ntOut.absolutePath)
                XposedBridge.log("[ShamrockNative] loaded extracted $ntOut")
                true
            }
        }.getOrElse {
            XposedBridge.log("[ShamrockNative] extractAndLoadPair failed: ${it.message}")
            false
        }
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
