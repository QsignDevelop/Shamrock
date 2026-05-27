package moe.RinShiona.Shamrock.xposed.helper

import com.tencent.qqnt.kernel.api.IKernelService
import com.tencent.qqnt.kernel.api.impl.MsgService
import com.tencent.qqnt.kernel.nativeinterface.IKernelGroupService
import com.tencent.qqnt.kernel.nativeinterface.IKernelGuildService
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.RinShiona.Shamrock.helper.Level
import moe.RinShiona.Shamrock.helper.LogCenter
import moe.RinShiona.Shamrock.remote.service.PacketReceiver
import moe.RinShiona.Shamrock.remote.service.listener.AioListener
import moe.RinShiona.Shamrock.remote.service.listener.GroupEventListener
import moe.RinShiona.Shamrock.remote.service.listener.KernelGuildListener
import moe.RinShiona.Shamrock.remote.service.listener.PrimitiveListener
import moe.RinShiona.Shamrock.tools.hookMethod
import moe.RinShiona.Shamrock.utils.PlatformUtils
import kotlin.reflect.jvm.javaMethod

internal object NTServiceFetcher {
    private lateinit var iKernelService: IKernelService
    private val lock = Mutex()
    private var curKernelHash = 0

    suspend fun onFetch(service: IKernelService) {
        lock.withLock {
            val msgService = service.msgService ?: return
            val sessionService = service.wrapperSession ?: return
            val groupService = sessionService.groupService ?: return

            val curHash = service.hashCode() + msgService.hashCode()
            if (isInitForNt(curHash)) return

            PacketHandler.initPacketHandler()
            PacketReceiver.init()

            LogCenter.log("Fetch kernel service successfully: $curKernelHash,$curHash,${PlatformUtils.isMainProcess()}")
            curKernelHash = curHash
            this.iKernelService = service


            initNTKernelListener(msgService, groupService)
            antiBackgroundMode(sessionService)
            //hookGuildListener(sessionService)
        }
    }

    /*
    private fun hookGuildListener(sessionService: IQQNTWrapperSession) {
        val guildService = sessionService.guildService
        XposedBridge.hookMethod(guildService::addKernelGuildListener.javaMethod, object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                val service = param?.thisObject as IKernelGuildService
                service.addKernelGuildListener(KernelGuildListener)
                LogCenter.log("Register Guild listener successfully.")
            }
        })
    }
    */

    private inline fun isInitForNt(hash: Int): Boolean {
        return hash == curKernelHash
    }

    private fun initNTKernelListener(msgService: MsgService, groupService: IKernelGroupService) {
        if (!PlatformUtils.isMainProcess()) return

        try {
            LogCenter.log("Register MSG listener successfully.")
            msgService.addMsgListener(AioListener)

            groupService.addKernelGroupListener(GroupEventListener)
            LogCenter.log("Register Group listener successfully.")

            PrimitiveListener.registerListener()
        } catch (e: Throwable) {
            LogCenter.log(e.stackTraceToString(), Level.WARN)
        }
    }

    private fun antiBackgroundMode(sessionService: IQQNTWrapperSession) {
        try {
            sessionService.javaClass.hookMethod("switchToBackGround").before {
                LogCenter.log({ "阻止进入后台模式！" }, Level.DEBUG)
                it.result = null
            }

            val msgService = sessionService.msgService
            msgService.javaClass.hookMethod("switchBackGroundForMqq").before {
                LogCenter.log({ "阻止进入后台模式！" }, Level.DEBUG)
                val cb = it.args[1] as IOperateCallback
                cb.onResult(-1, "injected")
                it.result = null
            }
            msgService.javaClass.hookMethod("switchBackGround").before {
                LogCenter.log({ "阻止进入后台模式！" }, Level.DEBUG)
                val cb = it.args[1] as IOperateCallback
                cb.onResult(-1, "injected")
                it.result = null
            }
            LogCenter.log({ "反后台模式注入成功！" }, Level.DEBUG)
        } catch (e: Throwable) {
            LogCenter.log("Keeping NT alive failed: ${e.message}", Level.WARN)
        }
    }

    val kernelService: IKernelService
        get() = iKernelService
}