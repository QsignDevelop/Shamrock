@file:Suppress(
    "SpellCheckingInspection", "unused", "PropertyName",
    "ClassName", "NonAsciiCharacters"
)
package moe.RinShiona.Shamrock.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import moe.RinShiona.Shamrock.R

private val LocalStringDefault = Default()
private val LocalString2B = Chūnibyō()

val CUTE_SUBTITLES = arrayOf(
    "今天也要元气满满喵～",
    "CherryPop 陪你连接 QQ 世界 ♡",
    "签签顺利，消息多多～",
    "白粉色小宇宙启动中…",
    "喵帕斯！OneBot 就绪了吗？",
    "像棉花糖一样软软的连接",
    "星星和 cherry 都为你亮着",
    "摸鱼不忘看日志喵（大概",
    "今日运势：接口全绿 ✨",
    "给 QQ 套上一层梦幻滤镜",
)

val RANDOM_TITLE = arrayOf("CherryPop")
val RANDOM_SUB_TITLE = arrayOf(
    "A Framework Base On Xposed",
    "今天吃什么好呢?",
    "遇事不决，量子力学!",
    "Just kkb?",
    "いいよ，こいよ",
    "伊已逝 吾亦逝",
    "忆久意久 把义领",
    "喵帕斯!",
    "Creeper?",
    "Make American Great Again!",
    "TXHookPro",
    "曾经有人失去了那个她",
    "欲买桂花同载酒，终不似，少年游。",
    "抚千窟为佑 看长安落花",
    "どこにもない",
    "春日和 かかってらしゃい"
)

val LocalString: VarString
    @ReadOnlyComposable
    @Composable
    get() {
        val ctx = LocalContext.current
        val sharedPreferences = ctx.getSharedPreferences("config", 0)
        return if (!sharedPreferences.getBoolean("2B", false)) {
            LocalStringDefault
        } else {
            LocalString2B
        }
    }

private open class Chūnibyō: Default() {
    init {
        TitlesWithIcon = arrayOf(
            "玄天" to R.drawable.round_home_24,
            "签印" to R.drawable.round_api_24,
            "天穹" to R.drawable.round_dashboard_24,
            "无极" to R.drawable.round_monitor_heart_24,
            "飘渺" to R.drawable.baseline_security_24,
        )
        frameworkYes = "仙路已通"
        frameworkNo = "鬼怪横行"
        frameworkYesLite = "五行已备"
        frameworkNoLite = "需待东风"
        legalWarning = "白榆，北辰，曜魄，应星，云川当方位不乱，即可作于无极之域。\n" +
                "执明起，至除免于灾祸。\n" +
                "元冥浩浩，非凡不可动之。"
        labWarning = "寒酥降矣，梅熟日久，莫不可测。"
    }
}

private open class Default: VarString(
    TitlesWithIcon = arrayOf(
        "主页" to R.drawable.round_home_24,
        "QSign" to R.drawable.round_api_24,
        "OneBot" to R.drawable.round_dashboard_24,
        "日志" to R.drawable.round_monitor_heart_24,
        "设置" to R.drawable.baseline_security_24,
    ), "框架已激活", "框架未激活",
    "已激活", "未激活",
    legalWarning = "该模块仅适用于目标版本9.2.90及以上的版本。\n" +
            "同时声明本项目仅用于学习与交流，请于24小时内删除。\n" +
            "同时开源贡献者均享受免责条例。",
    labWarning = "实验室功能，可能会导致出乎意料的BUG!",
    "日志"
)

open class VarString(
    var TitlesWithIcon: Array<Pair<String, Int>>,
    var frameworkYes: String,
    var frameworkNo: String,

    var frameworkYesLite: String,
    var frameworkNoLite: String,

    var legalWarning: String,

    var labWarning: String,

    var logTitle: String
)
