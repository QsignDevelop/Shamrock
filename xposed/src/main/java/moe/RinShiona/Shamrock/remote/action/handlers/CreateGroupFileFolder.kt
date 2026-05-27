package moe.RinShiona.Shamrock.remote.action.handlers

import kotlinx.serialization.json.JsonElement
import moe.RinShiona.Shamrock.qqinterface.servlet.FileSvc
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.action.IActionHandler
import moe.RinShiona.Shamrock.tools.EmptyJsonString

internal object CreateGroupFileFolder: IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        val groupId = session.getString("group_id")
        val folderName = session.getString("name")
        val echo = session.echo
        return invoke(groupId, folderName, echo)
    }

    operator fun invoke(groupId: String, folderName: String, echo: JsonElement = EmptyJsonString): String {
        FileSvc.createFileFolder(groupId, folderName)
        return ok(msg = "成功", echo = echo)
    }

    override val requiredParams: Array<String> = arrayOf("group_id", "name")

    override fun path(): String  = "create_group_file_folder"
}