package io.github.guys222.processkeepalive;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 存活率时序的本地存储与存活时长计算（仅 App 进程使用）。
 *
 * 两个口径要分清：
 *   · 24h 存活曲线 —— {@link SurvivalWorker} 每小时采样一次写入 24 槽环形缓冲；
 *   · 存活时长     —— 目标进程「当前这一次」连续跑了多久，见 {@link #uptimeMs}。
 */
public final class SurvivalData {

    /** 环形缓冲槽位数（24 小时，每小时一个采样点）。 */
    public static final int SLOTS = 24;

    private static final String SURVIVAL_PREFS = "survival_prefs";
    private static final String KEY_OVERALL = "surv_overall";
    private static final String PREFIX = "surv_";
    /** 开机即时采样标记（MainActivity 用，按 bootId 去重；新开机时随旧键一并清理）。 */
    public static final String KICK_KEY_PREFIX = "surv_bootkick_";

    /** 上次打点时的开机次数，用于判断记录是否属于本次开机。 */
    private static final String KEY_BOOT_PREFIX = "gboot_";
    /** 上次做开机清理时的开机次数。 */
    private static final String KEY_LAST_BOOT = "last_boot";

    private SurvivalData() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SURVIVAL_PREFS, Context.MODE_PRIVATE);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(Prefs.PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ---- 写入（由 SurvivalWorker 调用）----

    /** 写入某目标的一次采样（alive=1 或 0），维持最近 SLOTS 条。 */
    public static void pushSample(Context c, String pkg, float alive) {
        push(c, PREFIX + pkg, alive);
    }

    /** 写入整体存活率采样（0..1）。 */
    public static void pushOverall(Context c, float rate) {
        push(c, KEY_OVERALL, rate);
    }

    private static void push(Context c, String key, float value) {
        List<Float> buf = readBuf(sp(c), key);
        buf.add(value);
        while (buf.size() > SLOTS) buf.remove(0);
        sp(c).edit().putString(key, join(buf)).apply();
    }

    // ---- 读取 ----

    /** 整体 24 点存活率序列。 */
    public static float[] getOverallSeries(Context c) {
        return toSeries(readBuf(sp(c), KEY_OVERALL));
    }

    /**
     * 把原始缓冲规整为采样序列。
     *
     * <p>★ 044 修复「刚开机就显示 24h 存活率 100% / 最长连续存活 24h/24h」：
     * 原实现把<b>没采过样的空槽当存活</b>——缓冲为空时 24 槽全填 1f、有数据时前导
     * 空缺也拿最早样本补满。而 {@link SurvivalWorker} 每小时才采一次，刚开机一次都
     * 没采过，主页却凭空显示满格健康度，纯属无中生有（用户实测截图实锤）。
     *
     * <p>改为只返回<b>真实采到的样本</b>（长度 0..SLOTS）：
     * · 长度 0 → HomeFragment 既有保护生效，显示「—」，曲线走无数据基线；
     * · avg / longestContinuous 只在真实样本上计算，最长连续天然不超过已采样小时数。
     * 代价：曲线点数随采样数逐小时增长——这是诚实口径应有的样子。
     */
    private static float[] toSeries(List<Float> buf) {
        float[] out = new float[buf.size()];
        for (int i = 0; i < buf.size(); i++) out[i] = buf.get(i);
        return out;
    }

    /** 平均存活率（0..1）。 */
    public static float avg(float[] s) {
        if (s == null || s.length == 0) return 0f;
        float sum = 0f;
        for (float v : s) sum += v;
        return sum / s.length;
    }

    /** 连续存活（>=0.9）最长的小时数。 */
    public static int longestContinuous(float[] s) {
        if (s == null || s.length == 0) return 0;
        int best = 0, cur = 0;
        for (float v : s) {
            if (v >= 0.9f) { cur++; best = Math.max(best, cur); }
            else cur = 0;
        }
        return best;
    }

    // ---- 时间基准 ----

    /**
     * 本次开机到目前为止经过的毫秒数。
     *
     * 这是全套计时的基准。相较 {@link System#currentTimeMillis()} 的好处：它不受墙钟跳变
     * （用户改时间、NTP 校正）影响，而且每次开机从 0 重新计数 —— 关机那段时间不会被
     * 算进「保活时长」。
     */
    public static long sinceBootMs() {
        return android.os.SystemClock.elapsedRealtime();
    }

    /**
     * 当前开机次数（BOOT_COUNT，单调递增）。用来判断一条记录属于哪次开机。
     * 取不到时返回 -1，调用方需按「无法判定」处理（一般是放行，交给兜底逻辑）。
     */
    public static long bootId(Context c) {
        try {
            return android.provider.Settings.Global.getInt(
                    c.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    // ---- hook 上报（第一优先数据源）----

    /** App 进程无权读别家进程的 /proc（Android 9+ procfs hidepid=2），AMS 也被裁剪，
     *  所以「目标进程是否在跑、什么时候启动」的权威来源是 system_server 里的 hook，
     *  它每 10 秒把扫描结果经 ConfigProvider 推回来。 */
    public static final class ProcReport {
        /** 报告是否足够新鲜；不新鲜时调用方须退回本地探测。 */
        public final boolean fresh;
        /** 包名 → 进程启动时刻（tick，相对开机；换算用 clockTickHz()）。 */
        public final Map<String, Long> map;

        ProcReport(boolean fresh, Map<String, Long> map) {
            this.fresh = fresh;
            this.map = map;
        }
    }

    private static final String KEY_RPT_MAP = "rpt_map";
    private static final String KEY_RPT_AT = "rpt_at";
    private static final String KEY_RPT_BOOT = "rpt_boot";
    /** 报告有效期：hook 每 10 秒报一次，放宽到 90 秒容忍 App 短暂休眠。 */
    private static final long RPT_FRESH_MS = 90_000L;

    /** ConfigProvider 收到 hook 的 reportProcs 时调用（同进程，直接落盘）。 */
    public static void storeProcReport(Context c, Map<String, Long> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : map.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        sp(c).edit()
                .putString(KEY_RPT_MAP, sb.toString())
                .putLong(KEY_RPT_AT, sinceBootMs())
                .putLong(KEY_RPT_BOOT, bootId(c))
                .apply();
    }

    /** 当前有效的 hook 报告；过期或缺失时 fresh=false。 */
    public static ProcReport procReport(Context c) {
        SharedPreferences s = sp(c);
        String raw = s.getString(KEY_RPT_MAP, null);
        long at = s.getLong(KEY_RPT_AT, -1);
        long now = sinceBootMs();
        boolean fresh = raw != null && at > 0 && now >= at && now - at <= RPT_FRESH_MS;
        if (fresh) {
            long cur = bootId(c);
            long rec = s.getLong(KEY_RPT_BOOT, -1);
            if (cur >= 0 && rec != cur) fresh = false;   // 跨开机的旧报告
        }
        Map<String, Long> map = new HashMap<>();
        if (raw != null) {
            for (String tok : raw.split(",")) {
                int i = tok.indexOf(':');
                if (i <= 0) continue;
                try {
                    map.put(tok.substring(0, i), Long.parseLong(tok.substring(i + 1)));
                } catch (NumberFormatException ignored) { }
            }
        }
        return new ProcReport(fresh, map);
    }

    /** tick（相对开机）→ 毫秒。 */
    private static long ticksToMs(long ticks) {
        return ticks * 1000L / clockTickHz();
    }

    // ---- 进程探测 ----

    /**
     * 一次扫描出全部结果：包名 → 该进程「这一次」的启动时刻（开机后毫秒）。
     *
     * 为什么要整屏扫而不是逐个查：/proc 里动辄几百个目录，逐行去查会把同一份目录反复
     * 遍历一遍。实测这样每 tick 只需扫一次，配合下面的短缓存，几十个目标也不会卡。
     */
    public static Map<String, Long> processStarts(Context c) {
        long now = System.currentTimeMillis();
        if (sProcCache != null && now - sProcCacheAt >= 0 && now - sProcCacheAt < CACHE_MS) {
            return sProcCache;
        }
        Map<String, Long> out = new HashMap<>();
        Set<Integer> seen = new HashSet<>();

        // 先看 ActivityManager：它直接给 pid，最省；但对别家后台进程常被裁剪
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> procs =
                    (am != null) ? am.getRunningAppProcesses() : null;
            if (procs != null) {
                for (ActivityManager.RunningAppProcessInfo p : procs) {
                    if (p.pid <= 0 || p.processName == null) continue;
                    long age = processAgeMs(p.pid);
                    if (age < 0) continue;
                    String pkg = p.processName.contains(":")
                            ? p.processName.substring(0, p.processName.indexOf(':'))
                            : p.processName;
                    // 同一应用的多个进程取最新那个（它才代表「刚被拉起来」）
                    Long old = out.get(pkg);
                    long st = sinceBootMs() - age;
                    if (old == null || st > old) out.put(pkg, st);
                    seen.add(p.pid);
                }
            }
        } catch (Throwable ignored) { }

        // 兜底：直接比对 /proc/<pid>/cmdline，绕过 AMS 的可见性裁剪
        File[] dirs = new File("/proc").listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                if (!d.isDirectory()) continue;
                int pid = parseIntName(d.getName());
                if (pid <= 0 || seen.contains(pid)) continue;
                String cmd = readCmdline(pid);
                if (cmd == null || cmd.isEmpty()) continue;
                String pkg = cmd.contains(":") ? cmd.substring(0, cmd.indexOf(':')) : cmd;
                long age = processAgeMs(pid);
                if (age < 0) continue;
                long st = sinceBootMs() - age;
                Long old = out.get(pkg);
                if (old == null || st > old) out.put(pkg, st);
            }
        }

        sProcCache = out;
        sProcCacheAt = now;
        return out;
    }

    /** 快照缓存：1s 一次心跳时避免重复扫 /proc，又不会让数据明显滞后。 */
    private static final long CACHE_MS = 700L;
    private static Map<String, Long> sProcCache;
    private static long sProcCacheAt;

    // ---- 全量进程快照（进程页专用，权威数据源 = system_server 的 /proc 扫描）----

    /** 一次全量进程快照：fresh=数据是否还新鲜，ever=本机是否收到过任何上报，list=所有进程。 */
    public static final class ProcessSnapshot {
        public final boolean fresh;
        public final boolean ever;
        public final List<ProcessInfo> list;
        /**
         * 快照的产生时刻（墙钟毫秒，导报告用）。
         * 之所以要单独存一份墙钟：{@link #KEY_RPT_ALL_AT} 用的是「开机至今毫秒」，
         * 只适合算新鲜度，直接印在报告里别人看不出是哪天几点。
         * 无记录时为 0。
         */
        public final long time;

        ProcessSnapshot(boolean fresh, boolean ever, List<ProcessInfo> list, long time) {
            this.fresh = fresh;
            this.ever = ever;
            this.list = list;
            this.time = time;
        }
    }

    private static final String KEY_RPT_ALL = "rpt_all";
    private static final String KEY_RPT_ALL_AT = "rpt_all_at";
    /** 快照落地时的墙钟毫秒（导出报告时打印可读时间用；不要拿它算新鲜度，时钟可能被 NTP 校正）。 */
    private static final String KEY_RPT_ALL_WALL = "rpt_all_wall";
    private static final String KEY_RPT_ALL_BOOT = "rpt_all_boot";
    /** 快照有效期：system_server 在看进程页时每 3 秒推一次，放宽到 90 秒容忍 App 短暂休眠。 */
    private static final long ALL_FRESH_MS = 90_000L;

    /** ConfigProvider 收到 hook 的 reportProcs(allprocs) 时调用（同进程，直接落盘）。 */
    public static void storeProcessSnapshot(Context c, String[] entries) {
        android.util.Log.d("PKA", "storeProcessSnapshot 落盘 n=" + (entries == null ? 0 : entries.length));
        StringBuilder sb = new StringBuilder();
        for (String e : entries) {
            if (e == null) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(e);
        }
        sp(c).edit()
                .putString(KEY_RPT_ALL, sb.toString())
                .putLong(KEY_RPT_ALL_AT, sinceBootMs())
                .putLong(KEY_RPT_ALL_WALL, System.currentTimeMillis())
                .putLong(KEY_RPT_ALL_BOOT, bootId(c))
                .apply();
    }

    /**
     * 当前有效的全量进程快照；过期或缺失时 fresh=false。
     * ever 用于区分「从未收到任何上报」（钩子多半没生效）和「收到过但已过期」（等待下一轮扫描）。
     */
    public static ProcessSnapshot processSnapshot(Context c) {
        SharedPreferences s = sp(c);
        String raw = s.getString(KEY_RPT_ALL, null);
        long at = s.getLong(KEY_RPT_ALL_AT, -1);
        boolean ever = raw != null && at > 0;
        long now = sinceBootMs();
        boolean fresh = raw != null && !raw.isEmpty() && at > 0 && now >= at && now - at <= ALL_FRESH_MS;
        if (fresh) {
            long cur = bootId(c);
            long rec = s.getLong(KEY_RPT_ALL_BOOT, -1);
            if (cur >= 0 && rec != cur) fresh = false;   // 跨开机的旧快照
        }
        List<ProcessInfo> list = new ArrayList<>();
        if (raw != null) {
            for (String line : raw.split("\n")) {
                ProcessInfo pi = ProcessInfo.decode(line);
                if (pi != null) list.add(pi);
            }
        }
        long wall = s.getLong(KEY_RPT_ALL_WALL, 0);
        return new ProcessSnapshot(fresh, ever, list, wall);
    }

    /** tick（相对开机）→ 该进程已运行毫秒；读不到返回 -1。 */
    public static long ticksToUptimeMs(long startTicks) {
        if (startTicks < 0) return -1;
        long startMs = startTicks * 1000L / clockTickHz();
        long nowMs = sinceBootMs();
        return nowMs >= startMs ? (nowMs - startMs) : -1;
    }

    /** 抛弃缓存（下拉刷新时用，保证下一次读到最新结果）。 */
    public static void invalidateProcCache() {
        sProcCache = null;
        sProcCacheAt = 0;
    }

    /** 目标当前是否在跑（hook 报告优先，本地探测兜底）。 */
    public static boolean isAlive(Context c, String pkg) {
        ProcReport rpt = procReport(c);
        if (rpt.fresh) return rpt.map.containsKey(pkg);
        Map<String, Long> m = processStarts(c);
        Long st = m.get(pkg);
        return st != null && st >= 0;
    }

    private static int parseIntName(String s) {
        int pid = -1;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') return -1;
            if (pid < 0) pid = 0;
            pid = pid * 10 + (ch - '0');
        }
        return pid;
    }

    private static String readCmdline(int pid) {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/" + pid + "/cmdline"))) {
            String s = r.readLine();
            if (s == null) return null;
            // cmdline 以 NUL 分隔参数，进程名在第一段
            int z = s.indexOf('\0');
            return z > 0 ? s.substring(0, z) : s;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 进程「这一次」已经运行了多久（毫秒）。
     *
     * 读 /proc/&lt;pid&gt;/stat 的第 22 个字段 starttime —— 它是内核记录的进程创建时刻，
     * 单位是 tick（相对开机）。这是最权威的口径：进程被杀重启时它自然归零，
     * 手机重启时也自然归零，不需要我们自己判断。
     *
     * @return 运行时长毫秒；读不到（进程已退出 / SELinux 禁止）返回 -1。
     */
    public static long processAgeMs(int pid) {
        if (pid <= 0) return -1;
        String stat = readFirstLine("/proc/" + pid + "/stat");
        if (stat == null) return -1;
        // comm 字段形如 "(com.foo.bar)" 且内部可能含空格，必须按最后一个 ')' 切分
        int close = stat.lastIndexOf(')');
        if (close < 0 || close + 2 >= stat.length()) return -1;
        String[] f = stat.substring(close + 2).split(" ");
        // 切分后第 0 项是 state(字段3)，因此 starttime(字段22) 落在下标 19
        if (f.length <= 19) return -1;
        long ticks;
        try {
            ticks = Long.parseLong(f[19]);
        } catch (NumberFormatException e) {
            return -1;
        }
        long startMs = ticks * 1000L / clockTickHz();
        long nowMs = sinceBootMs();
        return nowMs >= startMs ? (nowMs - startMs) : -1;
    }

    /** 每秒 tick 数（/_SC_CLK_TCK），取不到按 Android 常见的 100 处理。 */
    private static long clockTickHz() {
        try {
            long hz = android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK);
            return hz > 0 ? hz : 100L;
        } catch (Throwable t) {
            return 100L;
        }
    }

    private static String readFirstLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return r.readLine();
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---- 存活时长 ----

    /**
     * 目标应用的连续保活时长（毫秒）。
     *
     * 起点取「进程真正启动」和「保活开始计时」两者中较晚的那个 —— 这两个口径碰巧
     * 覆盖了用户关心的所有重置场景：
     *   · 进程被杀后重启 → 进程启动时刻变晚，自动从 0 重新数
     *   · 手机重启       → 两条轴都以开机为原点，必然归零
     *   · 总开关关掉再开 → 保活起点被抬到「此刻」，那段时间本就没在保活，不该计入
     *
     * 进程启动时刻优先取 hook 的周期上报（system_server 读 /proc 无限制，最权威）；
     * 报告不可用时退回 App 本地探测（旧系统可用），再不行只依凭本地打点。
     *
     * @return 时长毫秒；进程不在 / 模块未启用 / 数据失效时返回 0。
     */
    public static long uptimeMs(Context c, String pkg) {
        if (!moduleEnabled(c)) return 0;

        long start = -1;   // 候选起点（均为「开机后毫秒」坐标系）
        ProcReport rpt = procReport(c);
        if (rpt.fresh) {
            // 报告新鲜时它就是权威：报告里没有这个包 = 进程已死，直接判 0，
            // 不能再拿本地打点续命（那会把死掉的时间也算进去）
            Long ticks = rpt.map.get(pkg);
            if (ticks == null) return 0;
            start = ticksToMs(ticks);
        } else {
            Long ps = processStarts(c).get(pkg);
            if (ps != null && ps >= 0) start = ps;
        }
        long marker = validMarker(c, pkg);
        // 必须显式排除 0：否则 start 还是 -1 时会被「无记录」的 0 顶替，
        // 结果变成「从开机起就算保活」，也就是用户看到的关不掉的十几小时
        if (marker > 0 && marker > start) start = marker;
        if (start < 0) return 0;

        long now = sinceBootMs();
        return now >= start ? (now - start) : 0;
    }

    /**
     * 状态文案：把「进程在跑但计数刚开始」和「进程压根不在」区分开。
     * 前者如果也显示「未运行」，用户会以为应用已经死了。
     */
    public static String statusText(Context c, String pkg) {
        if (!moduleEnabled(c)) return "未启用";
        long up = uptimeMs(c, pkg);
        if (up > 0) return "已保活 " + fmtUp(up);
        return isAlive(c, pkg) ? "保活刚开始" : "未运行";
    }

    /** 文案该用强调色（绿色）还是次要色。 */
    public static boolean isCounting(Context c, String pkg) {
        return moduleEnabled(c) && uptimeMs(c, pkg) > 0;
    }

    /** 本地打点里那次「保活开始」；不属于本次开机则返回 0（视为无记录）。 */
    private static long validMarker(Context c, String pkg) {
        SharedPreferences p = prefs(c);
        long v = p.getLong(Prefs.KEY_GUARD_START_PREFIX + pkg, 0);
        if (v <= 0) return 0;
        long cur = bootId(c);
        if (cur >= 0 && p.getLong(KEY_BOOT_PREFIX + pkg, -1) != cur) return 0;
        return v;
    }

    /** 模块总开关是否打开。关着的时候没有任何保活行为，时长应显示为未运行。 */
    public static boolean moduleEnabled(Context c) {
        return prefs(c).getBoolean(Prefs.KEY_ENABLED, true);
    }

    /**
     * 总开关被重新打开：把所有目标的计时原点抬到「此刻」。
     * 关闭期间进程虽然可能还活着，但那一段并不是本模块保活出来的，不该计入。
     */
    public static void onMasterEnabled(Context c) {
        Set<String> targets = prefs(c).getStringSet(Prefs.KEY_TARGETS, new HashSet<>());
        for (String pkg : targets) {
            rearmFallbackTimer(c, pkg);
        }
    }

    /**
     * 采样到某个进程存活时，校准它的兜底打点。
     *
     * 正常情况下 {@link #uptimeMs} 直接读进程真实启动时刻，用不到这个标记；
     * 但万一某些 ROM 不让读 /proc，就得靠这条打点兜住。三种情况要重置它：
     * 换了次开机、进程启动时刻比打点更新（说明重启过）、以及还没有打点。
     *
     * @param processStart 该进程本次启动时刻（开机后毫秒）
     */
    public static void noteAlive(Context c, String pkg, long processStart) {
        SharedPreferences p = prefs(c);
        long curBoot = bootId(c);
        long recBoot = p.getLong(KEY_BOOT_PREFIX + pkg, -1);
        long cur = p.getLong(Prefs.KEY_GUARD_START_PREFIX + pkg, 0);
        boolean needsReset = recBoot < 0
                || (curBoot >= 0 && recBoot != curBoot)
                || cur <= 0
                || processStart > cur;
        if (!needsReset) return;
        p.edit()
                .putLong(Prefs.KEY_GUARD_START_PREFIX + pkg, Math.max(processStart, 0))
                .putLong(KEY_BOOT_PREFIX + pkg, curBoot)
                .apply();
    }

    /** 把兜底计时原点重新拉到「此刻」，并记录当前开机次数。 */
    public static void rearmFallbackTimer(Context c, String pkg) {
        long b = bootId(c);
        prefs(c).edit()
                .putLong(Prefs.KEY_GUARD_START_PREFIX + pkg, sinceBootMs())
                .putLong(KEY_BOOT_PREFIX + pkg, b)
                .apply();
    }

    // ---- 守护动作计数（被杀/被拉起次数，本次开机累计）----

    /**
     * 每个目标一条记录，格式：{@code curK,curS,boot}。
     * 口径是「本次开机累计」：hook 侧内存态随 system_server 重启归零，App 侧按 boot
     * 校验 —— 换了开机旧记录直接作废。关总开关 / 取消该应用保活时主动清零。
     */
    private static final String KEY_GS_PREFIX = "gs_";

    /** ConfigProvider 收到 hook 的守护计数上报时调用（同进程，直接落盘）。 */
    public static void storeGuardStats(Context c, String pkg, long curK, long curS) {
        prefs(c).edit()
                .putString(KEY_GS_PREFIX + pkg,
                        Math.max(0, curK) + "," + Math.max(0, curS) + "," + bootId(c))
                .apply();
    }

    /** {被杀次数, 拉起次数}（本次开机累计；关总开关/取消保活/重启手机后归零）。 */
    public static long[] guardStats(Context c, String pkg) {
        long[] out = {0, 0};
        String raw = prefs(c).getString(KEY_GS_PREFIX + pkg, null);
        if (raw == null || raw.isEmpty()) return out;
        String[] f = raw.split(",");
        if (f.length < 3) return out;
        try {
            long rec = Long.parseLong(f[2].trim());
            long cur = bootId(c);
            if (rec >= 0 && cur >= 0 && rec != cur) return out;   // 跨开机的旧记录
            out[0] = Math.max(0, Long.parseLong(f[0].trim()));
            out[1] = Math.max(0, Long.parseLong(f[1].trim()));
        } catch (NumberFormatException ignored) { }
        return out;
    }

    /** 清掉某个目标的被杀/拉起计数（取消该应用的保活时调用）。 */
    public static void clearGuardStats(Context c, String pkg) {
        prefs(c).edit().remove(KEY_GS_PREFIX + pkg).apply();
    }

    /**
     * 彻底忘掉某个目标（它已从保活清单里被移除时调用）：
     * 守护计数 + 保活起始打点一并清掉。
     *
     * 打点必须清：不清的话将来把它重新加回来，系统会读到上一次的起始时刻，
     * 「这次才刚开始保活」却显示一段凭空多出来的时长。
     */
    public static void forgetTarget(Context c, String pkg) {
        prefs(c).edit()
                .remove(KEY_GS_PREFIX + pkg)
                .remove(Prefs.KEY_GUARD_START_PREFIX + pkg)
                .remove(KEY_BOOT_PREFIX + pkg)
                .commit();
    }

    /**
     * 清掉所有目标的被杀/拉起计数，以及「本次开机」的事件时间线与能力自检快照
     * （关闭模块总开关时调用）。
     *
     * <p>为什么必须连带清事件与自检：{@code guardEvents()} / {@code hookCaps()} 的失效判定
     * 只看「开机次数是否变化」，**跟总开关状态无关**。所以只清 gs_ 计数的话，用户关掉模块后
     * 首页仍会显示「本次开机 · 被拉起 N 次」、能力自检页仍显示全部生效 ——
     * 与「模块已关闭」自相矛盾，直接误导判断。
     *
     * <p>启动打点（{@link Prefs#KEY_GUARD_START_PREFIX}）也一并清，理由同 {@link #forgetTarget}。
     */
    public static void clearAllGuardStats(Context c) {
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor e = p.edit();
        for (String key : p.getAll().keySet()) {
            if (key.startsWith(KEY_GS_PREFIX)) e.remove(key);
            // 启动打点同样是「每目标」的残留，一并清掉，避免关模块后时长还在涨
            if (key.startsWith(Prefs.KEY_GUARD_START_PREFIX)) e.remove(key);
        }
        // 事件时间线 + 其开机戳：不清的话下次读时 bootId 未变，旧事件仍被判为「本次开机」
        e.remove(KEY_EVT).remove(KEY_EVT_BOOT);
        // 能力自检快照 + 其开机戳：同理，否则关模块后自检页依旧全绿
        e.remove(KEY_CAPS).remove(KEY_CAPS_BOOT);
        e.commit();
    }

    // ===== 守护事件时间线（带时间戳，本次开机累计）=====
    // 权威数据源在 system_server 侧（钩子埋点），App 这里只镜像全量事件列表。
    // 编码：pkg|type|time|who|reason，多条之间 ';' 分隔；type：0=被杀 1=拉起 2=开机拉起；
    // who：触发方（拉起来源），空白表示被杀无来源，形如「模块拉活 · 第一防线」
    // 「模块拉活 · 第二防线」「模块拉活 · 开机种子」「托底保活」。前缀就是【谁动的手】。
    // reason：被杀原因标签（仅 type=0 有值），如「用户划卡片清除」「用户强制停止」
    // 「系统低内存回收」「系统清理后台」「系统ANR清理」「应用自行退出」「其他原因」；
    // 拉起/开机事件 reason 留空。who（及 reason）为末尾字段，旧格式（无 who/reason）解析时按空串处理。
    private static final String KEY_EVT = "guard_events";
    private static final String KEY_EVT_BOOT = "guard_events_boot";

    /** 单条守护事件。 */
    public static final class GuardEvent {
        public final String pkg;
        public final int type;   // 0=被杀 1=拉起 2=开机拉起
        public final long time;  // 墙钟毫秒
        public final String who; // 触发方（拉起来源）：空白=被杀；其余形如
                                 // 「模块拉活 · 第一防线」「模块拉活 · 第二防线」「托底保活」
        public final String reason; // 被杀原因标签：空白=非被杀事件或无原因
        GuardEvent(String pkg, int type, long time, String who, String reason) {
            this.pkg = pkg;
            this.type = type;
            this.time = time;
            this.who = who;
            this.reason = reason;
        }
    }

    /** ConfigProvider 收到 hook 的事件上报时调用（同进程，直接镜像全量列表）。 */
    public static void storeGuardEvents(Context c, String[] entries) {
        StringBuilder sb = new StringBuilder();
        if (entries != null) {
            for (String s : entries) {
                if (s == null || s.isEmpty()) continue;
                if (sb.length() > 0) sb.append(';');
                sb.append(s);
            }
        }
        prefs(c).edit()
                .putString(KEY_EVT, sb.toString())
                .putLong(KEY_EVT_BOOT, bootId(c))
                .apply();
    }

    /** 本次开机的守护事件，按时间降序（最新在前）。重启后自动归零。 */
    public static List<GuardEvent> guardEvents(Context c) {
        SharedPreferences p = prefs(c);
        long rec = p.getLong(KEY_EVT_BOOT, -1);
        if (rec >= 0 && bootId(c) >= 0 && rec != bootId(c)) return new ArrayList<>();
        String raw = p.getString(KEY_EVT, null);
        List<GuardEvent> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String s : raw.split(";")) {
            int i1 = s.indexOf('|');
            int i2 = s.indexOf('|', i1 + 1);
            if (i1 <= 0 || i2 <= i1 + 1) continue;
            int i3 = s.indexOf('|', i2 + 1);   // who 起点（兼容旧格式：无 who/reason）
            int i4 = i3 > i2 ? s.indexOf('|', i3 + 1) : -1;  // reason 起点（可选）
            String who = "", reason = "";
            if (i3 > i2) {
                who = s.substring(i3 + 1, i4 > i3 ? i4 : s.length());
                if (i4 > i3) reason = s.substring(i4 + 1);
            }
            try {
                out.add(new GuardEvent(s.substring(0, i1),
                        Integer.parseInt(s.substring(i1 + 1, i2)),
                        Long.parseLong(s.substring(i2 + 1, i3 > i2 ? i3 : s.length())),
                        who, reason));
            } catch (NumberFormatException ignored) {
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.time, a.time));
        return out;
    }

    // ===== 钩子自检报告（能力可用性可见化）=====
    // hook 侧安装完后上报每个能力的挂载结局，App 落盘展示。
    // 编码：状态|能力名|目标类|命中名|重载数，状态 OK/MISS，多条 ';' 分隔。
    // 价值：模块靠「按名挂 AOSP 私有方法」实现，方法改名/ROM 魔改会导致
    // 功能静默失效；有了这份报告，用户与开发者能一眼看出哪台机器哪些能力没生效。
    private static final String KEY_CAPS = "hook_caps";
    private static final String KEY_CAPS_BOOT = "hook_caps_boot";

    /** 单条能力自检项。 */
    public static final class Capability {
        public final boolean ok;
        public final String name;      // 能力名，如「档位·降低OOM Adj」
        public final String cls;       // 目标类短名
        public final String hit;       // 命中的方法名（ok=false 时为空）
        public final int overloads;

        Capability(boolean ok, String name, String cls, String hit, int overloads) {
            this.ok = ok;
            this.name = name;
            this.cls = cls;
            this.hit = hit;
            this.overloads = overloads;
        }
    }

    /** ConfigProvider 收到 hook 自检上报时调用。 */
    public static void storeHookCaps(Context c, String[] entries) {
        StringBuilder sb = new StringBuilder();
        if (entries != null) {
            for (String s : entries) {
                if (s == null || s.isEmpty()) continue;
                if (sb.length() > 0) sb.append(';');
                sb.append(s);
            }
        }
        prefs(c).edit()
                .putString(KEY_CAPS, sb.toString())
                .putLong(KEY_CAPS_BOOT, bootId(c))
                .apply();
    }

    /** 本次开机的能力自检列表（安装顺序）。重启后自动归零。 */
    public static List<Capability> hookCaps(Context c) {
        SharedPreferences p = prefs(c);
        long rec = p.getLong(KEY_CAPS_BOOT, -1);
        if (rec >= 0 && bootId(c) >= 0 && rec != bootId(c)) return new ArrayList<>();
        String raw = p.getString(KEY_CAPS, null);
        List<Capability> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String s : raw.split(";")) {
            String[] f = s.split("\\|", -1);
            if (f.length < 5) continue;
            try {
                out.add(new Capability("OK".equals(f[0]), f[1], f[2], f[3],
                        Integer.parseInt(f[4])));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /**
     * 开机后清理上一轮开机残留的 pid 记录，让兜底打点重新起算。
     * 由 MainActivity 启动时调用；即使漏调也不影响正确性 ——
     * {@link #validMarker} 会用开机次数自行判定失效。
     */
    public static void resetForNewBoot(Context c) {
        long cur = bootId(c);
        if (cur < 0) return;
        SharedPreferences p = prefs(c);
        if (p.getLong(KEY_LAST_BOOT, -1) == cur) return;

        SharedPreferences.Editor e = p.edit();
        Set<String> pkgs = new HashSet<>(p.getStringSet(Prefs.KEY_TARGETS, new HashSet<>()));
        for (String key : p.getAll().keySet()) {
            if (key.startsWith(Prefs.KEY_GUARD_START_PREFIX)) {
                pkgs.add(key.substring(Prefs.KEY_GUARD_START_PREFIX.length()));
            }
        }
        for (String pkg : pkgs) {
            // ★ 用 remove 而不是 putLong(0)：写 0 会让 contains() 仍为 true，
            //   而 AppsFragment.saveTargets() 正是靠 contains() 判断「要不要重排定时器」，
            //   于是开机后不会重新打点，时长只能靠 hook 报告的进程启动时刻 ——
            //   一旦那份报告不新鲜就恒显示 0（最长要等约 1 小时 noteAlive 自愈才恢复）。
            //   删掉键与 forgetTarget 保持同一口径：无键 = 无记录。
            e.remove(Prefs.KEY_GUARD_START_PREFIX + pkg);
            e.putLong(KEY_BOOT_PREFIX + pkg, -1L);
        }
        // hook 的进程报告也属于上一次开机，一并作废；守护计数是「本次开机」口径，同样清掉
        e.remove(KEY_RPT_MAP);
        e.remove(KEY_RPT_AT);
        e.remove(KEY_RPT_BOOT);
        for (String key : p.getAll().keySet()) {
            if (key.startsWith(KEY_GS_PREFIX)) e.remove(key);
        }
        // 历次开机的即时采样标记（surv_bootkick_<bootId>）已失效，一并清掉防累积
        for (String key : p.getAll().keySet()) {
            if (key.startsWith(KICK_KEY_PREFIX)) e.remove(key);
        }
        e.putLong(KEY_LAST_BOOT, cur);

        // ★ 046：24h 存活率环形缓冲（surv_overall / surv_<pkg>）也要作废。
        //   原先跨开机残留：新开机 1 分 41 秒，主页却拿上一次开机的旧样本算出
        //   「24h 存活率 100%」，用户误以为没刷新/凭空满格。开机即清零，
        //   首个样本由 MainActivity 补的一次即时采样尽快补上（见 scheduleSampler）。
        //   一并清掉的还有开机器 kick 标记（surv_bootkick_<bootId>，每次开机新 key）。
        try {
            SharedPreferences.Editor se = sp(c).edit();
            for (String key : sp(c).getAll().keySet()) {
                if (key.startsWith(PREFIX)) se.remove(key);
            }
            se.apply();
        } catch (Throwable ignored) {
        }
        // commit 而非 apply：这个函数在开机后首次进入 App 时调用，紧接着的 saveTargets()
        // 会立刻去 contains(gstart_) 判断是否需要重排定时器。apply 是异步落盘，
        // 存在「remove 还没写下去、contains 仍读到旧键」的窗口，会让上面的修复偶发失效。
        e.commit();
    }

    /** 毫秒 → “21 小时 24 分” / “3 分 12 秒” / “45 秒”。 */
    public static String fmtUp(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long x = s % 60;
        if (h > 0) return h + " 小时 " + (m < 10 ? "0" : "") + m + " 分";
        if (m > 0) return m + " 分 " + (x < 10 ? "0" : "") + x + " 秒";
        return x + " 秒";
    }

    // ---- 内部工具 ----

    private static List<Float> readBuf(SharedPreferences sp, String key) {
        List<Float> out = new ArrayList<>();
        String raw = sp.getString(key, null);
        if (raw == null || raw.isEmpty()) return out;
        for (String tok : raw.split(",")) {
            try { out.add(Float.parseFloat(tok.trim())); }
            catch (NumberFormatException ignored) { }
        }
        return out;
    }

    private static String join(List<Float> buf) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < buf.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(buf.get(i));
        }
        return sb.toString();
    }
}
