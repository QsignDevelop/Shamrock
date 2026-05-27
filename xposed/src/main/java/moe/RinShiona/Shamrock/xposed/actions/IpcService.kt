@file:OptIn(DelicateCoroutinesApi::class)

package moe.RinShiona.Shamrock.xposed.actions

import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.DelicateCoroutinesApi
import moe.RinShiona.Shamrock.utils.PlatformUtils
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import moe.RinShiona.Shamrock.tools.broadcast
import moe.RinShiona.Shamrock.helper.Level
import moe.RinShiona.Shamrock.helper.LogCenter
import moe.RinShiona.Shamrock.xposed.helper.internal.*
import moe.RinShiona.Shamrock.xposed.ipc.ShamrockIpc
import moe.RinShiona.Shamrock.xposed.ipc.impl.ByteDataImpl
import moe.RinShiona.Shamrock.xposed.ipc.impl.QSignerImpl

internal class IpcService: IAction {
    override fun invoke(ctx: Context) {
        if (!PlatformUtils.isMsfProcess()) return
        initIPCFetcher(ctx)
    }

    private fun initIPCFetcher(ctx: Context) {
        LogCenter.log("IPC service started in MSF process: $ctx", Level.INFO)

        // Register the binder implementations that bridge into QQ's runtime.
        // These calls happen in the MSF process; the main process retrieves
        // them via the broadcast handshake below.
        runCatching {
            ShamrockIpc.register(ShamrockIpc.IPC_QSIGN, QSignerImpl())
            LogCenter.log("Registered IQSigner binder", Level.INFO)
        }.onFailure {
            LogCenter.log("Failed to register IQSigner: ${it.message}", Level.ERROR)
        }
        runCatching {
            ShamrockIpc.register(ShamrockIpc.IPC_BYTEDATA, ByteDataImpl())
            LogCenter.log("Registered IByteData binder", Level.INFO)
        }.onFailure {
            LogCenter.log("Failed to register IByteData: ${it.message}", Level.ERROR)
        }

        DynamicReceiver.register("fetch_ipc", IPCRequest {
            val name = it.getStringExtra("ipc_name")
            LogCenter.log("IPC FETCH => $name (verify this isn't leaking your API)")
            GlobalScope.launch {
                ShamrockIpc.get(name)?.also { binder ->
                    ctx.broadcast("xqbot") {
                        putExtra("__cmd", "ipc_callback")
                        putExtra("ipc", Bundle().also {
                            it.putString("name", name)
                            it.putBinder("binder", binder)
                        })
                    }
                } ?: LogCenter.log("IPC name not registered: $name", Level.WARN)
            }
        })
    }
}
