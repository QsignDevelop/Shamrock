package moe.RinShiona.Shamrock.xposed.helper

import android.content.ContentValues
import android.net.Uri
import mqq.app.MobileQQ

internal object AppTalker {
    private val URI = Uri.parse("content://moe.RinShiona.Shamrock.xqbot.provider")

    fun talk(values: ContentValues, onFailure: ((Throwable) -> Unit)? = null) {
        val ctx = MobileQQ.getContext()
        try {
            ctx.contentResolver.insert(URI, values)
        } catch (e: Throwable) {
            onFailure?.invoke(e)
        }
    }
}