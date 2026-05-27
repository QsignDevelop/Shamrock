package moe.RinShiona.Shamrock.xposed.actions

import android.content.Context
import de.robv.android.xposed.XposedHelpers
import moe.RinShiona.Shamrock.tools.hookMethod
import moe.RinShiona.Shamrock.helper.Level
import moe.RinShiona.Shamrock.helper.LogCenter
import moe.RinShiona.Shamrock.xposed.loader.LuoClassloader
import mqq.app.MobileQQ

internal class NoBackGround: IAction {
    override fun invoke(ctx: Context) {
        kotlin.runCatching {
            XposedHelpers.findClass("com.tencent.mobileqq.activity.miniaio.MiniMsgUser", LuoClassloader)
        }.onSuccess {
            it.hookMethod("onBackground").before {
                it.result = null
            }
        }.onFailure {
            LogCenter.log("Keeping MiniMsgUser alive failed: ${it.message}", Level.WARN)
        }

        try {
            val application = MobileQQ.getMobileQQ()
            application.javaClass.hookMethod("onActivityFocusChanged").before {
                it.args[1] = true
            }
        } catch (e: Throwable) {
            LogCenter.log("Keeping MSF alive failed: ${e.message}", Level.WARN)
        }
    }
}