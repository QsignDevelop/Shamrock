package moe.RinShiona.Shamrock.tools

import mqq.app.MobileQQ

import moe.RinShiona.Shamrock.xposed.helper.ModuleHide

private val context = MobileQQ.getContext()
private val packageManager = context.packageManager

private fun getPackageInfo(packageName: String) = packageManager.getPackageInfo(packageName, 0)

val ShamrockVersion: String = runCatching {
    getPackageInfo(ModuleHide.PACKAGE).versionName
}.getOrElse {
    getPackageInfo(ModuleHide.LEGACY_PACKAGE).versionName
}
