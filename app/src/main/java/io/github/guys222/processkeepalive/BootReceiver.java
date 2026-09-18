package io.github.guys222.processkeepalive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机后恢复常驻通知。
 *
 * 常驻服务挂了就算用户什么都不做也会自己回来 —— 前提是开关是开着的。
 * 注意：这里只恢复「显示」，真正的保活由 system_server 里的钩子完成，不依赖本 App 是否启动。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)) return;
        boolean on = ctx.getSharedPreferences(Prefs.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(Prefs.KEY_NOTIFY, false);
        if (on) GuardService.start(ctx);
    }
}
