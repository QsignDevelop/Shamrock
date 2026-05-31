package moe.RinShiona.Shamrock.xposed.helper

import com.tencent.qqnt.kernel.api.IKernelService
import com.tencent.qqnt.kernel.nativeinterface.IKernelGroupService
import com.tencent.qqnt.kernel.nativeinterface.IKernelGuildService
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.RinShiona.Shamrock.helper.KernelListenerProxy
import moe.RinShiona.Shamrock.helper.Level
import moe.RinShiona.Shamrock.helper.LogCenter
import moe.RinShiona.Shamrock.remote.service.PacketReceiver
import moe.RinShiona.Shamrock.remote.service.listener.AioListener
import moe.RinShiona.Shamrock.remote.service.listener.GroupEventListener
import moe.RinShiona.Shamrock.remote.service.listener.PrimitiveListener
import moe.RinShiona.Shamrock.tools.hookMethod
import moe.RinShiona.Shamrock.utils.PlatformUtils
import java.util.concurrent.atomic.AtomicBoolean

internal object NTServiceFetcher {
    private lateinit var iKernelService: IKernelService
    private val lock = Mutex()
    private var curKernelHash = 0
    private val msgServiceHookInstalled = AtomicBoolean(false)
    private val aioListenerRegistered = AtomicBoolean(false)

    suspend fun onFetch(service: IKernelService) {
        lock.withLock {
            val sessionService = service.wrapperSession ?: return
            val groupService = sessionService.groupService ?: return
            val msgService = resolveMsgService(service, sessionService) ?: return

            val curHash = service.hashCode() + msgService.hashCode()
            if (isInitForNt(curHash)) return

            PacketHandler.initPacketHandler()
            PacketReceiver.init()

            LogCenter.log("Fetch kernel service successfully: $curKernelHash,$curHash,${PlatformUtils.isMainProcess()}")
            curKernelHash = curHash
            this.iKernelService = service

            initNTKernelListener(msgService, groupService, service)
            antiBackgroundMode(sessionService)
        }
    }

    /** 9.2.90: session.msgService 常为 CppProxy，真正带 addMsgListener 的是 impl.MsgService */
    private fun resolveMsgService(service: IKernelService, session: com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession): Any? {
        val loader = service.javaClass.classLoader ?: return null
        installMsgServiceListenerHook(loader)

        runCatching {
            val m = service.javaClass.getMethod("getMsgService")
            m.invoke(service)?.let {
                XposedBridge.log("Shamrock: kernel.getMsgService => ${it.javaClass.name}")
                if (tryRegisterAioListener(it, loader)) return it
            }
        }
        for (field in service.javaClass.declaredFields) {
            runCatching {
                field.isAccessible = true
                val v = field.get(service) ?: return@runCatching
                if (v.javaClass.name.contains("MsgService", ignoreCase = true)) {
                    XposedBridge.log("Shamrock: kernel field ${field.name} => ${v.javaClass.name}")
                    if (tryRegisterAioListener(v, loader)) return v
                }
            }
        }
        session.msgService?.let {
            XposedBridge.log("Shamrock: session.msgService => ${it.javaClass.name}")
            return it
        }
        return null
    }

    fun installMsgServiceListenerHookEarly(loader: ClassLoader) {
        installMsgServiceListenerHook(loader)
    }

    private fun installMsgServiceListenerHook(loader: ClassLoader) {
        if (!msgServiceHookInstalled.compareAndSet(false, true)) return
        runCatching {
            val msgSvcCls = loader.loadClass("com.tencent.qqnt.kernel.api.impl.MsgService")
            val listenerClass = loader.loadClass("com.tencent.qqnt.kernel.nativeinterface.IKernelMsgListener")
            XposedBridge.hookAllMethods(msgSvcCls, "addMsgListener", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!aioListenerRegistered.compareAndSet(false, true)) return
                    runCatching {
                        val m = param.thisObject.javaClass.getMethod("addMsgListener", listenerClass)
                        val proxy = KernelListenerProxy.wrapMsgListener(loader, AioListener)
                        m.invoke(param.thisObject, proxy)
                        XposedBridge.log("Shamrock: AioListener piggyback on ${param.thisObject.javaClass.name}")
                        LogCenter.log("Register MSG listener (MsgService hook)", Level.INFO)
                    }.onFailure {
                        aioListenerRegistered.set(false)
                    }
                }
            })
            XposedBridge.log("Shamrock: hooked ${msgSvcCls.name}.addMsgListener")
        }.onFailure {
            msgServiceHookInstalled.set(false)
            XposedBridge.log("Shamrock: MsgService hook failed: ${it.message}")
        }
    }

    private fun tryRegisterAioListener(msgService: Any, loader: ClassLoader): Boolean {
        if (aioListenerRegistered.get()) return true
        val listenerClass = loader.loadClass("com.tencent.qqnt.kernel.nativeinterface.IKernelMsgListener")
        var cls: Class<*>? = msgService.javaClass
        while (cls != null) {
            val current = cls
            for (m in current.declaredMethods + current.methods) {
                if (m.name != "addMsgListener" || m.parameterCount != 1) continue
                if (!listenerClass.isAssignableFrom(m.parameterTypes[0])) continue
                runCatching {
                    m.isAccessible = true
                    val proxy = KernelListenerProxy.wrapMsgListener(loader, AioListener)
                    m.invoke(msgService, proxy)
                    aioListenerRegistered.set(true)
                    XposedBridge.log("Shamrock: AioListener direct on ${current.name}")
                    LogCenter.log("Register MSG listener on ${current.simpleName}", Level.INFO)
                    return true
                }
            }
            cls = current.superclass
        }
        return false
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

    private fun initNTKernelListener(msgService: Any, groupService: IKernelGroupService, kernel: IKernelService) {
        if (!PlatformUtils.isMainProcess()) return
        val loader = kernel.javaClass.classLoader ?: return

        try {
            if (!tryRegisterAioListener(msgService, loader)) {
                installMsgServiceListenerHook(loader)
                XposedBridge.log("Shamrock: waiting for QQ MsgService.addMsgListener …")
            }

            val groupIface = loader.loadClass("com.tencent.qqnt.kernel.nativeinterface.IKernelGroupListener")
            val groupProxy = KernelListenerProxy.wrapGroupListener(loader, GroupEventListener)
            val addGroup = groupService.javaClass.getMethod("addKernelGroupListener", groupIface)
            addGroup.invoke(groupService, groupProxy)
            LogCenter.log("Register Group listener (runtime proxy) successfully.", Level.INFO)

            PrimitiveListener.registerListener()
        } catch (e: Throwable) {
            LogCenter.log(e.stackTraceToString(), Level.WARN)
            XposedBridge.log("Shamrock: initNTKernelListener failed: ${e.message}")
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