package moe.RinShiona.Shamrock.xposed.helper

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full neutralization of QQ 9.2.90's QSec / Dtc / QsecEst / ByteData
 * scan loop so the sign-time anti-tamper bits never get populated.
 *
 * APK references (from 9.2.90_rev/key_classes_methods.txt):
 *
 *  com.tencent.mobileqq.qsec.qsecurity.QSec
 *      detectMethod(String, String): boolean          <-- already false
 *      execTasks(Context, int): int                   <-- main scan driver
 *      doSomething(Context, int): int   (native)      <-- core libfekit probe
 *      doReport(String*4): int           (native)     <-- detection upload
 *      getXpsInfo(): byte[]                           <-- env scan payload
 *      getXwDebugID(String): byte[]      (native)
 *      getFeKitAttach(Ctx, S, S, S): byte[]           <-- attach-stage scan
 *      getEstInfo(): String
 *      getEstInfo(Ctx, S): String
 *      initXps(): int
 *      closeXps()
 *
 *  com.tencent.mobileqq.dt.app.Dtc
 *      dtcProcessCall()
 *      dtcBL(byte[]): Object[]            <-- detected library list
 *      dtcSendMessage(S, byte[], long): int
 *      checkAppInstalled(String): boolean
 *      isAbnormalConfig(): boolean        <-- already false
 *      isDebugVersion(): boolean
 *
 *  com.tencent.mobileqq.qsec.qsecest.QsecEst
 *      a(Context, String, String): String
 *      d(Context, String, String): byte[] (native)    <-- env entropy collector
 *
 *  com.tencent.mobileqq.qsec.qsecprotocol.ByteData
 *      getByte(Context, Object): byte[]   (native)
 *      getSign(S, S, byte[]): byte[]
 *
 *  com.tencent.mobileqq.fe.utils.DeepSleepDetector
 *      startCheck()                                   <-- runs probe handler
 *      getCheckResult(): String
 *
 *  com.tencent.qmethod.pandoraex.core.MonitorReporter
 *      report*(...)                                   <-- side-band channel
 *
 * Strategy: do NOT replace getSign* methods (signing still needs to work),
 * but neutralize every "scan" / "report" / "probe" entrypoint so the QSec
 * coroutine that builds the sign-extra detection bits keeps coming up empty.
 */
internal object QSecBypassHooks {

    private const val QSEC = "com.tencent.mobileqq.qsec.qsecurity.QSec"
    private const val DTC = "com.tencent.mobileqq.dt.app.Dtc"
    private const val QSECEST = "com.tencent.mobileqq.qsec.qsecest.QsecEst"
    private const val BYTEDATA = "com.tencent.mobileqq.qsec.qsecprotocol.ByteData"
    private const val DEEPSLEEP = "com.tencent.mobileqq.fe.utils.DeepSleepDetector"
    private const val MONITOR_REPORTER =
        "com.tencent.qmethod.pandoraex.core.MonitorReporter"
    private const val FEBOUND = "com.tencent.mobileqq.dt.model.FEBound"

    private val installed = AtomicBoolean(false)

    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return

        neuterQSec(classLoader)
        neuterDtc(classLoader)
        neuterQsecEst(classLoader)
        neuterByteData(classLoader)
        neuterDeepSleepDetector(classLoader)
        neuterMonitorReporter(classLoader)

        log("QSec bypass layer installed")
    }

    // -------------------- QSec --------------------

    private fun neuterQSec(classLoader: ClassLoader) {
        val cls = runCatching { classLoader.loadClass(QSEC) }.getOrNull() ?: run {
            log("QSec class not found")
            return
        }

        // execTasks(Context, int): int — main scan driver; return 0 (success, nothing to do).
        runCatching {
            XposedBridge.hookAllMethods(cls, "execTasks", object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any = 0
            })
        }.onFailure { log("execTasks hook failed: ${it.message}") }

        // doSomething(Context, int): int — native probe; force 0.
        // Cannot trivially replace a native method with XC_MethodReplacement when the JNI
        // binding is already in place; use hookAllMethods which Xposed handles for natives.
        runCatching {
            XposedBridge.hookAllMethods(cls, "doSomething", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = 0
                }
            })
        }.onFailure { log("doSomething hook failed: ${it.message}") }

        // doReport(S, S, S, S): int — upload pipe to backend; return 0.
        runCatching {
            XposedBridge.hookAllMethods(cls, "doReport", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = 0
                }
            })
        }

        // getXpsInfo(): byte[] — environment scan payload; return empty.
        runCatching {
            XposedBridge.hookAllMethods(cls, "getXpsInfo", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = ByteArray(0)
                }
            })
        }

        // getFeKitAttach(Context, S, S, S): byte[] — attach-stage scan; return empty.
        runCatching {
            XposedBridge.hookAllMethods(cls, "getFeKitAttach", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = ByteArray(0)
                }
            })
        }

        // getEstInfo() / getEstInfo(Context, String): String — entropy scan; blank.
        runCatching {
            XposedBridge.hookAllMethods(cls, "getEstInfo", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = ""
                }
            })
        }

        // initXps(): int — internal scan-context init; return 0 success.
        runCatching {
            XposedBridge.hookAllMethods(cls, "initXps", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = 0
                }
            })
        }

        // closeXps() — no-op (already idempotent, but keep parity).
        runCatching {
            XposedBridge.hookAllMethods(cls, "closeXps", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = null
                }
            })
        }

        log("QSec scan methods neutralized")
    }

    // -------------------- Dtc --------------------

    private fun neuterDtc(classLoader: ClassLoader) {
        val cls = runCatching { classLoader.loadClass(DTC) }.getOrNull() ?: return

        // dtcProcessCall() — the periodic detection-collection runnable; no-op.
        runCatching {
            XposedBridge.hookAllMethods(cls, "dtcProcessCall", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = null
                }
            })
        }

        // dtcBL(byte[]): Object[] — detected blacklist; force empty array.
        runCatching {
            XposedBridge.hookAllMethods(cls, "dtcBL", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = emptyArray<Any?>()
                }
            })
        }

        // dtcSendMessage(S, byte[], long): int — backend channel; 0.
        runCatching {
            XposedBridge.hookAllMethods(cls, "dtcSendMessage", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = 0
                }
            })
        }

        // isDebugVersion(): false (already in QQ9290DetectionHooks but reinforce idempotently).
        runCatching {
            XposedBridge.hookAllMethods(cls, "isDebugVersion", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = false
                }
            })
        }

        // mHasDoNative public field stays untouched: it's a "init guard" not a detect flag.

        log("Dtc scan methods neutralized")
    }

    // -------------------- QsecEst --------------------

    private fun neuterQsecEst(classLoader: ClassLoader) {
        val cls = runCatching { classLoader.loadClass(QSECEST) }.getOrNull() ?: return

        // a(Context, String, String): String — wraps the native d(); blank.
        runCatching {
            XposedBridge.hookAllMethods(cls, "a", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = ""
                }
            })
        }

        // d(Context, String, String): byte[] — native entropy collector; empty.
        runCatching {
            XposedBridge.hookAllMethods(cls, "d", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = ByteArray(0)
                }
            })
        }

        log("QsecEst entropy probes neutralized")
    }

    // -------------------- ByteData --------------------

    private fun neuterByteData(classLoader: ClassLoader) {
        val cls = runCatching { classLoader.loadClass(BYTEDATA) }.getOrNull() ?: return

        // ByteData.getByte(Context, Object): byte[] — internal probe channel; leave SIGN
        // untouched (getSign is the legitimate sign path).
        //
        // Empirically `getByte` is the channel libfekit uses to read back its own probe
        // results when scanning native namespace; nuking it stops the loop early.
        runCatching {
            XposedBridge.hookAllMethods(cls, "getByte", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = ByteArray(0)
                }
            })
        }

        log("ByteData probe channel neutralized")
    }

    // -------------------- DeepSleepDetector --------------------

    private fun neuterDeepSleepDetector(classLoader: ClassLoader) {
        val cls = runCatching { classLoader.loadClass(DEEPSLEEP) }.getOrNull() ?: return

        runCatching {
            XposedBridge.hookAllMethods(cls, "startCheck", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = null
                }
            })
        }
        runCatching {
            XposedBridge.hookAllMethods(cls, "getCheckResult", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = "0"
                }
            })
        }

        log("DeepSleepDetector neutralized")
    }

    // -------------------- Pandora MonitorReporter --------------------

    private fun neuterMonitorReporter(classLoader: ClassLoader) {
        val cls = runCatching { classLoader.loadClass(MONITOR_REPORTER) }.getOrNull() ?: return

        // Block every method that contains "report" — most overloads are static.
        cls.declaredMethods.filter { it.name.contains("report", ignoreCase = true) }.forEach { m ->
            runCatching {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = when (m.returnType) {
                            Boolean::class.javaPrimitiveType,
                            java.lang.Boolean::class.java -> false
                            Int::class.javaPrimitiveType -> 0
                            Long::class.javaPrimitiveType -> 0L
                            Void.TYPE -> null
                            else -> null
                        }
                    }
                })
            }
        }

        log("Pandora MonitorReporter neutralized (${cls.declaredMethods.count { it.name.contains("report", ignoreCase = true) }} entries)")
    }

    private fun log(msg: String) {
        XposedBridge.log("[QSecBypass] $msg")
    }
}
