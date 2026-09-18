package io.github.guys222.processkeepalive;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * 外观主题管理（仅运行在模块 App 进程，不进入 system_server 快照路径）。
 * 模式值：0=跟随系统 1=浅色 2=深色。
 */
public final class ThemeManager {

    /** 跟随系统 */
    public static final int MODE_SYSTEM = 0;
    /** 浅色 */
    public static final int MODE_LIGHT = 1;
    /** 深色 */
    public static final int MODE_DARK = 2;

    private ThemeManager() {}

    /** 进程启动/Activity 创建时调用：把保存的模式应用到全局 night mode。 */
    public static void apply(Context context) {
        AppCompatDelegate.setDefaultNightMode(toNightMode(getMode(context)));
    }

    /** 直接设置模式并立即生效（会触发 Activity 重建）。 */
    public static void setMode(Context context, int mode) {
        persist(context, mode);
        AppCompatDelegate.setDefaultNightMode(toNightMode(mode));
    }

    /** 在「浅色 ↔ 深色」之间切换（覆盖「跟随系统」）。 */
    public static void toggle(Context context) {
        int cur = getMode(context);
        // 跟随系统时，按当前系统实际明暗决定切到对面；否则直接翻转
        boolean currentlyDark = AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES;
        setMode(context, currentlyDark ? MODE_LIGHT : MODE_DARK);
    }

    public static int getMode(Context context) {
        SharedPreferences sp = context.getSharedPreferences(Prefs.PREFS_NAME, Context.MODE_PRIVATE);
        return sp.getInt(Prefs.KEY_DARK, MODE_SYSTEM);
    }

    public static boolean isDark(Context context) {
        return AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES;
    }

    private static void persist(Context context, int mode) {
        context.getSharedPreferences(Prefs.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(Prefs.KEY_DARK, mode).apply();
    }

    private static int toNightMode(int mode) {
        switch (mode) {
            case MODE_LIGHT: return AppCompatDelegate.MODE_NIGHT_NO;
            case MODE_DARK: return AppCompatDelegate.MODE_NIGHT_YES;
            default: return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
    }
}
