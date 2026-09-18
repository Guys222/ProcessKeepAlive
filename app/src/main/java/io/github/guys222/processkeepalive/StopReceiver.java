package io.github.guys222.processkeepalive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * 常驻通知上的「关闭常驻」按钮。
 *
 * 让用户能在不进 App 的情况下把通知收掉：点了就写回开关并停掉服务，
 * 下次在设置页还能再打开 —— 属于「我想暂时安静点」，不是「我要卸载」。
 */
public class StopReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        ctx.getSharedPreferences(Prefs.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(Prefs.KEY_NOTIFY, false).apply();
        GuardService.stop(ctx);
        try {
            Toast.makeText(ctx, "已关闭常驻通知", Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
