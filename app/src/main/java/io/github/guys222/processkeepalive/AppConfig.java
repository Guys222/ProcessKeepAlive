package io.github.guys222.processkeepalive;

/** 单个目标应用的保活配置。 */
public class AppConfig {
    /**
     * 保活档位：0=前台级(激进/最强) 1=可见级(均衡) 2=可感知级(保守/最弱)。
     * 数值越小强度越高（对应 OOM adj 越小越不易被回收），与
     * {@code Prefs.Priority.POLICY} 的顺序一致。
     * 这里只是兜底默认；实际新应用的初始值取自设置页的「默认档位」
     * （见 {@link Prefs#KEY_PRIORITY}）。
     */
    public int adj = 1;
    /**
     * 拦截系统对整包的「强行停止」（forceStopPackage）。
     *
     * ★ 默认 false（原为 true）。
     *   它是**最激进的拦截**：开启后连用户在系统设置里手动点的「强行停止」也会被挡掉 ——
     *   用户想强制结束一个卡死的应用时，发现点了没用，会以为系统出问题了。
     *   而 force-stop 本身就是用户的明确意图，模块不该默认替用户否决。
     *   保活的核心诉求由档位 + 死后拉起满足，不需要靠默认拦截强停来实现。
     */
    public boolean forceStop = false;
    /**
     * 拦截系统「一键清理后台 / 内存清理」的批量杀进程（killBackgroundProcesses）。
     *
     * ★ 默认 false（原为 true）。
     *   理由同上：这两项属于「拦截」能力，都有副作用（可能干扰系统的正常内存回收，
     *   让系统在不该留的时候硬留着进程）。默认开启等于让所有用户默默接受这份代价。
     *   改为默认关闭后，用户发现自己需要的场景再按需打开，动机和后果都清楚。
     */
    public boolean killBackground = false;
    public boolean kill = false;
    public boolean persistent = false;
    /**
     * 托底保活 · 核心级（adj -1000，实验性）：比常驻(-800)更强，强制钉在系统核心级。
     * 开启时隐含 persistent —— 没有常驻底座，单靠反射钉 -1000 在下一轮 oom_adj 时
     * 很可能被系统重夹回 -800/-900。默认 false。
     */
    public boolean core = false;
    /** 消息保活（后台收消息：Doze/Standby 豁免 + 主进程伪装前台）。 */
    public boolean msg = false;

    /** 按设置页的默认档位创建配置。 */
    public static AppConfig withDefaultPriority(int globalDefault) {
        AppConfig c = new AppConfig();
        c.adj = Prefs.Priority.clamp(globalDefault);
        return c;
    }
}
