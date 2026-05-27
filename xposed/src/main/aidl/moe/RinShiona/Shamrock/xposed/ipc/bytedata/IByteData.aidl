// IByteData.aidl
package moe.RinShiona.Shamrock.xposed.ipc.bytedata;

import moe.RinShiona.Shamrock.xposed.ipc.bytedata.IByteDataSign;

interface IByteData {
    IByteDataSign sign(String uin, String data, in byte[] salt);
}