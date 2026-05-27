package moe.RinShiona.Shamrock.remote.action.handlers

import kotlinx.serialization.json.JsonElement
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.action.IActionHandler
import moe.RinShiona.Shamrock.qqinterface.servlet.CardSvc
import moe.RinShiona.Shamrock.tools.EmptyJsonString
import moe.RinShiona.Shamrock.utils.PlatformUtils

internal object SetModelShow : IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        val model = session.getString("model")
        val manu = session.getStringOrNull("manu") ?: session.getString("model_show")
        val modelShow = session.getStringOrNull("modelshow") ?: "Android"
        val imei = session.getStringOrNull("imei") ?: PlatformUtils.getAndroidID()
        val show = session.getBooleanOrDefault("show", true)
        return invoke(model, manu, modelShow, imei, show, session.echo)
    }

    suspend operator fun invoke(
        model: String,
        manu: String,
        modelShow: String,
        imei: String,
        show: Boolean,
        echo: JsonElement = EmptyJsonString
    ): String {
        CardSvc.setModelShow(model, manu, modelShow, imei, show)
        return ok("成功", echo = echo)
    }

    override val requiredParams: Array<String> = arrayOf("model")

    override fun path(): String = "_set_model_show"
}