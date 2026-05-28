package moe.RinShiona.Shamrock.xposed.helper

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * QQ 9.2.90 privacy layer — [PackageInstallMonitorKt] bypasses Pandora/Dtc for
 * authorized package probes (checkAppInstalled / isAppInstalled / batch variants).
 * Methods are obfuscated (a..g) but all take (Context, String, ...) signatures.
 */
internal object PackageInstallMonitorHooks {
    private const val CLS =
        "com.tencent.mobileqq.util.privacy.PackageInstallMonitorKt"

    private val installed = AtomicBoolean(false)

    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        val cls = runCatching { classLoader.loadClass(CLS) }.getOrNull()
        if (cls == null) {
            log("PackageInstallMonitorKt not found in classLoader")
            return
        }
        cls.declaredMethods.forEach { method ->
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!argsReferenceHiddenPackage(param.args)) return
                        when (method.returnType) {
                            Boolean::class.javaPrimitiveType,
                            java.lang.Boolean::class.java -> param.result = false
                            String::class.java -> param.result = ""
                            Int::class.javaPrimitiveType -> param.result = 0
                            Long::class.javaPrimitiveType -> param.result = 0L
                            Void.TYPE -> param.result = null
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = sanitizeResult(param.result, param.args)
                    }
                })
            }
        }
        log("PackageInstallMonitorKt hooked (${cls.declaredMethods.size} methods)")
    }

    private fun argsReferenceHiddenPackage(args: Array<Any?>): Boolean {
        return args.any { arg ->
            when (arg) {
                is String -> isHiddenPackageToken(arg)
                is Array<*> -> arg.any { it is String && isHiddenPackageToken(it) }
                is List<*> -> arg.any { it is String && isHiddenPackageToken(it) }
                else -> false
            }
        }
    }

    private fun isHiddenPackageToken(value: String): Boolean {
        if (ModuleHide.matchesPackage(value)) return true
        if (value.contains(',') || value.contains(';')) {
            return value.split(',', ';').any { token ->
                val t = token.trim()
                t.isNotEmpty() && (ModuleHide.matchesPackage(t) || ModuleHide.lineContainsSensitive(t))
            }
        }
        return value.contains('.') && ModuleHide.lineContainsSensitive(value)
    }

    private fun sanitizeResult(result: Any?, args: Array<Any?>): Any? {
        if (argsReferenceHiddenPackage(args)) {
            return when (result) {
                is Boolean -> false
                is String -> ""
                null -> null
                else -> result
            }
        }
        return when (result) {
            is String -> ModuleHide.sanitizeValue(result)?.let {
                ModuleHide.filterSensitiveLines(it)
            } ?: result
            is Boolean -> {
                if (result && args.any { it is String && ModuleHide.matchesPackage(it) }) false
                else result
            }
            is Array<*> -> result.filter { item ->
                item !is String || !isHiddenPackageToken(item)
            }.toTypedArray()
            is List<*> -> result.filter { item ->
                item !is String || !isHiddenPackageToken(item)
            }
            else -> result
        }
    }

    private fun log(msg: String) {
        XposedBridge.log("[PackageInstallHide] $msg")
    }
}
