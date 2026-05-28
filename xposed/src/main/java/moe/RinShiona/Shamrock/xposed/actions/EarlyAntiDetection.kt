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

    private val SKIP_TASK_IDS = setOf(
        "ArtTiHookTask",
        "ArtTiHook",
        "CodeCheckTask",
        "GuardCheckTask",
        "HookCheckTask",
    )

    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        log("installing early bypass (classLoader phase)")

        hookQSecDetectMethod(classLoader)
        // Do NOT blanket-block QSec.execTasks — it runs required FEKit / business init.
        hookNtTaskArtTiOnly(classLoader)
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

    private fun hookNtTaskArtTiOnly(classLoader: ClassLoader) {
        val skipHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val task = param.thisObject ?: return
                val taskId = readTaskId(task) ?: return
                if (shouldSkipTask(taskId)) {
                    log("skip NT anti-tamper task: $taskId")
                    param.result = null
                }
            }
        }

        // Only onTaskStart — do not touch run()/onTaskFinish or startup graph deadlocks.
        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.tencent.qqnt.startup.task.NtTask",
                classLoader,
                "onTaskStart",
                skipHook
            )
            log("NtTask.onTaskStart hooked (ArtTiHook-only)")
        }.onFailure { log("NtTask.onTaskStart hook failed: ${it.message}") }

        // ColdStartupTask enum — match constant name, not enum class name.
        runCatching {
            val coldTask = classLoader.loadClass(
                "com.tencent.mobileqq.startup.task.config.ColdStartupTask"
            )
            coldTask.enumConstants?.forEach { constant ->
                val name = constant?.javaClass?.simpleName ?: return@forEach
                if (!shouldSkipTask(name)) return@forEach
                constant.javaClass.declaredMethods
                    .filter { it.name == "onTaskStart" || it.name == "run" }
                    .forEach { method ->
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                log("skip ColdStartupTask.$name.${method.name}")
                                param.result = null
                            }
                        })
                    }
            }
            log("ColdStartupTask anti-tamper hooks installed")
        }.onFailure { log("ColdStartupTask not present: ${it.message}") }
    }

    private fun readTaskId(task: Any): String? {
        return runCatching {
            task.javaClass.getMethod("getTaskId").invoke(task) as? String
        }.getOrNull() ?: task.javaClass.simpleName
    }

    private fun shouldSkipTask(id: String): Boolean {
        if (SKIP_TASK_IDS.any { id.equals(it, ignoreCase = true) }) return true
        // Obfuscated builds may embed these tokens inside taskId strings.
        return id.contains("ArtTiHook", ignoreCase = true) ||
            id.contains("CodeCheck", ignoreCase = true) ||
            (id.contains("GuardCheck", ignoreCase = true) && !id.contains("GuardInit", ignoreCase = true))
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
