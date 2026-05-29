package moe.RinShiona.Shamrock.xposed.helper

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Swallows non-fatal QQ-internal JNI failures that would otherwise abort the
 * whole process via art's `JNI FatalError`.
 *
 * Observed on QQ NT 9.2.90 / HyperOS:
 *
 *   java.lang.UnsatisfiedLinkError: No implementation found for void
 *   com.tencent.mobileqq.cmark.NativeLib.registerExt()
 *     (tried Java_com_tencent_mobileqq_cmark_NativeLib_registerExt ...)
 *
 * `registerExt` registers an optional cmark (rich-text) native extension. The
 * binding is missing under this ROM/version regardless of Shamrock, and the
 * thrown UnsatisfiedLinkError bubbles into QQ's CrashDefender → process kill.
 * Replacing the native method with a Java no-op turns a fatal crash into a
 * silent skip (the feature was already non-functional).
 *
 * This is intentionally surgical: we only no-op known-safe optional natives,
 * never anything on the sign / login path.
 */
internal object NativeCrashGuard {

    private val installed = AtomicBoolean(false)

    // class name -> method names that are safe to neutralize when their native
    // binding is missing.
    private val SAFE_NOOP_NATIVES = mapOf(
        "com.tencent.mobileqq.cmark.NativeLib" to listOf("registerExt")
    )

    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        SAFE_NOOP_NATIVES.forEach { (className, methods) ->
            val cls = runCatching { classLoader.loadClass(className) }.getOrNull() ?: return@forEach
            methods.forEach { name ->
                runCatching {
                    XposedBridge.hookAllMethods(cls, name, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            // Skip the (missing) native body entirely.
                            param.result = defaultFor(param.method)
                        }
                    })
                    log("guarded $className.$name (no-op)")
                }.onFailure {
                    log("failed to guard $className.$name: ${it.message}")
                }
            }
        }
    }

    private fun defaultFor(method: java.lang.reflect.Member): Any? {
        val rt = (method as? java.lang.reflect.Method)?.returnType ?: return null
        return when (rt) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Void.TYPE -> null
            else -> null
        }
    }

    private fun log(msg: String) {
        XposedBridge.log("[NativeCrashGuard] $msg")
    }
}
