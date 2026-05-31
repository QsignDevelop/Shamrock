@file:OptIn(DelicateCoroutinesApi::class)

package moe.RinShiona.Shamrock.xposed.actions

import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import moe.RinShiona.Shamrock.tools.broadcast
import moe.RinShiona.Shamrock.xposed.helper.ModuleHide
import moe.RinShiona.Shamrock.utils.PlatformUtils
import moe.RinShiona.Shamrock.xposed.helper.QSecContextBridge
import moe.RinShiona.Shamrock.xposed.helper.ShamrockSignRelay
import moe.RinShiona.Shamrock.xposed.helper.internal.DynamicReceiver
import moe.RinShiona.Shamrock.xposed.helper.internal.IPCRequest
import moe.RinShiona.Shamrock.xposed.ipc.ShamrockIpc
import moe.RinShiona.Shamrock.xposed.ipc.impl.ByteDataImpl
import moe.RinShiona.Shamrock.xposed.ipc.impl.QSignerImpl
import mqq.app.MobileQQ

internal class IpcService : IAction {
    override fun invoke(ctx: Context) {
        if (!PlatformUtils.isMsfProcess()) return
        initIPCFetcher(ctx)
    }

    private fun initIPCFetcher(ctx: Context) {
        XposedBridge.log("Shamrock: IpcService starting in MSF")

        runCatching { ShamrockIpc.register(ShamrockIpc.IPC_QSIGN, QSignerImpl()) }
        runCatching { ShamrockIpc.register(ShamrockIpc.IPC_BYTEDATA, ByteDataImpl()) }

        ensureMsfBroadcastReceiver()

        DynamicReceiver.register("fetch_ipc", IPCRequest { intent ->
            val name = intent.getStringExtra("ipc_name") ?: return@IPCRequest
            GlobalScope.launch {
                ShamrockIpc.get(name)?.let { pushBinderToMain(ctx, name, it) }
            }
        })

        ShamrockSignRelay.startMsfLoop(ctx.classLoader)
        QSecContextBridge.startMsfPublisher(ctx.classLoader)
        pushBinderToMain(ctx, ShamrockIpc.IPC_QSIGN)
        pushBinderToMain(ctx, ShamrockIpc.IPC_BYTEDATA)
    }

    private fun ensureMsfBroadcastReceiver() {
        val app = MobileQQ.getMobileQQ()
        kotlin.runCatching { app.unregisterReceiver(DynamicReceiver) }
        val filter = IntentFilter(ModuleHide.ACTION_MSF_DYNAMIC)
        filter.addAction("${ModuleHide.LEGACY_PACKAGE}.msf.dynamic")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(DynamicReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(DynamicReceiver, filter)
        }
    }

    private fun pushBinderToMain(ctx: Context, name: String, binderOverride: android.os.IBinder? = null) {
        val binder = binderOverride ?: ShamrockIpc.get(name) ?: return
        kotlin.runCatching {
            ctx.broadcast("xqbot") {
                putExtra("__cmd", "ipc_callback")
                putExtra("ipc", Bundle().also {
                    it.putString("name", name)
                    it.putBinder("binder", binder)
                })
            }
        }
    }
}
