package moe.RinShiona.Shamrock.ui.service.internal

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import moe.RinShiona.Shamrock.ui.service.ModuleTalker
import moe.RinShiona.Shamrock.ui.service.handlers.*
import moe.RinShiona.Shamrock.ui.app.ShamrockConfig
import java.util.Locale

class MultifunctionalProvider: ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        return when (method) {
            "get_config" -> {
                val bundle = Bundle()
                ShamrockConfig.getConfigMap(ctx).forEach { (key, value) ->
                    when (value) {
                        null -> { /* skip */ }
                        is Int -> bundle.putInt(key, value)
                        is Long -> bundle.putLong(key, value)
                        is Boolean -> bundle.putBoolean(key, value)
                        is Float -> bundle.putFloat(key, value)
                        is String -> bundle.putString(key, value)
                        is ByteArray -> bundle.putByteArray(key, value)
                    }
                }
                bundle.putBoolean("__ok", true)
                bundle
            }
            else -> super.call(method, arg, extras)
        }
    }

    override fun insert(uri: Uri, content: ContentValues?): Uri {
        requireNotNull(content)
        requireNotNull(context)

        val hash = content.getAsInteger("__hash")
        val targetCmd = content.getAsString("__cmd")

        ModuleTalker.HandlerMap.forEach { (cmd, handler) ->
            if (cmd == targetCmd) {
                handler.onReceive(hash, content, context!!)
                return uri
            }
        }
        return uri
    }

    override fun onCreate(): Boolean {
        ModuleTalker.register(InitHandler)
        ModuleTalker.register(FetchPortHandler)
        ModuleTalker.register(LogHandler)
        return true
    }

    override fun query(
        p0: Uri,
        p1: Array<out String>?,
        p2: String?,
        p3: Array<out String>?,
        p4: String?
    ): Cursor? {
        return null
    }

    override fun getType(p0: Uri): String? {
        return null
    }

    override fun delete(p0: Uri, p1: String?, p2: Array<out String>?): Int {
        return 0
    }

    override fun update(p0: Uri, p1: ContentValues?, p2: String?, p3: Array<out String>?): Int {
        return 0
    }
}

fun Context.broadcastToModule(intentBuilder: Intent.() -> Unit) {
    val intent = Intent()
    intent.action = "moe.RinShiona.CherryPop.xqbot.dynamic"
    intent.setPackage("com.tencent.mobileqq")
    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
    intent.intentBuilder()
    runCatching { sendBroadcast(intent) }
    // MIUI/HyperOS (Greezer) may skip app broadcasts. If rooted, also send as shell via su.
    runCatching {
        val extras = intent.extras ?: return@runCatching
        val cmd = extras.getString("__cmd") ?: return@runCatching
        val sb = StringBuilder()
        sb.append("am broadcast -a ").append(intent.action)
            .append(" -p ").append("com.tencent.mobileqq")
            .append(" --es __cmd ").append(shellQuote(cmd))
        for (key in extras.keySet()) {
            if (key == "__cmd") continue
            val v = extras.get(key)
            when (v) {
                is String -> sb.append(" --es ").append(key).append(" ").append(shellQuote(v))
                is Int -> sb.append(" --ei ").append(key).append(" ").append(v)
                is Long -> sb.append(" --el ").append(key).append(" ").append(v)
                is Boolean -> sb.append(" --ez ").append(key).append(" ").append(if (v) "true" else "false")
                is Float -> sb.append(" --ef ").append(key).append(" ").append(v)
                // Keep it minimal; other types are not required for OneBot toggle / port refresh.
            }
        }
        // Try root first; if unavailable, it will fail silently.
        Runtime.getRuntime().exec(arrayOf("su", "-c", sb.toString())).waitFor()
    }
}

private fun shellQuote(s: String): String {
    // single-quote for sh; escape embedded single quotes
    return "'" + s.replace("'", "'\\''") + "'"
}