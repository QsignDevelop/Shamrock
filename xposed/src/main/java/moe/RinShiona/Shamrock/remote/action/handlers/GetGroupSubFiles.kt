package moe.RinShiona.Shamrock.remote.action.handlers

import kotlinx.serialization.json.JsonElement
import moe.RinShiona.Shamrock.qqinterface.servlet.FileSvc
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.action.IActionHandler
import moe.RinShiona.Shamrock.tools.EmptyJsonString

internal object GetGroupSubFiles: IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        val groupId = session.getString("group_id")
        val folderId = session.getString("folder_id")
        return invoke(groupId, folderId, session.echo)
    }

    suspend operator fun invoke(groupId: String, folderId: String, echo: JsonElement = EmptyJsonString): String {
        return ok(FileSvc.getGroupFiles(groupId.toLong(), folderId), echo)
    }

    override val requiredParams: Array<String> = arrayOf("group_id", "folder_id")

    override fun path(): String  = "get_group_files_by_folder"
}