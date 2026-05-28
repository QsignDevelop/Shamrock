package moe.RinShiona.Shamrock.xposed.actions

import android.os.Process
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicBoolean
import moe.RinShiona.Shamrock.xposed.helper.ModuleHideHooks

/**
 * Critical QQ 9.2.90 NT anti-tamper bypasses — MUST run before any other
 * Shamrock hook or NtTask (especially ArtTiHookTask) executes.
 *
 * Installed from [moe.RinShiona.Shamrock.xposed.XposedEntry.entryMQQ] with
 * only a ClassLoader; no Application context required.
 */
internal object EarlyAntiDetection {
    private val installed = AtomicBoolean(false)

    private val SKIP_TASK_KEYWORDS = listOf(
        "ArtTiHook", "ArtTi", "TiHook",
        "GuardCheck", "CodeCheck", "HookCheck",
        "SecurityScan", "EnvCheck", "XposedCheck",
        "AntiHook", "AntiTamper"
    )

    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        log("installing early bypass (classLoader phase)")

        hookQSecDetectMethod(classLoader)
        hookQSecExecTasks(classLoader)
        hookNtTaskLifecycle(classLoader)
        hookSelfKillGuard()
        ModuleHideHooks.installEarly(classLoader)
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

    private fun hookQSecExecTasks(classLoader: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.tencent.mobileqq.qsec.qsecurity.QSec",
                classLoader,
                "execTasks",
                android.content.Context::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any = 0
                }
            )
            log("QSec.execTasks -> 0")
        }.onFailure { log("QSec.execTasks hook failed: ${it.message}") }
    }

    private fun hookNtTaskLifecycle(classLoader: ClassLoader) {
        val skipHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val task = param.thisObject ?: return
                val taskId = readTaskId(task) ?: return
                if (shouldSkipTask(taskId)) {
                    log("skip NT task: $taskId (${param.method.name})")
                    param.result = null
                }
            }
        }

        listOf("onTaskStart", "onTaskFinish", "run").forEach { methodName ->
            runCatching {
                XposedHelpers.findAndHookMethod(
                    "com.tencent.qqnt.startup.task.NtTask",
                    classLoader,
                    methodName,
                    skipHook
                )
                log("NtTask.$methodName hooked")
            }.onFailure {
                // run() may not exist on abstract NtTask — ignore
            }
        }

        // Legacy ColdStartupTask path (pre-NT / hybrid builds)
        runCatching {
            val coldTask = classLoader.loadClass(
                "com.tencent.mobileqq.startup.task.config.ColdStartupTask"
            )
            coldTask.declaredMethods
                .filter { it.name.contains("run", ignoreCase = true) || it.name.contains("execute", ignoreCase = true) }
                .forEach { method ->
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val name = param.thisObject?.javaClass?.simpleName ?: return
                            if (shouldSkipTask(name)) {
                                log("skip ColdStartupTask: $name")
                                param.result = null
                            }
                        }
                    })
                }
            log("ColdStartupTask hooks installed")
        }.onFailure { log("ColdStartupTask not present: ${it.message}") }
    }

    private fun readTaskId(task: Any): String? {
        return runCatching {
            task.javaClass.getMethod("getTaskId").invoke(task) as? String
        }.getOrNull() ?: task.javaClass.simpleName
    }

    private fun shouldSkipTask(id: String): Boolean {
        return SKIP_TASK_KEYWORDS.any { id.contains(it, ignoreCase = true) }
    }

    /** Block QSec-triggered suicide while keeping normal exits elsewhere. */
    private fun hookSelfKillGuard() {
        val exitGuard = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isQSecKill()) return
                log("blocked self-terminate via ${param.method.declaringClass.simpleName}.${param.method.name}")
                param.result = null
            }
        }

        val killGuard = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val pid = param.args.getOrNull(0) as? Int ?: return
                if (pid != Process.myPid()) return
                if (!isQSecKill()) return
                log("blocked Process.killProcess($pid) from QSec stack")
                param.result = null
            }
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                Process::class.java,
                "killProcess",
                Int::class.javaPrimitiveType,
                killGuard
            )
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                System::class.java,
                "exit",
                Int::class.javaPrimitiveType,
                exitGuard
            )
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                Runtime::class.java,
                "exit",
                Int::class.javaPrimitiveType,
                exitGuard
            )
        }
    }

    private fun isQSecKill(): Boolean {
        return Thread.currentThread().stackTrace.any { frame ->
            val cn = frame.className
            cn.contains("qsec", ignoreCase = true) ||
                cn.contains("qsecurity", ignoreCase = true) ||
                cn.contains("ArtTiHook", ignoreCase = true) ||
                cn.contains("GuardCheck", ignoreCase = true) ||
                cn.contains("libfekit", ignoreCase = true)
        }
    }

    private fun log(msg: String) {
        XposedBridge.log("[EarlyAntiDetection] $msg")
    }
}
