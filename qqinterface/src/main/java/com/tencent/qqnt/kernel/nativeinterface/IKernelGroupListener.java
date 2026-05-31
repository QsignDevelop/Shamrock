package com.tencent.qqnt.kernel.nativeinterface;

import java.util.ArrayList;
import java.util.HashMap;

public interface IKernelGroupListener {
    void onGetGroupBulletinListResult(long j2, String str, GroupBulletinListResult groupBulletinListResult);

    void onGroupAvatarUrlChange(long j2, String str);

    void onGroupBulletinChange(long j2, GroupBulletin groupBulletin);

    void onGroupBulletinRichMediaDownloadComplete(BulletinFeedsDownloadInfo bulletinFeedsDownloadInfo);

    void onGroupBulletinRichMediaProgressUpdate(BulletinFeedsDownloadInfo bulletinFeedsDownloadInfo);

    void onGroupConfMemberChange(long j2, ArrayList<String> arrayList);

    void onGroupDetailInfoChange(GroupDetailInfo groupDetailInfo);

    void onGroupListUpdate(GroupListUpdateType groupListUpdateType, ArrayList<GroupSimpleInfo> arrayList);

    void onGroupNotifiesUnreadCountUpdated(boolean z, long j2, int i2);

    // Added in QQ NT 9.2.90 — present in the host's runtime interface. Must be
    // declared here (and implemented by every listener) or the kernel callback
    // raises AbstractMethodError when it fires.
    void onGroupNotifiesUnreadCountUpdatedV2(boolean z, long j2, int i2, int i3, int i4, int i5);

    void onGroupAllInfoChange(GroupAllInfo groupAllInfo);

    void onGroupNotifiesUpdated(boolean z, ArrayList<GroupNotifyMsg> arrayList);

    // QQ NT 9.2.90
    void onGroupNotifiesUpdatedV2(boolean z, long j2, ArrayList arrayList, ArrayList arrayList2);

    void onGroupExtListUpdate(GroupExtListUpdateType groupExtListUpdateType, ArrayList arrayList);

    void onGroupMemberNewExtInfoChange(long j2, ArrayList arrayList);

    void onGroupSingleScreenNotifiesV2(boolean z, long j2, ArrayList arrayList);

    void onGroupPortraitChange(long j2, ArrayList<String> arrayList, ArrayList<String> arrayList2);

    void onGroupSingleScreenNotifies(boolean z, long j2, ArrayList<GroupNotifyMsg> arrayList);

    void onGroupStatisticInfoChange(long j2, GroupStatisticInfo groupStatisticInfo);

    void onGroupsMsgMaskResult(ArrayList<GroupMsgMaskInfo> arrayList);

    void onJoinGroupNotify(JoinGroupNotifyMsg joinGroupNotifyMsg);

    void onMemberInfoChange(long j2, DataSource dataSource, HashMap<String, MemberInfo> hashMap);

    void onMemberListChange(GroupMemberListChangeInfo groupMemberListChangeInfo);

    // QQ NT 9.2.90 — kernel dispatches this; missing override → AbstractMethodError / JNI FatalError
    void onGroupMemberLevelInfoChange(long j2, GroupMemberLevelInfo groupMemberLevelInfo);

    // QQ NT 9.2.90 — AIO slot layout callbacks
    void onGroupAIOSlotInfoChange(ArrayList arrayList);

    void onSearchMemberChange(String str, String str2, ArrayList<GroupMemberInfoListId> arrayList, HashMap<String, MemberInfo> hashMap);

    void onShutUpMemberListChanged(long j2, ArrayList<MemberInfo> arrayList);
}
