package moe.RinShiona.Shamrock.xposed.helper

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * QQ 9.2.90 wraps PackageManager / Runtime via Tencent Pandora (qmethod.pandoraex).
 * Dtc/QSec reads installed apps through [InstalledAppListMonitor] — bypassing direct
 * PackageManager hooks in AntiDetection.kt.
 */
internal object PandoraHideHooks {
    private const val INSTALLED_APP_MONITOR =
        "com.tencent.qmethod.pandoraex.monitor.InstalledAppListMonitor"
    private const val RUNTIME_MONITOR =
        "com.tencent.qmethod.pandoraex.monitor.RuntimeMonitor"
    private const val DEX_MONITOR =
        "com.tencent.qmethod.pandoraex.monitor.DexMonitor"

    private val installed = AtomicBoolean(false)

    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        hookInstalledAppMonitor(classLoader)
        hookRuntimeMonitor(classLoader)
        hookDexMonitor(classLoader)
    }

    private fun hookInstalledAppMonitor(classLoader: ClassLoader) {
        val cls = runCatching { classLoader.loadClass(INSTALLED_APP_MONITOR) }.getOrNull() ?: return
        cls.declaredMethods.forEach { method ->
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = filterSensitiveResult(param.result)
                    }
                })
            }
        }
        log("InstalledAppListMonitor hooked (${cls.declaredMethods.size} methods)")
    }

    private fun hookRuntimeMonitor(classLoader: ClassLoader) {
        runCatching {
            classLoader.loadClass(RUNTIME_MONITOR).declaredClasses.forEach { inner ->
                inner.declaredMethods.forEach { method ->
                    runCatching {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                param.result = filterSensitiveResult(param.result)
                            }
                        })
                    }
                }
                log("RuntimeMonitor.${inner.simpleName} hooked")
            }
            // Static/runtime entry points on the outer class.
            classLoader.loadClass(RUNTIME_MONITOR).declaredMethods.forEach { method ->
                runCatching {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result = filterSensitiveResult(param.result)
                        }
                    })
                }
            }
        }.onFailure { log("RuntimeMonitor hook skipped: ${it.message}") }
    }

    /** Blocks LSPosed InMemoryDex / xposed library loads from appearing in scans. */
    private fun hookDexMonitor(classLoader: ClassLoader) {
        val cls = runCatching { classLoader.loadClass(DEX_MONITOR) }.getOrNull() ?: return
        cls.declaredMethods.forEach { method ->
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        when (val r = param.result) {
                            is String -> param.result = ModuleHide.sanitizeValue(r) ?: r
                            is List<*> -> param.result = r.filter { item ->
                                item !is String || !ModuleHide.lineContainsSensitive(item)
                            }
                            is Array<*> -> param.result = r.filter { item ->
                                item !is String || !ModuleHide.lineContainsSensitive(item)
                            }.toTypedArray()
                        }
                    }
                })
            }
        }
        log("DexMonitor hooked (${cls.declaredMethods.size} methods)")
    }

    private fun filterSensitiveResult(result: Any?): Any? {
        return when (result) {
            is List<*> -> result.filter { item -> !itemReferencesHiddenPackage(item) }
            is Array<*> -> result.filter { item -> !itemReferencesHiddenPackage(item) }.toTypedArray()
            is String -> ModuleHide.sanitizeValue(result) ?: result
            else -> result
        }
    }

    private fun itemReferencesHiddenPackage(item: Any?): Boolean {
        if (item == null) return false
        if (item is String) {
            return ModuleHide.lineContainsSensitive(item) || ModuleHide.matchesPackage(item)
        }
        extractPackageName(item)?.let { pkg ->
            if (ModuleHide.matchesPackage(pkg)) return true
            if (ModuleHide.lineContainsSensitive(pkg)) return true
        }
        return false
    }

    private fun extractPackageName(item: Any): String? {
        val clz = item.javaClass
        for (fieldName in listOf("packageName", "processName")) {
            runCatching {
                val f = clz.getField(fieldName)
                (f.get(item) as? String)?.let { return it }
            }
        }
        runCatching {
            val activityInfo = clz.getField("activityInfo").get(item) ?: return@runCatching
            return activityInfo.javaClass.getField("packageName").get(activityInfo) as? String
        }
        runCatching {
            val serviceInfo = clz.getField("serviceInfo").get(item) ?: return@runCatching
            return serviceInfo.javaClass.getField("packageName").get(serviceInfo) as? String
        }
        return null
    }

    private fun log(msg: String) {
        XposedBridge.log("[PandoraHide] $msg")
    }
}
