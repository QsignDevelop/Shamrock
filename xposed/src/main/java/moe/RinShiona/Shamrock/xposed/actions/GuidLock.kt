package moe.RinShiona.Shamrock.xposed.actions

import android.content.Context
import com.tencent.beacon.event.open.BeaconReport
import com.tencent.mobileqq.qsec.qsecurity.QSecConfig
import moe.RinShiona.Shamrock.helper.LogCenter
import moe.RinShiona.Shamrock.tools.hex2ByteArray
import moe.RinShiona.Shamrock.tools.hookMethod
import moe.RinShiona.Shamrock.utils.MMKVFetcher
import moe.RinShiona.Shamrock.utils.PlatformUtils
import oicq.wlogin_sdk.tools.util
import kotlin.coroutines.resume
import kotlin.reflect.jvm.javaMethod

internal class GuidLock: IAction {
    companion object {
        var qimei: String = ""
    }

    override fun invoke(ctx: Context) {
        val guildLock = MMKVFetcher.mmkvWithId("guid")
        val utilClass = util::class.java
        utilClass.hookMethod("needChangeGuid").before {
            if (guildLock.getString("guid", null) != null) {
                it.result = false
            }
        }
        utilClass.hookMethod("getGuidFromFile").before {
            val guid = guildLock.getString("guid", null)
            if (guid != null) {
                it.result = guid.hex2ByteArray()
            }
        }
        utilClass.hookMethod("saveGuidToFile").before {
            val guid = guildLock.getString("guid", null)
            if (guid != null) {
                it.args[1] = guid.hex2ByteArray()
            }
        }

        utilClass.hookMethod("get_last_guid").before {
            val guid = guildLock.getString("guid", null)
            if (guid != null) {
                it.result = guid.hex2ByteArray()
            }
        }

        utilClass.hookMethod("generateGuid").before {
            val guid = guildLock.getString("guid", null)
            if (guid != null) {
                it.result = guid.hex2ByteArray()
            }
        }

        QSecConfig::class.java.hookMethod("setupBusinessInfo").before {
            val guid = guildLock.getString("guid", null)
            if (guid != null) {
                it.args[2] = guid.hex2ByteArray()
            }
        }

        if (PlatformUtils.isMqqPackage()) {
            BeaconReport.getInstance().getQimei("0S200MNJT807V3GE", ctx) { qimei ->
                LogCenter.log("QIMEI获取: ${qimei.qimei36}")
                GuidLock.qimei = qimei.qimei36
            }
        } else {
            BeaconReport.getInstance().getQimei { qimei ->
                LogCenter.log("QIMEI获取: ${qimei.qimei36}")
                GuidLock.qimei = qimei.qimei36
            }
        }

    }
}