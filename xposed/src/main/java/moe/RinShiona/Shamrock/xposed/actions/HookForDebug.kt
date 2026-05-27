package moe.RinShiona.Shamrock.xposed.actions

import android.content.Context

internal class HookForDebug: IAction {
    override fun invoke(ctx: Context) {
        // MessageHelper.hookSendMessageOldChannel()
    }
}