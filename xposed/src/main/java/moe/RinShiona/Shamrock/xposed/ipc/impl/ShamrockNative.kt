package moe.RinShiona.Shamrock.xposed.ipc.impl

import android.content.Context
import de.robv.android.xposed.XposedBridge
import java.io.File

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
        val ctx = hostCtx ?: currentHostContext() ?: run {
            XposedBridge.log("[ShamrockNative] no host context for native load")
            return false
        }
        return loadFromModuleNativeDir(ctx)
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

    /**
     * Load .so files using the LSPosed module ClassLoader (the same CL that
     * owns this ShamrockNative class), so the JVM links exported Java_* symbols
     * back to the right Class instance. The .so itself avoids libandroid.so so
     * dlopen succeeds in the com_android_art namespace.
     */
    private fun loadFromModuleNativeDir(hostCtx: Context): Boolean {
        return kotlin.runCatching {
            val moduleCtx = hostCtx.createPackageContext(
                MODULE_PKG,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            )
            val ownCl = ShamrockNative::class.java.classLoader ?: run {
                XposedBridge.log("[ShamrockNative] no own ClassLoader for native libs")
                return false
            }
            val dir = moduleCtx.applicationInfo.nativeLibraryDir
            XposedBridge.log("[ShamrockNative] module nativeLibraryDir=$dir")
            val shadow = resolveNativeSo(moduleCtx, dir, LIB_SHADOW) ?: run {
                XposedBridge.log("[ShamrockNative] libshadowhook.so not found anywhere")
                return false
            }
            val nt = resolveNativeSo(moduleCtx, dir, LIB_NT) ?: run {
                XposedBridge.log("[ShamrockNative] libshamrocknt.so not found anywhere")
                return false
            }
            val nativeLoad = resolveNativeLoad()
            if (nativeLoad == null) {
                XposedBridge.log("[ShamrockNative] Runtime.nativeLoad missing — using System.load")
                System.load(shadow.absolutePath)
                System.load(nt.absolutePath)
            } else {
                loadWithModuleClassloader(nativeLoad, ownCl, shadow.absolutePath)
                loadWithModuleClassloader(nativeLoad, ownCl, nt.absolutePath)
            }
            XposedBridge.log("[ShamrockNative] loaded ${shadow.absolutePath} + ${nt.absolutePath} via own CL")
            true
        }.getOrElse {
            XposedBridge.log("[ShamrockNative] loadFromModuleNativeDir failed: ${it.message}")
            false
        }
    }

    /**
     * Look for a .so in the package nativeLibraryDir first, then fall back to
     * scanning the installed APK's lib/<abi>/ entries (handy when AGP packaged
     * the lib only for a specific ABI). Returns the absolute file we can dlopen.
     */
    private fun resolveNativeSo(
        moduleCtx: Context,
        nativeLibraryDir: String?,
        libName: String,
    ): File? {
        nativeLibraryDir?.let { dir ->
            val direct = File(dir, "lib$libName.so")
            if (direct.exists() && direct.length() > 0L) return direct
        }
        val abiList = listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86")
        val apkDir = File(moduleCtx.applicationInfo.sourceDir).parentFile ?: return null
        for (abi in abiList) {
            val candidate = File(apkDir, "lib/$abi/lib$libName.so")
            if (candidate.exists() && candidate.length() > 0L) return candidate
        }
        return null
    }

    private fun resolveNativeLoad(): java.lang.reflect.Method? {
        return kotlin.runCatching {
            val m = Runtime::class.java.getDeclaredMethod(
                "nativeLoad", String::class.java, ClassLoader::class.java
            )
            m.isAccessible = true
            m
        }.getOrNull()
    }

    private fun loadWithModuleClassloader(
        nativeLoad: java.lang.reflect.Method,
        moduleCl: ClassLoader,
        path: String,
    ) {
        val ret = nativeLoad.invoke(null, path, moduleCl)
        if (ret is String && ret.isNotEmpty()) {
            throw UnsatisfiedLinkError("dlopen failed for $path: $ret")
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
