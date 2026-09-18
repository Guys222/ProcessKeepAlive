package io.github.guys222.processkeepalive;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在 system_server 中读取本模块配置。
 *
 * 核心设计——快照模型（防止 system_server ANR）：
 *  - 所有跨进程/文件读取只在后台单线程执行；
 *  - 配置以「不可变快照」保存在 volatile 字段里；
 *  - 保活钩子运行在 AMS 锁内，热路径只允许读快照（纯内存），
 *    绝不能做 ContentProvider 查询等 IPC，否则会阻塞 system_server
 *    导致「进程 system 没有响应」→ watchdog 重启手机。
 */
public class Prefs {

    private static final String TAG = "ProcessKeepAlive";

    public static final String PREFS_NAME = "keepalive_prefs";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_TARGETS = "targets";
    /** 每个应用独立的保活档位（0=前台 1=可见 2=可感知），key 为 "adj_" + 包名。 */
    public static final String KEY_ADJ_PREFIX = "adj_";
    /** 每个应用独立的四个功能开关，key 为前缀 + 包名。 */
    public static final String KEY_FORCE_PREFIX = "fs_";
    public static final String KEY_KILLBG_PREFIX = "kb_";
    public static final String KEY_KILL_PREFIX = "kill_";
    public static final String KEY_PERSIST_PREFIX = "persist_";
    /** 每个应用独立的「托底保活 · 核心级」（adj -1000，实验性），key 为 "core_" + 包名。 */
    public static final String KEY_CORE_PREFIX = "core_";
    /** 消息保活开关（后台收消息）：key 为前缀 + 包名，默认关。 */
    public static final String KEY_MSG_PREFIX = "msg_";
    /**
     * 守护起始时间戳（毫秒）：key 为前缀 + 包名。
     * 记录该目标「开始被本模块守护」的时刻，用于计算真实存活时长。
     * 写入由 App 侧负责（勾选/启动保活时打点）；本类仅在加载时读取快照。
     */
    public static final String KEY_GUARD_START_PREFIX = "gstart_";
    /** 仅 App 界面使用：是否显示常驻通知（同时驱动桌面小部件的 30 秒刷新）。 */
    public static final String KEY_NOTIFY = "persistent_notify";
    /** 仅 App 界面使用：应用列表是否显示系统应用。 */
    public static final String KEY_SHOW_SYSTEM = "show_system_apps";
    /** 开机后自动拉起已勾选的目标应用；同时开启「进程掉了就静默补拉」（10s 心跳检测）。 */
    public static final String KEY_AUTO_START = "auto_start";
    /** 外观主题：0=跟随系统 1=浅色 2=深色。仅 App 界面使用。 */
    public static final String KEY_DARK = "ui_theme_mode";
    /**
     * 进程页「在看」时间戳（墙钟毫秒）：App 打开/刷新进程页时写入，
     * system_server 据此在 60 秒窗口内才扫描全量 /proc 并回传快照（避免无谓开销）。
     * 仅 App 界面写入，system_server 经 getConfig 读取。
     */
    public static final String KEY_PROC_WANTED = "proc_wanted";
    // 注：原先这里有个 KEY_KILL_QUEUE = "kill_queue"，杀进程指令曾以逗号分隔串存在
    // SharedPreferences 里。该方案已废弃 —— 读改写非原子 + apply() 异步落盘会导致
    // 并发时指令被抹掉。现改用 ConfigProvider 的进程内并发队列（见 ConfigProvider.queueKill）。
    // 常量一并删除，避免有人再按老路子接回去。
    /**
     * 全局默认保活档位（0=激进 1=均衡 2=保守），新勾选的目标应用默认采用它。
     * 注意：这只是「新应用的默认值」，不是所有应用的统一切换开关——
     * 每个应用的实际档位存在 {@link #KEY_ADJ_PREFIX} 下，可在应用配置弹窗里单独覆盖。
     * 仅 App 界面使用。
     */
    public static final String KEY_PRIORITY = "ui_priority";

    /**
     * 保活档位的统一命名与换算（唯一事实来源）。
     * <p>
     * 档位按「保活强度」从强到弱排列，mode 数值即为索引：
     * <pre>
     *   mode 0 → adj 0    FOREGROUND_APP_ADJ     激进 · 前台级      最强
     *   mode 1 → adj 100  VISIBLE_APP_ADJ        均衡 · 可见级      中等
     *   mode 2 → adj 200  PERCEPTIBLE_APP_ADJ    保守 · 可感知级    最弱
     * </pre>
     * 注意 Android 的 OOM adj 是「数值越小优先级越高」，所以强度顺序与 mode 顺序一致。
     */
    public static final class Priority {
        /** 档位数量。 */
        public static final int COUNT = 3;

        /** 强度口径：随 mode 由强到弱。 */
        private static final String[] POLICY = {"激进", "均衡", "保守"};

        /** 系统术语：对应 OOM adj 常量，随 mode 由强到弱。 */
        private static final String[] ADJ = {"前台级", "可见级", "可感知级"};

        /** 实际写入的 OOM adj 值（与 KeepAliveHooks.desiredAdj 必须一致）。 */
        private static final int[] ADJ_VALUE = {0, 100, 200};

        /** OOM adj 常量名。 */
        private static final String[] ADJ_CONST = {
                "FOREGROUND_APP_ADJ", "VISIBLE_APP_ADJ", "PERCEPTIBLE_APP_ADJ"};

        /**
         * 档位的「效果 + 代价」说明（对话框档位那行的第三行小字）。
         * <p>
         * 以「保活最强 / 适中 / 最弱」开头，先把用户最关心的强弱结论给出来，
         * 再补一句「系统会怎么对待它」和代价（耗电 / 省电）。
         * 刻意不用「用户正在交互 / 能被看到」这类纯系统视角的描述——用户读不出
         * 这对我保活有什么用。
         * 注意不要在此重复档位名（「前台级」等），档位名由调用方单独渲染。
         */
        private static final String[] MEANING = {
                "保活最强：系统按「正在前台使用」对待，几乎不会被回收。代价是更耗电、更占资源。",
                "保活适中：系统按「还看得见」对待，内存不紧张就一直留着，紧张时才回收。日常推荐。",
                "保活最弱：系统按「在后台运行」对待，内存一紧就优先回收。最省电。"};

        /** 系统术语的短标签，用于在说明前点明这是哪类进程（如「前台应用」）。 */
        private static final String[] KIND = {"前台应用", "可见应用", "可感知应用"};

        private Priority() { }

        /** 收敛到合法档位。 */
        public static int clamp(int mode) {
            return (mode < 0 || mode >= COUNT) ? 1 : mode;
        }

        /** 强度口径名称，如「激进」。 */
        public static String policyName(int mode) {
            return POLICY[clamp(mode)];
        }

        /** 系统术语名称，如「前台级」。 */
        public static String adjName(int mode) {
            return ADJ[clamp(mode)];
        }

        /** 实际写入的 OOM adj 数值，如 0。 */
        public static int adjValue(int mode) {
            return ADJ_VALUE[clamp(mode)];
        }

        /** 对应的 OOM adj 常量名，如 FOREGROUND_APP_ADJ。 */
        public static String adjConst(int mode) {
            return ADJ_CONST[clamp(mode)];
        }

        /**
         * 系统意愿说明（一句人话），用于在档位下方提示用户
         * 这个 adj 值意味着系统会怎么对待这个进程。不含档位名，避免与标题重复。
         */
        public static String meaning(int mode) {
            return MEANING[clamp(mode)];
        }

        /** 该档位对应的进程类型短标签，如「前台应用」。 */
        public static String kind(int mode) {
            return KIND[clamp(mode)];
        }

        /**
         * 数值+常量，如「adj 0 · FOREGROUND_APP_ADJ」。 */
        public static String adjDetail(int mode) {
            int m = clamp(mode);
            return "adj " + ADJ_VALUE[m] + " · " + ADJ_CONST[m];
        }

        /** 两套命名同时呈现，如「激进 · 前台级」。 */
        public static String both(int mode) {
            int m = clamp(mode);
            return POLICY[m] + " · " + ADJ[m];
        }
    }
    /** 全局强力模式：打开后对所有保活目标拦截底层杀进程入口（ProcessRecord.kill）。总闸。 */
    public static final String KEY_AGGRESSIVE = "aggressive";
    // 注：原「常驻实验」全局开关（KEY_PERSIST_EXP）已移除——它从未被钩子读取，开了也没效果，
    // 反而与「死后拉起」语义撞车。托底保活请以应用配置弹窗里的「托底保活 · 常驻级」为准（按应用开关）。

    /** 不可变配置快照：钩子热路径只读此对象（纯内存、无锁、无 IPC）。 */
    public static final class Snapshot {
        public final boolean enabled;
        public final boolean autoStart;
        /** 全局强力模式（总闸）：为 true 时所有保活目标都拦截底层杀死入口。 */
        public final boolean aggressive;
        public final Set<String> targets;
        private final Map<String, Integer> adjs;
        private final Map<String, Boolean> force;
        private final Map<String, Boolean> killBg;
        private final Map<String, Boolean> kill;
        private final Map<String, Boolean> persist;
        private final Map<String, Boolean> core;
        private final Map<String, Boolean> msg;
        /** 守护起始时间戳（毫秒）。无记录则为 0（表示未知/未开始）。 */
        private final Map<String, Long> guardStart;

        Snapshot(boolean enabled, boolean autoStart, boolean aggressive,
                 Set<String> targets,
                 Map<String, Integer> adjs, Map<String, Boolean> force,
                 Map<String, Boolean> killBg, Map<String, Boolean> kill,
                 Map<String, Boolean> persist, Map<String, Boolean> core,
                 Map<String, Boolean> msg, Map<String, Long> guardStart) {
            this.enabled = enabled;
            this.autoStart = autoStart;
            this.aggressive = aggressive;
            this.targets = targets;
            this.adjs = adjs;
            this.force = force;
            this.killBg = killBg;
            this.kill = kill;
            this.persist = persist;
            this.core = core;
            this.msg = msg;
            this.guardStart = guardStart;
        }

        int adjOf(String pkg) {
            Integer v = adjs.get(pkg);
            return v == null ? 1 : v;
        }

        boolean boolOf(Map<String, Boolean> m, String pkg, boolean def) {
            Boolean v = m.get(pkg);
            return v == null ? def : v;
        }
    }

    private static final Snapshot EMPTY = new Snapshot(true, false, false,
            Collections.<String>emptySet(),
            Collections.<String, Integer>emptyMap(),
            Collections.<String, Boolean>emptyMap(),
            Collections.<String, Boolean>emptyMap(),
            Collections.<String, Boolean>emptyMap(),
            Collections.<String, Boolean>emptyMap(),
            Collections.<String, Boolean>emptyMap(),
            Collections.<String, Boolean>emptyMap(),
            Collections.<String, Long>emptyMap());

    private volatile Snapshot snapshot = EMPTY;
    private volatile long lastLoad = 0;

    /**
     * ★ 058 P0 修复：目标名单的【实时覆盖层】。
     *
     * <p>问题：快照刷新受 {@link #refreshIfStale} 的 30 秒节流，而 KeepAliveHooks 的
     * 心跳循环本来就【每 10 秒】从 Provider 拿到最新 targets（写进 sCfgTargets）——
     * 可那份新鲜数据从没被判定链用过，判定一律走 {@code snapshot.targets}
     * （最长滞后 30 秒）。结果是 sCfgTargets 这个字段「只写不读」，纯摆设。
     *
     * <p>后果很实际：用户在应用页新勾一个目标，最多 30 秒内它不会被保活；
     * 取消勾选的目标，最多 30 秒内还在被保活。用户看到的是「改了不生效」。
     *
     * <p>修法：给 Prefs 开一个覆盖入口，心跳线程每轮把最新 targets 灌进来，
     * {@link #isTarget} 优先查它。为 null 时（心跳未跑/未读到配置）退回快照，
     * 行为与修复前一致 —— 即「有新鲜数据就用新鲜的，没有就用旧口径兜底」。
     */
    private volatile java.util.Set<String> liveTargets = null;

    /**
     * 由心跳线程写入最新的目标名单（纯内存赋值，无 IPC / 无 IO，可在任意线程调用）。
     * 传 null 表示「本轮没拿到有效名单」，此时退回快照口径。
     */
    public void setLiveTargets(java.util.Set<String> targets) {
        this.liveTargets = targets;
    }
    private volatile boolean providerLoaded = false;

    private Context context;
    private final AtomicBoolean loading = new AtomicBoolean(false);

    /**
     * 后台线程池。
     *
     * 注意：这里绝不能用 {@link Executors#newSingleThreadExecutor()}——保活模块在
     * system_server 里要跑多个「永不退出的循环」任务（激活上报、杀进程监视、进程上报），
     * 单线程池会被先排队的死循环（如杀进程监视）永久霸占，导致后排队的上报循环永远拿不到
     * 线程、从不执行，表现为「进程页不显示 + 被杀拉回失效」。所以必须用可并发的线程池，
     * 让每个长循环各自占一条线程。
     */
    private static final ExecutorService sExecutor =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "PKA-BG");
                t.setDaemon(true);
                return t;
            });

    public void setContext(Context ctx) {
        this.context = ctx;
    }

    public boolean hasContext() {
        return context != null;
    }

    /**
     * 热路径专用（可在 AMS 锁内安全调用）：配置过期则触发后台刷新并立即返回。
     * 本方法绝不做任何 IPC / 文件 IO，绝不阻塞调用线程。
     */
    public void refreshIfStale(long maxAgeMs) {
        if (System.currentTimeMillis() - lastLoad <= maxAgeMs) return;
        loadAsync();
    }

    /** 提交后台加载。ContentProvider 冷启动模块 App 可能耗时，只能在后台线程做。 */
    public void loadAsync() {
        if (context == null) return;
        if (!loading.compareAndSet(false, true)) return;
        sExecutor.execute(() -> {
            try {
                loadWithRetry();
            } finally {
                loading.set(false);
            }
        });
    }

    /** 在后台线程同步加载并返回快照。仅供后台任务调用，禁止在主线程/持锁线程调用。 */
    public Snapshot loadNow() {
        try {
            loadOnce();
        } catch (Throwable ignored) {
        }
        lastLoad = System.currentTimeMillis();
        return snapshot;
    }

    /**
     * 启动早期同步读取配置（供 HookEntry / ModernEntry 在注入 systemContext 之前调用）。
     * 此时 context 为空，只会读文件（无 IPC），即便失败也很快返回，不会阻塞 system_server。
     */
    public void reload() {
        try {
            loadOnce();
        } catch (Throwable ignored) {
        }
        lastLoad = System.currentTimeMillis();
    }

    /** 提交任意任务到后台线程（开机自启、激活上报等需要 IPC 的工作都在这里执行）。 */
    public static void runBackground(Runnable r) {
        sExecutor.execute(() -> {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        });
    }

    private void loadWithRetry() {
        for (int i = 0; i < 12; i++) {
            boolean ok = false;
            try {
                ok = loadOnce();
            } catch (Throwable ignored) {
            }
            lastLoad = System.currentTimeMillis();
            if (ok) break;
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private boolean loadOnce() {
        Map<String, Object> raw = new HashMap<>();
        loadFromProvider(raw);
        if (!providerLoaded) {
            // 回退：直接读 prefs XML（可能被 SELinux 拒绝，读不到就算了）
            parseFile(raw);
        }

        Set<String> targets = new HashSet<>();
        Map<String, Integer> adjs = new HashMap<>();
        Map<String, Boolean> force = new HashMap<>();
        Map<String, Boolean> killBg = new HashMap<>();
        Map<String, Boolean> kill = new HashMap<>();
        Map<String, Boolean> persist = new HashMap<>();
        Map<String, Boolean> core = new HashMap<>();
        Map<String, Boolean> msg = new HashMap<>();
        Map<String, Long> guardStart = new HashMap<>();

        Object t = raw.get(KEY_TARGETS);
        if (t instanceof Set) {
            for (Object o : (Set<?>) t) {
                String pkg = String.valueOf(o);
                targets.add(pkg);
                Object av = raw.get(KEY_ADJ_PREFIX + pkg);
                adjs.put(pkg, av instanceof Number ? ((Number) av).intValue() : 1);
                // 拦截类开关默认 false，必须与 AppConfig 字段默认、AppsFragment 读取默认
                // 保持一致。三处任一不同，就会出现「界面显示关着、实际还在拦」这种最坑的
                // 不一致 —— 用户以为放开了，系统里点强停却依然无效，无从判断问题出在哪。
                force.put(pkg, bool(raw.get(KEY_FORCE_PREFIX + pkg), false));
                killBg.put(pkg, bool(raw.get(KEY_KILLBG_PREFIX + pkg), false));
                kill.put(pkg, bool(raw.get(KEY_KILL_PREFIX + pkg), false));
                persist.put(pkg, bool(raw.get(KEY_PERSIST_PREFIX + pkg), false));
                core.put(pkg, bool(raw.get(KEY_CORE_PREFIX + pkg), false));
                msg.put(pkg, bool(raw.get(KEY_MSG_PREFIX + pkg), false));
                Object gs = raw.get(KEY_GUARD_START_PREFIX + pkg);
                guardStart.put(pkg, gs instanceof Number ? ((Number) gs).longValue() : 0L);
            }
        }

        boolean enabled = bool(raw.get(KEY_ENABLED), true);
        boolean autoStart = bool(raw.get(KEY_AUTO_START), false);
        boolean aggressive = bool(raw.get(KEY_AGGRESSIVE), false);

        snapshot = new Snapshot(enabled, autoStart, aggressive, targets,
                adjs, force, killBg, kill, persist, core, msg, guardStart);
        return providerLoaded;
    }

    private static boolean bool(Object v, boolean def) {
        return v instanceof Boolean ? (Boolean) v : def;
    }

    private void loadFromProvider(Map<String, Object> raw) {
        try {
            Context ctx = context;
            Bundle b = ctx.getContentResolver().call(
                    Uri.parse("content://" + BuildConfig.APPLICATION_ID + ".config"),
                    "getConfig", null, null);
            if (b == null) {
                providerLoaded = false;
                return;
            }
            ArrayList<String> targetList = b.getStringArrayList("targets");
            if (targetList != null) {
                raw.put(KEY_TARGETS, new HashSet<>(targetList));
            }
            for (String key : b.keySet()) {
                if ("targets".equals(key)) continue;
                raw.put(key, b.get(key));
            }
            providerLoaded = true;
        } catch (Throwable t) {
            providerLoaded = false;
        }
    }

    private boolean parseFile(Map<String, Object> raw) {
        try {
            File f = new File("/data/data/" + BuildConfig.APPLICATION_ID
                    + "/shared_prefs/" + PREFS_NAME + ".xml");
            if (!f.exists()) return false;
            try (InputStream in = new FileInputStream(f)) {
                XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
                p.setInput(in, "UTF-8");
                String currentSet = null;
                Set<String> currentSetValues = null;
                int type = p.getEventType();
                while (type != XmlPullParser.END_DOCUMENT) {
                    if (type == XmlPullParser.START_TAG) {
                        String tag = p.getName();
                        String name = p.getAttributeValue(null, "name");
                        switch (tag) {
                            case "set":
                                currentSet = name;
                                currentSetValues = new HashSet<>();
                                break;
                            case "string":
                                if (currentSet != null && KEY_TARGETS.equals(currentSet)) {
                                    currentSetValues.add(p.nextText());
                                } else if (name != null) {
                                    raw.put(name, p.nextText());
                                }
                                break;
                            case "boolean":
                                if (name != null) {
                                    raw.put(name, Boolean.parseBoolean(
                                            p.getAttributeValue(null, "value")));
                                }
                                break;
                            case "int":
                            case "long":
                                if (name != null) {
                                    raw.put(name, Long.parseLong(
                                            p.getAttributeValue(null, "value")));
                                }
                                break;
                            default:
                                break;
                        }
                    } else if (type == XmlPullParser.END_TAG) {
                        if ("set".equals(p.getName()) && currentSet != null) {
                            raw.put(currentSet, currentSetValues);
                            currentSet = null;
                            currentSetValues = null;
                        }
                    }
                    type = p.next();
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------- 热路径只读接口（纯内存，无 IPC） ----------------

    public boolean isEnabled() {
        return snapshot.enabled;
    }

    public boolean isAutoStart() {
        return snapshot.autoStart;
    }

    /**
     * 该包是否在保活目标名单内。
     *
     * ★ 058：优先用心跳线程灌进来的【实时名单】（10 秒级新鲜度），
     *   没有时才退回快照（受 30 秒节流）。修掉「改了目标最长 30 秒不生效」。
     */
    public boolean isTarget(String pkg) {
        if (pkg == null) return false;
        java.util.Set<String> live = liveTargets;
        if (live != null) return live.contains(pkg);
        return snapshot.targets.contains(pkg);
    }

    /** 返回指定应用的保活档位（0=前台 1=可见 2=可感知），默认可见级。 */
    public int getAdjMode(String pkg) {
        return snapshot.adjOf(pkg);
    }

    /**
     * 是否拦截整包「强行停止」。
     *
     * ★ 058 P0 修复：兜底默认曾是 {@code true}，与其余三处（AppConfig 字段注释、
     *   AppsFragment 读开关、ConfigProvider 推快照）的 {@code false} 全部相反。
     *
     *   危害不是"显示不对"这么轻 —— 本方法是 system_server 侧【真正决定拦不拦】的
     *   判据。它默认 true 意味着：只要 Provider 这条线拿到了某包（哪怕用户压根没开），
     *   钩子就会去拦截强停；而后端 UI 与 Provider 都按 false 显示"未开启"。
     *   用户想强制结束一个卡死的应用时点了没反应，却找不到任何"我开过这功能"的痕迹。
     *   反过来，两条取数路径（Provider 可达 / 回退 parseFile）对同一包还会得出相反结论，
     *   表现为「有时拦有时不拦」这种最难查的间歇性故障。
     *
     *   现统一为 {@code false}：不默认替用户否决他自己的操作，需要就手动开。
     */
    public boolean isPreventForceStop(String pkg) {
        return snapshot.boolOf(snapshot.force, pkg, false);
    }

    /** 是否拦截「一键清理后台」的批量杀进程。默认 false，理由同 {@link #isPreventForceStop}。 */
    public boolean isPreventKillBackground(String pkg) {
        return snapshot.boolOf(snapshot.killBg, pkg, false);
    }

    public boolean isPreventKill(String pkg) {
        return snapshot.boolOf(snapshot.kill, pkg, false);
    }

    /**
     * 全局强力模式是否开启（总闸）。开启后，拦截底层杀死入口对所有保活目标生效，
     * 与 per-app 的 {@link #isPreventKill(String)} 是「或」的关系——任一为真即拦截。
     */
    public boolean isAggressive() {
        return snapshot.aggressive;
    }

    public boolean isPersistent(String pkg) {
        return snapshot.boolOf(snapshot.persist, pkg, false);
    }

    /** 该包是否开启「托底保活 · 核心级」（adj -1000，实验性）。core 开启时隐含 persistent。 */
    public boolean isCore(String pkg) {
        return snapshot.boolOf(snapshot.core, pkg, false);
    }

    /** 该包是否开启「消息保活」（后台收消息：Doze/Standby 豁免 + 主进程伪装前台）。 */
    public boolean isMsgProcess(String pkg) {
        return pkg != null && snapshot.boolOf(snapshot.msg, pkg, false);
    }

    /**
     * 该目标「开始被守护」的时间戳（毫秒），热路径只读快照（纯内存、无 IPC）。
     * 返回 0 表示无记录（未知/尚未打点）。前端可用 {@code now - guardStartOf(pkg)}
     * 计算真实存活时长，替换预览稿里的 mock 计时。
     */
    public long guardStartOf(String pkg) {
        if (pkg == null) return 0L;
        Long v = snapshot.guardStart.get(pkg);
        return v == null ? 0L : v;
    }

    public int targetsCount() {
        return snapshot.targets.size();
    }

    /** 返回目标包名集合（副本）。 */
    public Set<String> getTargets() {
        return new HashSet<>(snapshot.targets);
    }

    /** 最近一次是否成功从 ContentProvider 读到了配置。 */
    public boolean isProviderLoaded() {
        return providerLoaded;
    }
}
