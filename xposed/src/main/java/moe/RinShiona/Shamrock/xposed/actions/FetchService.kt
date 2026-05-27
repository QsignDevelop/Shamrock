@file:OptIn(DelicateCoroutinesApi::class)
package moe.RinShiona.Shamrock.xposed.actions

import android.content.Context
import com.tencent.qqnt.kernel.api.IKernelService
import com.tencent.qqnt.kernel.api.impl.KernelServiceImpl
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import moe.RinShiona.Shamrock.tools.hookMethod
import moe.RinShiona.Shamrock.utils.PlatformUtils
import moe.RinShiona.Shamrock.helper.Level
import moe.RinShiona.Shamrock.helper.LogCenter
import moe.RinShiona.Shamrock.xposed.helper.NTServiceFetcher
import moe.RinShiona.Shamrock.xposed.loader.NativeLoader

internal class FetchService: IAction {
    override fun invoke(ctx: Context) {
        NativeLoader.load("shamrock")

        if (PlatformUtils.isMqq()) {
            KernelServiceImpl::class.java.hookMethod("initService").after {
                val service = it.thisObject as IKernelService
                LogCenter.log("NTKernel try to init service: $service", Level.DEBUG)
                GlobalScope.launch {
                    NTServiceFetcher.onFetch(service)
                }
            }
        } else if (PlatformUtils.isTim()) {
            // TIM 尚未进入 NTKernel
            LogCenter.log("NTKernel init failed: tim not support NT", Level.ERROR)
        }

    }
}