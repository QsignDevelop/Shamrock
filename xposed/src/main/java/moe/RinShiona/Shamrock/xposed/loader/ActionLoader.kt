package moe.RinShiona.Shamrock.xposed.loader

import android.content.Context
import de.robv.android.xposed.XposedBridge
import moe.RinShiona.Shamrock.xposed.actions.*
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance

object ActionLoader {
    private val ACTION_FIRST_LIST = arrayOf(
        AntiDetection::class, // MUST be first — layers on EarlyAntiDetection
        DataReceiver::class,
        IpcService::class,
        PullConfig::class,
        ForceTablet::class,
        HookWrapperCodec::class,
        HookForDebug::class,
        FixLibraryLoad::class,
        FetchService::class,
    )

    private val ACTION_LIST = arrayOf<KClass<*>>(
        InitRemoteService::class, // 创建HTTP API
        NoBackGround::class, // 反QQ后台模式
        GuidLock::class,
    )

    // 先从APP拉取配置文件，再执行其他操作
    fun runFirst(ctx: Context) {
        kotlin.runCatching {
            ACTION_FIRST_LIST.forEach {
                val action = it.createInstance()
                action.invoke(ctx)
            }
        }.onFailure {
            XposedBridge.log(it)
        }
    }

    fun runService(ctx: Context) {
        ACTION_LIST.forEach {
            if (it.java != DataReceiver::class.java) {
                val action = it.createInstance() as IAction
                action.invoke(ctx)
            }
        }
    }
}