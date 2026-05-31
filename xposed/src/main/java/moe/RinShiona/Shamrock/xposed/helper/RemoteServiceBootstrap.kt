package moe.RinShiona.Shamrock.xposed.helper

import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.RinShiona.Shamrock.helper.Level
import moe.RinShiona.Shamrock.helper.LogCenter
import moe.RinShiona.Shamrock.remote.service.HttpService
import moe.RinShiona.Shamrock.remote.service.WebSocketClientService
import moe.RinShiona.Shamrock.remote.service.WebSocketService
import moe.RinShiona.Shamrock.remote.service.api.GlobalPusher
import moe.RinShiona.Shamrock.remote.service.config.ShamrockConfig
import moe.RinShiona.Shamrock.tools.ShamrockVersion
import moe.RinShiona.Shamrock.utils.PlatformUtils
import moe.RinShiona.Shamrock.xposed.helper.AppRuntimeFetcher
import mqq.app.MobileQQ
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.timer

/**
 * 根据 Shamrock App 写入的 XSharedPreferences 热更新 OneBot 推送通道。
 * HyperOS 会拦截 App→QQ 广播，因此必须轮询 prefs 文件。
 */
@OptIn(DelicateCoroutinesApi::class)
internal object RemoteServiceBootstrap {

    private val pollStarted = AtomicBoolean(false)
    private val wsStarting = AtomicBoolean(false)
    private var httpHookRegistered = false
    private var wsServer: WebSocketService? = null
    private var wsServerPort = -1
    private val wsClients = mutableMapOf<String, WebSocketClientService>()
    private val wsClientTimers = mutableMapOf<String, java.util.Timer>()

    fun start(ctx: android.content.Context) {
        apply(ctx)
        if (pollStarted.compareAndSet(false, true)) {
            GlobalScope.launch {
                while (isActive) {
                    delay(2000)
                    if (XPrefConfigLoader.reloadIfChanged()) {
                        apply(MobileQQ.getContext())
                    }
                }
            }
        }
    }

    fun apply(ctx: android.content.Context) {
        syncHttpWebhook()
        syncWebSocketServer()
        if (PlatformUtils.isMqqPackage()) {
            syncWebSocketClients()
        }
        XposedBridge.log("Shamrock: OneBot sync webhook=${ShamrockConfig.allowWebHook()} ws=${ShamrockConfig.openWebSocket()} wsClient=${ShamrockConfig.openWebSocketClient()}")
    }

    private fun syncHttpWebhook() {
        val want = ShamrockConfig.allowWebHook()
        if (want && !httpHookRegistered) {
            GlobalPusher.register(HttpService)
            httpHookRegistered = true
            LogCenter.log("HTTP 回调已启用", Level.INFO)
        } else if (!want && httpHookRegistered) {
            GlobalPusher.unregister(HttpService)
            httpHookRegistered = false
            LogCenter.log("HTTP 回调已关闭", Level.INFO)
        }
    }

    private fun syncWebSocketServer() {
        val want = ShamrockConfig.openWebSocket()
        val port = ShamrockConfig.getWebSocketPort()
        if (!want) {
            wsServer?.let {
                runCatching { it.stop() }
                GlobalPusher.unregister(it)
            }
            wsServer = null
            wsServerPort = -1
            return
        }
        if (wsServer != null && wsServerPort == port) return
        if (!wsStarting.compareAndSet(false, true)) return
        wsServer?.let {
            runCatching { it.stop() }
            GlobalPusher.unregister(it)
            wsServer = null
            wsServerPort = -1
        }
        GlobalScope.launch {
            try {
                val server = WebSocketService(port)
                server.start()
                wsServer = server
                wsServerPort = port
                LogCenter.log("主动 WebSocket 已启动 ws://0.0.0.0:$port", Level.INFO)
            } catch (e: Throwable) {
                LogCenter.log("主动 WebSocket 启动失败: ${e.message}", Level.ERROR)
            } finally {
                wsStarting.set(false)
            }
        }
    }

    private fun syncWebSocketClients() {
        val want = ShamrockConfig.openWebSocketClient()
        if (!want) {
            wsClients.keys.toList().forEach { stopWsClient(it) }
            return
        }
        val runtime = AppRuntimeFetcher.appRuntime
        val curUin = runtime.currentAccountUin
        val wsHeaders = hashMapOf(
            "X-Client-Role" to "Universal",
            "X-Self-ID" to curUin,
            "User-Agent" to "Shamrock/$ShamrockVersion",
            "X-QQ-Version" to PlatformUtils.getClientVersion(MobileQQ.getContext()),
            "X-OneBot-Version" to "11",
            "X-Impl" to "Shamrock",
            "Sec-WebSocket-Protocol" to "11.Shamrock",
        )
        ShamrockConfig.getToken().takeIf { it.isNotBlank() }?.let {
            wsHeaders["authorization"] = "bearer $it"
        }
        val wanted = ShamrockConfig.getWebSocketClientAddress()
            .split(",", "|", "，")
            .map { it.trim() }
            .filter { it.isNotBlank() && (it.startsWith("ws://") || it.startsWith("wss://")) }
            .toSet()
        wsClients.keys.filter { it !in wanted }.forEach { stopWsClient(it) }
        wanted.forEach { url -> ensureWsClient(url, wsHeaders) }
    }

    private fun ensureWsClient(url: String, headers: HashMap<String, String>) {
        if (wsClients.containsKey(url)) return
        GlobalScope.launch {
            runCatching {
                val client = WebSocketClientService(url, headers)
                client.connect()
                wsClients[url] = client
                wsClientTimers[url]?.cancel()
                wsClientTimers[url] = timer(initialDelay = 5000L, period = 5000L) {
                    val c = wsClients[url] ?: return@timer
                    if (c.isClosed || c.isClosing) {
                        GlobalPusher.unregister(c)
                        val rebuilt = WebSocketClientService(url, headers)
                        rebuilt.connect()
                        wsClients[url] = rebuilt
                    }
                }
                LogCenter.log("被动 WebSocket 已连接 $url", Level.INFO)
            }.onFailure {
                LogCenter.log("被动 WebSocket 连接失败 $url: ${it.message}", Level.ERROR)
            }
        }
    }

    private fun stopWsClient(url: String) {
        wsClientTimers.remove(url)?.cancel()
        wsClients.remove(url)?.let {
            runCatching { it.close() }
            GlobalPusher.unregister(it)
        }
    }
}
