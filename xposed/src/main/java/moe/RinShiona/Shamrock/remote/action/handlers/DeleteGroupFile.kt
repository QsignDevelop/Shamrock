package moe.RinShiona.Shamrock.remote.action.handlers

import kotlinx.serialization.json.JsonElement
import moe.RinShiona.Shamrock.qqinterface.servlet.FileSvc
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.action.IActionHandler
import moe.RinShiona.Shamrock.tools.EmptyJsonString

internal object DeleteGroupFile: IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        val groupId = session.getString("group_id")
        val fileId = session.getString("file_id")
        val busid = session.getInt("busid")
        return invoke(groupId, fileId, busid, session.echo)
    }

    operator fun invoke(groupId: String, fileId: String, bizId: Int, echo: JsonElement = EmptyJsonString): String {
        FileSvc.deleteGroupFile(groupId, bizId, fileId)
        return ok("成功", echo)
    }

    override fun path(): String = "delete_group_file"
}