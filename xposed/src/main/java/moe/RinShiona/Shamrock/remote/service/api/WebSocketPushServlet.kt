package moe.RinShiona.Shamrock.remote.service.api

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.RinShiona.Shamrock.remote.action.ActionManager
import moe.RinShiona.Shamrock.remote.action.ActionSession
import moe.RinShiona.Shamrock.remote.entries.EmptyObject
import moe.RinShiona.Shamrock.remote.entries.Status
import moe.RinShiona.Shamrock.remote.entries.resultToString
import moe.RinShiona.Shamrock.remote.service.config.ShamrockConfig
import moe.RinShiona.Shamrock.remote.service.data.BotStatus
import moe.RinShiona.Shamrock.remote.service.data.Self
import moe.RinShiona.Shamrock.remote.service.data.push.MetaEventType
import moe.RinShiona.Shamrock.remote.service.data.push.MetaSubType
import moe.RinShiona.Shamrock.remote.service.data.push.PostType
import moe.RinShiona.Shamrock.remote.service.data.push.PushMetaEvent
import moe.RinShiona.Shamrock.tools.GlobalJson
import moe.RinShiona.Shamrock.tools.*
import moe.RinShiona.Shamrock.helper.Level
import moe.RinShiona.Shamrock.helper.LogCenter
import moe.RinShiona.Shamrock.xposed.helper.AppRuntimeFetcher
import mqq.app.MobileQQ
import org.java_websocket.WebSocket
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.Collections
import kotlin.concurrent.timer

internal abstract class WebSocketPushServlet(
    port: Int
) : BasePushServlet, WebSocketServer(InetSocketAddress(port)) {
    protected val eventReceivers: MutableList<WebSocket> = Collections.synchronizedList(mutableListOf<WebSocket>())

    override val address: String
        get() = "-"

     override fun allowPush(): Boolean {
         return ShamrockConfig.openWebSocket()
     }

    inline fun <reified T> broadcastAnyEvent(any: T) {
        broadcastTextEvent(GlobalJson.encodeToString(any))
    }

    fun broadcastTextEvent(text: String) {
        broadcast(text, eventReceivers)
    }

    init {
        timer("heartbeat", true, 0, 1000L * 5) {
            val runtime = AppRuntimeFetcher.appRuntime
            val curUin = runtime.currentAccountUin
            broadcastAnyEvent(PushMetaEvent(
                time = System.currentTimeMillis() / 1000,
                selfId = app.longAccountUin,
                postType = PostType.Meta,
                type = MetaEventType.Heartbeat,
                subType = MetaSubType.Connect,
                status = BotStatus(
                    Self("qq", curUin.toLong()), runtime.isLogin, status = "正常", good = true
                ),
                interval = 15000
            ))
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val path = URI.create(conn.resourceDescriptor).path
        if (path != "/api") {
            eventReceivers.remove(conn)
        }
        LogCenter.log({ "WSServer断开(${conn.remoteSocketAddress.address.hostAddress}:${conn.remoteSocketAddress.port}$path): $code,$reason,$remote" }, Level.DEBUG)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val path = URI.create(conn.resourceDescriptor).path
        GlobalScope.launch {
            onHandleAction(conn, message, path)
        }
    }

    private suspend fun onHandleAction(conn: WebSocket, message: String, path: String) {
        val respond = kotlin.runCatching {
            val actionObject = Json.parseToJsonElement(message).asJsonObject
            if (actionObject["post_type"].asStringOrNull == "meta_event") {
                // 防止二比把元事件push回来
                return
            }
            val action = actionObject["action"].asString
            val echo = actionObject["echo"] ?: EmptyJsonString
            val params = actionObject["params"].asJsonObject

            val handler = ActionManager[action]
            handler?.handle(ActionSession(params, echo))
                ?: resultToString(false, Status.UnsupportedAction, EmptyObject, "不支持的Action", echo = echo)
        }.getOrNull()
        respond?.let { conn.send(it) }
    }

    override fun onError(conn: WebSocket, ex: Exception?) {
        LogCenter.log("WSServer Error: " + ex?.stackTraceToString(), Level.ERROR)
        GlobalPusher.unregister(this)
    }

    override fun onStart() {
        GlobalPusher.register(this)
        LogCenter.log("WSServer start running on ws://0.0.0.0:$port!")
    }

    protected inline fun <reified T> pushTo(body: T) {
        if(!allowPush()) return
        try {
            broadcastTextEvent(GlobalJson.encodeToString(body))
        } catch (e: Throwable) {
            LogCenter.log("WS推送失败: ${e.stackTraceToString()}", Level.ERROR)
        }
    }
 }