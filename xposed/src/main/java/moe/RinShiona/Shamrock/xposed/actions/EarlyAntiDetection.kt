package moe.RinShiona.Shamrock.xposed.actions

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicBoolean
import moe.RinShiona.Shamrock.xposed.AntiDetectionConfig
import moe.RinShiona.Shamrock.xposed.helper.DetectionKillShield
import moe.RinShiona.Shamrock.xposed.helper.AdbHideHooks
import moe.RinShiona.Shamrock.xposed.helper.HookEvasion
import moe.RinShiona.Shamrock.xposed.helper.KillGuardHooks
import moe.RinShiona.Shamrock.xposed.helper.ModuleHideHooks
import moe.RinShiona.Shamrock.xposed.helper.PackageInstallMonitorHooks
import moe.RinShiona.Shamrock.xposed.helper.PandoraHideHooks
import moe.RinShiona.Shamrock.xposed.helper.QQ9290DetectionHooks
import moe.RinShiona.Shamrock.xposed.helper.QSecBypassHooks
import moe.RinShiona.Shamrock.xposed.helper.SignExtraSanitizer
import moe.RinShiona.Shamrock.xposed.actions.StackTraceHideHooks
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
    private val liteInstalled = AtomicBoolean(false)
    private val liteDeferredInstalled = AtomicBoolean(false)
    private val nativeEarlyStarted = AtomicBoolean(false)
    private val attachHookInstalled = AtomicBoolean(false)

    /**
     * connectivity-safe 下 sign extra 只在 :MSF 生成；主进程装 native（fopen/probe）
     * 会与 QQ 自带 shadowhook / Looper 冲突并 SIGSEGV，表现为 HTTP/WS 循环重启。
     */
    private fun allowMainProcessNativeBootstrap(): Boolean {
        if (!isMainQqProcess()) return false
        if (AntiDetectionConfig.connectivitySafeMode) return false
        return true
    }

    /**
     * ArtTiHook 在 attach 后极早运行 — 必须在此时装好 maps 过滤 / exit hook。
     */
    fun installAttachNativeHook(classLoader: ClassLoader) {
        if (!AntiDetectionConfig.allowLiteAntiDetect()) return
        if (!allowMainProcessNativeBootstrap()) return
        if (!isMainQqProcess()) return
        if (!attachHookInstalled.compareAndSet(false, true)) return
        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.tencent.common.app.BaseApplicationImpl",
                classLoader,
                "attachBaseContext",
                android.content.Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctx = param.args.getOrNull(0) as? android.content.Context ?: return
                        bootstrapNativeAntiDetect(ctx)
                    }
                },
            )
            log("attachBaseContext native hook installed")
        }.onFailure {
            log("attach native hook failed: ${it.message}")
        }
    }

    /** 轮询兜底：attach 未触发时尽快 bootstrap native。 */
    fun scheduleNativeBootstrapEarly() {
        if (!AntiDetectionConfig.allowLiteAntiDetect()) return
        if (!allowMainProcessNativeBootstrap()) {
            log("skip main native bootstrap (MSF-only in connectivity-safe)")
            return
        }
        if (!isMainQqProcess()) return
        if (!nativeEarlyStarted.compareAndSet(false, true)) return
        Thread({
            var attempts = 0
            while (attempts < 240 && !ShamrockNative.initialized) {
                bootstrapNativeAntiDetect(currentHostContext())
                if (ShamrockNative.initialized) {
                    log("early native OK (attempt=$attempts)")
                    return@Thread
                }
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    break
                }
                attempts++
            }
            if (!ShamrockNative.initialized) {
                log("early native bootstrap timeout")
            }
        }, "Shamrock-EarlyNative").apply { isDaemon = true; start() }
    }

    fun bootstrapNativeAntiDetectForMsf(ctx: android.content.Context? = null) {
        if (!AntiDetectionConfig.allowLiteAntiDetect()) return
        if (!isMsfProcess()) return
        if (ShamrockNative.initialized) return
        val host = ctx ?: currentHostContext() ?: return
        kotlin.runCatching {
            val ok = ShamrockNative.bootstrapAntiDetectOnly(host.applicationContext ?: host)
            log("MSF native anti bootstrap => $ok")
        }.onFailure {
            log("MSF native anti bootstrap failed: ${it.message}")
        }
    }

    private fun isMsfProcess(): Boolean =
        currentProcessName().endsWith(":MSF")

    fun bootstrapNativeAntiDetect(ctx: android.content.Context? = null) {
        if (!AntiDetectionConfig.allowLiteAntiDetect()) return
        if (!allowMainProcessNativeBootstrap()) return
        if (!isMainQqProcess()) return
        if (ShamrockNative.initialized) return
        val host = ctx ?: currentHostContext() ?: return
        kotlin.runCatching {
            val ok = ShamrockNative.bootstrapAntiDetectOnly(host.applicationContext ?: host)
            log("native anti bootstrap => $ok")
        }.onFailure {
            log("native anti bootstrap failed: ${it.message}")
        }
    }

    /**
     * 联网优先：Java 关键 hook + 尽早 native（ArtTiHook 依赖 maps 过滤）。
     */
    fun installLite(classLoader: ClassLoader) {
        if (!AntiDetectionConfig.allowLiteAntiDetect()) {
            log("skip lite: connectivitySafe=${AntiDetectionConfig.connectivitySafeMode}")
            return
        }
        if (!liteInstalled.compareAndSet(false, true)) return
        val mainProc = isMainQqProcess()
        log("installing lite anti-detect (proc=${currentProcessName()} main=$mainProc)")
        DetectionKillShield.arm(600_000L)
        KillGuardHooks.enableLiteColdStartWindow(600_000L)
        patchBuildTags()
        if (mainProc) {
            scheduleNativeBootstrapEarly()
            installAttachNativeHook(classLoader)
        } else {
            log("lite subproc: Java anti-detect + MSF native bootstrap")
            bootstrapNativeAntiDetectForMsf(currentHostContext())
        }
        QQ9290DetectionHooks.installCritical(classLoader)
        runCatching { QQ9290DetectionHooks.installExtended(classLoader) }
            .onFailure { log("lite extended hooks failed: ${it.message}") }
        KillGuardHooks.install(classLoader)
        runCatching { HookEvasion.install(classLoader) }
            .onFailure { log("lite HookEvasion failed: ${it.message}") }
        runCatching { PandoraHideHooks.install(classLoader) }
            .onFailure { log("lite PandoraHide failed: ${it.message}") }
        runCatching { PackageInstallMonitorHooks.install(classLoader) }
            .onFailure { log("lite PackageInstallMonitor failed: ${it.message}") }
        runCatching { ModuleHideHooks.installEarly(classLoader) }
            .onFailure { log("lite ModuleHide failed: ${it.message}") }
        runCatching { QSecBypassHooks.installLite(classLoader, mainProc) }
            .onFailure { log("lite QSecBypass failed: ${it.message}") }
        if (AntiDetectionConfig.hookSign) {
            runCatching { hookSignExtraSanitizer(classLoader) }
                .onFailure { log("lite sign sanitizer failed: ${it.message}") }
        }
        log("lite anti-detect installed")
    }

    /** NtTask 安全任务前：主进程 bootstrap native；子进程仅 Java。 */
    fun installLiteBeforeArtTi(classLoader: ClassLoader) {
        if (!AntiDetectionConfig.allowLiteAntiDetect()) return
        val mainProc = isMainQqProcess()
        if (mainProc && allowMainProcessNativeBootstrap()) {
            bootstrapNativeAntiDetect(currentHostContext())
        } else if (isMsfProcess()) {
            bootstrapNativeAntiDetectForMsf(currentHostContext())
        }
        DetectionKillShield.arm(180_000L)
        QQ9290DetectionHooks.installCritical(classLoader)
        runCatching { HookEvasion.install(classLoader) }
        if (mainProc) {
            runCatching { ModuleHideHooks.installEarly(classLoader) }
        } else {
            runCatching { ModuleHideHooks.installFileHideOnly() }
        }
        runCatching { QQ9290DetectionHooks.installExtended(classLoader) }
        log("lite pre-ArtTi layer ready (main=$mainProc native=${ShamrockNative.initialized})")
    }

    /** 延迟补全 Pandora / 栈隐藏（不再延迟 native）。 */
    fun installLiteDeferred(classLoader: ClassLoader) {
        if (!AntiDetectionConfig.allowLiteAntiDetect()) return
        if (!liteDeferredInstalled.compareAndSet(false, true)) return
        val mainProc = isMainQqProcess()
        log("installing lite deferred (proc=${currentProcessName()} main=$mainProc)")
        runCatching { StackTraceHideHooks.install() }
        runCatching { HookEvasion.installDeferred(classLoader) }
        runCatching { PandoraHideHooks.install(classLoader) }
        runCatching { PackageInstallMonitorHooks.install(classLoader) }
        log("lite deferred installed (sign sanitizer already in installLite)")
    }

    private fun isMainQqProcess(): Boolean {
        return currentProcessName() == "com.tencent.mobileqq"
    }

    private fun patchBuildTags() {
        kotlin.runCatching {
            val tags = android.os.Build.TAGS ?: return
            if (tags.contains("test", ignoreCase = true)) {
                XposedHelpers.setStaticObjectField(android.os.Build::class.java, "TAGS", "release-keys")
            }
        }
    }

    fun installForMsf(classLoader: ClassLoader) {
        if (AntiDetectionConfig.allowLiteAntiDetect()) {
            installLite(classLoader)
            return
        }
        if (!AntiDetectionConfig.allowEarlyHooks()) {
            log("MSF: skip — connectivity-safe or disabled")
            return
        }
        if (!msfInstalled.compareAndSet(false, true)) return
        log("MSF: installing sign-process anti-detection (proc=${currentProcessName()})")

        runCatching { HookEvasion.install(classLoader) }
            .onFailure { log("MSF HookEvasion failed: ${it.message}") }
        runCatching { AdbHideHooks.install(classLoader) }
            .onFailure { log("MSF AdbHide failed: ${it.message}") }
        runCatching { KillGuardHooks.install(classLoader) }
            .onFailure { log("MSF KillGuard failed: ${it.message}") }
        bootstrapNativeAntiDetectForMsf(currentHostContext())
        log("MSF: sign-process anti-detect installed")
    }

    /** Fast path on main thread — QSec.detectMethod + KillGuard only. */
    fun installCritical(classLoader: ClassLoader) {
        if (!AntiDetectionConfig.allowEarlyHooks()) {
            log("skip critical: connectivity-safe or disabled")
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

    fun hookSignExtraSanitizerOnly(classLoader: ClassLoader) {
        if (!AntiDetectionConfig.hookSign) return
        hookSignExtraSanitizer(classLoader)
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

    /** MSF: bootstrap native only after libfekit loads — avoids early dlopen crash. */
    private fun hookLibFeKitLoadWithBootstrap() {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val hit = when (param.method.name) {
                    "loadLibrary" -> (param.args.getOrNull(0) as? String)?.contains("fekit", ignoreCase = true) == true
                    "load" -> (param.args.getOrNull(0) as? String)?.contains("libfekit", ignoreCase = true) == true
                    else -> false
                }
                if (!hit) return
                kotlin.runCatching { bootstrapNativeAntiDetect(currentHostContext()) }
                ShamrockNative.onLibFeKitLoaded()
                log("libfekit loaded — MSF native bootstrap + probe refresh")
            }
        }
        runCatching {
            XposedHelpers.findAndHookMethod(System::class.java, "loadLibrary", String::class.java, hook)
        }
        runCatching {
            XposedHelpers.findAndHookMethod(System::class.java, "load", String::class.java, hook)
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
