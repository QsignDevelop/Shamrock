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
    private val msfInstalled = AtomicBoolean(false)

    @Volatile
    var fullyInstalled: Boolean = false
        private set

    /**
     * MSF-process anti-detection. THE sign (QQSecuritySign.getSign / libfekit)
     * is generated in the `com.tencent.mobileqq:MSF` process, so the libfekit
     * native environment scan that packs the detection bitfield runs HERE, not
     * in the main process. Previously MSF ran "IPC-only" with zero anti-detect,
     * which is why the decrypted detection bits never changed.
     *
     * We install ONLY the pieces that are safe for the fragile MSF process AND
     * cannot break the sign itself:
     *   - HookEvasion   : hide hook frames / spoof system props (no sign touch)
     *   - KillGuard     : stop self-kill if a probe still fires
     *   - libfekit load hook + native bootstrap : the native /proc maps filter
     *     and libfekit probe inline-hooks (return 0 = "no hook") — this is the
     *     part that actually changes what libfekit's native scan observes.
     *
     * Deliberately NOT installed in MSF:
     *   - QSecBypassHooks — it neutralizes QSec.execTasks / doSomething /
     *     getFeKitAttach which, in the MSF sign process, are load-bearing for
     *     generating the sign. Neutering them here would break login, not just
     *     detection. (In the main process they are safe because sign isn't
     *     produced there.)
     *   - the broad System.loadLibrary blockers / heavy startup hooks that
     *     previously killed MSF with libbasic_share / libforcedarkimpl errors.
     */
    fun installForMsf(classLoader: ClassLoader) {
        if (!AntiDetectionConfig.allowEarlyHooks()) {
            log("MSF: skip — anti-detect disabled by config")
            return
        }
        if (!msfInstalled.compareAndSet(false, true)) return
        log("MSF: installing sign-process anti-detection (proc=${currentProcessName()})")

        runCatching { HookEvasion.install(classLoader) }
            .onFailure { log("MSF HookEvasion failed: ${it.message}") }
        runCatching { KillGuardHooks.install(classLoader) }
            .onFailure { log("MSF KillGuard failed: ${it.message}") }
        // Refresh libfekit probe hooks the moment MSF loads libfekit.so.
        runCatching { hookLibFeKitLoad() }
            .onFailure { log("MSF libfekit-load hook failed: ${it.message}") }
        // Native maps filter + libfekit detection-probe inline hooks. This is
        // the piece that makes the native scan come back clean in MSF, without
        // touching the Java sign methods.
        runCatching { bootstrapNative() }
            .onFailure { log("MSF native bootstrap failed: ${it.message}") }

        log("MSF: sign-process anti-detection installed")
    }

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
