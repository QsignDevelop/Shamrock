package moe.RinShiona.Shamrock.xposed.actions

import android.content.Context

internal interface IAction {

    operator fun invoke(ctx: Context)

}