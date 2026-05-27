package moe.RinShiona.Shamrock.xposed.actions

import android.content.Context
import moe.RinShiona.Shamrock.tools.hookMethod
import moe.RinShiona.Shamrock.xposed.loader.NativeLoader

internal class FixLibraryLoad: IAction {
    val redirectedLibrary =arrayOf(
        "ffmpegkit_abidetect",
        "avutil",
        "swscale",
        "swresample",
        "avcodec",
        "avformat",
        "avfilter",
        "avdevice",
        "ffmpegkit"
    )

    override fun invoke(ctx: Context) {
        com.arthenica.ffmpegkit.NativeLoader::class.java.hookMethod("loadLibrary").before {
            val name: String = it.args[0] as String
            if (name in redirectedLibrary) {
                redirectedLibrary.forEach {
                    NativeLoader.load(it)
                }
                NativeLoader.load(name)
            }
            it.result = null
        }
    }
}