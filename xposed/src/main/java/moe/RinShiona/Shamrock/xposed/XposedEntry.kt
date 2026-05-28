package moe.RinShiona.Shamrock.xposed

import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedBridge.log
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import moe.RinShiona.Shamrock.utils.MMKVFetcher
import moe.RinShiona.Shamrock.xposed.actions.EarlyAntiDetection
import moe.RinShiona.Shamrock.xposed.ipc.impl.ShamrockNative
import moe.RinShiona.Shamrock.xposed.loader.ActionLoader
import moe.RinShiona.Shamrock.xposed.loader.FuckAMS
import moe.RinShiona.Shamrock.xposed.loader.LuoClassloader
import moe.RinShiona.Shamrock.xposed.helper.XPrefConfigLoader
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
    private data class HookInstallReport(
        val tier1: Boolean,
        val tier2: Boolean,
        val tier3: Boolean,
        val tier4: Boolean,
        val tier5: Boolean,
    ) {
        fun any(): Boolean = tier1 || tier2 || tier3 || tier4 || tier5
    }

    companion object {
        @JvmStatic
        var sec_static_stage_inited = false

        // Idempotency guard - the new multi-tier startup hook may fire from
        // several different methods on different QQ versions, but init must
        // only run once. Use AtomicBoolean for thread-safe one-shot semantics.
        private val initOnce = AtomicBoolean(false)
        private val retryScheduled = AtomicBoolean(false)
        private const val STARTUP_RETRY_MS = 500L
        private const val STARTUP_MAX_RETRIES = 60

        private fun procTag(): String = kotlin.runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentProcessName")
                .invoke(null) as String
        }.getOrDefault("?")

        private fun plog(msg: String) = log("Shamrock[${procTag()}]: $msg")

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
        plog("entryMQQ — installing startup hooks")
        installEarlyStage(classLoader)
        tryHookAttachBaseContext(classLoader)

        val startup = afterHook(51) { param ->
            val loader = param.thisObject?.javaClass?.classLoader
                ?: param.args.firstOrNull()?.javaClass?.classLoader
                ?: return@afterHook
            val source = "${param.method.declaringClass.simpleName}.${param.method.name}"
            tryStartupInit(loader, param, source)
        }

        val report = installStartupHookTiers(classLoader, startup)
        firstStageInit = report.any()
        if (!firstStageInit) {
            plog("FATAL — no startup hook tier succeeded. QQ version may be too new/old.")
        } else {
            scheduleStartupRetry(classLoader, "entryMQQ-fallback")
        }
    }

    private fun installEarlyStage(classLoader: ClassLoader) {
        kotlin.runCatching { XPrefConfigLoader.loadIfAvailable() }
        // Native bootstrap runs only after Application Context exists (attachBaseContext / onCreate).
        if (AntiDetectionConfig.allowEarlyHooks()) {
            EarlyAntiDetection.install(classLoader)
        } else {
            plog("early anti-detection disabled by config")
        }
    }

    private fun installStartupHookTiers(
        classLoader: ClassLoader,
        startup: de.robv.android.xposed.XC_MethodHook
    ): HookInstallReport {
        val tier1 = tryHookLoadDex(classLoader, startup)
        val tier2 = tryHookBaseApplicationOnCreate(classLoader, startup)
        val tier3 = tryHookNTColdStartupTask(classLoader, startup)
        val tier4 = tryHookLegacyFuzzy(classLoader, startup)
        val tier5 = tryHookMobileQQOnCreate(classLoader, startup)
        return HookInstallReport(
            tier1 = tier1,
            tier2 = tier2,
            tier3 = tier3,
            tier4 = tier4,
            tier5 = tier5
        )
    }

    private fun tryStartupInit(
        loader: ClassLoader,
        param: de.robv.android.xposed.XC_MethodHook.MethodHookParam?,
        source: String
    ) {
        if (initOnce.get()) return
        try {
            LuoClassloader.ctxClassLoader = loader

            val app = if (param != null) {
                resolveBaseApplicationContext(loader, param)
            } else {
                resolveContextFromLoader(loader)
            }
            if (app == null) {
                plog("startup hook fired before context ready: $source — will retry")
                scheduleStartupRetry(loader, source)
                return
            }
            if (!isMobileQQReady()) {
                plog("MobileQQ singleton not ready yet from $source — will retry")
                scheduleStartupRetry(loader, source)
                return
            }

            synchronized(XposedEntry::class.java) {
                if (initOnce.get()) return
                if (execStartupInit(app)) {
                    initOnce.set(true)
                    retryScheduled.set(false)
                    plog("startup triggered via $source")
                } else {
                    plog("execStartupInit failed from $source — will retry")
                    scheduleStartupRetry(loader, source)
                }
            }
        } catch (e: Throwable) {
            plog("startup hook error from $source")
            log(e)
            scheduleStartupRetry(loader, source)
        }
    }

    private fun scheduleStartupRetry(loader: ClassLoader, source: String) {
        if (initOnce.get()) return
        if (!retryScheduled.compareAndSet(false, true)) return

        Thread({
            var attempts = 0
            while (!initOnce.get() && attempts < STARTUP_MAX_RETRIES) {
                try {
                    Thread.sleep(STARTUP_RETRY_MS)
                } catch (_: InterruptedException) {
                    break
                }
                attempts++
                val app = resolveContextFromLoader(loader)
                if (app != null && isMobileQQReady()) {
                    synchronized(XposedEntry::class.java) {
                        if (!initOnce.get() && execStartupInit(app)) {
                            initOnce.set(true)
                            retryScheduled.set(false)
                            plog("startup triggered via delayed retry (after $source, attempt $attempts)")
                            return@Thread
                        }
                    }
                } else if (attempts % 10 == 0) {
                    plog("startup retry waiting (after $source, attempt $attempts/$STARTUP_MAX_RETRIES)")
                }
            }
            if (!initOnce.get()) {
                plog("FATAL — startup retry exhausted after $source ($attempts attempts)")
            }
            retryScheduled.set(false)
        }, "Shamrock-StartupRetry").apply {
            isDaemon = true
            start()
        }
    }

    private fun isMobileQQReady(): Boolean {
        return kotlin.runCatching {
            MobileQQ.getMobileQQ()
            true
        }.getOrDefault(false)
    }

    private fun resolveContextFromLoader(loader: ClassLoader): Context? {
        kotlin.runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        }.getOrNull()?.let { return it }

        kotlin.runCatching {
            MobileQQ.getContext()?.let { ctx ->
                return ctx.applicationContext ?: ctx
            }
        }
        return try {
            val clz = loader.loadClass("com.tencent.common.app.BaseApplicationImpl")
            try {
                clz.getField("sApplication").get(null) as? Context
            } catch (_: NoSuchFieldException) {
                clz.declaredFields.firstOrNull { it.type == clz && Modifier.isStatic(it.modifiers) }?.let { f ->
                    if (!f.isAccessible) f.isAccessible = true
                    f.get(null) as? Context
                }
            }
        } catch (_: Throwable) {
            null
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
    /** Earliest Application hook — retry native load with Context. */
    private fun tryHookAttachBaseContext(classLoader: ClassLoader) {
        runCatching {
            val baseApp = classLoader.loadClass("com.tencent.common.app.BaseApplicationImpl")
            val attach = baseApp.getDeclaredMethod("attachBaseContext", Context::class.java)
            XposedBridge.hookMethod(attach, object : de.robv.android.xposed.XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val ctx = param.args.getOrNull(0) as? Context ?: return
                    if (ShamrockNative.bootstrap(ctx)) {
                        plog("native bootstrap OK from attachBaseContext")
                    } else {
                        plog("native bootstrap deferred (attachBaseContext)")
                    }
                }
            })
            plog("hooked BaseApplicationImpl.attachBaseContext (early native retry)")
        }.onFailure {
            plog("attachBaseContext early hook skipped: ${it.message}")
        }
    }

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
            log("Shamrock: [Tier 5] hooked MobileQQ.onCreate()")
            true
        } catch (e: Throwable) {
            log("Shamrock: [Tier 5] hook failed: ${e.message}")
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
        // 优先尝试：thisObject 本身就是 Application / Context
        (param.thisObject as? Context)?.let { return it }

        // 9.2.90 NT：thisObject 常为 BaseApplicationImpl（非 Context），尝试 getApplication()
        param.thisObject?.let { obj ->
            kotlin.runCatching {
                obj.javaClass.getMethod("getApplication").invoke(obj) as? Context
            }.getOrNull()?.let { return it }
            kotlin.runCatching {
                obj.javaClass.getMethod("getApplicationContext").invoke(obj) as? Context
            }.getOrNull()?.let { return it }
        }

        // 次优：attachBaseContext(Context) 的第一个参数
        if (param.args.isNotEmpty()) {
            (param.args[0] as? Context)?.let { return it }
        }

        return resolveContextFromLoader(loader)
    }

    private fun execStartupInit(ctx: Context): Boolean {
        if (sec_static_stage_inited) return true

        val classLoader = ctx.classLoader ?: run {
            plog("execStartupInit aborted — null classLoader")
            return false
        }

        LuoClassloader.hostClassLoader = classLoader

        if (!injectClassloader(XposedEntry::class.java.classLoader)) {
            plog("execStartupInit aborted — classloader inject failed")
            return false
        }

        val processName = try {
            MobileQQ.getMobileQQ().qqProcessName
        } catch (e: Throwable) {
            plog("cannot read qqProcessName, defaulting to ?")
            "?"
        }
        plog("Process Name = $processName")

        try {
            ShamrockNative.bootstrap(ctx)
        } catch (e: Throwable) {
            plog("ShamrockNative bootstrap failed (non-fatal): ${e.message}")
        }

        kotlin.runCatching { MMKVFetcher.initMMKV(ctx) }
            .onFailure { plog("MMKV init skipped/failed: ${it.message}") }

        kotlin.runCatching {
            ActionLoader.runFirst(ctx)
        }.onFailure {
            plog("ActionLoader.runFirst failed")
            log(it)
            return false
        }

        sec_static_stage_inited = true
        System.setProperty("qxbot_flag", "1")
        return true
    }

    private fun injectClassloader(moduleLoader: ClassLoader?): Boolean {
        if (moduleLoader != null) {
            if (kotlin.runCatching {
                moduleLoader.loadClass("mqq.app.MobileQQ")
            }.isSuccess) {
                plog("ModuleClassloader already injected.")
                return true
            }

            val parent = moduleLoader.parent
            val field = ClassLoader::class.java.declaredFields
                .first { it.name == "parent" }
            field.isAccessible = true

            field.set(LuoClassloader, parent)

            if (LuoClassloader.load("mqq.app.MobileQQ") == null) {
                plog("LuoClassloader init failed.")
                return false
            }

            field.set(moduleLoader, LuoClassloader)

            return kotlin.runCatching {
                Class.forName("mqq.app.MobileQQ")
            }.onFailure {
                plog("Classloader inject failed.")
            }.onSuccess {
                plog("Classloader inject successfully.")
            }.isSuccess
        }
        return false
    }
}
