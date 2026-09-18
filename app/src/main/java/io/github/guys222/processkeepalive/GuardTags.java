package io.github.guys222.processkeepalive;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 守护目标「已启用功能」标签的统一口径。
 *
 * <p>★ 为什么要抽出来：这套标签原先只写在 {@link HomeFragment} 里（主页「已守护应用」下方）。
 * 进程页守护卡后来也要展示同样的标签，若各处各自内联一份，就会出现
 * 「同一个功能两个叫法 / 一边有一边没有」的不对标（046→047 已经踩过一次）。
 * 现在主页与进程页都走这里，口径唯一。
 *
 * <p>标签来源全部是 {@link Prefs} 的 per-app 开关，只读内存快照、无 IPC。
 */
public final class GuardTags {

    private GuardTags() {}

    /**
     * 某目标已启用的功能标签（按展示顺序）。
     * 一个都没开时返回空列表。
     *
     * ⚠ 默认值必须与 AppConfig / AppsFragment / ConfigProvider 完全一致，
     *   四处任何一处对不上，标签就会与实况不符（显示「已开启」但实际没开）。
     *   排查发现本文件曾把 fs_/kb_ 写成默认 true，而其余三处均为 false ——
     *   后果是新目标会凭空多出「防强停 / 拦后台清理」两个假标签。
     */
    public static List<String> of(Context ctx, String pkg) {
        List<String> tags = new ArrayList<>();
        SharedPreferences sp = ctx.getSharedPreferences(Prefs.PREFS_NAME, Context.MODE_PRIVATE);
        // 读数口径与 AppConfig 一致：全部默认关（见 AppConfig 字段注释里的「原为 true」变更说明）
        boolean fs = sp.getBoolean(Prefs.KEY_FORCE_PREFIX + pkg, false);
        boolean kb = sp.getBoolean(Prefs.KEY_KILLBG_PREFIX + pkg, false);
        boolean kill = sp.getBoolean(Prefs.KEY_KILL_PREFIX + pkg, false);
        boolean persist = sp.getBoolean(Prefs.KEY_PERSIST_PREFIX + pkg, false);
        boolean core = sp.getBoolean(Prefs.KEY_CORE_PREFIX + pkg, false);
        boolean msg = sp.getBoolean(Prefs.KEY_MSG_PREFIX + pkg, false);
        boolean aggressive = sp.getBoolean("aggressive", false);
        int adj = sp.getInt(Prefs.KEY_ADJ_PREFIX + pkg, 1);

        if (fs) tags.add("防强停");
        if (kb) tags.add("拦后台清理");
        if (kill || aggressive) tags.add("强杀拦截");
        // core 开启时已是核心级（含常驻底座），用「核心级」标识覆盖「常驻级」，避免重复
        if (core) tags.add("核心级");
        else if (persist) tags.add("常驻级");
        if (msg) tags.add("消息保活");
        // 常驻/核心开启时 adj 被系统底座接管（-800/-1000），档位标签失去意义，
        // 再打「档位:可见」这类标签反而误导（用户误以为档位还在生效）。
        // ★ 060：消息保活【不再】排除档位标签 —— 它与档位可叠加、各自独立生效，
        //   故同时显示「消息保活」与「档位:可见」是正确的，不是自相矛盾。
        if (!persist && !core) {
            String[] adjNames = {"档位:前台", "档位:可见", "档位:可感知"};
            tags.add(adj >= 0 && adj < adjNames.length ? adjNames[adj] : adjNames[1]);
        }
        return tags;
    }

    /** 功能标签配色：一眼区分保活底座（红/蓝）与普通防护（绿/橙/紫）。 */
    public static int color(String tag) {
        switch (tag) {
            case "核心级":     return 0xFFD84315;   // 红橙：最强底座
            case "常驻级":     return 0xFF1565C0;   // 蓝：托底保活底座
            case "强杀拦截":   return 0xFFEF6C00;   // 橙：激进手段
            case "消息保活":   return 0xFF6A1FB9;   // 紫：省电豁免
            case "防强停":
            case "拦后台清理": return 0xFF00796B;   // 青：常规防护
            default:           return 0xFF5F6368;   // 灰：档位等
        }
    }

    /**
     * 生成标签行视图（横向排列，超出靠外层滚动容器/自动换行）。
     * 没有任何标签时返回 null，调用方据此隐藏容器。
     *
     * ★ 059 P2：{@code includeTier} 重载已删除。它于 050 为进程页守护卡引入
     * （过滤掉 tier，避免与右上钉值徽章「两套徽章」重复），但 055 之后进程页
     * **整行标签都不再展示**，该参数再无任何调用方传 {@code false} —— 属纯死参数。
     * 留在签名里会让人误以为"还有个开关能关掉 tier"，反而增加误用面。
     * 若将来进程页重新引入标签行、且仍需过滤 tier，再加回带默认值的重载即可。
     */
    public static LinearLayout makeRow(Context ctx, String pkg) {
        List<String> tags = of(ctx, pkg);
        if (tags.isEmpty()) return null;

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 4);
        row.setLayoutParams(lp);

        for (String t : tags) {
            TextView tv = new TextView(ctx);
            tv.setText(t);
            // 醒目版：加粗 + 每类功能独立配色
            tv.setTextSize(10.5f);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            int c = color(t);
            tv.setTextColor(c);
            tv.setPadding(dp(ctx, 8), dp(ctx, 2), dp(ctx, 8), dp(ctx, 2));
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.RECTANGLE);
            gd.setColor((c & 0x00FFFFFF) | 0x2E000000);   // 同色 18% 底
            gd.setCornerRadius(dp(ctx, 8));
            tv.setBackground(gd);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ilp.setMargins(0, 0, dp(ctx, 5), 0);
            tv.setLayoutParams(ilp);
            row.addView(tv);
        }
        return row;
    }

    private static int dp(Context ctx, float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
