package io.github.guys222.processkeepalive;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

/**
 * 桌面小部件：一眼看到「守护中 N/M」与本次开机的守护动作。
 *
 * 刷新策略：AppWidgetProvider 自带的 updatePeriodMillis 下限被系统锁死在 30 分钟，
 * 指望它等于半小时前的旧数据；真正的刷新由 {@link GuardService} 每 30 秒调用这里完成
 * （用户没开常驻通知时，就只能跟随系统的 30 分钟节拍，这是 AppWidget 的硬限制）。
 */
public class GuardWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context ctx, AppWidgetManager awm, int[] appWidgetIds) {
        refreshAll(ctx);
    }

    @Override
    public void onEnabled(Context ctx) {
        refreshAll(ctx);
    }

    /** 全部实例刷新一次（由常驻服务周期调用）。 */
    public static void refreshAll(Context ctx) {
        AppWidgetManager awm = AppWidgetManager.getInstance(ctx);
        if (awm == null) return;
        ComponentName cn = new ComponentName(ctx, GuardWidget.class);
        int[] ids = awm.getAppWidgetIds(cn);
        if (ids == null || ids.length == 0) return;   // 桌面上没放实例，白刷新也无所谓
        RemoteViews rv = render(ctx);
        for (int id : ids) {
            try {
                awm.updateAppWidget(id, rv);
            } catch (Throwable ignored) {
            }
        }
    }

    private static RemoteViews render(Context ctx) {
        GuardStatus st = GuardStatus.of(ctx);
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_guard);
        rv.setTextViewText(R.id.w_title, st.title());
        rv.setTextViewText(R.id.w_stats, st.statsLine());
        String down = st.downLine();
        rv.setTextViewText(R.id.w_down, down == null ? "" : down);
        rv.setViewVisibility(R.id.w_down, down == null ? android.view.View.GONE
                : android.view.View.VISIBLE);

        Intent tap = new Intent(ctx, MainActivity.class);
        tap.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        rv.setOnClickPendingIntent(R.id.w_root,
                PendingIntent.getActivity(ctx, 0, tap, flags));
        return rv;
    }
}
