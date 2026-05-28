package moe.RinShiona.Shamrock.xposed.helper

import android.content.Intent
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import moe.RinShiona.Shamrock.remote.service.config.ShamrockConfig
import moe.RinShiona.Shamrock.xposed.AntiDetectionConfig

/**
 * Load Shamrock App settings from LSPosed-shared SharedPreferences.
 *
 * HyperOS/MIUI AppsFilter often blocks Shamrock App -> QQ broadcasts and breaks
 * the ContentProvider init callback path. XSharedPreferences reads the same
 * `config` file the App writes, without cross-app IPC.
 */
internal object XPrefConfigLoader {
    private const val MODULE_PKG = "moe.RinShiona.Shamrock"
    private const val PREF_NAME = "config"

    fun loadIfAvailable(): Boolean {
        return try {
            val prefs = XSharedPreferences(MODULE_PKG, PREF_NAME)
            prefs.reload()
            if (!prefs.file.canRead()) {
                XposedBridge.log("Shamrock: XSharedPreferences not readable — open Shamrock App once")
                return false
            }
            if (prefs.all.isEmpty()) {
                XposedBridge.log("Shamrock: XSharedPreferences empty — configure Shamrock App first")
                return false
            }

            ShamrockConfig.updateConfig(prefsToIntent(prefs))
            applyAntiDetection(prefs)
            XposedBridge.log("Shamrock: config loaded via XSharedPreferences (${prefs.all.size} keys)")
            true
        } catch (e: Throwable) {
            XposedBridge.log("Shamrock: XSharedPreferences load failed: ${e.message}")
            false
        }
    }

    private fun prefsToIntent(prefs: XSharedPreferences): Intent {
        val neko = prefs.getBoolean("neko_api", prefs.getBoolean("pro_api", false))
        return Intent().apply {
            putExtra("tablet", prefs.getBoolean("tablet", false))
            putExtra("port", prefs.getInt("port", 5700))
            putExtra("ws", prefs.getBoolean("ws", false))
            putExtra("ws_port", prefs.getInt("ws_port", 5800))
            putExtra("ssl_port", prefs.getInt("ssl_port", 5701))
            putExtra("http", prefs.getBoolean("webhook", false))
            putExtra("http_addr", prefs.getString("http_addr", ""))
            putExtra("ws_client", prefs.getBoolean("ws_client", false))
            putExtra("use_cqcode", prefs.getBoolean("use_cqcode", false))
            putExtra("ws_addr", prefs.getString("ws_addr", ""))
            putExtra("neko_api", neko)
            putExtra("pro_api", neko)
            putExtra("token", prefs.getString("token", null))
            putExtra("inject_packet", prefs.getBoolean("inject_packet", false))
            putExtra("debug", prefs.getBoolean("debug", false))
            putExtra("auto_clear", prefs.getBoolean("auto_clear", false))
            putExtra("enable_self_msg", prefs.getBoolean("enable_self_msg", false))
            putExtra("echo_number", prefs.getBoolean("echo_number", false))
            putExtra("key_store", prefs.getString("key_store", ""))
            putExtra("ssl_pwd", prefs.getString("ssl_pwd", ""))
            putExtra("ssl_private_pwd", prefs.getString("ssl_private_pwd", ""))
            putExtra("ssl_alias", prefs.getString("ssl_alias", ""))
        }
    }

    private fun applyAntiDetection(prefs: XSharedPreferences) {
        AntiDetectionConfig.apply {
            enabled = prefs.getBoolean("anti_detection_enabled", true)
            hideXposed = prefs.getBoolean("anti_hide_xposed", true)
            hideRoot = prefs.getBoolean("anti_hide_root", true)
            hideMagisk = prefs.getBoolean("anti_hide_magisk", true)
            hideDebug = prefs.getBoolean("anti_hide_debug", true)
            hideFiles = prefs.getBoolean("anti_hide_files", true)
            hideProps = prefs.getBoolean("anti_hide_props", true)
            hideSignature = prefs.getBoolean("anti_hide_signature", true)
            hideEmulator = prefs.getBoolean("anti_hide_emulator", true)
            fakeDevice = prefs.getBoolean("anti_fake_device", true)
            hookSign = prefs.getBoolean("anti_hook_sign", true)
            debugLog = prefs.getBoolean("anti_debug_log", false)
            qsignServerUrl = prefs.getString("anti_qsign_url", "http://127.0.0.1:8080") ?: "http://127.0.0.1:8080"
        }
    }
}
