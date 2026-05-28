package moe.RinShiona.Shamrock.xposed.helper

import android.os.Process
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Block QQ QSec / Guard / startup-task driven process suicide.
 * Native _exit/kill hooks live in anti_detect_native.cpp.
 */
internal object KillGuardHooks {
    private val installed = AtomicBoolean(false)

    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        hookJvmExit()
        hookMobileQQ(classLoader)
        hookGuardManager(classLoader)
        hookAppRuntime(classLoader)
        hookGKillProcessMonitor(classLoader)
        hookSystemMethodProxy(classLoader)
        log("kill guard installed")
    }

    /** QQ's perf module wraps killProcess; intercept it to stop self-kill on Shamrock-induced errors. */
    private fun hookSystemMethodProxy(classLoader: ClassLoader) {
        val cls = runCatching {
            classLoader.loadClass("com.tencent.mobileqq.perf.block.SystemMethodProxy")
        }.getOrNull() ?: return
        runCatching {
            XposedBridge.hookAllMethods(cls, "killProcess", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val pid = param.args.getOrNull(0) as? Int ?: return
                    if (pid != Process.myPid() && pid != 0) return
                    if (!isShamrockInducedStack()) return
                    log("blocked SystemMethodProxy.killProcess($pid) — Shamrock-induced")
                    param.result = null
                }
            })
        }
    }

    /**
     * Block kills that originate from a stack frame referencing our module —
     * e.g. NativeLoader.load failure cascading into QQ's perf monitor.
     */
    private fun isShamrockInducedStack(): Boolean {
        return currentStack().any { frame ->
            val cn = frame.className
            cn.contains("moe.RinShiona.Shamrock", ignoreCase = true) ||
                cn.contains("Shamrock", ignoreCase = true) ||
                cn.contains("NativeLoader", ignoreCase = true) ||
                cn.contains("PullConfig", ignoreCase = true) ||
                cn.contains("InitRemoteService", ignoreCase = true)
        }
    }

    private fun hookJvmExit() {
        val block = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!shouldBlock()) return
                log("blocked ${param.method.declaringClass.simpleName}.${param.method.name}")
                param.result = null
            }
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                Process::class.java, "killProcess", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pid = param.args.getOrNull(0) as? Int ?: return
                        if (pid != Process.myPid() && pid != 0) return
                        if (!shouldBlock()) return
                        log("blocked Process.killProcess($pid)")
                        param.result = null
                    }
                }
            )
        }

        runCatching { XposedBridge.hookAllMethods(Runtime::class.java, "exit", block) }
        runCatching { XposedBridge.hookAllMethods(Runtime::class.java, "halt", block) }
        runCatching { XposedBridge.hookAllMethods(System::class.java, "exit", block) }

        runCatching {
            XposedBridge.hookAllMethods(Process::class.java, "sendSignal", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val pid = param.args.getOrNull(0) as? Int ?: return
                    if (pid != Process.myPid()) return
                    if (!shouldBlock()) return
                    log("blocked Process.sendSignal($pid)")
                    param.result = null
                }
            })
        }
    }

    private fun hookMobileQQ(classLoader: ClassLoader) {
        val cls = runCatching { classLoader.loadClass("mqq.app.MobileQQ") }.getOrNull() ?: return
        listOf("qqProcessExit", "otherProcessExit", "realExit", "exit").forEach { name ->
            runCatching {
                XposedBridge.hookAllMethods(cls, name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!shouldBlock()) return
                        log("blocked MobileQQ.$name")
                        param.result = null
                    }
                })
            }
        }
    }

    private fun hookGuardManager(classLoader: ClassLoader) {
        val cls = runCatching {
            classLoader.loadClass("com.tencent.mobileqq.app.guard.GuardManager")
        }.getOrNull() ?: return
        runCatching {
            XposedBridge.hookAllMethods(cls, "exit", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!shouldBlock()) return
                    log("blocked GuardManager.exit")
                    param.result = null
                }
            })
        }
    }

    private fun hookAppRuntime(classLoader: ClassLoader) {
        val cls = runCatching { classLoader.loadClass("mqq.app.AppRuntime") }.getOrNull() ?: return
        runCatching {
            XposedBridge.hookAllMethods(cls, "exit", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!shouldBlock()) return
                    log("blocked AppRuntime.exit")
                    param.result = null
                }
            })
        }
        runCatching {
            XposedBridge.hookAllMethods(cls, "kick", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!shouldBlock()) return
                    log("blocked AppRuntime.kick")
                    param.result = null
                }
            })
        }
    }

    /** AV layer process killer used after security failures on some builds. */
    private fun hookGKillProcessMonitor(classLoader: ClassLoader) {
        val cls = runCatching {
            classLoader.loadClass("com.tencent.av.app.GKillProcessMonitor")
        }.getOrNull() ?: return
        cls.declaredMethods.forEach { method ->
            val name = method.name.lowercase()
            if (!name.contains("kill") && !name.contains("exit")) return@forEach
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!shouldBlock()) return
                        log("blocked GKillProcessMonitor.${method.name}")
                        param.result = null
                    }
                })
            }
        }
    }

    /**
     * Only block QQ self-kill when a detection hook just fired and armed the shield.
     * Do not blanket-block during cold start — that leaves a broken process on white screen.
     */
    private fun shouldBlock(): Boolean {
        if (isShamrockInducedStack()) return true
        if (!DetectionKillShield.isArmed()) return false
        return isSecurityStack() || isTencentDetectionStack()
    }

    private fun isSecurityStack(): Boolean {
        return currentStack().any { frame -> securityClassMatches(frame.className) }
    }

    private fun isTencentDetectionStack(): Boolean {
        return currentStack().any { frame ->
            val cn = frame.className
            cn.contains("com.tencent.mobileqq") &&
                (cn.contains("qmethod", ignoreCase = true) ||
                    cn.contains("pandoraex", ignoreCase = true) ||
                    cn.contains("privacy", ignoreCase = true) ||
                    cn.contains("mobileqq.fe", ignoreCase = true) ||
                    cn.contains("dt.app", ignoreCase = true) ||
                    cn.contains("startup", ignoreCase = true) ||
                    cn.contains("GKillProcess", ignoreCase = true))
        }
    }

    private fun securityClassMatches(className: String): Boolean {
        return className.contains("qsec", ignoreCase = true) ||
            className.contains("qsecurity", ignoreCase = true) ||
            className.contains("ArtTiHook", ignoreCase = true) ||
            className.contains("GuardCheck", ignoreCase = true) ||
            className.contains("GuardManager", ignoreCase = true) ||
            className.contains("GuardInit", ignoreCase = true) ||
            className.contains("CodeCheck", ignoreCase = true) ||
            className.contains("NativeMonitor", ignoreCase = true) ||
            className.contains("processkiller", ignoreCase = true) ||
            className.contains("libfekit", ignoreCase = true) ||
            className.contains("dt.app", ignoreCase = true) ||
            className.contains("mobileqq.fe", ignoreCase = true)
    }

    private fun currentStack(): Array<StackTraceElement> {
        return Thread.currentThread().stackTrace
    }

    private fun log(msg: String) {
        XposedBridge.log("[KillGuard] $msg")
    }
}
