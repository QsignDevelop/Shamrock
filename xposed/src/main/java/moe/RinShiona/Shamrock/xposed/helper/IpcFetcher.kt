package moe.RinShiona.Shamrock.xposed.helper

import android.os.IBinder
import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import moe.RinShiona.Shamrock.tools.broadcast
import moe.RinShiona.Shamrock.xposed.helper.internal.DynamicReceiver
import moe.RinShiona.Shamrock.xposed.helper.internal.IPCRequest
import moe.RinShiona.Shamrock.xposed.ipc.ShamrockIpc
import mqq.app.MobileQQ
import java.util.concurrent.ConcurrentHashMap

/**
 * Cross-process Binder fetch: main (HTTP) ↔ MSF (FEKit/QSign).
 *
 * MSF registers binders locally and answers `fetch_ipc` broadcasts.
 * Main registers `ipc_callback` and stores received binders in [ShamrockIpc].
 */
internal object IpcFetcher {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<IBinder>>()
    private val initialized = java.util.concurrent.atomic.AtomicBoolean(false)

    fun initMainProcess() {
        if (!initialized.compareAndSet(false, true)) return
        DynamicReceiver.register("ipc_callback", IPCRequest { intent ->
            val bundle = intent.getBundleExtra("ipc") ?: return@IPCRequest
            val name = bundle.getString("name") ?: return@IPCRequest
            val binder = bundle.getBinder("binder") ?: return@IPCRequest
            ShamrockIpc.register(name, binder)
            pending.remove(name)?.complete(binder)
            XposedBridge.log("Shamrock: IPC binder received in main process: $name")
        })
        XposedBridge.log("Shamrock: IpcFetcher main-process handler registered")
    }

    suspend fun fetch(name: String, timeoutMs: Long = 8000): IBinder? {
        ShamrockIpc.get(name)?.let { return it }

        val deferred = CompletableDeferred<IBinder>()
        pending[name] = deferred

        kotlin.runCatching {
            MobileQQ.getContext().broadcast("msf") {
                putExtra("__cmd", "fetch_ipc")
                putExtra("ipc_name", name)
            }
        }.onFailure {
            pending.remove(name)
            XposedBridge.log("Shamrock: fetch_ipc broadcast failed for $name: ${it.message}")
            return null
        }

        return withTimeoutOrNull(timeoutMs) {
            deferred.await()
        } ?: ShamrockIpc.get(name).also {
            pending.remove(name)
            if (it == null) {
                XposedBridge.log("Shamrock: IPC fetch timeout for $name")
            }
        }
    }

    suspend fun prefetchAll() {
        fetch(ShamrockIpc.IPC_QSIGN)
        fetch(ShamrockIpc.IPC_BYTEDATA)
    }
}
