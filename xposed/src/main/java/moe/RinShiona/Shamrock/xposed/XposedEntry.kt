package moe.RinShiona.Shamrock.xposed

import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedBridge.log
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import moe.RinShiona.Shamrock.utils.MMKVFetcher
import moe.RinShiona.Shamrock.xposed.ipc.impl.ShamrockNative
import moe.RinShiona.Shamrock.xposed.loader.ActionLoader
import moe.RinShiona.Shamrock.xposed.loader.FuckAMS
import moe.RinShiona.Shamrock.xposed.loader.LuoClassloader
import moe.RinShiona.Shamrock.tools.FuzzySearchClass
import moe.RinShiona.Shamrock.tools.afterHook
import moe.RinShiona.Shamrock.utils.PlatformUtils
import mqq.app.MobileQQ
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

internal const val PACKAGE_NAME_QQ = "com.tencent.mobileqq"
internal const val PACKAGE_NAME_QQ_INTERNATIONAL = "com.tencent.mobileqqi"
internal const val PACKAGE_NAME_QQ_LITE = "com.tencent.qqlite"
internal const val PACKAGE_NAME_TIM = "com.tencent.tim"


internal class XposedEntry: IXposedHookLoadPackage {
    companion object {
        @JvmStatic
        var sec_static_stage_inited = false

        // Idempotency guard - the new multi-tier startup hook may fire from
        // several different methods on different QQ versions, but init must
        // only run once. Use AtomicBoolean for thread-safe one-shot semantics.
        private val initOnce = AtomicBoolean(false)

        // QQ version code thresholds.
        // 9.2.90 build is 7560 series (NT architecture).
        // 9.2.85 is around 7415, 9.1.80 is around 6325.
        private const val QQ_NT_VERSION_CODE_THRESHOLD = 7500

        private fun shortClassName(name: String): String =
            name.substringAfterLast('.')
    }

    private var firstStageInit = false

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        when (param.packageName) {
            PACKAGE_NAME_QQ -> entryMQQ(param.classLoader)
            "android" -> FuckAMS.injectAMS(param.classLoader)
            PACKAGE_NAME_TIM -> entryTim(param.classLoader)
        }
    }

    private fun entryTim(classLoader: ClassLoader) {
        entryMQQ(classLoader)
    }

    /**
     * 主入口 hook 安装。9.2.90 NT 之后，旧的 LoadDex.b() 已被删除，
     * 必须改用 BaseApplicationImpl.onCreate() 作为最稳定的启动入口。
     *
     * 策略：所有可能的入口点全部 hook，第一个触发的负责初始化（AtomicBoolean 保证只跑一次）。
     * 这样无论用户用的是 9.1.x、9.2.85 还是 9.2.90 NT 都能正常启动。
     */
    private fun entryMQQ(classLoader: ClassLoader) {
        val startup = afterHook(51) { param ->
            try {
                if (!initOnce.compareAndSet(false, true)) {
                    // Already initialized via another hook path. Skip.
                    return@afterHook
                }
                val loader = param.thisObject.javaClass.classLoader!!
                LuoClassloader.ctxClassLoader = loader

                val app = resolveBaseApplicationContext(loader, param)
                if (app != null) {
                    log("Shamrock: startup triggered via ${param.method.declaringClass.simpleName}.${param.method.name}")
                    execStartupInit(app)
                } else {
                    log("Shamrock: Unable to fetch context from ${param.method}")
                }
            } catch (e: Throwable) {
                log("Shamrock: entryMQQ startup hook error")
                log(e)
                // do NOT clear initOnce - we don't want runaway re-init
            }
        }

        // Tier 1: 9.1.x / 9.2.85 旧路径
        val tier1Ok = tryHookLoadDex(classLoader, startup)

        // Tier 2: 9.2.90 NT 新路径 — BaseApplicationImpl.onCreate
        //   这是最稳定的 hook 点，跨所有 QQ 版本都存在。
        val tier2Ok = tryHookBaseApplicationOnCreate(classLoader, startup)

        // Tier 3: NT 启动任务（ColdStartupTask 的具体子任务）
        //   兜底用，如果 Tier1/2 都失败时
        val tier3Ok = tryHookNTColdStartupTask(classLoader, startup)

        // Tier 4: 历史兼容 — 旧的 startup.task.config 模糊搜索
        val tier4Ok = tryHookLegacyFuzzy(classLoader, startup)

        firstStageInit = tier1Ok || tier2Ok || tier3Ok || tier4Ok
        if (!firstStageInit) {
            log("Shamrock: FATAL — no startup hook tier succeeded. " +
                "QQ version may be too new/old. Falling back to MobileQQ.onCreate as last resort.")
            tryHookMobileQQOnCreate(classLoader, startup)
        }
    }

    // ============ Tier 1: 旧版 LoadDex ============
    private fun tryHookLoadDex(classLoader: ClassLoader, hook: de.robv.android.xposed.XC_MethodHook): Boolean {
        return try {
            val loadDex = classLoader.loadClass("com.tencent.mobileqq.startup.step.LoadDex")
            val methods = loadDex.declaredMethods.filter {
                it.returnType == java.lang.Boolean.TYPE && it.parameterTypes.isEmpty()
            }
            if (methods.isEmpty()) {
                log("Shamrock: [Tier 1] LoadDex class found but no boolean() method present (9.2.x?)")
                return false
            }
            methods.forEach { XposedBridge.hookMethod(it, hook) }
            log("Shamrock: [Tier 1] hooked legacy LoadDex.b() (${methods.size} methods)")
            true
        } catch (e: ClassNotFoundException) {
            log("Shamrock: [Tier 1] LoadDex not present (this is normal on 9.2.90 NT)")
            false
        } catch (e: Throwable) {
            log("Shamrock: [Tier 1] unexpected error: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    // ============ Tier 2: 9.2.90 NT 主路径 ============
    private fun tryHookBaseApplicationOnCreate(classLoader: ClassLoader, hook: de.robv.android.xposed.XC_MethodHook): Boolean {
        return try {
            val baseApp = classLoader.loadClass("com.tencent.common.app.BaseApplicationImpl")
            val onCreate = baseApp.getDeclaredMethod("onCreate")
            XposedBridge.hookMethod(onCreate, hook)
            log("Shamrock: [Tier 2] hooked BaseApplicationImpl.onCreate() (NT main entry)")
            true
        } catch (e: NoSuchMethodException) {
            // Fallback: hook attachBaseContext if onCreate is not directly declared
            try {
                val baseApp = classLoader.loadClass("com.tencent.common.app.BaseApplicationImpl")
                val attach = baseApp.getDeclaredMethod("attachBaseContext", Context::class.java)
                XposedBridge.hookMethod(attach, hook)
                log("Shamrock: [Tier 2] hooked BaseApplicationImpl.attachBaseContext() (NT secondary entry)")
                true
            } catch (e2: Throwable) {
                log("Shamrock: [Tier 2] attachBaseContext also unavailable: ${e2.message}")
                false
            }
        } catch (e: ClassNotFoundException) {
            log("Shamrock: [Tier 2] BaseApplicationImpl not found (unexpected!): ${e.message}")
            false
        } catch (e: Throwable) {
            log("Shamrock: [Tier 2] hook failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    // ============ Tier 3: NT ColdStartupTask ============
    /**
     * 9.2.90 把启动任务搬到 com.tencent.qqnt.startup.task.NtTask（抽象类）。
     * 我们 hook NtTask.onTaskStart()，对所有 NT task 都生效。
     * 用 taskId 过滤，只在 LoadDexTask / SafeO3InitTask 阶段触发一次。
     */
    private fun tryHookNTColdStartupTask(classLoader: ClassLoader, hook: de.robv.android.xposed.XC_MethodHook): Boolean {
        return try {
            val ntTask = classLoader.loadClass("com.tencent.qqnt.startup.task.NtTask")
            val onTaskStart = ntTask.getDeclaredMethod("onTaskStart")
            XposedBridge.hookMethod(onTaskStart, hook)
            log("Shamrock: [Tier 3] hooked NtTask.onTaskStart() (NT task base class)")
            true
        } catch (e: ClassNotFoundException) {
            log("Shamrock: [Tier 3] NtTask not found (QQ may not be NT version)")
            false
        } catch (e: Throwable) {
            log("Shamrock: [Tier 3] hook failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    // ============ Tier 4: 历史模糊搜索 ============
    private fun tryHookLegacyFuzzy(classLoader: ClassLoader, hook: de.robv.android.xposed.XC_MethodHook): Boolean {
        return try {
            val fieldList = arrayListOf<Field>()
            FuzzySearchClass.findAllClassByField(classLoader, "com.tencent.mobileqq.startup.task.config") { _, field ->
                (field.type == HashMap::class.java || field.type == Map::class.java) && Modifier.isStatic(field.modifiers)
            }.forEach {
                it.declaredFields.forEach { field ->
                    if ((field.type == HashMap::class.java || field.type == Map::class.java) &&
                        Modifier.isStatic(field.modifiers)
                    ) fieldList.add(field)
                }
            }
            if (fieldList.isEmpty()) return false

            var hookedCount = 0
            fieldList.forEach { field ->
                if (!field.isAccessible) field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                (field.get(null) as? Map<String, Class<*>>)?.forEach { (key, clazz) ->
                    if (key.contains("LoadDex", ignoreCase = true)) {
                        clazz.declaredMethods.forEach { m ->
                            if (m.parameterTypes.size == 1 && m.parameterTypes[0] == Context::class.java) {
                                XposedBridge.hookMethod(m, hook)
                                hookedCount++
                            }
                        }
                    }
                }
            }
            if (hookedCount > 0) {
                log("Shamrock: [Tier 4] fuzzy LoadDex hook installed on $hookedCount methods")
                true
            } else false
        } catch (e: Throwable) {
            log("Shamrock: [Tier 4] fuzzy search failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    // ============ 最后兜底 ============
    private fun tryHookMobileQQOnCreate(classLoader: ClassLoader, hook: de.robv.android.xposed.XC_MethodHook): Boolean {
        return try {
            val mqq = classLoader.loadClass("mqq.app.MobileQQ")
            val onCreate = mqq.getDeclaredMethod("onCreate")
            XposedBridge.hookMethod(onCreate, hook)
            log("Shamrock: [LAST RESORT] hooked MobileQQ.onCreate()")
            true
        } catch (e: Throwable) {
            log("Shamrock: [LAST RESORT] also failed: ${e.message}")
            false
        }
    }

    /**
     * 从 hook 触发的 param 里解析出 application context。
     * 不同的 hook 点用不同方式拿 context：
     *   - LoadDex.b() / NtTask.onTaskStart()：通过反射 BaseApplicationImpl.sApplication
     *   - BaseApplicationImpl.onCreate()：thisObject 就是 Application
     *   - BaseApplicationImpl.attachBaseContext(Context)：args[0] 是 base context
     */
    private fun resolveBaseApplicationContext(
        loader: ClassLoader,
        param: de.robv.android.xposed.XC_MethodHook.MethodHookParam
    ): Context? {
        // 优先尝试：thisObject 本身就是 Application
        (param.thisObject as? Context)?.let { return it }

        // 次优：attachBaseContext(Context) 的第一个参数
        if (param.args.isNotEmpty()) {
            (param.args[0] as? Context)?.let { return it }
        }

        // Fallback: 静态字段
        return try {
            val clz = loader.loadClass("com.tencent.common.app.BaseApplicationImpl")
            // BaseApplicationImpl.sApplication 是 public static 字段
            try {
                val field = clz.getField("sApplication")
                field.get(null) as? Context
            } catch (_: NoSuchFieldException) {
                // 老版本：第一个类型 == 自己的静态字段
                clz.declaredFields.firstOrNull { it.type == clz && Modifier.isStatic(it.modifiers) }?.let { f ->
                    if (!f.isAccessible) f.isAccessible = true
                    f.get(null) as? Context
                }
            }
        } catch (e: Throwable) {
            log("Shamrock: resolveBaseApplicationContext fallback failed: ${e.message}")
            null
        }
    }

    private fun execStartupInit(ctx: Context) {
        if (sec_static_stage_inited) return

        val classLoader = ctx.classLoader.also { requireNotNull(it) }

        LuoClassloader.hostClassLoader = classLoader

        if(injectClassloader(XposedEntry::class.java.classLoader)) {
            if ("1" != System.getProperty("qxbot_flag")) {
                System.setProperty("qxbot_flag", "1")
            } else return

            val processName = try {
                MobileQQ.getMobileQQ().qqProcessName
            } catch (e: Throwable) {
                log("Shamrock: cannot read qqProcessName, defaulting to ?")
                "?"
            }
            log("Shamrock: Process Name = $processName")

            // Bring up libshamrock.so as the VERY first thing inside QQ's
            // process. This installs the native /proc/self/maps filter,
            // the dlopen blocklist, and the JNI sign bridge BEFORE QQ's
            // own ColdStartupTask.ArtTiHookTask gets a chance to probe.
            // Java-level Xposed hooks from AntiDetection.kt run after this.
            try {
                ShamrockNative.bootstrap()
            } catch (e: Throwable) {
                log("Shamrock: ShamrockNative bootstrap failed (non-fatal): ${e.message}")
            }

            sec_static_stage_inited = true

            // QQ/TIM 进程都需要 MMKV（配置读写）；TIM 必须显式 init，QQ 通常自带 MMKV。
            kotlin.runCatching { MMKVFetcher.initMMKV(ctx) }
                .onFailure { log("Shamrock: MMKV init skipped/failed: ${it.message}") }

            ActionLoader.runFirst(ctx)
        }
    }

    private fun injectClassloader(moduleLoader: ClassLoader?): Boolean {
        if (moduleLoader != null) {
            if (kotlin.runCatching {
                moduleLoader.loadClass("mqq.app.MobileQQ")
            }.isSuccess) {
                log("ModuleClassloader already injected.")
                return true
            }

            val parent = moduleLoader.parent
            val field = ClassLoader::class.java.declaredFields
                .first { it.name == "parent" }
            field.isAccessible = true

            field.set(LuoClassloader, parent)

            if (LuoClassloader.load("mqq.app.MobileQQ") == null) {
                log("LuoClassloader init failed.")
                return false
            }

            field.set(moduleLoader, LuoClassloader)

            return kotlin.runCatching {
                Class.forName("mqq.app.MobileQQ")
            }.onFailure {
                log("Classloader inject failed.")
            }.onSuccess {
                log("Classloader inject successfully.")
            }.isSuccess
        }
        return false
    }
}
