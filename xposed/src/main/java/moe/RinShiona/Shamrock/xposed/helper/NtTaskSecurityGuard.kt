package moe.RinShiona.Shamrock.xposed.helper

import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicBoolean
import moe.RinShiona.Shamrock.xposed.AntiDetectionConfig
import moe.RinShiona.Shamrock.xposed.actions.EarlyAntiDetection
/**
 * Arm Java-layer QSec/Dtc hooks BEFORE ArtTiHookTask (attach stage).
 * Do NOT load libshadowhook/libshamrocknt here — early dlopen breaks QQ native startup.
 */
internal object NtTaskSecurityGuard {
    private val ntHookInstalled = AtomicBoolean(false)
    private val preArtTiReady = AtomicBoolean(false)

    fun install(classLoader: ClassLoader) {
        if (!ntHookInstalled.compareAndSet(false, true)) return
        runCatching {
            val ntTask = classLoader.loadClass("com.tencent.qqnt.startup.task.NtTask")
            XposedHelpers.findAndHookMethod(ntTask, "onTaskStart", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val taskId = resolveTaskId(param.thisObject) ?: return
                    if (taskId.contains("ArtTiHook", ignoreCase = true) ||
                        taskId.contains("GuardInit", ignoreCase = true) ||
                        taskId.contains("CodeCheck", ignoreCase = true) ||
                        taskId.contains("DtSdkInit", ignoreCase = true)
                    ) {
                        onSecurityTask(classLoader, taskId)
                    }
                }
            })
            log("NtTask.onTaskStart guard installed")
        }.onFailure {
            log("NtTask guard failed: ${it.message}")
        }
    }

    private fun onSecurityTask(classLoader: ClassLoader, taskId: String) {
        if (!preArtTiReady.compareAndSet(false, true)) return
        if (!AntiDetectionConfig.allowEarlyHooks()) return
        log("critical Java hooks before $taskId (no native load)")
        patchBuildTags()
        EarlyAntiDetection.installCritical(classLoader)
        log("critical hooks ready for $taskId")
    }

    private fun patchBuildTags() {
        kotlin.runCatching {
            val tags = Build.TAGS ?: return
            if (tags.contains("test", ignoreCase = true)) {
                XposedHelpers.setStaticObjectField(Build::class.java, "TAGS", "release-keys")
            }
        }
    }

    private fun resolveTaskId(task: Any?): String? {
        if (task == null) return null
        val cls = task.javaClass
        for (methodName in listOf("getTaskId", "getTaskID", "getId", "getName")) {
            kotlin.runCatching {
                val m = cls.getMethod(methodName)
                (m.invoke(task) as? String)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        for (fieldName in listOf("taskId", "taskID", "mTaskId", "id")) {
            kotlin.runCatching {
                val f = cls.getDeclaredField(fieldName)
                f.isAccessible = true
                val v = f.get(task)
                if (v is String && v.isNotBlank()) return v
                val text = v?.toString()
                if (!text.isNullOrBlank()) return text
            }
        }
        return cls.simpleName
    }

    private fun log(msg: String) {
        XposedBridge.log("[NtTaskGuard] $msg")
    }
}
