package com.tencent.mobileqq.fe;

import android.content.Context;
import com.tencent.mobileqq.sign.QQSecuritySign;

import java.util.Collections;
import java.util.List;

/**
 * Compile-time stub for qqinterface. Real FEKit is provided by QQ at runtime.
 */
public class FEKit {

    private static FEKit instance;

    public static FEKit getInstance() {
        if (instance == null) {
            instance = new FEKit();
        }
        return instance;
    }

    public void init(Context ctx, String uin, String guid, String o3did, String q36, String qua) {
        // stub
    }

    public QQSecuritySign.SignResult getSign(String cmd, byte[] buffer, int seq) {
        return new QQSecuritySign.SignResult();
    }

    public QQSecuritySign.SignResult getSign(String cmd, byte[] buffer, int seq, String uin) {
        return new QQSecuritySign.SignResult();
    }

    public List<String> getCmdWhiteList() {
        return Collections.emptyList();
    }
}
