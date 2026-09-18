package io.github.guys222.processkeepalive;

/**
 * 单进程信息（system_server 与 App 两侧共用，纯数据、无 Android 依赖）。
 *
 * 字段来源全部是 system_server 在钩子进程里直接读 /proc 得到的——App 进程自己
 * 读别家 /proc 被 hidepid=2 挡死，所以这套数据是「看全系统进程」的唯一权威来源。
 *
 * 运输：经 ConfigProvider 的 Bundle 以字符串数组回传，这里负责 encode/decode。
 * 字段顺序固定为：pid|ppid|state|oomAdj|vmRssKb|startTicks|pkg|name
 * （name 可能含 ':' 但不能含 '|'；pkg 为空串表示系统/内核进程）。
 */
public class ProcessInfo {

    /** pid */
    public final int pid;
    /** 父进程 pid */
    public final int ppid;
    /** 进程状态（R/S/D/T/Z/I 等单个字符；读不到为 '?'） */
    public final char state;
    /** oom_score_adj（范围约 -1000..1000；越小越不容易被回收） */
    public final int oomAdj;
    /** 常驻内存 VmRSS，单位 KB */
    public final long vmRssKb;
    /** 进程创建时刻，单位 tick（相对开机）；换算成毫秒用 SurvivalData.ticksToUptimeMs */
    public final long startTicks;
    /** 解析出的包名；为空表示系统/内核/原生进程（没有可对应的应用） */
    public final String pkg;
    /** 进程名：应用进程为 cmdline（含 ":suffix"），否则为 comm（内核线程形如 [kthreadd]） */
    public final String name;

    public ProcessInfo(int pid, int ppid, char state, int oomAdj, long vmRssKb,
                       long startTicks, String pkg, String name) {
        this.pid = pid;
        this.ppid = ppid;
        this.state = state;
        this.oomAdj = oomAdj;
        this.vmRssKb = vmRssKb;
        this.startTicks = startTicks;
        this.pkg = pkg;
        this.name = name;
    }

    /** 是否为系统/内核/原生进程（没有可对应的应用包名）。 */
    public boolean isSystem() {
        return pkg == null || pkg.isEmpty();
    }

    /** 编码为运输字符串。 */
    public String encode() {
        return pid + "|" + ppid + "|" + state + "|" + oomAdj + "|"
                + vmRssKb + "|" + startTicks + "|" + (pkg == null ? "" : pkg) + "|" + name;
    }

    /** 从运输字符串解码；格式不符返回 null（由调用方跳过，不崩整份快照）。 */
    public static ProcessInfo decode(String line) {
        if (line == null) return null;
        // -1 保留末尾空段（pkg 可能为空串）
        String[] f = line.split("\\|", -1);
        if (f.length < 8) return null;
        try {
            int pid = Integer.parseInt(f[0].trim());
            int ppid = Integer.parseInt(f[1].trim());
            char state = f[2].isEmpty() ? '?' : f[2].charAt(0);
            int oomAdj = Integer.parseInt(f[3].trim());
            long vmRssKb = Long.parseLong(f[4].trim());
            long startTicks = Long.parseLong(f[5].trim());
            String pkg = f[6].isEmpty() ? null : f[6];
            String name = f[7];
            return new ProcessInfo(pid, ppid, state, oomAdj, vmRssKb, startTicks, pkg, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 状态字符 → 中文短标签，用于行内徽标。 */
    public static String stateLabel(char s) {
        switch (s) {
            case 'R': return "运行";
            case 'S': return "睡眠";
            case 'D': return "不可中断";
            case 'T': return "暂停";
            case 'Z': return "僵尸";
            case 'I': return "空闲";
            default: return "未知";
        }
    }
}
