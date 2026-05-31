package moe.RinShiona.Shamrock.xposed.helper

import android.content.Intent
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import moe.RinShiona.Shamrock.remote.service.config.ShamrockConfig
import moe.RinShiona.Shamrock.xposed.AntiDetectionConfig
import moe.RinShiona.Shamrock.xposed.helper.ModuleHide

/**
 * Load Shamrock App settings from LSPosed-shared SharedPreferences.
 *
 * HyperOS/MIUI AppsFilter often blocks Shamrock App -> QQ broadcasts and breaks
 * the ContentProvider init callback path. XSharedPreferences reads the same
 * `config` file the App writes, without cross-app IPC.
 */
internal object XPrefConfigLoader {
    private const val MODULE_PKG = ModuleHide.PACKAGE
    private const val PREF_NAME = "config"

    private var lastRevision = -1L

    /** 当 Shamrock App 修改 config SharedPreferences 后返回 true。 */
    fun reloadIfChanged(): Boolean {
        return try {
            val prefs = XSharedPreferences(MODULE_PKG, PREF_NAME)
            prefs.reload()
            if (!prefs.file.canRead()) return false
            val rev = prefs.getLong("config_revision", prefs.file.lastModified())
            if (rev == lastRevision && lastRevision >= 0) return false
            lastRevision = rev
            loadIfAvailable()
        } catch (_: Throwable) {
            false
        }
    }

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
            putExtra("anti_connectivity_safe", prefs.getBoolean("anti_connectivity_safe", true))
            putExtra("anti_qsec_heavy", prefs.getBoolean("anti_qsec_heavy", false))
            putExtra("anti_detection_enabled", prefs.getBoolean("anti_detection_enabled", true))
        }
    }

    private fun applyAntiDetection(prefs: XSharedPreferences) {
        val connectivitySafe = prefs.getBoolean("anti_connectivity_safe", true)
        AntiDetectionConfig.apply {
            connectivitySafeMode = connectivitySafe
            if (connectivitySafe) {
                // 联网优先：强制开轻量反检测（防踢号），关 QSec 重度绕过
                enabled = true
                earlyEnabled = true
                qsecHeavyBypass = false
                hideNetwork = false
                useRemoteQSign = false
            } else {
                enabled = prefs.getBoolean("anti_detection_enabled", false)
                earlyEnabled = prefs.getBoolean("anti_early_enabled", enabled)
                qsecHeavyBypass = prefs.getBoolean("anti_qsec_heavy", false)
                hideNetwork = false
                useRemoteQSign = prefs.getBoolean("anti_use_remote_qsign", false)
            }
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
        XposedBridge.log(
            "Shamrock: anti-config connectivitySafe=$connectivitySafe enabled=${AntiDetectionConfig.enabled}",
        )
        QSignConfig.apply(
            signMode = QSignConfig.MODE_MSF,
            signTraceEnabled = prefs.getBoolean("qsign_sign_trace", false),
        )
    }

    fun reloadQSignOnly(): Boolean {
        return try {
            val prefs = XSharedPreferences(MODULE_PKG, PREF_NAME)
            prefs.reload()
            if (!prefs.file.canRead()) return false
            applyAntiDetection(prefs)
            true
        } catch (e: Throwable) {
            false
        }
    }
}
