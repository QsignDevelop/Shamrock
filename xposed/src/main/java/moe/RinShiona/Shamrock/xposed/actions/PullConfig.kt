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
import android.net.Uri
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
                XPrefConfigLoader.loadIfAvailable()
                if (!ShamrockConfig.isOneBotV11Enabled()) {
                    if (HTTPServer.isServiceStarted) {
                        GlobalScope.launch { HTTPServer.stop() }
                        HTTPServer.isServiceStarted = false
                    }
                    RemoteServiceBootstrap.apply(MobileQQ.getContext())
                    return@IPCRequest
                }
                RemoteServiceBootstrap.apply(MobileQQ.getContext())
                if (serviceBootstrapped.get()) {
                    ensureHttpServerStarted()
                } else {
                    initAppService(MobileQQ.getContext())
                }
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
                    hookSign = it.getBooleanExtra("anti_hook_sign", false)
                    msfNativeAntiDetect = it.getBooleanExtra("anti_msf_native", false)
                    debugLog = it.getBooleanExtra("anti_debug_log", false)
                    connectivitySafeMode = it.getBooleanExtra("anti_connectivity_safe", true)
                    qsecHeavyBypass = it.getBooleanExtra("anti_qsec_heavy", false)
                    qsignServerUrl = it.getStringExtra("anti_qsign_url") ?: "http://127.0.0.1:8080"
                    if (connectivitySafeMode) {
                        enabled = true
                        earlyEnabled = true
                        msfNativeAntiDetect = false
                        hookSign = false
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
        var providerTried = false
        repeat(30) { attempt ->
            if (serviceBootstrapped.get()) return
            if (XPrefConfigLoader.loadIfAvailable()) {
                isConfigOk = true
                initAppService(ctx)
                return
            }
            // MIUI 常拦截 QQ→CherryPop Provider；仅尝试一次，避免登录页被 IPC 重试拖死。
            if (!providerTried) {
                providerTried = true
                runCatching {
                    val uri = Uri.parse("content://${moe.RinShiona.Shamrock.xposed.helper.ModuleHide.PACKAGE}.xqbot.provider")
                    val bundle = MobileQQ.getContext().contentResolver.call(uri, "get_config", null, null)
                    if (bundle != null && bundle.getBoolean("__ok", false)) {
                        isConfigOk = true
                        XposedBridge.log("Shamrock: config loaded via provider call (${bundle.keySet().size} keys)")
                        val intent = android.content.Intent().apply {
                            bundle.keySet().forEach { k ->
                                if (k == "__ok") return@forEach
                                when (val v = bundle.get(k)) {
                                    is Int -> putExtra(k, v)
                                    is Long -> putExtra(k, v)
                                    is Boolean -> putExtra(k, v)
                                    is Float -> putExtra(k, v)
                                    is String -> putExtra(k, v)
                                    is ByteArray -> putExtra(k, v)
                                }
                            }
                        }
                        ShamrockConfig.updateConfig(intent)
                        initAppService(ctx)
                        return
                    }
                }.onFailure {
                    XposedBridge.log("Shamrock: provider config skipped (MIUI?): ${it.message}")
                }
            }
            if (ShamrockConfig.isInit()) {
                ctx.toast("使用缓存配置启动")
                isConfigOk = true
                initAppService(ctx)
                return
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

    private fun ensureHttpServerStarted() {
        if (!ShamrockConfig.isOneBotV11Enabled()) return
        GlobalScope.launch {
            try {
                if (HTTPServer.isServiceStarted) {
                    HTTPServer.restart()
                } else {
                    HTTPServer.start(ShamrockConfig.getPort())
                }
                XposedBridge.log("Shamrock: ensureHttpServerStarted port=${ShamrockConfig.getPort()}")
            } catch (e: Throwable) {
                XposedBridge.log("Shamrock: ensureHttpServerStarted failed: ${e.message}")
            }
        }
    }

    private fun initAppService(ctx: Context) {
        if (!serviceBootstrapped.compareAndSet(false, true)) {
            ensureHttpServerStarted()
            return
        }
        // libshamrock.so = CQ 编解码等 JNI；与 anti-detect 的 cherrypopnt 无关，联网安全模式也要加载。
        kotlin.runCatching { NativeLoader.load("shamrock") }
            .onFailure { XposedBridge.log("Shamrock: NativeLoader.load(shamrock) failed: ${it.message}") }
        if (!moe.RinShiona.Shamrock.xposed.AntiDetectionConfig.connectivitySafeMode) {
            val nativeStatus = safeTestNativeLibrary()
            XposedBridge.log("Shamrock: native probe => $nativeStatus")
            if (!nativeStatus.startsWith("Shamrock library not loaded")) {
                ctx.toast(nativeStatus)
            }
        } else {
            XposedBridge.log("Shamrock: connectivity-safe — shamrock JNI loaded, anti-detect probe skipped")
        }
        ActionLoader.runService(ctx)
        ensureHttpServerStarted()
        kotlin.runCatching {
            QSecContextBridge.installHooks(ctx.classLoader)
            QuaBootstrap.forceApply(ctx.classLoader, null)
        }
        if (!moe.RinShiona.Shamrock.xposed.AntiDetectionConfig.connectivitySafeMode) {
            GlobalScope.launch(Dispatchers.Default) { IpcFetcher.prefetchAll() }
        }
    }
}