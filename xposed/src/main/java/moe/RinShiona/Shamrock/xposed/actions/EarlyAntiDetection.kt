package moe.RinShiona.Shamrock.xposed.actions

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicBoolean
import moe.RinShiona.Shamrock.xposed.AntiDetectionConfig
import moe.RinShiona.Shamrock.xposed.helper.DetectionKillShield
import moe.RinShiona.Shamrock.xposed.helper.HookEvasion
import moe.RinShiona.Shamrock.xposed.helper.KillGuardHooks
import moe.RinShiona.Shamrock.xposed.helper.ModuleHideHooks
import moe.RinShiona.Shamrock.xposed.helper.PackageInstallMonitorHooks
import moe.RinShiona.Shamrock.xposed.helper.PandoraHideHooks
import moe.RinShiona.Shamrock.xposed.helper.QQ9290DetectionHooks
import moe.RinShiona.Shamrock.xposed.helper.QSecBypassHooks
import moe.RinShiona.Shamrock.xposed.helper.SignExtraSanitizer
import moe.RinShiona.Shamrock.xposed.ipc.impl.ShamrockNative

/**
 * QQ 9.2.90 NT anti-tamper bypasses — installed from [XposedEntry.execStartupInit]
 * after Application + MobileQQ are ready (not at loadPackage).
 *
 * Strategy: QSec.detectMethod=false + Dtc/Pandora hide + fekit load probe.
 */
internal object EarlyAntiDetection {
    private val criticalInstalled = AtomicBoolean(false)
    private val deferredInstalled = AtomicBoolean(false)

    @Volatile
    var fullyInstalled: Boolean = false
        private set

    /** Fast path on main thread — QSec.detectMethod + KillGuard only. */
    fun installCritical(classLoader: ClassLoader) {
        if (!AntiDetectionConfig.allowEarlyHooks()) {
            log("skip critical: disabled by config")
            return
        }
        if (!criticalInstalled.compareAndSet(false, true)) return
        log("installing critical bypass (proc=${currentProcessName()})")
        QQ9290DetectionHooks.installCritical(classLoader)
        KillGuardHooks.install(classLoader)
        // Heavy-hitting QSec / Dtc / QsecEst scan loop bypass — runs BEFORE the
        // first sign call so libfekit's native scan returns an empty result set.
        QSecBypassHooks.install(classLoader)
        // Hide hook frames from stack-trace + reflection probes that the QQ NT
        // anti-tamper coroutines run between cold-start tasks.
        HookEvasion.install(classLoader)
    }

    /** Pandora / stack / Dtc sanitizers — run off main thread after splash. */
    fun installDeferred(classLoader: ClassLoader) {
        if (!AntiDetectionConfig.allowEarlyHooks()) return
        if (fullyInstalled || !deferredInstalled.compareAndSet(false, true)) return
        val proc = currentProcessName()
        val isMain = proc == "com.tencent.mobileqq" || !proc.contains(':')
        log("installing deferred bypass (proc=$proc main=$isMain)")
        if (AntiDetectionConfig.hideApk || AntiDetectionConfig.hideFiles ||
            AntiDetectionConfig.hideProps || AntiDetectionConfig.hideSignature ||
            AntiDetectionConfig.hideTrace || AntiDetectionConfig.hideProc
        ) {
            QQ9290DetectionHooks.installExtended(classLoader)
        }
        if (isMain) {
            installMainProcessHooks(classLoader)
        } else {
            installNonMainProcessHooks()
        }
        if (isMain) {
            bootstrapNative()
        }
        fullyInstalled = true
    }

    /** Boot libshamrocknt + libshadowhook in the QQ main process. */
    private fun bootstrapNative() {
        kotlin.runCatching {
            val ctx = currentHostContext()
            if (ctx == null) {
                log("native bootstrap skipped: no host context")
                return
            }
            val ok = ShamrockNative.bootstrap(ctx)
            log("native bootstrap => $ok (initialized=${ShamrockNative.initialized})")
            if (ok) {
                log("native status: ${ShamrockNative.status()}")
            }
        }.onFailure {
            log("native bootstrap failed: ${it.message}")
        }
    }

    private fun currentHostContext(): android.content.Context? {
        return kotlin.runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context
        }.getOrElse {
            kotlin.runCatching {
                Class.forName("mqq.app.MobileQQ")
                    .getMethod("getContext")
                    .invoke(null) as? android.content.Context
            }.getOrNull()
        }
    }

    fun install(classLoader: ClassLoader) {
        installCritical(classLoader)
        installDeferred(classLoader)
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
        if (AntiDetectionConfig.hookSign) {
            hookSignExtraSanitizer(classLoader)
        }
    }

    /**
     * Install the byte-level [SignExtraSanitizer] on `QQSecuritySign.getSign`
     * as early as possible — before [AntiDetection.invoke] reaches FEKit.
     * This ensures the very first sign call (which usually happens on the
     * MSF login path right after Application.onCreate) already sees a clean
     * extra header even if the rest of [AntiDetection] hasn't run yet.
     */
    private fun hookSignExtraSanitizer(classLoader: ClassLoader) {
        runCatching {
            val cls = classLoader.loadClass("com.tencent.mobileqq.sign.QQSecuritySign")
            XposedBridge.hookAllMethods(cls, "getSign", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = SignExtraSanitizer.sanitizeSignResult(param.result)
                }
            })
            log("SignExtraSanitizer bound on QQSecuritySign.getSign (early)")
        }.onFailure {
            log("early sign sanitizer install failed: ${it.message}")
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
