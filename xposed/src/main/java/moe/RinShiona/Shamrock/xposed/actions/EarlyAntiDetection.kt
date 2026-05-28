package moe.RinShiona.Shamrock.xposed.actions

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicBoolean
import moe.RinShiona.Shamrock.xposed.AntiDetectionConfig
import moe.RinShiona.Shamrock.xposed.helper.KillGuardHooks
import moe.RinShiona.Shamrock.xposed.helper.ModuleHideHooks
import moe.RinShiona.Shamrock.xposed.helper.PackageInstallMonitorHooks
import moe.RinShiona.Shamrock.xposed.helper.PandoraHideHooks
import moe.RinShiona.Shamrock.xposed.helper.QQ9290DetectionHooks
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
        if (!AntiDetectionConfig.allowEarlyHooks()) {
            log("skip install: disabled by config")
            return
        }
        if (!installed.compareAndSet(false, true)) return
        val proc = currentProcessName()
        val isMain = proc == "com.tencent.mobileqq" || !proc.contains(':')
        log("installing early bypass (proc=$proc main=$isMain)")

        // APK-verified Dtc / QSec / RuntimeMonitor hooks (9.2.90_rev scan).
        if (AntiDetectionConfig.hideApk || AntiDetectionConfig.hideFiles ||
            AntiDetectionConfig.hideProps || AntiDetectionConfig.hideSignature ||
            AntiDetectionConfig.hideTrace || AntiDetectionConfig.hideProc
        ) {
            QQ9290DetectionHooks.install(classLoader)
        }
        if (isMain) {
            installMainProcessHooks(classLoader)
        } else {
            installNonMainProcessHooks()
        }
    }

    private fun installMainProcessHooks(classLoader: ClassLoader) {
        if (AntiDetectionConfig.hideApk || AntiDetectionConfig.hideSignature) {
            PandoraHideHooks.install(classLoader)
            PackageInstallMonitorHooks.install(classLoader)
        }
        if (AntiDetectionConfig.hideTrace) {
            StackTraceHideHooks.install()
        }
        if (AntiDetectionConfig.hideFiles || AntiDetectionConfig.hideProc || AntiDetectionConfig.hideNative) {
            // Full Dtc + file hide BEFORE ArtTiHookTask / GuardInitTask (AntiDetection runs too late).
            ModuleHideHooks.installEarly(classLoader)
        }
        if (AntiDetectionConfig.hideNative || AntiDetectionConfig.hideSignature) {
            hookLibFeKitLoad()
        }
        if (AntiDetectionConfig.hideNative || AntiDetectionConfig.hideTrace) {
            KillGuardHooks.install(classLoader)
        }
    }

    private fun installNonMainProcessHooks() {
        // Keep non-main process hooks minimal to avoid affecting QQ startup libs.
        if (AntiDetectionConfig.hideFiles) {
            ModuleHideHooks.installFileHideOnly()
        }
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
