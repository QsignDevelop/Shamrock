@file:OptIn(DelicateCoroutinesApi::class)

package moe.RinShiona.Shamrock.xposed.actions

import android.content.Context
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import moe.RinShiona.Shamrock.helper.Level
import moe.RinShiona.Shamrock.helper.LogCenter
import moe.RinShiona.Shamrock.remote.HTTPServer
import moe.RinShiona.Shamrock.remote.service.config.ShamrockConfig
import moe.RinShiona.Shamrock.utils.PlatformUtils
import de.robv.android.xposed.XposedBridge
import moe.RinShiona.Shamrock.xposed.helper.RemoteServiceBootstrap

internal class InitRemoteService : IAction {
    override fun invoke(ctx: Context) {
        if (!PlatformUtils.isMainProcess()) return
        if (!ShamrockConfig.isOneBotV11Enabled()) {
            LogCenter.log("OneBot v11 已关闭 — 跳过 HTTP/WS 启动", Level.INFO)
            XposedBridge.log("Shamrock: OneBot v11 disabled — skip HTTP")
            return
        }

        GlobalScope.launch {
            try {
                HTTPServer.start(ShamrockConfig.getPort())
                XposedBridge.log("Shamrock: HTTPServer started port=${ShamrockConfig.getPort()}")
            } catch (e: Throwable) {
                LogCenter.log(e.stackTraceToString(), Level.ERROR)
                XposedBridge.log("Shamrock: HTTPServer start failed: ${e.message}")
            }
        }

        RemoteServiceBootstrap.start(ctx)
    }
}
