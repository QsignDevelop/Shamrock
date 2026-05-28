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
    private val processStartMs = System.currentTimeMillis()

    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        hookJvmExit()
        hookMobileQQ(classLoader)
        hookGuardManager(classLoader)
        hookAppRuntime(classLoader)
        log("kill guard installed")
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
            val cls = Process::class.java
            XposedBridge.hookAllMethods(cls, "sendSignal", object : XC_MethodHook() {
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
                    if (!isSecurityStack()) return
                    log("blocked AppRuntime.kick (security)")
                    param.result = null
                }
            })
        }
    }

    private fun shouldBlock(): Boolean {
        if (isSecurityStack()) return true
        // Cold-start window: QQ often self-kills right after hook scan (~0-90s).
        if (System.currentTimeMillis() - processStartMs > 90_000) return false
        return Thread.currentThread().stackTrace.any { frame ->
            val cn = frame.className
            cn.contains("com.tencent.mobileqq") &&
                (cn.contains("startup") ||
                    cn.contains("Guard") ||
                    cn.contains("qsec") ||
                    cn.contains("ArtTi") ||
                    cn.contains("NativeMonitor") ||
                    cn.contains("processkiller"))
        }
    }

    private fun isSecurityStack(): Boolean {
        return Thread.currentThread().stackTrace.any { frame ->
            val cn = frame.className
            cn.contains("qsec", ignoreCase = true) ||
                cn.contains("qsecurity", ignoreCase = true) ||
                cn.contains("ArtTiHook", ignoreCase = true) ||
                cn.contains("GuardCheck", ignoreCase = true) ||
                cn.contains("GuardManager", ignoreCase = true) ||
                cn.contains("GuardInit", ignoreCase = true) ||
                cn.contains("CodeCheck", ignoreCase = true) ||
                cn.contains("NativeMonitor", ignoreCase = true) ||
                cn.contains("processkiller", ignoreCase = true) ||
                cn.contains("libfekit", ignoreCase = true) ||
                cn.contains("dt.app", ignoreCase = true) ||
                cn.contains("mobileqq.fe", ignoreCase = true)
        }
    }

    private fun log(msg: String) {
        XposedBridge.log("[KillGuard] $msg")
    }
}
