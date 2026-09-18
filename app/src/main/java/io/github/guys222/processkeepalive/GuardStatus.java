package io.github.guys222.processkeepalive;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 常驻通知与桌面小部件共用的「一眼状态」。
 *
 * 两个出口要展示的是同一件事（现在守住了几个应用、被杀/拉起几次），所以把取数集中在这里，
 * 避免通知和服务各写一份、将来口径打架。
 *
 * 关键取舍：存活判定**只用 hook 上报**，不做本地 /proc 兜底。
 * 理由见 {@link SurvivalWorker}：App 进程读别家 /proc 在 Android 9+ 基本读不到，
 * 本地扫描既慢又不准；而这里是每 30 秒跑一次的常驻任务，宁可显示「等待上报」也不要
 * 每半分钟扫一次全量 /proc（那才是真正的耗电大户）。
 */
public final class GuardStatus {

    /** 是否拿到 system_server 的有效上报；false 时 running 无意义。 */
    public final boolean hookOnline;
    /** 目标应用总数。 */
    public final int total;
    /** 当前在跑的目标数量；hookOnline=false 时为 -1。 */
    public final int running;
    /** 本次开机累计：被杀次数、拉起次数。 */
    public final long kills, starts;
    /** 未在跑的应用名（最多 3 个，给通知正文用）。 */
    public final List<String> downLabels;

    private GuardStatus(boolean hookOnline, int total, int running,
                        long kills, long starts, List<String> downLabels) {
        this.hookOnline = hookOnline;
        this.total = total;
        this.running = running;
        this.kills = kills;
        this.starts = starts;
        this.downLabels = downLabels;
    }

    public static GuardStatus of(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(Prefs.PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> targets = new HashSet<>(sp.getStringSet(Prefs.KEY_TARGETS, new HashSet<>()));
        SurvivalData.ProcReport rpt = SurvivalData.procReport(ctx);
        Map<String, Long> map = rpt.fresh ? rpt.map : Collections.emptyMap();

        int running = -1;
        long kills = 0, starts = 0;
        List<String> down = new ArrayList<>();
        if (rpt.fresh) {
            running = 0;
            for (String pkg : targets) {
                Long v = map.get(pkg);
                long[] gs = SurvivalData.guardStats(ctx, pkg);
                kills += gs[0];
                starts += gs[1];
                boolean up = v != null && v >= 0;
                if (up) {
                    running++;
                } else if (down.size() < 3) {
                    down.add(label(ctx, pkg));
                }
            }
        }
        return new GuardStatus(rpt.fresh, targets.size(), running, kills, starts, down);
    }

    /** 大标题：如「守护中 8 / 12」。上报没到位时不假装知道。 */
    public String title() {
        if (!hookOnline) return "等待 system_server 上报";
        if (total == 0) return "未选择保活目标";
        return "守护中 " + running + " / " + total;
    }

    /** 正文第一行：本次开机的守护动作合计。 */
    public String statsLine() {
        if (!hookOnline) return "打开模块可查看完整状态";
        return "本次开机 · 被杀 " + kills + " 次 · 拉起 " + starts + " 次";
    }

    /** 正文第二行：掉线的应用（没有则为 null）。 */
    public String downLine() {
        if (downLabels.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("未运行：");
        for (int i = 0; i < downLabels.size(); i++) {
            if (i > 0) sb.append('、');
            sb.append(downLabels.get(i));
        }
        if (total - running > downLabels.size()) sb.append(" 等");
        return sb.toString();
    }

    private static String label(Context ctx, String pkg) {
        try {
            CharSequence cs = ctx.getPackageManager()
                    .getApplicationLabel(ctx.getPackageManager().getApplicationInfo(pkg, 0));
            if (!TextUtils.isEmpty(cs)) return cs.toString();
        } catch (Throwable ignored) {
            // 目标已卸载、或包名解析不到：直接退化为包名，至少还能认出来是谁
        }
        return pkg;
    }
}
