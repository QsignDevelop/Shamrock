@file:OptIn(DelicateCoroutinesApi::class)
package moe.RinShiona.Shamrock.xposed.actions

import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.widget.Toast
import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import moe.RinShiona.Shamrock.xposed.helper.ModuleHide
import moe.RinShiona.Shamrock.utils.PlatformUtils
import moe.RinShiona.Shamrock.xposed.helper.IpcFetcher
import moe.RinShiona.Shamrock.xposed.helper.QSecContextBridge
import moe.RinShiona.Shamrock.xposed.helper.internal.DynamicReceiver
import mqq.app.MobileQQ

internal lateinit var GlobalUi: Handler

internal fun Context.toast(msg: String, flag: Int = Toast.LENGTH_SHORT) {
    XposedBridge.log(msg)
    if (!::GlobalUi.isInitialized) {
        return
    }
    GlobalUi.post { Toast.makeText(this, msg, flag).show() }
}

internal class DataReceiver: IAction {
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun invoke(ctx: Context) {
        kotlin.runCatching {
            MobileQQ.getMobileQQ().unregisterReceiver(DynamicReceiver)
        }

        if (PlatformUtils.isMainProcess()) {
            IpcFetcher.initMainProcess()
            QSecContextBridge.startMainPublisher(ctx.classLoader)
            GlobalUi = Handler(ctx.mainLooper)
            GlobalScope.launch {
                val intentFilter = IntentFilter()
                intentFilter.addAction(ModuleHide.ACTION_XQBOT_DYNAMIC)
                intentFilter.addAction("${ModuleHide.LEGACY_PACKAGE}.xqbot.dynamic")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    MobileQQ.getMobileQQ().registerReceiver(
                        DynamicReceiver, intentFilter,
                        Context.RECEIVER_EXPORTED
                    )
                } else {
                    MobileQQ.getMobileQQ().registerReceiver(DynamicReceiver, intentFilter)
                }
                XposedBridge.log("Register Main::Broadcast successfully.")
            }
        } else if (PlatformUtils.isMsfProcess()) {
            val intentFilter = IntentFilter()
            intentFilter.addAction(ModuleHide.ACTION_MSF_DYNAMIC)
            intentFilter.addAction("${ModuleHide.LEGACY_PACKAGE}.msf.dynamic")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                MobileQQ.getMobileQQ().registerReceiver(
                    DynamicReceiver, intentFilter,
                    Context.RECEIVER_EXPORTED
                )
            } else {
                MobileQQ.getMobileQQ().registerReceiver(DynamicReceiver, intentFilter)
            }
        }
    }
}

