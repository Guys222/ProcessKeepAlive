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
    /** 消息保活开关（后台收消息）：key 为前缀 + 包名，默认关。 */
    public static final String KEY_MSG_PREFIX = "msg_";
    /** 仅 App 界面使用：应用列表是否显示系统应用。 */
    public static final String KEY_SHOW_SYSTEM = "show_system_apps";
    /** 开机后自动拉起已勾选的目标应用。 */
    public static final String KEY_AUTO_START = "auto_start";

    /** 不可变配置快照：钩子热路径只读此对象（纯内存、无锁、无 IPC）。 */
    public static final class Snapshot {
        public final boolean enabled;
        public final boolean autoStart;
        public final Set<String> targets;
        private final Map<String, Integer> adjs;
        private final Map<String, Boolean> force;
        private final Map<String, Boolean> killBg;
        private final Map<String, Boolean> kill;
        private final Map<String, Boolean> persist;
        private final Map<String, Boolean> msg;

        Snapshot(boolean enabled, boolean autoStart,
                 Set<String> targets,
                 Map<String, Integer> adjs, Map<String, Boolean> force,
                 Map<String, Boolean> killBg, Map<String, Boolean> kill,
                 Map<String, Boolean> persist, Map<String, Boolean> msg) {
            this.enabled = enabled;
            this.autoStart = autoStart;
            this.targets = targets;
            this.adjs = adjs;
            this.force = force;
            this.killBg = killBg;
            this.kill = kill;
            this.persist = persist;
            this.msg = msg;
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

    private static final Snapshot EMPTY = new Snapshot(true, false,
            Collections.<String>emptySet(),
            Collections.<String, Integer>emptyMap(),
            Collections.<String, Boolean>emptyMap(),
            Collections.<String, Boolean>emptyMap(),
            Collections.<String, Boolean>emptyMap(),
            Collections.<String, Boolean>emptyMap(),
            Collections.<String, Boolean>emptyMap());

    private volatile Snapshot snapshot = EMPTY;
    private volatile long lastLoad = 0;
    private volatile boolean providerLoaded = false;

    private Context context;
    private final AtomicBoolean loading = new AtomicBoolean(false);

    private static final ExecutorService sExecutor =
            Executors.newSingleThreadExecutor();

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
        Map<String, Boolean> msg = new HashMap<>();

        Object t = raw.get(KEY_TARGETS);
        if (t instanceof Set) {
            for (Object o : (Set<?>) t) {
                String pkg = String.valueOf(o);
                targets.add(pkg);
                Object av = raw.get(KEY_ADJ_PREFIX + pkg);
                adjs.put(pkg, av instanceof Number ? ((Number) av).intValue() : 1);
                force.put(pkg, bool(raw.get(KEY_FORCE_PREFIX + pkg), true));
                killBg.put(pkg, bool(raw.get(KEY_KILLBG_PREFIX + pkg), true));
                kill.put(pkg, bool(raw.get(KEY_KILL_PREFIX + pkg), false));
                persist.put(pkg, bool(raw.get(KEY_PERSIST_PREFIX + pkg), false));
                msg.put(pkg, bool(raw.get(KEY_MSG_PREFIX + pkg), false));
            }
        }

        boolean enabled = bool(raw.get(KEY_ENABLED), true);
        boolean autoStart = bool(raw.get(KEY_AUTO_START), false);

        snapshot = new Snapshot(enabled, autoStart, targets,
                adjs, force, killBg, kill, persist, msg);
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

    public boolean isTarget(String pkg) {
        return pkg != null && snapshot.targets.contains(pkg);
    }

    /** 返回指定应用的保活档位（0=前台 1=可见 2=可感知），默认可见级。 */
    public int getAdjMode(String pkg) {
        return snapshot.adjOf(pkg);
    }

    public boolean isPreventForceStop(String pkg) {
        return snapshot.boolOf(snapshot.force, pkg, true);
    }

    public boolean isPreventKillBackground(String pkg) {
        return snapshot.boolOf(snapshot.killBg, pkg, true);
    }

    public boolean isPreventKill(String pkg) {
        return snapshot.boolOf(snapshot.kill, pkg, false);
    }

    public boolean isPersistent(String pkg) {
        return snapshot.boolOf(snapshot.persist, pkg, false);
    }

    /** 该包是否开启「消息保活」（后台收消息：Doze/Standby 豁免 + 主进程伪装前台）。 */
    public boolean isMsgProcess(String pkg) {
        return pkg != null && snapshot.boolOf(snapshot.msg, pkg, false);
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
