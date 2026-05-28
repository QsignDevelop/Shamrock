package moe.RinShiona.Shamrock.xposed.helper

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.io.FileInputStream
import java.util.Collections
import java.util.WeakHashMap

/**
 * Java-layer hooks that scrub Shamrock fingerprints from QQ / Dtc scans.
 * [installEarly] runs from [EarlyAntiDetection] before NtTask startup;
 * [installMapsFilter] runs from [AntiDetection] when proc hiding is enabled.
 */
internal object ModuleHideHooks {
    private const val DTC = "com.tencent.mobileqq.dt.app.Dtc"
    private val mapsStreams =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<FileInputStream, Boolean>()))

    fun installEarly(classLoader: ClassLoader) {
        hookDtc(classLoader)
        hookSensitivePaths()
    }

    fun installMapsFilter() {
        hookMapsReadFilter()
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

        // getLibraryList(String), getPluginInfo(String), getNativeLibraryDir(), getPackageName()
        listOf(
            "getLibraryList",
            "getPluginInfo",
            "getNativeLibraryDir",
            "getPackageName",
            "getPropSafe",
            "mmKVValue",
            "mmQsecKVValue",
            "systemGetSafe",
        ).forEach { method ->
            hookSanitizeStringReturn(dtc, method)
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

    private fun hookMapsReadFilter() {
        runCatching {
            XposedHelpers.findAndHookConstructor(
                FileInputStream::class.java,
                File::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val path = (param.args[0] as? File)?.absolutePath ?: return
                        if (path.contains("/proc/") && path.contains("maps")) {
                            mapsStreams.add(param.thisObject as FileInputStream)
                        }
                    }
                }
            )
        }

        val readHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val stream = param.thisObject as? FileInputStream ?: return
                if (!mapsStreams.contains(stream)) return
                val n = param.result as? Int ?: return
                if (n <= 0) return
                val buf = param.args[0] as? ByteArray ?: return
                val off = param.args[1] as? Int ?: 0
                val len = param.args[2] as? Int ?: n
                val chunk = String(buf, off, minOf(len, n), Charsets.UTF_8)
                val filtered = ModuleHide.filterSensitiveLines(chunk)
                if (filtered.length == chunk.length) return
                val out = filtered.toByteArray(Charsets.UTF_8)
                System.arraycopy(out, 0, buf, off, minOf(out.size, len))
                param.result = minOf(out.size, len)
            }
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                FileInputStream::class.java,
                "read",
                ByteArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                readHook
            )
        }
    }
}
