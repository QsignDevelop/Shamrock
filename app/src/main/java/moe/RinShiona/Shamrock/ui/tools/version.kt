package moe.RinShiona.Shamrock.ui.tools

import android.content.Context

fun getShamrockVersion(context: Context): String {
    val packageManager = context.packageManager
    val packageInfo = packageManager.getPackageInfo(context.packageName, 0)
    return packageInfo.versionName
}