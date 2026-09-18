package io.github.guys222.processkeepalive;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 应用图标的加载与缓存。
 *
 * 图标解码不便宜：一次 Binder IPC 拿到包信息，再解码 APK 里的 PNG。
 * 之前两处都踩过同一个坑：
 *   1. AppsFragment 在装载列表时对「全部已安装应用」同步解码，上百个应用阻塞主线程
 *      一秒以上，表现为切到应用页明显卡顿；
 *   2. HomeFragment 每次重建「已守护应用」列表都重新解码一遍，反复切页反复解码。
 *
 * 现在统一走这里：解码一次进缓存，之后处处复用。列表只给可见行异步加载，
 * 且缓存是进程级的，ViewPager2 重建 Fragment 后依然命中。
 */
public final class AppIcons {

    private AppIcons() {}

    /** 按条目数计：200 个约覆盖两屏图标，够用且不会攒下太多 Drawable。 */
    private static final int CACHE_MAX = 200;

    private static final LruCache<String, Drawable> CACHE =
            new LruCache<String, Drawable>(CACHE_MAX) {
                @Override
                protected int sizeOf(String key, Drawable value) {
                    return 1;
                }
            };

    /** 后台解码线程。守护线程，随进程回收。 */
    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "pka-icon");
        t.setDaemon(true);
        return t;
    });

    /** 图标未就绪时的占位色块（按包名取色，避免加载期间一片空白）。 */
    private static final int[] PLACEHOLDER_COLORS = {
            0xff3b82f6, 0xff22d3ee, 0xff818cf8, 0xff3ddc84, 0xfffbbf24
    };

    /**
     * 同步取图标，未命中就当场解码并缓存。
     * 适合目标数量少的场景（如主页「已守护应用」、弹窗标题图标）。
     *
     * @return 图标；应用已卸载或包信息损坏时返回 null，由调用方回退到占位。
     */
    @Nullable
    public static Drawable get(Context ctx, String pkg) {
        Drawable cached = CACHE.get(pkg);
        if (cached != null) return cached;
        try {
            Drawable d = ctx.getPackageManager().getApplicationIcon(pkg);
            if (d != null) {
                CACHE.put(pkg, d);
                return d;
            }
        } catch (Exception ignored) {
            // 卸载 / 包信息损坏：交给调用方回退
        }
        return null;
    }

    /**
     * 异步装载到 ImageView：命中缓存直接设置，否则先上占位色块、后台解码后回填。
     * 适合长列表——只为可见的十几行解码，滚动时才继续补。
     */
    public static void into(Context ctx, String pkg, ImageView iv) {
        Drawable cached = CACHE.get(pkg);
        if (cached != null) {
            iv.setImageDrawable(cached);
            return;
        }
        iv.setImageDrawable(placeholder(pkg));
        // 用 tag 记住这一格当前 belongs to 哪个包名：行是复用的，快速滚动时它可能
        // 已经换成别的应用，回填时不比对就会把 A 的图标贴到 B 上。
        iv.setTag(pkg);
        // pm 在调用线程取好再交给后台，避免后台线程去碰可能已销毁的 Context
        final PackageManager pm = ctx.getPackageManager();
        EXEC.execute(() -> {
            Drawable d = null;
            try {
                d = pm.getApplicationIcon(pkg);
            } catch (Exception ignored) {
                // 保留占位色块，不塌陷成空白
            }
            if (d != null) CACHE.put(pkg, d);
            final Drawable got = d;
            iv.post(() -> {
                if (got != null && pkg.equals(iv.getTag())) iv.setImageDrawable(got);
            });
        });
    }

    /** 图标未就绪时的占位色块。每次新建实例——Drawable 不该被多个 ImageView 共享。 */
    public static Drawable placeholder(String pkg) {
        return new ColorDrawable(
                PLACEHOLDER_COLORS[Math.abs(pkg.hashCode()) % PLACEHOLDER_COLORS.length]);
    }
}
