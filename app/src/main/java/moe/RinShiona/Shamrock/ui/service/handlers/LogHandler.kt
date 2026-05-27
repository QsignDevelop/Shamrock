package moe.RinShiona.Shamrock.ui.service.handlers

import android.content.ContentValues
import android.content.Context
import moe.RinShiona.Shamrock.ui.app.AppRuntime
import moe.RinShiona.Shamrock.ui.app.Level

object LogHandler: ModuleHandler() {
    override val cmd: String = "send_message"

    override fun onReceive(callbackId: Int, values: ContentValues, context: Context) {
        val msg = values.getAsString("string")
        val level = when (values.getAsInteger("level")) {
            0 -> Level.DEBUG
            1 -> Level.INFO
            2 -> Level.WARN
            3 -> Level.ERROR
            else -> return
        }
        AppRuntime.log(msg, level)
    }
}