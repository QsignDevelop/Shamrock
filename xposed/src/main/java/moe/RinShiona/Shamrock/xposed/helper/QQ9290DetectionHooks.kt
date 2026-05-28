package moe.RinShiona.Shamrock.xposed.helper

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicBoolean

/**
 * QQ 9.2.90 NT detection bypass — method signatures from APK reverse (androguard).
 *
 * Sources: 9.2.90_rev/detection_scan.txt, key_classes_methods.txt, libfekit_offsets.json
 *
 * ColdStartupTask enum includes ArtTiHookTask / GuardInitTask / CodeCheckTask; the scan
 * itself runs in libfekit.so (native probes) plus Java Dtc / QSec / Pandora monitors.
 */
internal object QQ9290DetectionHooks {
    private const val DTC = "com.tencent.mobileqq.dt.app.Dtc"
    private const val QSEC = "com.tencent.mobileqq.qsec.qsecurity.QSec"
    private const val RUNTIME_MONITOR = "com.tencent.qmethod.pandoraex.monitor.RuntimeMonitor"

    private val installed = AtomicBoolean(false)

    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        hookDtc(classLoader)
        hookQSec(classLoader)
        hookRuntimeMonitorExec(classLoader)
        log("QQ 9.2.90 detection hooks installed")
    }

    /** Dtc — device fingerprint + installed-app / library probes (DEX-verified). */
    private fun hookDtc(classLoader: ClassLoader) {
        val dtc = runCatching { classLoader.loadClass(DTC) }.getOrNull() ?: return

        runCatching {
            XposedHelpers.findAndHookMethod(
                dtc, "isAbnormalConfig",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = false
                }
            )
        }

        runCatching {
            XposedBridge.hookAllMethods(dtc, "checkAppInstalled", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val pkg = param.args.firstOrNull() as? String ?: return
                    if (ModuleHide.matchesPackage(pkg)) param.result = false
                }
            })
        }

        // Private in APK; hookAllMethods still binds them.
        listOf(
            "getAccessibilityEnabledServiceList",
            "getAccessibilityServiceList",
        ).forEach { name ->
            runCatching {
                XposedBridge.hookAllMethods(dtc, name, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val raw = param.result as? String ?: return
                        param.result = ModuleHide.filterSensitiveLines(raw)
                    }
                })
            }
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                dtc, "isDebugVersion",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = false
                }
            )
        }

        runCatching {
            XposedBridge.hookAllMethods(dtc, "getLibraryList", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val raw = param.result as? String ?: return
                    param.result = ModuleHide.filterSensitiveLines(raw)
                }
            })
        }

        runCatching {
            XposedBridge.hookAllMethods(dtc, "getPropSafe", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val raw = param.result as? String ?: return
                    param.result = ModuleHide.sanitizeValue(raw) ?: raw
                }
            })
        }

        // Native callback blacklist: dtcBL([B)[Ljava/lang/Object;
        runCatching {
            XposedBridge.hookAllMethods(dtc, "dtcBL", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = sanitizeDtcBlResult(param.result)
                }
            })
        }

        listOf("mmQsecKVValue").forEach { name ->
            runCatching {
                XposedBridge.hookAllMethods(dtc, name, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val raw = param.result as? String ?: return
                        param.result = ModuleHide.sanitizeValue(raw)
                            ?: ModuleHide.filterSensitiveLines(raw).ifEmpty { "" }
                    }
                })
            }
        }

        runCatching {
            XposedBridge.hookAllMethods(dtc, "dtcProcessCall", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    DetectionKillShield.arm()
                }
            })
        }

        runCatching {
            XposedBridge.hookAllMethods(dtc, "mmQsecKVValueBytes", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val bytes = param.result as? ByteArray ?: return
                    val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return
                    if (!ModuleHide.lineContainsSensitive(text)) return
                    param.result = ByteArray(0)
                }
            })
        }

        log("Dtc hooks OK")
    }

    /** QSec — ArtTiHook uses detectMethod; getXpsInfo may expose hook environment. */
    private fun hookQSec(classLoader: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                QSEC,
                classLoader,
                "detectMethod",
                String::class.java,
                String::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        DetectionKillShield.arm()
                        return false
                    }
                }
            )
        }

        runCatching {
            val qsec = classLoader.loadClass(QSEC)
            XposedBridge.hookAllMethods(qsec, "getXpsInfo", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    DetectionKillShield.arm(15_000)
                    param.result = ByteArray(0)
                }
            })
        }

        runCatching {
            val qsec = classLoader.loadClass(QSEC)
            XposedBridge.hookAllMethods(qsec, "doReport", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    DetectionKillShield.arm(15_000)
                    param.result = 0
                }
            })
        }

        runCatching {
            val qsec = classLoader.loadClass(QSEC)
            XposedBridge.hookAllMethods(qsec, "doSomething", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    DetectionKillShield.arm()
                }
            })
        }

        runCatching {
            val qsec = classLoader.loadClass(QSEC)
            XposedBridge.hookAllMethods(qsec, "execTasks", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    DetectionKillShield.arm(30_000)
                }
            })
        }

        log("QSec hooks OK")
    }

    /**
     * Pandora RuntimeMonitor.exec* — QQ routes shell probes here instead of Runtime.exec.
     * Signatures from APK: exec(Runtime, String) and overloads with env/dir.
     */
    private fun hookRuntimeMonitorExec(classLoader: ClassLoader) {
        val cls = runCatching { classLoader.loadClass(RUNTIME_MONITOR) }.getOrNull() ?: return
        cls.declaredMethods.filter { it.name == "exec" }.forEach { method ->
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val cmd = extractExecCommand(param.args) ?: return
                        if (isDangerousShellCommand(cmd)) {
                            param.throwable = SecurityException("permission denied")
                        }
                    }
                })
            }
        }
        log("RuntimeMonitor.exec hooked (${cls.declaredMethods.count { it.name == "exec" }} overloads)")
    }

    private fun extractExecCommand(args: Array<Any?>): String? {
        for (arg in args) {
            when (arg) {
                is String -> return arg
                is Array<*> -> {
                    val parts = arg.filterIsInstance<String>()
                    if (parts.isNotEmpty()) return parts.joinToString(" ")
                }
            }
        }
        return null
    }

    private fun isDangerousShellCommand(cmd: String): Boolean {
        val c = cmd.lowercase()
        return c.contains("which su") || c == "su" || c.startsWith("su ") ||
            c.contains("magisk") || c.contains("xposed") || c.contains("lsposed") ||
            c.contains("busybox") || (c.contains("getprop") && c.contains("debug"))
    }

    private fun sanitizeDtcBlResult(result: Any?): Any? {
        return when (result) {
            null -> null
            is Array<*> -> result.map { sanitizeDtcBlElement(it) }.toTypedArray()
            is String -> ModuleHide.sanitizeValue(result) ?: result
            else -> result
        }
    }

    private fun sanitizeDtcBlElement(item: Any?): Any? {
        return when (item) {
            null -> null
            is String -> {
                if (ModuleHide.matchesPackage(item) || ModuleHide.lineContainsSensitive(item)) ""
                else ModuleHide.sanitizeValue(item) ?: item
            }
            else -> item
        }
    }

    private fun log(msg: String) {
        XposedBridge.log("[QQ9290Detect] $msg")
    }
}
