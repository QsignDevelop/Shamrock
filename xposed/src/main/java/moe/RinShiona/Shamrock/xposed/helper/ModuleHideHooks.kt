package moe.RinShiona.Shamrock.xposed.helper

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.File

/**
 * Java-layer hooks that scrub Shamrock fingerprints from QQ / Dtc scans.
 * [installEarly] runs from [EarlyAntiDetection] before NtTask startup;
 * [installMapsFilter] runs from [AntiDetection] when proc hiding is enabled.
 */
internal object ModuleHideHooks {
    private const val DTC = "com.tencent.mobileqq.dt.app.Dtc"

    fun installEarly(classLoader: ClassLoader) {
        hookDtc(classLoader)
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
        ).forEach { method ->
            hookSanitizeStringReturn(dtc, method)
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

    /** Minimal file hiding before AntiDetection Action runs. */
    private fun hookSensitivePaths() {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val file = param.thisObject as? File ?: return
                val path = runCatching { file.absolutePath }.getOrNull() ?: return
                if (ModuleHide.matchesPath(path)) param.result = false
            }
        }
        listOf("exists", "canRead", "isFile", "isDirectory").forEach { name ->
            runCatching { XposedBridge.hookAllMethods(File::class.java, name, hook) }
        }
    }
}
