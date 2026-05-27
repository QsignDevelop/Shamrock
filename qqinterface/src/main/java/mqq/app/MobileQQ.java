package mqq.app;

import com.tencent.qphone.base.remote.SimpleAccount;
import com.tencent.qphone.base.util.BaseApplication;

import java.util.List;

import android.content.Context;

public abstract class MobileQQ extends BaseApplication {
    public static MobileQQ getMobileQQ() {
        throw new UnsupportedOperationException("only view.");
    }

    public static Context getContext() {
        throw new UnsupportedOperationException("stub — real MobileQQ at runtime.");
    }

    public String getQQProcessName() {
        throw new UnsupportedOperationException("only view.");
    }

    public AppRuntime peekAppRuntime() {
        throw new RuntimeException();
    }

    public AppRuntime waitAppRuntime(BaseActivity baseActivity) {
        return null;
    }

    public AppRuntime waitAppRuntime() {
        return waitAppRuntime(null);
    }

    public List<SimpleAccount> getAllAccounts() {
        return null;
    }

    public int getMsfConnectedNetType() {
        return 0;
    }
}
