package moe.RinShiona.Shamrock.xposed.actions

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicBoolean
import moe.RinShiona.Shamrock.xposed.helper.KillGuardHooks
import moe.RinShiona.Shamrock.xposed.helper.ModuleHide
import moe.RinShiona.Shamrock.xposed.helper.ModuleHideHooks
import moe.RinShiona.Shamrock.xposed.helper.PackageInstallMonitorHooks
import moe.RinShiona.Shamrock.xposed.helper.PandoraHideHooks
import moe.RinShiona.Shamrock.xposed.ipc.impl.ShamrockNative

/**
 * Critical QQ 9.2.90 NT anti-tamper bypasses — MUST run before other Shamrock hooks.
 *
 * Strategy: QSec.detectMethod=false + Dtc/Pandora hide + early native maps filter.
 * Do NOT skip NtTask startup tasks (causes QQ white-screen on HyperOS).
 */
internal object EarlyAntiDetection {
    private val installed = AtomicBoolean(false)

    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        val proc = currentProcessName()
        val isMain = proc == "com.tencent.mobileqq" || !proc.contains(':')
        log("installing early bypass (proc=$proc main=$isMain)")

        hookQSecDetectMethod(classLoader)
        hookDtcEarly(classLoader)
        if (isMain) {
            PandoraHideHooks.install(classLoader)
            PackageInstallMonitorHooks.install(classLoader)
            StackTraceHideHooks.install()
            // Full Dtc + file hide BEFORE ArtTiHookTask / GuardInitTask (AntiDetection runs too late).
            ModuleHideHooks.installEarly(classLoader)
            hookLibFeKitLoad()
            KillGuardHooks.install(classLoader)
        } else {
            // Keep non-main process hooks minimal to avoid affecting QQ startup libs.
            ModuleHideHooks.installFileHideOnly()
        }
    }

    /** Dtc probes run during cold startup — must hook here, not in AntiDetection action. */
    private fun hookDtcEarly(classLoader: ClassLoader) {
        val dtc = runCatching { classLoader.loadClass("com.tencent.mobileqq.dt.app.Dtc") }.getOrNull()
            ?: return
        runCatching {
            XposedHelpers.findAndHookMethod(
                dtc, "isAbnormalConfig",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = false
                }
            )
            log("Dtc.isAbnormalConfig -> false")
        }
        runCatching {
            XposedBridge.hookAllMethods(dtc, "checkAppInstalled", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val pkg = param.args.firstOrNull() as? String ?: return
                    if (ModuleHide.matchesPackage(pkg)) param.result = false
                }
            })
        }
        listOf("getAccessibilityEnabledServiceList", "getAccessibilityServiceList").forEach { method ->
            runCatching {
                XposedBridge.hookAllMethods(dtc, method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val raw = param.result as? String ?: return
                        param.result = ModuleHide.filterSensitiveLines(raw)
                    }
                })
            }
        }
    }

    private fun hookQSecDetectMethod(classLoader: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.tencent.mobileqq.qsec.qsecurity.QSec",
                classLoader,
                "detectMethod",
                String::class.java,
                String::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = false
                }
            )
            log("QSec.detectMethod -> false")
        }.onFailure { log("QSec.detectMethod hook failed: ${it.message}") }
    }

    /** Re-install libfekit probe hooks right after QQ loads libfekit.so. */
    private fun hookLibFeKitLoad() {
        runCatching {
            XposedHelpers.findAndHookMethod(
                System::class.java,
                "loadLibrary",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val name = param.args.getOrNull(0) as? String ?: return
                        if (name.contains("fekit", ignoreCase = true)) {
                            ShamrockNative.onLibFeKitLoaded()
                            log("libfekit loaded — probe hooks refreshed")
                        }
                    }
                }
            )
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                System::class.java,
                "load",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val path = param.args.getOrNull(0) as? String ?: return
                        if (path.contains("libfekit", ignoreCase = true)) {
                            ShamrockNative.onLibFeKitLoaded()
                            log("libfekit loaded (path) — probe hooks refreshed")
                        }
                    }
                }
            )
        }
    }

    private fun log(msg: String) {
        XposedBridge.log("[EarlyAntiDetection] $msg")
    }

    private fun currentProcessName(): String {
        return runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentProcessName")
                .invoke(null) as String
        }.getOrElse { "?" }
    }
}
