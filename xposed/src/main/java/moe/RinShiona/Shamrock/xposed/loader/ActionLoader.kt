package moe.RinShiona.Shamrock.xposed.loader

import android.content.Context
import de.robv.android.xposed.XposedBridge
import moe.RinShiona.Shamrock.xposed.actions.AntiDetection
import moe.RinShiona.Shamrock.xposed.actions.DataReceiver
import moe.RinShiona.Shamrock.xposed.actions.FetchService
import moe.RinShiona.Shamrock.xposed.actions.FixLibraryLoad
import moe.RinShiona.Shamrock.xposed.actions.ForceTablet
import moe.RinShiona.Shamrock.xposed.actions.GuidLock
import moe.RinShiona.Shamrock.xposed.actions.HookForDebug
import moe.RinShiona.Shamrock.xposed.actions.HookWrapperCodec
import moe.RinShiona.Shamrock.xposed.AntiDetectionConfig
import moe.RinShiona.Shamrock.xposed.actions.IAction
import moe.RinShiona.Shamrock.xposed.actions.InitRemoteService
import moe.RinShiona.Shamrock.xposed.actions.IpcService
import moe.RinShiona.Shamrock.xposed.actions.NoBackGround
import moe.RinShiona.Shamrock.xposed.actions.PullConfig
import kotlin.reflect.KClass

object ActionLoader {
    private val ACTION_FIRST_MAIN = arrayOf(
        AntiDetection::class,
        DataReceiver::class,
        PullConfig::class,
        ForceTablet::class,
        HookWrapperCodec::class,
        HookForDebug::class,
        FixLibraryLoad::class,
        FetchService::class,
    )

    private val ACTION_FIRST_SAFE = arrayOf(
        DataReceiver::class,
        PullConfig::class,
        FetchService::class,
    )

    private val ACTION_SERVICE_SAFE = arrayOf(
        InitRemoteService::class,
    )

    private val ACTION_MSF = arrayOf(
        DataReceiver::class,
        IpcService::class,
    )

    private val ACTION_SERVICE = arrayOf(
        InitRemoteService::class,
        NoBackGround::class,
        GuidLock::class,
    )

    /** Avoid KClass.createInstance() — fails in hooked QQ after classloader inject. */
    private fun newAction(actionClass: KClass<*>): IAction {
        val ctor = actionClass.java.getDeclaredConstructor()
        ctor.isAccessible = true
        return ctor.newInstance() as IAction
    }

    private fun runActions(ctx: Context, actions: Array<out KClass<out IAction>>) {
        actions.forEach { actionClass ->
            kotlin.runCatching {
                newAction(actionClass).invoke(ctx)
            }.onFailure {
                XposedBridge.log("Shamrock: action ${actionClass.simpleName} failed: $it")
                XposedBridge.log(it)
            }
        }
    }

    fun runFirst(ctx: Context) {
        runActions(
            ctx,
            if (AntiDetectionConfig.connectivitySafeMode) ACTION_FIRST_SAFE else ACTION_FIRST_MAIN,
        )
    }

    fun runMsf(ctx: Context) {
        runActions(ctx, ACTION_MSF)
    }

    fun runService(ctx: Context) {
        runActions(
            ctx,
            if (AntiDetectionConfig.connectivitySafeMode) ACTION_SERVICE_SAFE else ACTION_SERVICE,
        )
    }
}
