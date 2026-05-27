package moe.RinShiona.Shamrock.remote.action.handlers

import kotlinx.serialization.json.JsonElement
import moe.RinShiona.Shamrock.utils.FileUtils
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.action.IActionHandler
import moe.RinShiona.Shamrock.tools.EmptyJsonString
import moe.RinShiona.Shamrock.utils.MMKVFetcher

internal object CleanCache: IActionHandler() {
    override suspend fun internalHandle(session: ActionSession): String {
        return invoke(session.echo)
    }

    operator fun invoke(echo: JsonElement = EmptyJsonString): String {
        FileUtils.clearCache()
        MMKVFetcher.mmkvWithId("hash2id")
            .clear()
        MMKVFetcher.mmkvWithId("id2id")
            .clear()
        MMKVFetcher.mmkvWithId("seq2id")
            .clear()
        MMKVFetcher.mmkvWithId("audio2silk")
            .clear()
        return ok("成功", echo)
    }

    override fun path(): String = "clean_cache"
}