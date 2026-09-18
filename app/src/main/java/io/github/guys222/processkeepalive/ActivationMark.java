package io.github.guys222.processkeepalive;

import java.io.File;
import java.io.FileOutputStream;

import android.content.Context;
import android.provider.Settings;

/**
 * 激活标记的统一读写工具。
 *
 * 文件位置：模块私有目录 files/activated
 * 文件格式（v3）：
 *   bc=<开机次数 BOOT_COUNT>
 *   wc=<写入时刻的墙钟毫秒>
 *
 * 为什么用 BOOT_COUNT（Settings.Global.boot_count）而不是单纯比较墙钟时间：
 * 手机开机早期若没网，系统时钟可能不准，之后 NTP 校时会"往前跳"。旧逻辑
 * （标记时间 >= 本次开机时刻）会因时钟跳变把刚写入的标记误判为"早于开机"，
 * 导致明明已激活却显示未激活。BOOT_COUNT 是内核记录的开机次数，单调递增、
 * 完全不受时钟跳变影响，检测更可靠。墙钟仅用于展示"何时写入"。
 */
public final class ActivationMark {

    public static final String FILE_NAME = "activated";

    private ActivationMark() {
    }

    /** 在模块 App 自己的私有目录写入激活标记（有现成 Context 时优先用这个重载）。 */
    public static void write(Context context) {
        try {
            write(context.getFilesDir(), context);
        } catch (Throwable ignored) {
        }
    }

    /** 兼容旧调用（只有 filesDir，如在 Xposed 注入的模块自身进程内）。 */
    public static void write(File filesDir) {
        write(filesDir, null);
    }

    private static void write(File filesDir, Context ctx) {
        try {
            File f = new File(filesDir, FILE_NAME);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            // 优先用传入的 Context 直接读（App 进程内反射取 systemContext 会失败，
            // 导致 bc=-1），失败再退回反射
            int bc = getBootCount(ctx);
            if (bc < 0) bc = getBootCount(null);
            long wc = System.currentTimeMillis();
            String content = "bc=" + bc + "\nwc=" + wc + "\n";
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(content.getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 读取开机次数（Settings.Global.BOOT_COUNT，API 24+，无需权限）。
     * ctx 为 null 时（Xposed 注入场景）通过 ActivityThread 反射取 context。
     * 读取失败返回 -1。
     */
    public static int getBootCount(Context ctx) {
        try {
            Context c = ctx;
            if (c == null) {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Object thread = at.getMethod("currentActivityThread").invoke(null);
                Object sysCtx = at.getMethod("getSystemContext").invoke(thread);
                if (sysCtx instanceof Context) c = (Context) sysCtx;
            }
            if (c != null) {
                return Settings.Global.getInt(c.getContentResolver(), "boot_count", -1);
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }
}
