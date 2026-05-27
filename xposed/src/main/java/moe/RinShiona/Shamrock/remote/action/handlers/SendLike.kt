package moe.RinShiona.Shamrock.remote.action.handlers

import kotlinx.serialization.json.JsonElement
import moe.RinShiona.Shamrock.qqinterface.servlet.VisitorSvc
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.action.IActionHandler
import moe.RinShiona.Shamrock.tools.EmptyJsonString
import moe.RinShiona.Shamrock.tools.errMsg

internal object SendLike: IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        val times = session.getInt("times")
        val uin = session.getLong("user_id")
        return invoke(uin, times, session.echo)
    }

    suspend operator fun invoke(uin: Long, cnt: Int, echo: JsonElement = EmptyJsonString): String {
        val result = VisitorSvc.vote(uin, cnt)
        return if(result.isSuccess) {
            ok("成功", echo)
        } else {
            logic(result.errMsg(), echo)
        }
    }

    override val requiredParams: Array<String> = arrayOf("times", "user_id")

    override fun path(): String = "send_like"
}