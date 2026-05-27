@file:OptIn(DelicateCoroutinesApi::class)

package moe.RinShiona.Shamrock.xposed.actions

import android.content.Context
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import moe.RinShiona.Shamrock.remote.service.WebSocketClientService
import moe.RinShiona.Shamrock.remote.service.WebSocketService
import moe.RinShiona.Shamrock.remote.service.api.GlobalPusher
import moe.RinShiona.Shamrock.remote.service.config.ShamrockConfig
import moe.RinShiona.Shamrock.utils.PlatformUtils
import moe.RinShiona.Shamrock.helper.Level
import moe.RinShiona.Shamrock.helper.LogCenter
import moe.RinShiona.Shamrock.remote.HTTPServer
import moe.RinShiona.Shamrock.tools.ShamrockVersion
import moe.RinShiona.Shamrock.xposed.helper.AppRuntimeFetcher
import mqq.app.MobileQQ
import kotlin.concurrent.timer

internal class InitRemoteService : IAction {
    override fun invoke(ctx: Context) {
        if (!PlatformUtils.isMainProcess()) return

        GlobalScope.launch {
            try {
                HTTPServer.start(ShamrockConfig.getPort())
            } catch (e: Throwable) {
                LogCenter.log(e.stackTraceToString(), Level.ERROR)
            }
        }

        if (!PlatformUtils.isMqqPackage()) return

        if (ShamrockConfig.allowWebHook()) {
            GlobalPusher.register(moe.RinShiona.Shamrock.remote.service.HttpService)
        }

        if (ShamrockConfig.openWebSocket()) {
            startWebSocketServer()
        }

        if (ShamrockConfig.openWebSocketClient()) {
            val runtime = AppRuntimeFetcher.appRuntime
            val curUin = runtime.currentAccountUin
            val wsHeaders = hashMapOf(
                "X-Client-Role" to "Universal",
                "X-Self-ID" to curUin,
                "User-Agent" to "Shamrock/$ShamrockVersion",
                "X-QQ-Version" to PlatformUtils.getClientVersion(MobileQQ.getContext()),
                "X-OneBot-Version" to "11",
                "X-Impl" to "Shamrock",
                "Sec-WebSocket-Protocol" to "11.Shamrock"
            )
            val token = ShamrockConfig.getToken()
            if (token.isNotBlank()) {
                wsHeaders["authorization"] = "bearer $token"
                //wsHeaders["bearer"] = token
            }
            ShamrockConfig.getWebSocketClientAddress().split(",", "|", "，").forEach { url ->
                if (url.isNotBlank())
                    startWebSocketClient(url, wsHeaders)
            }
        }
    }

    private fun startWebSocketServer() {
        GlobalScope.launch {
            try {
                val server = WebSocketService(ShamrockConfig.getWebSocketPort())
                server.start()
            } catch (e: Throwable) {
                LogCenter.log(e.stackTraceToString(), Level.ERROR)
            }
        }
    }

    private fun startWebSocketClient(url: String, wsHeaders: HashMap<String, String>) {
        GlobalScope.launch {
            try {
                if (url.startsWith("ws://") || url.startsWith("wss://")) {
                    var wsClient = WebSocketClientService(url, wsHeaders)
                    wsClient.connect()
                    timer(initialDelay = 5000L, period = 5000L) {
                        if (wsClient.isClosed || wsClient.isClosing) {
                            GlobalPusher.unregister(wsClient)
                            wsClient = WebSocketClientService(url, wsHeaders)
                            wsClient.connect()
                        }
                    }
                } else {
                    LogCenter.log("被动WebSocket地址不合法: $url", Level.ERROR)
                }
            } catch (e: Throwable) {
                LogCenter.log(e.stackTraceToString(), Level.ERROR)
            }
        }
    }
}