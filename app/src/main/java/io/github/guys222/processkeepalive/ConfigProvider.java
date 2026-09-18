package io.github.guys222.processkeepalive;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * 通过 Binder 向 system_server 暴露模块配置。
 *
 * system_server 受 SELinux 限制无法直接读 App 的 /data/data 目录，但可以
 * 通过 ContentProvider 跨进程查询。这里用 call() 一次性返回全部配置。
 */
public class ConfigProvider extends ContentProvider {

    // ==================================================================
    // 杀进程指令队列（App 侧入队，system_server 侧经 Binder 取走）
    // ==================================================================

    /**
     * 待执行的杀进程 pid 队列。
     *
     * <p>为什么用进程内静态队列而不是 SharedPreferences：
     * 这类「一次性指令」的语义是「取走即消费」，最适合队列；而 SharedPreferences 的
     * 读改写不是原子的，「读旧值 + remove」会把并发写入的新指令一起抹掉。
     * 入队方（{@code ProcessesFragment}）与出队方（本 Provider 的 call）**在同一进程**，
     * 用一个并发队列就能做到不丢不重。
     *
     * <p>容量兜底：正常情况下 system_server 每秒取一次，不会堆积；
     * 万一 system_server 侧没在跑（模块未激活），队列会一直涨 —— 这里封顶，
     * 超出丢最旧的，避免长期未激活时无限占用内存。
     */
    private static final int KILL_QUEUE_CAP = 256;
    private static final java.util.concurrent.ConcurrentLinkedQueue<Integer> sKillQueue =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** 入队一个待杀 pid（App UI 侧调用）。 */
    public static void queueKill(int pid) {
        sKillQueue.add(pid);
        // 封顶：超出上限时丢最旧的（正常情况下永远走不到这里）
        while (sKillQueue.size() > KILL_QUEUE_CAP) sKillQueue.poll();
    }

    /**
     * 一次性取走队列里的全部 pid 并清空（system_server 侧经 Binder 调用）。
     *
     * <p>{@code poll()} 是原子的出队：即便两处并发调用，同一个 pid 也只会被一方拿到，
     * 不会出现「同一个 pid 被杀两次」或「指令被抹掉」。
     *
     * @return pid 字符串数组（队列为空时返回空数组，不会返回 null）
     */
    public static String[] takeQueuedKills() {
        java.util.List<String> out = new ArrayList<>();
        Integer pid;
        while ((pid = sKillQueue.poll()) != null) {
            out.add(String.valueOf(pid));
        }
        return out.toArray(new String[0]);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        // system_server 钩子安装成功后上报激活状态：在 App 自己目录写激活标记
        if ("markActive".equals(method)) {
            try {
                ActivationMark.write(getContext());
            } catch (Throwable ignored) {
            }
            Bundle ok = new Bundle();
            ok.putBoolean("ok", true);
            return ok;
        }
        // hook 侧周期推送「当前在跑的进程 → 启动时刻(tick)」，
        // App 进程自己读不到别家的 /proc，这份报告就是存活时长的权威数据源
        if ("reportProcs".equals(method)) {
            try {
                String[] entries = extras == null ? null : extras.getStringArray("procs");
                if (entries != null) {
                    java.util.Map<String, Long> map = new java.util.HashMap<>();
                    for (String s : entries) {
                        int i = s == null ? -1 : s.indexOf('|');
                        if (i <= 0) continue;
                        try {
                            map.put(s.substring(0, i), Long.parseLong(s.substring(i + 1)));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    SurvivalData.storeProcReport(getContext(), map);
                }
                // 守护动作计数（被杀/拉起次数）：pkg|kills|starts，值为本次开机绝对累计
                String[] stats = extras == null ? null : extras.getStringArray("stats");
                if (stats != null) {
                    for (String s : stats) {
                        if (s == null) continue;
                        String[] f = s.split("\\|");
                        if (f.length < 3) continue;
                        try {
                            SurvivalData.storeGuardStats(getContext(), f[0],
                                    Long.parseLong(f[1]), Long.parseLong(f[2]));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                // 全量进程快照（进程页用）：allprocs 编码字符串数组，直接落盘
                String[] allprocs = extras == null ? null : extras.getStringArray("allprocs");
                if (allprocs != null) {
                    try {
                        SurvivalData.storeProcessSnapshot(getContext(), allprocs);
                    } catch (Throwable ignored) {
                    }
                }
                // 守护事件时间线：events 编码字符串数组（pkg|type|time），全量镜像落盘
                String[] events = extras == null ? null : extras.getStringArray("events");
                if (events != null) {
                    try {
                        SurvivalData.storeGuardEvents(getContext(), events);
                    } catch (Throwable ignored) {
                    }
                }
                // 钩子自检报告：caps 编码字符串数组（状态|能力名|类|命中名|重载数）
                String[] caps = extras == null ? null : extras.getStringArray("caps");
                if (caps != null) {
                    try {
                        SurvivalData.storeHookCaps(getContext(), caps);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            Bundle ok = new Bundle();
            ok.putBoolean("ok", true);
            return ok;
        }
        if ("takeKill".equals(method)) {
            // App → system_server 的杀进程指令：原子取走并清空，返回 pid 数组。
            // system_server 的 kill 监视线程拿到后真正 SIGKILL；取走即删，避免重复杀。
            //
            // ★ 改用进程内静态队列，不再走 SharedPreferences。
            //   原来的实现是「读 csv → remove(apply)」两步，中间无互斥：
            //     · 读侧在 Binder 线程池，写侧在 UI 线程，两者可能交错；
            //     · 写侧用 apply() 异步落盘，「读到的旧值 + 随后的 remove」
            //       会把刚写进去的新指令一起抹掉。
            //   表现为：连点几个「杀进程」时，Toast 说已发送，进程却永远不会被杀，
            //   重试时好时坏。队列的读写都在同一个进程内（Provider 与 Fragment 同进程），
            //   用 ConcurrentLinkedQueue 即可保证「取出即消费」不丢不重。
            try {
                Bundle b = new Bundle();
                b.putStringArray("pids", takeQueuedKills());
                return b;
            } catch (Throwable t) {
                return null;
            }
        }
        if (!"getConfig".equals(method)) return null;
        try {
            SharedPreferences prefs = getContext()
                    .getSharedPreferences(Prefs.PREFS_NAME, Context.MODE_PRIVATE);
            Bundle b = new Bundle();
            b.putBoolean("enabled", prefs.getBoolean(Prefs.KEY_ENABLED, true));
            b.putBoolean("auto_start", prefs.getBoolean(Prefs.KEY_AUTO_START, false));
            // 全局强力模式：打开后对所有保活目标拦截底层杀死入口（ProcessRecord.kill），
            // 是总闸；每个应用的 per-app「强力模式」仍可在总闸关闭时单独开启。
            b.putBoolean(Prefs.KEY_AGGRESSIVE, prefs.getBoolean(Prefs.KEY_AGGRESSIVE, false));
            // proc_wanted：进程页「在看」时间戳，system_server 据此决定是否扫全量 /proc
            b.putLong(Prefs.KEY_PROC_WANTED, prefs.getLong(Prefs.KEY_PROC_WANTED, 0L));

            Set<String> targets = prefs.getStringSet(Prefs.KEY_TARGETS, new HashSet<String>());
            b.putStringArrayList("targets", new ArrayList<>(targets));
            for (String pkg : targets) {
                b.putInt(Prefs.KEY_ADJ_PREFIX + pkg, prefs.getInt(Prefs.KEY_ADJ_PREFIX + pkg, 1));
                // 默认 false：与 AppConfig / AppsFragment / Prefs 三处口径一致（详见 Prefs 注释）
                b.putBoolean(Prefs.KEY_FORCE_PREFIX + pkg,
                        prefs.getBoolean(Prefs.KEY_FORCE_PREFIX + pkg, false));
                b.putBoolean(Prefs.KEY_KILLBG_PREFIX + pkg,
                        prefs.getBoolean(Prefs.KEY_KILLBG_PREFIX + pkg, false));
                b.putBoolean(Prefs.KEY_KILL_PREFIX + pkg,
                        prefs.getBoolean(Prefs.KEY_KILL_PREFIX + pkg, false));
                b.putBoolean(Prefs.KEY_PERSIST_PREFIX + pkg,
                        prefs.getBoolean(Prefs.KEY_PERSIST_PREFIX + pkg, false));
                // ★ 托底保活 · 核心级：这一键原先漏了没推。后果是两侧都读不到：
                //   KeepAliveHooks 的 sCfgCore 恒为空，Prefs 快照（走本 Provider 时）的
                //   core 也恒为 false → markCore 一次都不执行 → 进程页永远停在 -800/0。
                //   更隐蔽的是时序：开机早期 Provider 不可达，system_server 走 parseFile
                //   直读 XML 反而能拿到 core=true（于是刚开完看着是生效的），等 App 起来
                //   Provider 变可达、快照重建后 core 又消失 —— 表现为「先有效、后回落」。
                b.putBoolean(Prefs.KEY_CORE_PREFIX + pkg,
                        prefs.getBoolean(Prefs.KEY_CORE_PREFIX + pkg, false));
                b.putBoolean(Prefs.KEY_MSG_PREFIX + pkg,
                        prefs.getBoolean(Prefs.KEY_MSG_PREFIX + pkg, false));
                // 守护起始时间戳：透传给 system_server 快照，供前端计算真实存活时长
                b.putLong(Prefs.KEY_GUARD_START_PREFIX + pkg,
                        prefs.getLong(Prefs.KEY_GUARD_START_PREFIX + pkg, 0L));
            }
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
