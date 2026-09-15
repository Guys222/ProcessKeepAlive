package com.processkeepalive;

import android.util.Log;

import java.io.File;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * modern API 入口（新一代 LSPosed，如 LSPosed 2.x）。
 *
 * 注册方式：META-INF/xposed/java_init.list
 */
public class ModernEntry extends XposedModule {

    private static final String TAG = "ProcessKeepAlive";

    public ModernEntry() {
        // 框架通过无参构造反射实例化；初始化工作放到生命周期回调中
    }

    /** 双写日志：logcat + LSPosed 模块日志（持久保存，方便用户在管理器里查看）。 */
    private void logBoth(String msg) {
        Log.i(TAG, msg);
        try {
            log(Log.INFO, TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        logBoth("modern onModuleLoaded: process=" + param.getProcessName()
                + ", isSystemServer=" + param.isSystemServer());
        // 模块自身进程被注入，说明 LSPosed 已启用本模块；写激活标记供主页检测
        if (BuildConfig.APPLICATION_ID.equals(param.getProcessName())) {
            markActivated();
        }
    }

    /**
     * 在本模块 App 进程内写激活标记。
     *
     * 注意：必须写入「墙钟时间」System.currentTimeMillis()（与 legacy 的
     * HookEntry.markActivated、以及 system_server 经 ContentProvider 调用的
     * ConfigProvider.markActive 保持同一约定）。主页 detectActivated() 会把它当作
     * 墙钟时间，判断其是否落在本机开机时间窗内。
     *
     * 旧实现误写为 Process.getStartElapsedRealtime()（开机至今的毫秒数，是个很小的数），
     * 在「本模块」被勾入作用域时它会覆盖掉正确的墙钟值，导致主页永远显示「未激活」——
     * 这是 modern 模式下激活状态失效的根因。
     */
    /**
     * 在本模块 App 进程内写激活标记（统一走 ActivationMark，内容为 开机次数+墙钟）。
     */
    private void markActivated() {
        try {
            ActivationMark.write(new File("/data/data/"
                    + BuildConfig.APPLICATION_ID + "/files"));
            logBoth("已写入激活标记");
        } catch (Throwable t) {
            logBoth("写激活标记失败: " + t);
        }
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        logBoth("modern onSystemServerStarting，开始安装保活钩子");
        try {
            Prefs prefs = new Prefs();
            prefs.reload();
            logBoth("配置读取完成: enabled=" + prefs.isEnabled()
                    + ", autoStart=" + prefs.isAutoStart()
                    + ", targets=" + prefs.targetsCount());
            KeepAliveHooks.installModern(this, param.getClassLoader(), prefs);
        } catch (Throwable t) {
            logBoth("modern 安装失败: " + Log.getStackTraceString(t));
        }
    }
}
