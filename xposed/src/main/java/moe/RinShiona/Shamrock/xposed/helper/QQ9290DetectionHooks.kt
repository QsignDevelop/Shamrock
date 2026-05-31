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

    private val criticalInstalled = AtomicBoolean(false)
    private val extendedInstalled = AtomicBoolean(false)

    /** Minimal hooks for ArtTiHook / cold start — must be fast on main thread. */
    fun installCritical(classLoader: ClassLoader) {
        if (!criticalInstalled.compareAndSet(false, true)) return
        hookDtcCritical(classLoader)
        hookQSecCritical(classLoader)
        log("QQ 9.2.90 critical hooks installed")
    }

    /** Heavier sanitizers — call from a background thread after splash progresses. */
    fun installExtended(classLoader: ClassLoader) {
        if (!extendedInstalled.compareAndSet(false, true)) return
        hookDtcExtended(classLoader)
        hookQSecExtended(classLoader)
        hookRuntimeMonitorExec(classLoader)
        log("QQ 9.2.90 extended hooks installed")
    }

    fun install(classLoader: ClassLoader) {
        installCritical(classLoader)
        installExtended(classLoader)
    }

    /** Dtc — only cheap checks needed before NtTask security scans. */
    private fun hookDtcCritical(classLoader: ClassLoader) {
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
        runCatching {
            XposedHelpers.findAndHookMethod(
                dtc, "isDebugVersion",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = false
                }
            )
        }
        listOf("isRoot", "isRooted", "checkRoot", "hasRoot", "isSuExist", "checkSu").forEach { name ->
            runCatching {
                XposedBridge.hookAllMethods(dtc, name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = false
                    }
                })
            }
        }
    }

    /** Dtc — string sanitizers (afterHook only; avoid hooking dtcProcessCall hot path). */
    private fun hookDtcExtended(classLoader: ClassLoader) {
        val dtc = runCatching { classLoader.loadClass(DTC) }.getOrNull() ?: return

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
                    val key = param.args.firstOrNull() as? String
                    val raw = param.result as? String ?: return
                    param.result = ModuleHide.sanitizeBootloaderProp(key, raw)
                        ?: ModuleHide.sanitizeValue(raw) ?: raw
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
            XposedBridge.hookAllMethods(dtc, "mmQsecKVValueBytes", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val bytes = param.result as? ByteArray ?: return
                    val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return
                    if (!ModuleHide.lineContainsSensitive(text)) return
                    param.result = ByteArray(0)
                }
            })
        }

        log("Dtc extended hooks OK")
    }

    private fun hookQSecCritical(classLoader: ClassLoader) {
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
            XposedBridge.hookAllMethods(qsec, "doReport", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = 0
                }
            })
        }
        runCatching {
            val qsec = classLoader.loadClass(QSEC)
            XposedBridge.hookAllMethods(qsec, "getXpsInfo", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = ByteArray(0)
                }
            })
        }
        runCatching {
            val qsec = classLoader.loadClass(QSEC)
            XposedBridge.hookAllMethods(qsec, "getXwDebugID", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = ByteArray(0)
                }
            })
        }
    }

    private fun hookQSecExtended(classLoader: ClassLoader) {
        runCatching {
            val qsec = classLoader.loadClass(QSEC)
            XposedBridge.hookAllMethods(qsec, "doSomething", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    DetectionKillShield.arm()
                }
            })
        }

        log("QSec extended hooks OK")
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
        return c.contains("which su") || c == "su" || c.startsWith("su ") || c.endsWith(" su") ||
            c.contains(" magisk") || c.startsWith("magisk") ||
            c.contains("xposed") || c.contains("lsposed") || c.contains("zygisk") ||
            c.contains("busybox") ||
            c.contains("getprop") && (
                c.contains("debug") || c.contains("secure") || c.contains("adb") ||
                    c.contains("boot.") || c.contains("usb") || c.contains("root")
                ) ||
            c.contains("cat /proc/") && (c.contains("maps") || c.contains("mount")) ||
            c.contains("dumpsys package") ||
            (c.startsWith("ps") && (c.contains("magisk") || c.contains("lsposed") || c.contains("zygisk")))
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
