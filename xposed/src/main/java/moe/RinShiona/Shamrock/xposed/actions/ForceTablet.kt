package moe.RinShiona.Shamrock.xposed.actions

import android.content.Context
import com.tencent.common.config.pad.DeviceType
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import moe.RinShiona.Shamrock.remote.service.config.ShamrockConfig
import moe.RinShiona.Shamrock.tools.FuzzySearchClass
import moe.RinShiona.Shamrock.utils.PlatformUtils
import moe.RinShiona.Shamrock.helper.LogCenter
import moe.RinShiona.Shamrock.xposed.loader.LuoClassloader

internal class ForceTablet: IAction {
    override fun invoke(ctx: Context) {
        if (!PlatformUtils.isMqqPackage()) return
        if (ShamrockConfig.forceTablet()) {
            if (PlatformUtils.isMainProcess()) {
                LogCenter.log("强制协议类型 (PAD)", toast = true)
            }
            FuzzySearchClass.findAllClassByMethod(
                LuoClassloader.hostClassLoader, "com.tencent.common.config.pad"
            ) { _, method ->
                method.returnType == DeviceType::class.java
            }.forEach { clazz ->
                //log("Inject to tablet mode in ${clazz.name}")
                val method = clazz.declaredMethods.first { it.returnType == DeviceType::class.java }
                XposedBridge.hookMethod(method, object: XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        //log("Original deviceMode = ${param.result}, but change to TABLET.")
                        param.result = DeviceType.TABLET
                    }
                })
            }
        }
    }
}