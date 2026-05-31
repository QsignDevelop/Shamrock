package moe.RinShiona.Shamrock.xposed.helper

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicBoolean

/** HyperOS MSF: fix libmmkv ClassLoader + skip miuiFeatureInit. */
internal object MsfBootGuard {

    private val msfInstalled = AtomicBoolean(false)

    fun installMsf(classLoader: ClassLoader) {
        if (!msfInstalled.compareAndSet(false, true)) return
        hookMsfMmkvCallerFix()
        skipMiuiFeatureInit()
        XposedBridge.log("[MsfBootGuard] MSF minimal boot guard installed")
    }

    private fun hookMsfMmkvCallerFix() {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val lib = when (param.method.name) {
                    "loadLibrary" -> param.args.getOrNull(0) as? String
                    "loadLibrary0" -> param.args.getOrNull(if (param.args.size >= 3) 2 else 1) as? String
                    else -> null
                } ?: return
                if (!lib.contains("mmkv", ignoreCase = true)) return
                val loader = resolveHostClassLoader() ?: return
                runCatching {
                    val mmkvClass = loader.loadClass("com.tencent.mmkv.MMKV")
                    when (param.method.name) {
                        "loadLibrary0" -> when (param.args.size) {
                            3 -> { param.args[0] = loader; param.args[1] = mmkvClass }
                            2 -> param.args[0] = mmkvClass
                        }
                    }
                }
            }
        }
        runCatching { XposedBridge.hookAllMethods(Runtime::class.java, "loadLibrary0", hook) }
        runCatching { XposedBridge.hookAllMethods(Runtime::class.java, "loadLibrary", hook) }
    }

    private fun skipMiuiFeatureInit() {
        runCatching {
            val atCls = Class.forName("android.app.ActivityThread")
            XposedBridge.hookAllMethods(atCls, "miuiFeatureInit", object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any? = null
            })
        }
    }

    private fun resolveHostClassLoader(): ClassLoader? {
        return runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null)?.javaClass?.classLoader
        }.getOrNull() ?: runCatching {
            val at = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null) ?: return@runCatching null
            val bound = XposedHelpers.getObjectField(at, "mBoundApplication") ?: return@runCatching null
            val loadedApk = XposedHelpers.getObjectField(bound, "info") ?: return@runCatching null
            XposedHelpers.callMethod(loadedApk, "getClassLoader") as? ClassLoader
        }.getOrNull()
    }
}
