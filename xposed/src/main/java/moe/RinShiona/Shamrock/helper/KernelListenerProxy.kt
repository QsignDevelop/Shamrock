package moe.RinShiona.Shamrock.helper

import de.robv.android.xposed.XposedBridge
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Wraps a CherryPop listener delegate in a JDK proxy that implements QQ's
 * *runtime* kernel listener interface (loaded from QQ's ClassLoader).
 *
 * QQ NT 9.2.90+ adds methods to IKernelGroupListener / IKernelMsgListener on
 * every release. A compile-time stub cannot stay in sync; missing overrides
 * surface as AbstractMethodError → JNI FatalError → main process death.
 *
 * Unknown methods are no-ops; known methods are forwarded to [delegate] when
 * name and arity match.
 */
internal object KernelListenerProxy {

    fun wrapGroupListener(loader: ClassLoader, delegate: Any): Any {
        val iface = loader.loadClass("com.tencent.qqnt.kernel.nativeinterface.IKernelGroupListener")
        return newProxy(loader, iface, delegate, "IKernelGroupListener")
    }

    fun wrapMsgListener(loader: ClassLoader, delegate: Any): Any {
        val iface = loader.loadClass("com.tencent.qqnt.kernel.nativeinterface.IKernelMsgListener")
        return newProxy(loader, iface, delegate, "IKernelMsgListener")
    }

    private fun newProxy(loader: ClassLoader, iface: Class<*>, delegate: Any, label: String): Any {
        val handler = ListenerInvocationHandler(delegate, label)
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(loader, arrayOf(iface), handler)
    }

    private class ListenerInvocationHandler(
        private val delegate: Any,
        private val label: String,
    ) : InvocationHandler {

        private val methodsByName: Map<String, List<Method>> =
            delegate.javaClass.methods.groupBy { it.name }

        override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
            if (method.declaringClass == Any::class.java) {
                return when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "$label-proxy@${System.identityHashCode(proxy).toString(16)}"
                    else -> null
                }
            }

            val argc = args?.size ?: 0
            val candidates = methodsByName[method.name].orEmpty()
            for (candidate in candidates) {
                if (candidate.parameterCount != argc) continue
                runCatching {
                    candidate.isAccessible = true
                    return candidate.invoke(delegate, *(args ?: emptyArray()))
                }.onFailure { e ->
                    if (e !is IllegalArgumentException) {
                        XposedBridge.log("Shamrock: $label.${method.name} forward failed: ${e.message}")
                    }
                }
            }
            return defaultReturn(method.returnType)
        }

        private fun defaultReturn(type: Class<*>): Any? = when (type) {
            Void.TYPE -> null
            Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> false
            Byte::class.javaPrimitiveType, java.lang.Byte::class.java -> 0.toByte()
            Short::class.javaPrimitiveType, java.lang.Short::class.java -> 0.toShort()
            Int::class.javaPrimitiveType, Integer::class.java -> 0
            Long::class.javaPrimitiveType, java.lang.Long::class.java -> 0L
            Float::class.javaPrimitiveType, java.lang.Float::class.java -> 0f
            Double::class.javaPrimitiveType, java.lang.Double::class.java -> 0.0
            Char::class.javaPrimitiveType, Character::class.java -> '\u0000'
            else -> null
        }
    }
}
