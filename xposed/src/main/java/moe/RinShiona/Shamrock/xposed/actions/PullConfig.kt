@file:OptIn(DelicateCoroutinesApi::class)

package moe.RinShiona.Shamrock.xposed.actions

import android.content.Context
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.RinShiona.Shamrock.remote.HTTPServer
import moe.RinShiona.Shamrock.remote.service.config.ShamrockConfig
import moe.RinShiona.Shamrock.utils.PlatformUtils
import moe.RinShiona.Shamrock.xposed.helper.IpcFetcher
import moe.RinShiona.Shamrock.xposed.helper.QSecContextBridge
import moe.RinShiona.Shamrock.xposed.helper.QuaBootstrap
import moe.RinShiona.Shamrock.xposed.helper.RemoteServiceBootstrap
import moe.RinShiona.Shamrock.xposed.helper.XPrefConfigLoader
import moe.RinShiona.Shamrock.xposed.helper.internal.DataRequester
import moe.RinShiona.Shamrock.xposed.helper.internal.DynamicReceiver
import moe.RinShiona.Shamrock.xposed.helper.internal.IPCRequest
import moe.RinShiona.Shamrock.xposed.loader.ActionLoader
import moe.RinShiona.Shamrock.xposed.loader.NativeLoader
import de.robv.android.xposed.XposedBridge
import mqq.app.MobileQQ
import java.util.concurrent.atomic.AtomicBoolean

class PullConfig: IAction {
    companion object {
        @JvmStatic
        var isConfigOk = false
        private val serviceBootstrapped = AtomicBoolean(false)
    }

    private external fun testNativeLibrary(): String

    private fun safeTestNativeLibrary(): String = kotlin.runCatching {
        testNativeLibrary()
    }.getOrElse { "Shamrock library not loaded (${it.javaClass.simpleName})" }

    override fun invoke(ctx: Context) {
        if (!PlatformUtils.isMainProcess()) return

        GlobalScope.launch(Dispatchers.Default) {
            DynamicReceiver.register("fetchPort", IPCRequest {
                DataRequester.request("success", values = mapOf(
                    "port" to HTTPServer.currServerPort,
                    "voice" to NativeLoader.isVoiceLoaded
                ))
            })
            DynamicReceiver.register("checkAndStartService", IPCRequest {
                if (HTTPServer.isServiceStarted) {
                    HTTPServer.isServiceStarted = false
                }
                XPrefConfigLoader.loadIfAvailable()
                RemoteServiceBootstrap.apply(MobileQQ.getContext())
                initAppService(MobileQQ.getContext())
            })
            DynamicReceiver.register("push_config", IPCRequest {
                ctx.toast("动态推送配置文件成功。")
                if (!XPrefConfigLoader.loadIfAvailable()) {
                    ShamrockConfig.updateConfig(it)
                }
                RemoteServiceBootstrap.apply(ctx)
                // 同步反检测配置
                moe.RinShiona.Shamrock.xposed.AntiDetectionConfig.apply {
                    enabled = it.getBooleanExtra("anti_detection_enabled", true)
                    earlyEnabled = it.getBooleanExtra("anti_early_enabled", enabled)
                    hideXposed = it.getBooleanExtra("anti_hide_xposed", true)
                    hideRoot = it.getBooleanExtra("anti_hide_root", true)
                    hideMagisk = it.getBooleanExtra("anti_hide_magisk", true)
                    hideDebug = it.getBooleanExtra("anti_hide_debug", true)
                    hideFiles = it.getBooleanExtra("anti_hide_files", true)
                    hideProps = it.getBooleanExtra("anti_hide_props", true)
                    hideSignature = it.getBooleanExtra("anti_hide_signature", true)
                    hideEmulator = it.getBooleanExtra("anti_hide_emulator", true)
                    fakeDevice = it.getBooleanExtra("anti_fake_device", true)
                    hookSign = it.getBooleanExtra("anti_hook_sign", true)
                    debugLog = it.getBooleanExtra("anti_debug_log", false)
                    connectivitySafeMode = it.getBooleanExtra("anti_connectivity_safe", true)
                    qsecHeavyBypass = it.getBooleanExtra("anti_qsec_heavy", false)
                    qsignServerUrl = it.getStringExtra("anti_qsign_url") ?: "http://127.0.0.1:8080"
                    if (connectivitySafeMode) {
                        enabled = true
                        earlyEnabled = true
                        useRemoteQSign = false
                    }
                }
            })
            DynamicReceiver.register("change_port", IPCRequest {
                when (it.getStringExtra("type")) {
                    "port" -> {
                        ctx.toast("动态修改HTTP端口成功。")
                        HTTPServer.changePort(it.getIntExtra("port", 5700))
                    }
                    "ws_port" -> {
                        ctx.toast("动态修改WS端口不支持。")
                    }
                    "restart" -> {
                        if(HTTPServer.isServiceStarted) {
                            ctx.toast("重启HTTPServer完成。")
                            HTTPServer.restart()
                        }
                    }
                }
            })

            loadConfigAndStart(ctx)
        }
    }

    private suspend fun loadConfigAndStart(ctx: Context) {
        // HyperOS/MIUI blocks App->QQ broadcast; poll LSPosed-shared prefs.
        repeat(30) { attempt ->
            if (serviceBootstrapped.get()) return
            if (XPrefConfigLoader.loadIfAvailable()) {
                isConfigOk = true
                initAppService(ctx)
                return
            }
            if (ShamrockConfig.isInit()) {
                ctx.toast("使用缓存配置启动")
                isConfigOk = true
                initAppService(ctx)
                return
            }
            if (attempt == 0) {
                DataRequester.request("init", onFailure = { e ->
                    XposedBridge.log("Shamrock: init handshake failed: ${e.message}")
                }, bodyBuilder = null) {
                    isConfigOk = true
                    ShamrockConfig.updateConfig(it)
                    initAppService(ctx)
                }
            }
            XposedBridge.log("Shamrock: waiting for Shamrock App config (attempt ${attempt + 1}/30)")
            delay(2000)
        }
        XposedBridge.log("Shamrock: config load retries exhausted — starting with cached/default config")
        XPrefConfigLoader.loadIfAvailable()
        if (!serviceBootstrapped.get()) {
            isConfigOk = true
            initAppService(ctx)
        }
    }

    private fun initAppService(ctx: Context) {
        if (!serviceBootstrapped.compareAndSet(false, true)) return
        if (!moe.RinShiona.Shamrock.xposed.AntiDetectionConfig.connectivitySafeMode) {
            kotlin.runCatching { NativeLoader.load("shamrock") }
                .onFailure { XposedBridge.log("Shamrock: NativeLoader.load(shamrock) failed: ${it.message}") }
            val nativeStatus = safeTestNativeLibrary()
            XposedBridge.log("Shamrock: native probe => $nativeStatus")
            if (!nativeStatus.startsWith("Shamrock library not loaded")) {
                ctx.toast(nativeStatus)
            }
        } else {
            XposedBridge.log("Shamrock: connectivity-safe — skip legacy shamrock native load")
        }
        ActionLoader.runService(ctx)
        kotlin.runCatching {
            QSecContextBridge.installHooks(ctx.classLoader)
            QuaBootstrap.forceApply(ctx.classLoader, null)
        }
        if (!moe.RinShiona.Shamrock.xposed.AntiDetectionConfig.connectivitySafeMode) {
            GlobalScope.launch(Dispatchers.Default) { IpcFetcher.prefetchAll() }
        }
    }
}