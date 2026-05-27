package moe.RinShiona.Shamrock.remote.action.handlers

import kotlinx.serialization.json.JsonElement
import moe.RinShiona.Shamrock.qqinterface.servlet.CardSvc
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.action.IActionHandler
import moe.RinShiona.Shamrock.tools.EmptyJsonString

internal object GetModelShow: IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        val uin = session.getLongOrNull("user_id")
        return if (uin == null) {
            invoke(session.echo)
        } else {
            invoke(uin, session.echo)
        }
    }

    suspend operator fun invoke(echo: JsonElement = EmptyJsonString): String {
        return ok(CardSvc.getModelShow(), echo)
    }

    suspend operator fun invoke(uin: Long, echo: JsonElement = EmptyJsonString): String {
        if (uin == 0L) {
            return invoke(echo)
        }
        return ok(CardSvc.getModelShow(uin), echo)
    }

    override fun path(): String = "get_model_show"
}