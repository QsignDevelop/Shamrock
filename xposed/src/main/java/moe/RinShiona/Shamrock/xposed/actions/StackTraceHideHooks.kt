package moe.RinShiona.Shamrock.xposed.actions

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import moe.RinShiona.Shamrock.xposed.helper.ModuleHide
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scrub Xposed / Shamrock frames from stack traces when QSec reads them.
 */
internal object StackTraceHideHooks {
    private val installed = AtomicBoolean(false)

    fun install() {
        if (!installed.compareAndSet(false, true)) return

        // Always strip Xposed frames — sensitive-method scans run inside Pandora/Dtc
        // and the stack still contains our hook frames even when the caller is not QSec.
        val traceFilter = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val trace = param.result as? Array<*> ?: return
                val filtered = filterStackTrace(trace)
                if (filtered.size != trace.size) {
                    @Suppress("UNCHECKED_CAST")
                    param.result = filtered
                }
            }
        }

        runCatching { XposedBridge.hookAllMethods(Throwable::class.java, "getStackTrace", traceFilter) }
        runCatching { XposedBridge.hookAllMethods(Thread::class.java, "getStackTrace", traceFilter) }
        runCatching { XposedBridge.hookAllMethods(Throwable::class.java, "getOurStackTrace", traceFilter) }

        runCatching {
            val logClass = Class.forName("android.util.Log")
            XposedBridge.hookAllMethods(logClass, "getStackTraceString", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val text = param.result as? String ?: return
                    val sanitized = sanitizeStackTraceText(text)
                    if (sanitized != text) param.result = sanitized
                }
            })
        }

        runCatching {
            XposedBridge.hookAllMethods(StackTraceElement::class.java, "toString", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val text = param.result as? String ?: return
                    if (frameTextIsSensitive(text)) {
                        param.result = "java.lang.Object.<init>(Unknown Source)"
                    }
                }
            })
        }

        XposedBridge.log("[StackTraceHide] installed")
    }

    private fun filterStackTrace(trace: Array<*>): Array<StackTraceElement> {
        @Suppress("UNCHECKED_CAST")
        return trace.filter { frame ->
            val cn = when (frame) {
                is StackTraceElement -> frame.className
                else -> frame?.toString().orEmpty()
            }
            !frameClassIsSensitive(cn)
        }.map { it as StackTraceElement }.toTypedArray()
    }

    private fun frameClassIsSensitive(className: String): Boolean {
        return ModuleHide.traceKeywords.any { className.contains(it, ignoreCase = true) } ||
            className.contains("xposed", ignoreCase = true) ||
            className.contains("lsposed", ignoreCase = true) ||
            className.contains("EdXposed", ignoreCase = true)
    }

    private fun frameTextIsSensitive(text: String): Boolean {
        return ModuleHide.traceKeywords.any { text.contains(it, ignoreCase = true) } ||
            text.contains("xposed", ignoreCase = true) ||
            text.contains("lsposed", ignoreCase = true)
    }

    private fun sanitizeStackTraceText(text: String): String {
        return text.lineSequence()
            .filterNot { line -> frameTextIsSensitive(line) }
            .joinToString("\n")
    }
}
