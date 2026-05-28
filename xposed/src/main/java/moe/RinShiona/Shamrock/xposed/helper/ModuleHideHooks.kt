package moe.RinShiona.Shamrock.xposed.helper

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Java-layer hooks that scrub Shamrock fingerprints from QQ / Dtc scans.
 * [installEarly] runs from [EarlyAntiDetection] before NtTask startup;
 * [installMapsFilter] runs from [AntiDetection] when proc hiding is enabled.
 */
internal object ModuleHideHooks {
    private const val DTC = "com.tencent.mobileqq.dt.app.Dtc"
    private val earlyInstalled = AtomicBoolean(false)
    private val fileHideInstalled = AtomicBoolean(false)

    fun installEarly(classLoader: ClassLoader) {
        if (!earlyInstalled.compareAndSet(false, true)) return
        installFileHideOnly()
        hookDtc(classLoader)
        hookContentProviderQueries()
    }

    /** File hiding only — safe before Application context exists. */
    fun installFileHideOnly() {
        if (!fileHideInstalled.compareAndSet(false, true)) return
        hookSensitivePaths()
    }

    fun installMapsFilter() {
        // Native openat/maps filter in libshamrocknt handles the hot path.
        // Java partial-read filtering on /proc/self/maps can corrupt buffers → disabled.
    }

    private fun hookDtc(classLoader: ClassLoader) {
        val dtc = runCatching { classLoader.loadClass(DTC) }.getOrNull() ?: return

        // checkAppInstalled(String): boolean
        hookStringArg(dtc, "checkAppInstalled") { pkg ->
            if (ModuleHide.matchesPackage(pkg)) false else null
        }

        // getApkPath(String): String
        hookAfterStringMethods(dtc, "getApkPath") { arg, result ->
            when {
                ModuleHide.matchesPackage(arg) -> ""
                else -> ModuleHide.sanitizeValue(result) ?: ""
            }
        }

        // getLibraryList(String), getPluginInfo(String) — scrub module libs only
        listOf(
            "getLibraryList",
            "getPluginInfo",
            "getPropSafe",
            "mmKVValue",
            "mmQsecKVValue",
            "systemGetSafe",
            "dtcProcessCall",
        ).forEach { method ->
            hookSanitizeStringReturn(dtc, method)
        }

        // dtcBL(byte[]) -> String[] blacklist from QSec channel
        runCatching {
            XposedBridge.hookAllMethods(dtc, "dtcBL", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    when (val r = param.result) {
                        is Array<*> -> param.result = r.filter { item ->
                            item !is String || !ModuleHide.lineContainsSensitive(item)
                        }.toTypedArray()
                        is String -> param.result = ModuleHide.sanitizeValue(r) ?: r
                    }
                }
            })
        }

        runCatching {
            XposedBridge.hookAllMethods(dtc, "isAbnormalConfig", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = false
                }
            })
        }

        runCatching {
            XposedBridge.hookAllMethods(dtc, "mmQsecKVValueBytes", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val bytes = param.result as? ByteArray ?: return
                    val text = runCatching { String(bytes) }.getOrNull() ?: return
                    if (ModuleHide.lineContainsSensitive(text)) {
                        param.result = ByteArray(0)
                    }
                }
            })
        }

        // getNativeLibraryDir(): only blank if it actually references our module
        runCatching {
            XposedBridge.hookAllMethods(dtc, "getNativeLibraryDir", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = ModuleHide.sanitizeValue(param.result as? String)
                }
            })
        }

        // mmkvQsecAllKeys(String) — comma-separated key list
        runCatching {
            XposedBridge.hookAllMethods(dtc, "mmkvQsecAllKeys", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val raw = param.result as? String ?: return
                    param.result = raw.split(',')
                        .filter { key -> !ModuleHide.lineContainsSensitive(key) }
                        .joinToString(",")
                }
            })
        }
    }

    private fun hookStringArg(
        cls: Class<*>,
        method: String,
        block: (String) -> Any?
    ) {
        runCatching {
            XposedBridge.hookAllMethods(cls, method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val arg = param.args.firstOrNull() as? String ?: return
                    block(arg)?.let { param.result = it }
                }
            })
        }
    }

    private fun hookAfterStringMethods(
        cls: Class<*>,
        method: String,
        block: (arg: String?, result: String?) -> String
    ) {
        runCatching {
            XposedBridge.hookAllMethods(cls, method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val arg = param.args.firstOrNull() as? String
                    val result = param.result as? String
                    param.result = block(arg, result)
                }
            })
        }
    }

    private fun hookSanitizeStringReturn(cls: Class<*>, method: String) {
        runCatching {
            XposedBridge.hookAllMethods(cls, method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    when (val r = param.result) {
                        is String -> param.result = ModuleHide.filterSensitiveLines(
                            ModuleHide.sanitizeValue(r) ?: r
                        )
                        is Array<*> -> {
                            param.result = r.filter { item ->
                                item !is String || !ModuleHide.lineContainsSensitive(item)
                            }.toTypedArray()
                        }
                    }
                }
            })
        }
    }

    /** Hide Shamrock ContentProvider from QSec package/content scans. */
    private fun hookContentProviderQueries() {
        runCatching {
            val cr = Class.forName("android.content.ContentResolver")
            XposedBridge.hookAllMethods(cr, "query", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ModuleHide.isSecurityScannerCaller()) return
                    val uri = param.args.getOrNull(0) ?: return
                    val uriStr = uri.toString()
                    if (ModuleHide.lineContainsSensitive(uriStr)) {
                        param.result = null
                    }
                }
            })
            XposedBridge.hookAllMethods(cr, "call", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ModuleHide.isSecurityScannerCaller()) return
                    val uri = param.args.getOrNull(0) ?: return
                    if (ModuleHide.lineContainsSensitive(uri.toString())) {
                        param.result = null
                    }
                }
            })
        }
    }

    /**
     * Hide Shamrock files from QQ scanners (Dtc / QSec / Pandora) only.
     * Do NOT touch File.exists() globally — that breaks our own native loader
     * which must stat /data/app/<shamrock>/lib/<abi>/lib*.so.
     */
    private fun hookSensitivePaths() {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val file = param.thisObject as? File ?: return
                val path = runCatching { file.absolutePath }.getOrNull() ?: return
                if (!ModuleHide.matchesPath(path)) return
                if (!ModuleHide.isSecurityScannerCaller()) return
                param.result = false
            }
        }
        listOf("exists", "canRead", "isFile", "isDirectory").forEach { name ->
            runCatching { XposedBridge.hookAllMethods(File::class.java, name, hook) }
        }
    }
}
