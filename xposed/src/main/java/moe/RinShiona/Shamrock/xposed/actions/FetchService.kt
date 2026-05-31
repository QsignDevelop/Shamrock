@file:OptIn(DelicateCoroutinesApi::class)
package moe.RinShiona.Shamrock.xposed.actions

import android.content.Context
import com.tencent.qqnt.kernel.api.IKernelService
import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.RinShiona.Shamrock.helper.Level
import moe.RinShiona.Shamrock.helper.LogCenter
import moe.RinShiona.Shamrock.tools.hookMethod
import moe.RinShiona.Shamrock.utils.PlatformUtils
import moe.RinShiona.Shamrock.xposed.AntiDetectionConfig
import moe.RinShiona.Shamrock.xposed.helper.AppRuntimeFetcher
import moe.RinShiona.Shamrock.xposed.helper.NTServiceFetcher
import moe.RinShiona.Shamrock.xposed.loader.NativeLoader
import mqq.app.api.IRuntimeService

internal class FetchService : IAction {
    override fun invoke(ctx: Context) {
        if (!AntiDetectionConfig.connectivitySafeMode) {
            NativeLoader.load("shamrock")
        } else {
            XposedBridge.log("Shamrock: connectivity-safe — hook NTKernel for OneBot (skip legacy native)")
        }

        if (!PlatformUtils.isMqq()) {
            if (PlatformUtils.isTim()) {
                LogCenter.log("NTKernel init failed: tim not support NT", Level.ERROR)
            }
            return
        }

        hookKernelInit(ctx.classLoader)
        NTServiceFetcher.installMsgServiceListenerHookEarly(ctx.classLoader)
        scheduleKernelAttach(ctx.classLoader)
    }

    private fun hookKernelInit(loader: ClassLoader) {
        runCatching {
            val kernelCls = loader.loadClass("com.tencent.qqnt.kernel.api.impl.KernelServiceImpl")
            kernelCls.hookMethod("initService").after {
                val service = it.thisObject as IKernelService
                LogCenter.log("NTKernel initService: $service", Level.INFO)
                GlobalScope.launch { NTServiceFetcher.onFetch(service) }
            }
            XposedBridge.log("Shamrock: hooked ${kernelCls.name}.initService")
        }.onFailure {
            XposedBridge.log("Shamrock: KernelServiceImpl hook failed: ${it.message}")
        }
    }

    private fun scheduleKernelAttach(loader: ClassLoader) {
        GlobalScope.launch {
            repeat(40) { attempt ->
                if (attachKernelIfReady(loader)) {
                    XposedBridge.log("Shamrock: NTKernel attached via poll (attempt ${attempt + 1})")
                    return@launch
                }
                delay(3000)
            }
            XposedBridge.log("Shamrock: NTKernel poll exhausted — check QQ login / NT kernel")
        }
    }

    private suspend fun attachKernelIfReady(loader: ClassLoader): Boolean {
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val kernelIface = loader.loadClass("com.tencent.qqnt.kernel.api.IKernelService")
                as Class<IRuntimeService>
            val runtime = AppRuntimeFetcher.appRuntime
            val service = runtime.getRuntimeService(kernelIface, "all") as IKernelService
            if (!service.isInit) return false
            NTServiceFetcher.onFetch(service)
            true
        }.getOrDefault(false)
    }
}
