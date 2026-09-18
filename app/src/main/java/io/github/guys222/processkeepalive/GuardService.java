package io.github.guys222.processkeepalive;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/**
 * 常驻状态服务：前台服务 + 常驻通知，同时驱动桌面小部件刷新。
 *
 * 为什么用前台服务而不是「贴一个 ongoing 通知就算了」：
 *   1. 通知要一直准确 —— 每 30 秒重算一次，没人跑的话数字会停在打开 App 那一刻；
 *   2. 它顺带把**模块 App 自己的进程**也守住了一层。system_server 侧的上报循环是
 *      通过 ConfigProvider 把数据推回 App 的，App 进程活着才能让进程页/时间线一直有数据。
 *
 * 与 hook 的关系：本服务只做「显示 + 刷新」，不做任何保活动作。真正的保活在 system_server 里，
 * 即便本服务被杀、进程被回收，保活依然照常工作。
 *
 * Android 14（target 34）起前台服务必须声明类型：这里用 specialUse 并在 manifest 里写明用途。
 */
public class GuardService extends Service {

    private static final String CHANNEL_ID = "guard_status";
    private static final int NOTI_ID = 20260917;
    /** 刷新节奏：比 hook 的 10 秒上报慢一个量级，够看且不至于空转。 */
    private static final long TICK_MS = 30_000L;

    private Handler handler;
    private HandlerThread thread;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            publish();
            if (handler != null) handler.postDelayed(this, TICK_MS);
        }
    };

    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, GuardService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable ignored) {
            // 通知权限缺失、ROM 后台限制过严时静默失败：主界面本身不受影响
        }
    }

    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, GuardService.class));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("PKA-guard");
        thread.start();
        handler = new Handler(thread.getLooper());
        ensureChannel();
        // target 34 要求 manifest 里声明 foregroundServiceType；类型由系统按声明判定，
        // 所以这里用不带 type 的老写法即可（装饰器版本只是允许运行时收窄类型，用不上）
        try {
            startForeground(NOTI_ID, build());
        } catch (Throwable t) {
            // 极端情况（ROM 抽风 / 类型不允许）：退化成普通启动，至少别把 App 干崩
            stopSelf();
        }
        handler.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 已在上次 onCreate 里前台化；重复 start 只刷新一次内容
        if (handler != null) {
            handler.removeCallbacks(tick);
            handler.post(tick);
        }
        return START_STICKY;   // 被杀后系统会择机重启，正好是「常驻」要的行为
    }

    @Override
    public void onDestroy() {
        if (handler != null) handler.removeCallbacks(tick);
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void publish() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTI_ID, build());
        } catch (Throwable ignored) {
        }
        // 顺手把桌面小部件刷新一遍：这是它唯一的主动刷新源，
        // AppWidgetProvider 自带的 updatePeriodMillis 最短只有 30 分钟
        try {
            GuardWidget.refreshAll(this);
        } catch (Throwable ignored) {
        }
    }

    private Notification build() {
        GuardStatus st = GuardStatus.of(this);
        Intent tap = new Intent(this, MainActivity.class);
        tap.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap, flags);

        Intent stop = new Intent(this, StopReceiver.class);
        PendingIntent stopPi = PendingIntent.getBroadcast(this, 1, stop, flags);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_processes)
                .setContentTitle(st.title())
                .setContentText(st.statsLine())
                .setStyle(new NotificationCompat.BigTextStyle()
                        .setBigContentTitle(st.title())
                        .bigText(statsAndDown(st)))
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(R.drawable.ic_power, "关闭常驻", stopPi);
        return b.build();
    }

    private static String statsAndDown(GuardStatus st) {
        String s = st.statsLine();
        String d = st.downLine();
        return d == null ? s : s + "\n" + d;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "保活状态",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("显示当前守护中的应用数量与本次开机的守护动作");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }
}
