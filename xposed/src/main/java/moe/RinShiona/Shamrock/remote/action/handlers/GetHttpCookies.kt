package moe.RinShiona.Shamrock.remote.action.handlers

import kotlinx.serialization.json.JsonElement
import moe.RinShiona.Shamrock.qqinterface.servlet.TicketSvc
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.action.IActionHandler
import moe.RinShiona.Shamrock.remote.service.data.Credentials
import moe.RinShiona.Shamrock.tools.EmptyJsonString

internal object GetHttpCookies : IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        val appid = session.getString("appid")
        val daid = session.getString("daid")
        val jumpurl = session.getString("jumpurl")
        return invoke(appid, daid, jumpurl, session.echo)
    }

    suspend operator fun invoke(
        appid: String,
        daid: String,
        jumpurl: String,
        echo: JsonElement = EmptyJsonString
    ): String {
        val ck = TicketSvc.GetHttpCookies(appid, daid, jumpurl) ?: ""
        return ok(Credentials(cookie = ck), echo)
    }

    override fun path(): String = "get_http_cookies"
}