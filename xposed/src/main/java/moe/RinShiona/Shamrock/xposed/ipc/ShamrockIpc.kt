package moe.RinShiona.Shamrock.xposed.ipc

import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-process Binder registry for cross-component lookup.
 *
 * Lifecycle (MSF process, where FEKit is loaded):
 *   1. After QQ starts and FEKit gets initialized, Shamrock calls
 *      [register] with the appropriate Stub.Stub() IBinder.
 *   2. The main process (where the Ktor HTTP server runs) issues a
 *      "fetch_ipc" broadcast (see IpcService.kt) and the MSF process
 *      replies with the IBinder via [get].
 *
 * Lifecycle (main process):
 *   1. After receiving the IBinder via broadcast, callers wrap it with
 *      `IQSigner.Stub.asInterface(binder)` etc.
 *
 * NOTE: This class is process-local. The cross-process IBinder hand-off
 * is performed via Android broadcasts in IpcService.kt.
 */
internal object ShamrockIpc {
    // Well-known names used to identify the binder kind.
    // These string constants are the contract between IpcService (MSF side)
    // and consumers in the main process (e.g. GenerateQSign.kt).
    const val IPC_QSIGN = "qsign"
    const val IPC_BYTEDATA = "bytedata"
    const val IPC_FE_BRIDGE = "fe_bridge"

    private val binders = ConcurrentHashMap<String, IBinder>()

    /** Register an IBinder under [name]. Replaces any prior entry. */
    fun register(name: String, binder: IBinder) {
        binders[name] = binder
    }

    /** Unregister an entry. Safe to call when [name] is not present. */
    fun unregister(name: String) {
        binders.remove(name)
    }

    /** Look up the IBinder by name. Returns null if not registered. */
    fun get(name: String): IBinder? = binders[name]

    /** All registered names (snapshot). */
    fun keys(): Set<String> = binders.keys.toSet()
}
