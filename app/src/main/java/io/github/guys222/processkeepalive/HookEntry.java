package io.github.guys222.processkeepalive;

import android.util.Log;

import java.io.File;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed 入口。
 *
 * - 注入 system_server（包名 "android"）：执行所有保活钩子；
 * - 注入模块自身（包名 io.github.guys222.processkeepalive）：在本进程内写「激活标记」，供主页实时判断
 *   LSPosed 是否真正启用了本模块。
 */
public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "ProcessKeepAlive";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        // 诊断日志：确认 LSPosed 注入了哪些进程
        Log.i(TAG, "handleLoadPackage: " + lp.packageName);

        // 系统框架：不同 LSPosed 版本传入的包名可能是 "android" 或 "system"，两者都兼容
        if ("android".equals(lp.packageName) || "system".equals(lp.packageName)) {
            // 系统框架：安装保活钩子
            Prefs prefs = new Prefs();
            prefs.reload();
            KeepAliveHooks.install(lp.classLoader, prefs);
        } else if (BuildConfig.APPLICATION_ID.equals(lp.packageName)) {
            // 模块自身被 LSPosed 注入，说明模块已激活；在 App 自己的进程内写标记
            markActivated();
        }
    }

    /**
     * 写激活标记（统一走 ActivationMark，内容为 开机次数+墙钟）。
     */
    private static void markActivated() {
        try {
            ActivationMark.write(new File("/data/data/"
                    + BuildConfig.APPLICATION_ID + "/files"));
        } catch (Throwable t) {
            Log.w(TAG, "写激活标记失败: " + t);
        }
    }
}
