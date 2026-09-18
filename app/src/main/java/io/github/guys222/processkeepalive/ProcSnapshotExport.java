package io.github.guys222.processkeepalive;

import android.content.Context;
import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 进程快照导出（纯文本报告）。
 *
 * 进程页看到的数据是 system_server 推回来的快照（App 自己读不到别家的 /proc），
 * 这份报告把它落成文件，方便复制给他人排查、或者隔一段时间留一份对比。
 *
 * 导出内容是「当前快照的全部进程」，不看页面上的搜索/筛选状态 —— 用户要的是客观快照，
 * 屏幕上被过滤掉的那部分往往才是要查的东西。
 *
 * 同样走 SAF 落盘，不需要存储权限。
 */
public final class ProcSnapshotExport {

    private ProcSnapshotExport() {}

    /** 建议文件名，如 pka-procs-20260917-0012.txt。 */
    public static String suggestName() {
        String ts = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(new Date());
        return "pka-procs-" + ts + ".txt";
    }

    /**
     * 生成报告正文。
     *
     * @return 报告文本；快照不可用时返回 null（调用方据此提示用户）
     */
    public static String build(Context ctx) {
        SurvivalData.ProcessSnapshot snap = SurvivalData.processSnapshot(ctx);
        if (snap == null || snap.list == null || snap.list.isEmpty()) return null;

        // 按包名分桶；系统/内核进程归到 sys 桶
        Map<String, List<ProcessInfo>> buckets = new LinkedHashMap<>();
        List<ProcessInfo> sysList = new ArrayList<>();
        for (ProcessInfo p : snap.list) {
            if (p.isSystem()) {
                sysList.add(p);
            } else {
                List<ProcessInfo> l = buckets.get(p.pkg);
                if (l == null) {
                    l = new ArrayList<>();
                    buckets.put(p.pkg, l);
                }
                l.add(p);
            }
        }

        StringBuilder sb = new StringBuilder();
        long ts = snap.time > 0 ? snap.time : System.currentTimeMillis();
        sb.append("ProcessKeepAlive 进程快照\n");
        sb.append("生成时间：").append(fmtTime(ts)).append('\n');
        sb.append("模块版本：v").append(BuildConfig.VERSION_NAME).append('\n');
        long totalKb = 0;
        for (ProcessInfo p : snap.list) totalKb += p.vmRssKb;
        sb.append("进程总数：").append(snap.list.size())
                .append(" · 应用 ").append(buckets.size())
                .append(" 个 · RSS 合计 ").append(fmtMem(totalKb)).append("\n\n");
        sb.append("说明：内存为各进程的 VmRSS，含与其他进程共享的库（bionic / libart / 图形驱动等），\n");
        sb.append("      直接相加会重复计数，因此合计值通常大于手机真实占用与物理内存总量。\n");
        sb.append("      oom_adj 数值越小，系统越不愿意回收它；运行时长为「自该进程本次启动起」累计。\n");
        sb.append("----------------------------------------------------------------\n\n");

        // 应用按总内存降序，读数时最关心的通常是最占内存的那个
        List<String> pkgs = new ArrayList<>(buckets.keySet());
        Collections.sort(pkgs, (a, b) -> Long.compare(sum(buckets.get(b)), sum(buckets.get(a))));
        for (String pkg : pkgs) {
            List<ProcessInfo> procs = new ArrayList<>(buckets.get(pkg));
            sortProcs(procs);
            long gsum = sum(procs);
            sb.append('[').append(label(ctx, pkg)).append("]\n");
            sb.append("  包名：").append(pkg).append('\n');
            sb.append("  进程 ").append(procs.size()).append(" 个 · RSS 合计 ").append(fmtMem(gsum)).append('\n');
            for (ProcessInfo p : procs) sb.append("    ").append(procLine(ctx, p)).append('\n');
            sb.append('\n');
        }

        if (!sysList.isEmpty()) {
            sortProcs(sysList);
            sb.append("[系统进程 / 内核线程]\n");
            sb.append("  进程 ").append(sysList.size()).append(" 个 · RSS 合计 ").append(fmtMem(sum(sysList))).append('\n');
            for (ProcessInfo p : sysList) sb.append("    ").append(procLine(ctx, p)).append('\n');
            sb.append('\n');
        }
        sb.append("----------------------------------------------------------------\n");
        sb.append("共 ").append(snap.list.size()).append(" 个进程 · RSS 合计 ").append(fmtMem(totalKb)).append('\n');
        return sb.toString();
    }

    /** 单行进程记录，字段顺序刻意做成「 pid → 名字 → 内存 → 状态 → adj → 时长 」，一眼能读数。 */
    private static String procLine(Context ctx, ProcessInfo p) {
        long up = SurvivalData.ticksToUptimeMs(p.startTicks);
        return String.format(Locale.CHINA, "pid %-6d %-28s %9s  %s  adj %-5d %s",
                p.pid,
                shorten(p.name, 28),
                fmtMem(p.vmRssKb),
                ProcessInfo.stateLabel(p.state),
                p.oomAdj,
                up >= 0 ? SurvivalData.fmtUp(up) : "-");
    }

    private static void sortProcs(List<ProcessInfo> procs) {
        Collections.sort(procs, new Comparator<ProcessInfo>() {
            @Override
            public int compare(ProcessInfo a, ProcessInfo b) {
                return Long.compare(b.vmRssKb, a.vmRssKb);   // 内存降序
            }
        });
    }

    private static long sum(List<ProcessInfo> procs) {
        long s = 0;
        for (ProcessInfo p : procs) s += p.vmRssKb;
        return s;
    }

    private static String label(Context ctx, String pkg) {
        try {
            CharSequence cs = ctx.getPackageManager()
                    .getApplicationLabel(ctx.getPackageManager().getApplicationInfo(pkg, 0));
            if (!TextUtils.isEmpty(cs)) return cs.toString();
        } catch (Throwable ignored) {
        }
        return pkg;
    }

    /** 超长进程名截断，保持列对齐不被 ":daemon" 之类的后缀冲歪。 */
    private static String shorten(String s, int max) {
        if (s == null) return "-";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    private static String fmtMem(long kb) {
        if (kb >= 1024 * 1024) return String.format(Locale.CHINA, "%.1f GB", kb / 1048576.0);
        if (kb >= 1024) return String.format(Locale.CHINA, "%.1f MB", kb / 1024.0);
        return kb + " KB";
    }

    private static String fmtTime(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(millis));
    }
}
