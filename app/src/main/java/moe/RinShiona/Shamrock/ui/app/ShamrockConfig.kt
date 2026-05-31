package moe.RinShiona.Shamrock.ui.app

import android.content.Context
import moe.RinShiona.Shamrock.ui.service.internal.broadcastToModule

object ShamrockConfig {
    fun getSSLKeyPath(ctx: Context): String {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getString("key_store", "")!!
    }

    fun setSSLKeyPath(ctx: Context, path: String) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putString("key_store", path).apply()
        pushUpdate(ctx)
    }

    fun getSSLPort(ctx: Context): Int {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getInt("ssl_port", 5701)
    }

    fun setSSLPort(ctx: Context, port: Int) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putInt("ssl_port", port).apply()
        pushUpdate(ctx)
    }

    fun getSSLAlias(ctx: Context): String {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getString("ssl_alias", "")!!
    }

    fun setSSLAlias(ctx: Context, alias: String) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putString("ssl_alias", alias).apply()
        pushUpdate(ctx)
    }

    fun getSSLPwd(ctx: Context): String {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getString("ssl_pwd", "")!!
    }

    fun setSSLPwd(ctx: Context, alias: String) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putString("ssl_pwd", alias).apply()
        pushUpdate(ctx)
    }

    fun getSSLPrivatePwd(ctx: Context): String {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getString("ssl_private_pwd", "")!!
    }

    fun setSSLPrivatePwd(ctx: Context, alias: String) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putString("ssl_private_pwd", alias).apply()
        pushUpdate(ctx)
    }

    fun getHttpAddr(ctx: Context): String {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getString("http_addr", "")!!
    }

    fun setHttpAddr(ctx: Context, v: String) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putString("http_addr", v).apply()
        pushUpdate(ctx)
    }

    fun isNeko(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("neko_api", preferences.getBoolean("pro_api", true))
    }

    fun setNeko(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit()
            .putBoolean("neko_api", v)
            .putBoolean("pro_api", v)
            .apply()
        ctx.broadcastToModule {
            putExtra("type", "restart")
            putExtra("__cmd", "change_port")
        }
        pushUpdate(ctx)
    }

    @Deprecated("Use isNeko()", ReplaceWith("isNeko(ctx)"))
    fun isPro(ctx: Context): Boolean = isNeko(ctx)

    @Deprecated("Use setNeko()", ReplaceWith("setNeko(ctx, v)"))
    fun setPro(ctx: Context, v: Boolean) = setNeko(ctx, v)

    fun getToken(ctx: Context): String {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getString("token", null) ?: ""
    }

    fun setToken(ctx: Context, v: String?) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putString("token", v).apply()
        pushUpdate(ctx)
    }

    fun isWs(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("ws", false)
    }

    fun setWs(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("ws", v).apply()
        pushUpdate(ctx)
    }

    fun isWsClient(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("ws_client", false)
    }

    fun setWsClient(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("ws_client", v).apply()
        pushUpdate(ctx)
    }

    fun isTablet(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("tablet", false)
    }

    fun setTablet(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("tablet", v).apply()
        pushUpdate(ctx)
    }

    fun isUseCQCode(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("use_cqcode", false)
    }

    fun setUseCQCode(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("use_cqcode", v).apply()
        pushUpdate(ctx)
    }

    fun isWebhook(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("webhook", false)
    }

    fun setWebhook(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("webhook", v).apply()
        pushUpdate(ctx)
    }

    /** OneBot v11 总开关：关闭后不启动 HTTP/WS，且 App 内不显示日志页。 */
    fun isOneBotV11Enabled(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("onebot_v11", true)
    }

    fun setOneBotV11Enabled(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("onebot_v11", v).apply()
        AppRuntime.uiLogEnabled = v
        pushUpdate(ctx)
    }

    fun getWsAddr(ctx: Context): String {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getString("ws_addr", "")!!
    }

    fun setWsAddr(ctx: Context, v: String) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putString("ws_addr", v).apply()
        pushUpdate(ctx)
    }

    fun getHttpPort(ctx: Context): Int {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getInt("port", 5700)
    }

    fun setHttpPort(ctx: Context, v: Int) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putInt("port", v).apply()
        ctx.broadcastToModule {
            putExtra("type", "port")
            putExtra("port", v)
            putExtra("__cmd", "change_port")
        }
        pushUpdate(ctx)
    }

    fun getWsPort(ctx: Context): Int {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getInt("ws_port", 5800)
    }

    fun setWsPort(ctx: Context, v: Int) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putInt("ws_port", v).apply()
        ctx.broadcastToModule {
            putExtra("type", "ws_port")
            putExtra("port", v)
            putExtra("__cmd", "change_port")
        }
        pushUpdate(ctx)
    }

    fun is2B(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("2B", false)
    }

    fun set2B(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("2B", v).apply()
    }

    fun setAutoClean(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("auto_clear", v).apply()
    }

    fun isAutoClean(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("auto_clear", false)
    }

    fun isDebug(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("debug", false)
    }

    fun setDebug(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("debug", v).apply()
    }

    fun isInjectPacket(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("inject_packet", false)
    }

    fun setInjectPacket(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("inject_packet", v).apply()
    }

    fun enableAutoStart(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("enable_auto_start", false)
    }

    fun setAutoStart(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("enable_auto_start", v).apply()
    }

    fun enableSelfMsg(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("enable_self_msg", false)
    }

    fun setEnableSelfMsg(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("enable_self_msg", v).apply()
    }

    fun isEchoNumber(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("echo_number", false)
    }

    fun setEchoNumber(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("echo_number", v).apply()
    }

    fun getConfigMap(ctx: Context): Map<String, Any?> {
        val preferences = ctx.getSharedPreferences("config", 0)
        return mapOf(
            "tablet" to preferences.getBoolean("tablet", false),
            "port" to preferences.getInt("port", 5700),
            "ws" to preferences.getBoolean("ws", false),
            "ws_port" to preferences.getInt("ws_port", 5800),
            "ssl_port" to preferences.getInt("ssl_port", 5701),
            "http" to preferences.getBoolean("webhook", false),
            "http_addr" to preferences.getString("http_addr", ""),
            "ws_client" to preferences.getBoolean("ws_client", false),
            "use_cqcode" to preferences.getBoolean("use_cqcode", false),
            "ws_addr" to preferences.getString("ws_addr", ""),
            "ssl_alias" to preferences.getString("ssl_alias", ""),
            "neko_api" to preferences.getBoolean("neko_api", preferences.getBoolean("pro_api", false)),
            "pro_api" to preferences.getBoolean("neko_api", preferences.getBoolean("pro_api", false)),
            "token" to preferences.getString("token", null),
            "ssl_pwd" to preferences.getString("ssl_pwd", ""),
            "inject_packet" to preferences.getBoolean("inject_packet", false),
            "debug" to preferences.getBoolean("debug", false),
            "auto_clear" to preferences.getBoolean("auto_clear", false),
            "ssl_private_pwd" to preferences.getString("ssl_private_pwd", ""),
            "key_store" to preferences.getString("key_store", ""),
            "enable_self_msg" to preferences.getBoolean("enable_self_msg", false),
            "echo_number" to preferences.getBoolean("echo_number", false),
            "onebot_v11" to preferences.getBoolean("onebot_v11", true),
            // Anti-Detection Config
            "anti_detection_enabled" to preferences.getBoolean("anti_detection_enabled", true),
            "anti_connectivity_safe" to preferences.getBoolean("anti_connectivity_safe", true),
            "anti_qsec_heavy" to preferences.getBoolean("anti_qsec_heavy", false),
            "anti_hide_xposed" to preferences.getBoolean("anti_hide_xposed", true),
            "anti_hide_root" to preferences.getBoolean("anti_hide_root", true),
            "anti_hide_magisk" to preferences.getBoolean("anti_hide_magisk", true),
            "anti_hide_debug" to preferences.getBoolean("anti_hide_debug", true),
            "anti_hide_files" to preferences.getBoolean("anti_hide_files", true),
            "anti_hide_props" to preferences.getBoolean("anti_hide_props", true),
            "anti_hide_signature" to preferences.getBoolean("anti_hide_signature", true),
            "anti_hide_emulator" to preferences.getBoolean("anti_hide_emulator", true),
            "anti_fake_device" to preferences.getBoolean("anti_fake_device", true),
            "anti_hook_sign" to preferences.getBoolean("anti_hook_sign", true),
            "anti_debug_log" to preferences.getBoolean("anti_debug_log", false),
            "anti_qsign_url" to preferences.getString("anti_qsign_url", "http://127.0.0.1:8080")
        )
    }

    fun pushUpdate(ctx: Context) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit()
            .putLong("config_revision", System.currentTimeMillis())
            .commit()
        ctx.broadcastToModule {
            getConfigMap(ctx).forEach { (key, value) ->
                if (value == null) {
                    val v: String? = null
                    this.putExtra(key, v)
                } else {
                    when (value) {
                        is Int -> this.putExtra(key, value)
                        is Long -> this.putExtra(key, value)
                        is Short -> this.putExtra(key, value)
                        is Byte -> this.putExtra(key, value)
                        is String -> this.putExtra(key, value)
                        is ByteArray -> this.putExtra(key, value)
                        is Boolean -> this.putExtra(key, value)
                        is Float -> this.putExtra(key, value)
                        is Double -> this.putExtra(key, value)
                    }
                }
            }
            putExtra("__cmd", "push_config")
        }
    }

    fun isAntiDetectionEnabled(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("anti_detection_enabled", true)
    }

    fun setAntiDetectionEnabled(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("anti_detection_enabled", v).apply()
        pushUpdate(ctx)
    }

    fun isAntiHideXposed(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("anti_hide_xposed", true)
    }

    fun setAntiHideXposed(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("anti_hide_xposed", v).apply()
        pushUpdate(ctx)
    }

    fun isAntiHideRoot(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("anti_hide_root", true)
    }

    fun setAntiHideRoot(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("anti_hide_root", v).apply()
        pushUpdate(ctx)
    }

    fun isAntiHideMagisk(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("anti_hide_magisk", true)
    }

    fun setAntiHideMagisk(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("anti_hide_magisk", v).apply()
        pushUpdate(ctx)
    }

    fun isAntiHideDebug(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("anti_hide_debug", true)
    }

    fun setAntiHideDebug(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("anti_hide_debug", v).apply()
        pushUpdate(ctx)
    }

    fun isAntiHideFiles(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("anti_hide_files", true)
    }

    fun setAntiHideFiles(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("anti_hide_files", v).apply()
        pushUpdate(ctx)
    }

    fun isAntiHideProps(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("anti_hide_props", true)
    }

    fun setAntiHideProps(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("anti_hide_props", v).apply()
        pushUpdate(ctx)
    }

    fun isAntiHideSignature(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("anti_hide_signature", true)
    }

    fun setAntiHideSignature(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("anti_hide_signature", v).apply()
        pushUpdate(ctx)
    }

    fun isAntiHideEmulator(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("anti_hide_emulator", true)
    }

    fun setAntiHideEmulator(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("anti_hide_emulator", v).apply()
        pushUpdate(ctx)
    }

    fun isAntiFakeDevice(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("anti_fake_device", true)
    }

    fun setAntiFakeDevice(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("anti_fake_device", v).apply()
        pushUpdate(ctx)
    }

    fun isAntiHookSign(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("anti_hook_sign", true)
    }

    fun setAntiHookSign(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("anti_hook_sign", v).apply()
        pushUpdate(ctx)
    }

    fun isAntiDebugLog(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("anti_debug_log", false)
    }

    fun setAntiDebugLog(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("anti_debug_log", v).apply()
        pushUpdate(ctx)
    }

    fun isAntiConnectivitySafe(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("anti_connectivity_safe", true)
    }

    fun setAntiConnectivitySafe(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("anti_connectivity_safe", v).apply()
        pushUpdate(ctx)
    }

    fun getAntiQSignUrl(ctx: Context): String {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getString("anti_qsign_url", "http://127.0.0.1:8080")!!
    }

    fun setAntiQSignUrl(ctx: Context, v: String) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putString("anti_qsign_url", v).apply()
        pushUpdate(ctx)
    }
    
    // ====== 自动检测配置（用于修复Unidbg） ======
    fun isAutoDetectJNI(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("auto_detect_jni", true)
    }
    
    fun setAutoDetectJNI(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("auto_detect_jni", v).apply()
        pushUpdate(ctx)
    }
    
    fun isAutoDetectNatives(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("auto_detect_natives", true)
    }
    
    fun setAutoDetectNatives(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("auto_detect_natives", v).apply()
        pushUpdate(ctx)
    }
    
    fun isAutoDetectO3Env(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("auto_detect_o3_env", true)
    }
    
    fun setAutoDetectO3Env(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("auto_detect_o3_env", v).apply()
        pushUpdate(ctx)
    }
    
    fun isAutoDetectEnvPack(ctx: Context): Boolean {
        val preferences = ctx.getSharedPreferences("config", 0)
        return preferences.getBoolean("auto_detect_env_pack", true)
    }
    
    fun setAutoDetectEnvPack(ctx: Context, v: Boolean) {
        val preferences = ctx.getSharedPreferences("config", 0)
        preferences.edit().putBoolean("auto_detect_env_pack", v).apply()
        pushUpdate(ctx)
    }
}