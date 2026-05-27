// IQSigner.aidl
package moe.RinShiona.Shamrock.xposed.ipc.qsign;

import moe.RinShiona.Shamrock.xposed.ipc.qsign.IQSign;

interface IQSigner {
    IQSign sign(String cmd, int seq, String uin, in byte[] buffer);
    byte[] energy(String module, in byte[] salt);
    byte[] energyData(String data, in byte[] salt);
    boolean submit(String cmd, long callbackId, in byte[] buffer);
    byte[] xwDebugId(String uin, String start, String end);
    List<String> getCmdWhiteList();
}