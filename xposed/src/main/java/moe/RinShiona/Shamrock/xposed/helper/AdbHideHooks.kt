package moe.RinShiona.Shamrock.xposed.helper

import android.provider.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hide USB/ADB/debug developer state from QQ / libfekit sign-time probes.
 *
 * Observed in sign extra when ADB is on: byte[3]=0x02 (adb_enabled) and trailer 04 02.
 * libfekit may read Settings.Secure, SystemProperties, or /proc/self/status — not
 * always with a QSec/Dtc class on the stack, so we use [isSignOrSecurityProbe].
 */
internal object AdbHideHooks {

    private val installed = AtomicBoolean(false)

    private val SECURE_KEYS = setOf(
        "adb_enabled",
        "adb_wifi_enabled",
        "development_settings_enabled",
        "adb_wifi_secure_settings",
    )

    private val GLOBAL_KEYS = setOf(
        "adb_enabled",
        "development_settings_enabled",
        "adb_wifi_enabled",
    )

    fun install(classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        hookSettingsSecure()
        hookSettingsGlobal()
        hookSettingsSystem()
        log("ADB/dev-settings hide installed")
    }

    /** Broader than [ModuleHide.isSecurityScannerCaller] — includes libfekit / sign JNI paths. */
    fun isSignOrSecurityProbe(): Boolean {
        if (ModuleHide.isSecurityScannerCaller()) return true
        return Thread.currentThread().stackTrace.any { frame ->
            val cn = frame.className
            cn.contains("fekit", ignoreCase = true) ||
                cn.contains("FEKit", ignoreCase = true) ||
                cn.contains("mobileqq.sign", ignoreCase = true) ||
                cn.contains("QQSecuritySign", ignoreCase = true) ||
                cn.contains("qsec", ignoreCase = true) ||
                cn.contains("qsecurity", ignoreCase = true) ||
                cn.contains(".dt.", ignoreCase = true) ||
                cn.contains("qmethod", ignoreCase = true) ||
                cn.contains("pandoraex", ignoreCase = true) ||
                cn.contains("ArtTiHook", ignoreCase = true) ||
                cn.contains("GuardCheck", ignoreCase = true)
        }
    }

    private fun isAdbRelatedKey(key: String?): Boolean {
        if (key.isNullOrEmpty()) return false
        val k = key.lowercase()
        return k.contains("adb") ||
            k.contains("development_settings") ||
            k.contains("debug_app") ||
            k == "adb_enabled"
    }

    private fun isMsfProcess(): Boolean =
        kotlin.runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentProcessName")
                .invoke(null) as? String
        }.getOrNull()?.endsWith(":MSF") == true

    /** MSF generates sign extra; hide ADB even when libfekit has no recognizable stack frame. */
    fun shouldHideAdb(): Boolean = isMsfProcess() || isSignOrSecurityProbe()

    private fun hookSettingsSecure() {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!shouldHideAdb()) return
                val key = param.args.getOrNull(1) as? String ?: return
                if (!isAdbRelatedKey(key) && key !in SECURE_KEYS) return
                param.result = when (param.method.name) {
                    "getInt" -> 0
                    "getLong" -> 0L
                    "getFloat" -> 0f
                    else -> "0"
                }
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(Settings.Secure::class.java, "getInt", hook)
            XposedBridge.hookAllMethods(Settings.Secure::class.java, "getString", hook)
            XposedBridge.hookAllMethods(Settings.Secure::class.java, "getLong", hook)
        }
    }

    private fun hookSettingsGlobal() {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!shouldHideAdb()) return
                val key = param.args.getOrNull(1) as? String ?: return
                if (!isAdbRelatedKey(key) && key !in GLOBAL_KEYS) return
                param.result = when (param.method.name) {
                    "getInt" -> 0
                    "getLong" -> 0L
                    else -> "0"
                }
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(Settings.Global::class.java, "getInt", hook)
            XposedBridge.hookAllMethods(Settings.Global::class.java, "getString", hook)
        }
    }

    private fun hookSettingsSystem() {
        runCatching {
            XposedHelpers.findAndHookMethod(
                Settings.System::class.java,
                "getInt",
                android.content.ContentResolver::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!shouldHideAdb()) return
                        val key = param.args.getOrNull(1) as? String ?: return
                        if (!isAdbRelatedKey(key)) return
                        param.result = 0
                    }
                },
            )
        }
    }

    private fun log(msg: String) {
        XposedBridge.log("[AdbHide] $msg")
    }
}
