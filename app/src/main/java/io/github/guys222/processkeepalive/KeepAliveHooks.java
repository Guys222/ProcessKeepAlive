package io.github.guys222.processkeepalive;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.libxposed.api.XposedInterface;

/**
 * 保活核心钩子，全部运行在 system_server 进程内。
 *
 * 采用「逻辑层 + 双安装器」架构：
 *  - HookLogic：纯粹的钩子逻辑（before/after），与具体框架 API 无关；
 *  - install(ClassLoader, Prefs)：legacy 安装器（XposedBridge / API 82）；
 *  - installModern(XposedInterface, ClassLoader, Prefs)：modern 安装器（libxposed API）。
 */
public final class KeepAliveHooks {

    private static final String TAG = "ProcessKeepAlive";

    private static final String AMS = "com.android.server.am.ActivityManagerService";
    private static final String PROCESS_RECORD = "com.android.server.am.ProcessRecord";
    private static final String OOM_ADJUSTER = "com.android.server.am.OomAdjuster";
    private static final String PROCESS_LIST = "com.android.server.am.ProcessList";

    private static final String DEVICE_IDLE = "com.android.server.deviceidle.DeviceIdleController";
    private static final String DEVICE_IDLE_LEGACY = "com.android.server.DeviceIdleController";
    private static final String APP_STANDBY = "com.android.server.usage.AppStandbyController";

    /** 可感知级 adj（保活消息进程的最低保活档）。 */
    private static final int PERCEPTIBLE_APP_ADJ = 200;

    /** before 返回该哨兵表示放行（不拦截）。 */
    public static final Object UNHANDLE = new Object();

    /** modern 模式下的框架接口，用于把日志同时写入 LSPosed 模块日志（持久保存）。 */
    private static volatile XposedInterface sXposed;

    /** 已打印过 persistent 标记日志的包名，避免 updateOomAdj 热路径刷屏。 */
    private static final Set<String> sPersistLogged =
            java.util.Collections.synchronizedSet(new HashSet<String>());
    /** 已打印过系统核心(-1000)钉值日志的包名，避免 updateOomAdj 热路径刷屏。 */
    private static final Set<String> sCoreLogged =
            java.util.Collections.synchronizedSet(new HashSet<String>());

    /** 每个包名最近一次打印 killLocked 拦截日志的时间，用于日志节流（防高频拦截刷屏）。 */
    private static final java.util.Map<String, Long> sLastKillLog =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    /** 每个包名最近一次打印伪造前台日志的时间，用于日志节流。 */
    private static final java.util.Map<String, Long> sLastFakeLog =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    /** 双写日志：logcat + LSPosed 模块日志（modern 模式下）。 */
    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedInterface x = sXposed;
        if (x != null) {
            try {
                x.log(Log.INFO, TAG, msg);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void logWarn(String msg) {
        Log.w(TAG, msg);
        XposedInterface x = sXposed;
        if (x != null) {
            try {
                x.log(Log.WARN, TAG, msg);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 已打过「只报一次」警告的 key，避免热路径刷屏。 */
    private static final java.util.Set<String> sWarnedOnce =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /** {@link #logThrottled} 的节流记账表：key → 上次打印时刻。 */
    private static final java.util.Map<String, Long> sThrottleAt =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    /**
     * 同一条警告在本次开机内只打一次。
     *
     * <p>用于那些处于「每次 OOM 调整都会跑」热路径上的失败上报 —— 例如进程字段
     * 写入失败。这类失败往往是系统改版导致的【持续性】问题（不是偶发抖动），
     * 每次调用都打日志会瞬间刷爆 logcat；但完全静默又会让「档位不生效」无从排查。
     * 折中：第一次出现时完整报警，之后抑制。
     */
    private static void logWarnOnce(String msg) {
        if (sWarnedOnce.add(msg)) logWarn(msg);
    }

    /** 同一条普通日志在本次开机内只打一次（同样用于热路径，避免刷屏）。 */
    private static void logOnce(String msg) {
        if (sWarnedOnce.add("[I]" + msg)) log(msg);
    }

    /**
     * 按 key 节流打日志：同一个 key 在 {@code windowMs} 窗口内最多打一条。
     *
     * <p>为什么不用 {@link #logWarnOnce}：漂移是【反复发生】的事件，
     * 打一次就永久静默的话，用户会以为只漂了一次（实际可能每轮都在漂）；
     * 每轮都打又会刷爆 logcat。这里取中间值——同一目标一分钟内一条，
     * 既能看出「一直在被夹」，又不至于刷屏。
     */
    private static void logThrottled(String key, long windowMs, String msg) {
        long now = System.currentTimeMillis();
        synchronized (sThrottleAt) {
            Long last = sThrottleAt.get(key);
            if (last != null && now - last < windowMs) return;
            sThrottleAt.put(key, now);
        }
        logWarn(msg);
    }

    private KeepAliveHooks() {
    }

    // ==================================================================
    // 钩子逻辑层（legacy / modern 共用）
    // ==================================================================

    public static abstract class HookLogic {
        public final String className;
        public final String[] altClassNames;
        public final String methodName;
        /**
         * 候选方法名（含 {@link #methodName} 本身）。用于跨 Android 版本改名兼容。
         * <p>
         * 为什么要它：AOSP 会在版本间给同一个方法改名加锁语义后缀，例如
         * {@code OomAdjuster.applyOomAdjLocked} 在 Android 15/16 上已改名为
         * {@code applyOomAdjLSP}（LSP = Locked with Service &amp; ProcLock）。
         * 只按单一名字挂，改名后钩子静默失效——表现为「配置全对但档位毫无效果」，
         * 而且日志里只有一行「0 个方法」，极易被当成没问题。列出全部候选名逐一尝试，
         * 才能新老系统通吃。
         */
        public final String[] methodNames;
        /** 该逻辑实际挂上的方法名（安装后回填，供日志显示到底命中了哪个名字）。 */
        public volatile String hitMethodName;
        public final String name;
        final ClassLoader cl;
        final Prefs prefs;

        protected HookLogic(ClassLoader cl, Prefs prefs,
                            String className, String methodName, String name) {
            this(cl, prefs, className, null, methodName, name);
        }

        protected HookLogic(ClassLoader cl, Prefs prefs,
                            String className, String methodName,
                            String[] altMethodNames, String name) {
            this(cl, prefs, className, null, methodName, altMethodNames, name);
        }

        protected HookLogic(ClassLoader cl, Prefs prefs,
                            String className, String[] altClassNames,
                            String methodName, String name) {
            this(cl, prefs, className, altClassNames, methodName, null, name);
        }

        protected HookLogic(ClassLoader cl, Prefs prefs,
                            String className, String[] altClassNames,
                            String methodName, String[] altMethodNames, String name) {
            this.cl = cl;
            this.prefs = prefs;
            this.className = className;
            this.altClassNames = altClassNames == null ? new String[0] : altClassNames;
            this.methodName = methodName;
            // 候选名：主名在前，其余去重后追加
            java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
            if (methodName != null) names.add(methodName);
            if (altMethodNames != null) {
                for (String m : altMethodNames) {
                    if (m != null && !m.isEmpty()) names.add(m);
                }
            }
            this.methodNames = names.toArray(new String[0]);
            this.name = name;
        }

        /** before：返回 UNHANDLE 放行；返回其他值表示拦截并作为方法返回值。 */
        public Object before(Object thiz, Object[] args) {
            return UNHANDLE;
        }

        /** after：可修改并返回结果（默认原样返回）。 */
        public Object after(Object thiz, Object[] args, Object result) {
            return result;
        }
    }

    /**
     * ★ 058：安装时留存的 Prefs 实例。
     *
     * <p>为什么需要它：心跳线程 {@link #startProcReporter} 是静态方法、在后台线程里
     * 独立跑，它每 10 秒拿到最新 targets 后要灌给 Prefs 的实时覆盖层
     * （{@link Prefs#setLiveTargets}）。但 Prefs 实例此前只作为参数在 install/钩子
     * 闭包里流转，心跳线程拿不到。存一份静态引用即可（system_server 内单进程单实例）。
     */
    private static volatile Prefs sPrefs;

    public static HookLogic[] createLogics(ClassLoader cl, Prefs prefs) {
        sPrefs = prefs;   // ★ 058：留存实例，供心跳线程写入实时目标名单
        return new HookLogic[] {
            // 1. 阻止 forceStopPackage
            //    ★ 候选名兼容：Android 12 的锁重构给大量 AMS 方法加了后缀
            //      （-Locked / -LSP / -LOSP / -LPr，AOSP 锁命名约定）。
            //      对外入口 forceStopPackage 长期稳定，但部分 ROM 只有带锁版本，
            //      这里一并列出，避免新系统上「0 个方法」静默失效。
            new HookLogic(cl, prefs, AMS, "forceStopPackage",
                    new String[]{"forceStopPackageLSP", "forceStopPackageLocked"},
                    "forceStopPackage") {
                @Override
                public Object before(Object thiz, Object[] args) {
                    prefs.refreshIfStale(30000);
                    if (!prefs.isEnabled()) return UNHANDLE;
                    String pkg = firstStringArg(args);
                    if (!prefs.isTarget(pkg) || !prefs.isPreventForceStop(pkg)) return UNHANDLE;
                    log("已阻止 forceStopPackage: " + pkg);
                    return null; // void 方法，拦截即可
                }
            },
            // 2. 阻止 killBackgroundProcesses
            //    ★ 同上，补 LSP/Locked 变体；Android 14+ 起第三方调用被限制，
            //      但系统「一键清理」走的仍是 AMS 内部这条路径。
            new HookLogic(cl, prefs, AMS, "killBackgroundProcesses",
                    new String[]{"killBackgroundProcessesLSP", "killBackgroundProcessesLocked"},
                    "killBackgroundProcesses") {
                @Override
                public Object before(Object thiz, Object[] args) {
                    prefs.refreshIfStale(30000);
                    if (!prefs.isEnabled()) return UNHANDLE;
                    String pkg = firstStringArg(args);
                    if (!prefs.isTarget(pkg) || !prefs.isPreventKillBackground(pkg)) return UNHANDLE;
                    log("已阻止 killBackgroundProcesses: " + pkg);
                    return null;
                }
            },
            // 3. 降低 OOM Adj —— 在【写入内核之前】改写，这是档位真正生效的关键。
            //
            //    为什么必须 hook applyOomAdj* 而不是只在 updateOomAdjLocked 之后改：
            //    updateOomAdjLocked 返回时，系统已经把 adj 写进 /proc/<pid>/oom_score_adj，
            //    之后再改 ProcessRecord 的 Java 字段只改了内存里的副本，内核并不知情；
            //    而且下一轮 computeOomAdj 会重新计算并覆盖掉它。
            //    applyOomAdj* 才是「把 curAdj 应用到内核」的那一步，在它执行【之前】
            //    改 curAdj，系统随后就会把我们给的值写进内核，档位才真的生效。
            //
            //    ★ 方法名跨版本兼容：Android 14 及以前叫 applyOomAdjLocked，
            //      Android 15/16 的 AOSP 已改名为 applyOomAdjLSP（LSP = Locked with
            //      Service & ProcLock 的锁语义后缀）。只挂旧名会在新系统上「0 个方法」
            //      静默失效，档位毫无效果——这正是「三个档位都不生效」的根因。
            new HookLogic(cl, prefs, OOM_ADJUSTER, "applyOomAdjLocked",
                    new String[]{"applyOomAdjLSP"},
                    "applyOomAdjLocked") {
                @Override
                public Object before(Object thiz, Object[] args) {
                    prefs.refreshIfStale(30000);
                    if (!prefs.isEnabled()) return UNHANDLE;
                    Object proc = findProcessRecord(args);
                    // ★ 004 诊断：无论是不是目标，先看清"findProcessRecord 到底拿到了什么"。
                    //   之所以放在目标判断之前：本机目标进程的 adj 处理是否走到这里都还没证实，
                    //   先抓任意一个进程的结构，就能知道 ProcessRecordInternal 长什么样。
                    dumpAdjRuntime(args, proc);
                    if (proc == null) return UNHANDLE;
                    // ★066 新增：本机签名 applyOomAdjLSP(ProcessRecordInternal,...)，
                    //   裁决对象是 psc 包的 ProcessRecordInternal（ProcessRecord 之外
                    //   还有一份平行的 adj 副本）。以前只写 findProcessRecord 找到的
                    //   ProcessRecord/mProfile，内核侧每轮仍被 ROM 用 PRI 的值重算回 0。
                    //   这里直接对实参对象本身 markOn 一遍——若两者是同一对象则天然幂等。
                    if (args != null && args.length > 0 && args[0] != null && args[0] != proc) {
                        try {
                            String pkgI = getPackageName(args[0]);
                            if (pkgI != null && (prefs.isTarget(pkgI)
                                    || sCfgPersist.contains(pkgI) || sCfgCore.contains(pkgI))) {
                                java.util.List<String> gotI = new java.util.ArrayList<>();
                                markOn(args[0], "PRI.", desiredAdj(prefs.getAdjMode(pkgI)), gotI);
                                if (!gotI.isEmpty()) {
                                    logThrottled("PRI写入:" + pkgI, 60000,
                                        "已直接写入 ProcessRecordInternal: " + pkgI
                                        + "（命中: " + String.join(", ", gotI) + "）");
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    String pkg = getPackageName(proc);
                    if (pkg == null || !prefs.isTarget(pkg)) return UNHANDLE;
                    int mode = prefs.getAdjMode(pkg);
                    // 同时下调 adj 与进程状态：adj 管「被回收的优先级」，
                    // procState 管「系统是否把它当后台」（standby / Doze 看的是它）。
                    // ★ 060：档位与消息保活【各自独立】。档位一律按用户选择写入，
                    //   不因消息保活让位 —— 两者机制不同、可叠加：
                    //     档位 → 改 adj/procState，让内核少杀（解决"被杀"）
                    //     消息保活 → Doze/Standby 豁免 + 伪装前台，让应用别自己断连接
                    applyKeepAlive(proc, desiredAdj(mode), desiredProcState(mode));
                    rememberPid(proc, pkg);
                    return UNHANDLE;
                }
            },
            // 3b. 兜底：直接改写写入内核的 adj 参数。
            //     ProcessList.setOomAdj 是写 /proc/<pid>/oom_score_adj 前的最后一站，
            //     这里按 pid 反查包名，命中目标就把 adj 换成保活档位值。
            //     （applyOomAdj* 若因 ROM 改名而没钩上，这一层仍能起作用。）
            //     ★ 候选名：部分版本/ROM 会带 LSP/LPr 后缀。
            new HookLogic(cl, prefs, PROCESS_LIST, "setOomAdj",
                    new String[]{"setOomAdjLSP", "setOomAdjLPr"}, "setOomAdj") {
                @Override
                public Object before(Object thiz, Object[] args) {
                    if (!prefs.isEnabled()) return UNHANDLE;
                    if (args == null || args.length < 2) return UNHANDLE;
                    // 取 pid：按「合法 pid 范围内的首个 int」定位，不盲取第一个 int
                    int pidIdx = pickPidArgIndex(args);
                    if (pidIdx < 0) return UNHANDLE;
                    int pid = (Integer) args[pidIdx];
                    // ★ pid 复用校验：pid 可能已被分配给别的进程，查不到就放弃
                    String pkg = lookupPkgByPid(pid);
                    if (pkg == null || !prefs.isTarget(pkg)) return UNHANDLE;
                    int want = desiredAdj(prefs.getAdjMode(pkg));
                    // ★ adj 位置：不再"取最后一个 int"盲猜 —— 那在含额外 flag/reason
                    //   的重载上会改错字段（甚至把 uid 改掉）。这里按下述已知签名定位：
                    //     (int pid, int uid, int adj)  → adj 在 index 2
                    //     (int pid, int adj)           → adj 在 index 1
                    //     (int pid, int uid, int adj, int reason) → adj 在 index 2
                    //   判据：index 2 存在且为 Integer 时优先取它（uid 恒在 index 1）。
                    //   ★ 043 补强：位置法仍是"按结构猜"。本机实测 ProcessList 只有
                    //     batchSetOomAdj(ArrayList) / makeOomAdjString(int,boolean) /
                    //     setOomAdj(int,int,int) 三个方法，签名已确认是 (pid, uid, adj)；
                    //     但换 ROM 后未必。所以先用记录下来的【真实方法签名】校验一次，
                    //     签名不符就直接放弃改写（宁可不改，也不能改错进程的 adj）。
                    if (!adjSignatureVerified(thiz, (Integer) args[pidIdx], args)) {
                        logOnce("adj兜底 放弃: setOomAdj 参数结构与已知签名不符，"
                                + "不做位置猜测（宁可少改也不改错）");
                        return UNHANDLE;
                    }
                    int adjIdx = pickAdjArgIndex(args);
                    if (adjIdx < 0) return UNHANDLE;
                    // 记一次「兜底真的改成功了」——这条线是静默的，没有这行日志，
                    // 事后完全无法区分「兜底生效」与「兜底空转」。
                    logOnce("adj兜底: setOomAdj(" + pid + ") 命中 " + pkg
                            + "，adj 改写为 " + want);
                    args[adjIdx] = want;
                    return UNHANDLE;
                }
            },
            // 3b-2. ★066 新线：Android 16 的 psc 重构把「写内核」改成了批量通道。
            //     本机 ProcessList 只有 batchSetOomAdj(ArrayList) / makeOomAdjString /
            //     setOomAdj(int,int,int) 三个相关方法，而 066 日志显示 adj兜底(setOomAdj)
            //     全程触发 0 次 —— 单条通道已是死路，真正的写入走 batchSetOomAdj。
            //     这里逐元素反查 pid → 包名，命中目标就把元素里所有 adj 类 int 字段钳到档位值。
            //     元素类型本机未定（psc 包新类），所以按字段名/取值双向探测，不猜类名。
            new HookLogic(cl, prefs, PROCESS_LIST, "batchSetOomAdj",
                    new String[]{"batchSetOomAdjLSP", "batchSetOomAdjLPr"}, "batchSetOomAdj") {
                @Override
                public Object before(Object thiz, Object[] args) {
                    if (!prefs.isEnabled()) return UNHANDLE;
                    if (args == null || args.length < 1
                            || !(args[0] instanceof java.util.List)) return UNHANDLE;
                    java.util.List<?> list = (java.util.List<?>) args[0];
                    if (list.isEmpty() || list.get(0) == null) return UNHANDLE;
                    dumpBatchElemOnce(list.get(0));
                    for (Object e : list) {
                        if (e == null) continue;
                        String pkg = lookupPkgByElemPid(e);
                        if (pkg == null || !prefs.isTarget(pkg)) continue;
                        int want = desiredAdj(prefs.getAdjMode(pkg));
                        if (clampElemAdj(e, want)) {
                            logThrottled("batch钳位:" + pkg, 60000,
                                "batchSetOomAdj 已把 " + pkg + " 的 adj 钳为 " + want);
                        }
                    }
                    return UNHANDLE;
                }
            },
            // 3c. 原有钩子保留：改 Java 字段，供系统内部读取 curAdj 做决策的路径
            //     （部分 ROM 的清理策略直接读 curAdj，不走 apply 流程）。
            //    Android 15/16 中 computeOomAdjLocked 已改名/移除，核心入口是
            //    OomAdjuster.updateOomAdjLocked（存在多个重载，参数里含 ProcessRecord）。
            //    ★ 候选名：新版 AOSP 用 updateOomAdjLSP（锁语义后缀）。
            new HookLogic(cl, prefs, OOM_ADJUSTER, "updateOomAdjLocked",
                    new String[]{"updateOomAdjLSP"}, "updateOomAdjLocked") {
                @Override
                public Object before(Object thiz, Object[] args) {
                    prefs.refreshIfStale(30000);
                    return UNHANDLE;
                }
                @Override
                public Object after(Object thiz, Object[] args, Object result) {
                    if (!prefs.isEnabled()) return result;
                    Object proc = findProcessRecord(args);
                    if (proc == null) return result;
                    String pkg = getPackageName(proc);
                    // ★ 目标判定必须「或」上 per-app 实时集合（sCfgPersist / sCfgCore）。
                    //   原因：prefs.isTarget 走 Prefs 快照，受 refreshIfStale 的 30 秒节流；
                    //   而 sCfg* 由心跳循环每轮从 Provider 逐包读回，是最新的。
                    //   064 实测的坑：开机早期 / 刚改完配置时快照还是 targets=0，
                    //   这里若在快照上单独把关就会提前 return —— 后面的 ...
                    //   markPersistent / markCore 一次都跑不到，表现为
                    //   「托底保活开着但 adj 根本没钉住（进程页仍是 500）」且日志无痕。
                    if (!prefs.isTarget(pkg) && !sCfgPersist.contains(pkg)
                            && !sCfgCore.contains(pkg)) return result;

                    String pname = getProcessName(proc);
                    // ★ 060：档位与消息保活【可叠加、互不覆盖】。
                    //   档位（adj/procState）→ 让内核少杀，解决"被杀"；
                    //   消息保活（Doze/Standby 豁免 + 伪装前台）→ 让应用别自己断长连接。
                    //   057 曾让消息保活屏蔽档位（为单独测消息链路），实测确认两者不冲突，已恢复。
                    int adj = desiredAdj(prefs.getAdjMode(pkg));
                    lowerAdj(proc, adj);
                    // ★ pid → 包名记账：以前只有 applyOomAdjLocked 里会调 rememberPid，
                    //   一旦那条线在新系统上挂不上（方法改名），sPidToPkg 就永远是空的，
                    //   连带 3b 的 setOomAdj 兜底也一起空转。这里补上，让记账不依赖单一条线。
                    rememberPid(proc, pkg);
                    // 消息保活：仅对主进程伪装前台——很多 App 检测到自己在后台会主动
                    // 断开推送长连接/停止心跳，让 App 查询自身状态时看到「前台」。
                    if (pname != null && pname.equals(pkg) && prefs.isMsgProcess(pkg)) {
                        fakeForeground(cl, proc, pkg);
                    }
                    // ★ 打标决策要「或」上 sCfgPersist，不能只看 Prefs 快照。
                    //   两者来源不同：sCfgPersist 由心跳循环每轮（约 10 秒）从 Provider
                    //   逐包读 persist_<pkg>，是最新的；Prefs 快照则受 refreshIfStale
                    //   的 30 秒节流，配置刚改完时最长滞后 30 秒。
                    //   只信快照的后果：用户开机后刚勾上「托底保活」，进程被拉起
                    //   却因为快照没刷新而拿不到常驻标记，要等半分钟才生效——
                    //   表现为「拉起来了但没变成常驻」，且日志里毫无痕迹。
                    if (sCfgPersist.contains(pkg) || prefs.isPersistent(pkg)) {
                        markPersistent(cl, proc, pkg);
                    }
                    // ★ 托底保活 · 核心级：钉 -1000（实验性）。core 开启时隐含 persistent，
                    //   故与上面常驻标记可同时生效；每轮重钉对抗系统 oom_adj 重算。
                    if (sCfgCore.contains(pkg) || prefs.isCore(pkg)) {
                        markCore(cl, proc, pkg);
                    }
                    return result;
                }
            },
            // 4. 强力模式：拦截 ProcessRecord.killLocked
            //    Android 15+ 中 ProcessRecord.kill 已改名为 killLocked。
            //    两种开法：全局总闸（设置页「强力模式」，对所有目标生效）
            //    或 per-app 开关（应用对话框里的「强力模式」，仅对该应用生效）。
            //    ★ 候选名：老版本叫 kill，新版本 killLocked。
            new HookLogic(cl, prefs, PROCESS_RECORD, "killLocked",
                    new String[]{"kill"}, "ProcessRecord.killLocked") {
                @Override
                public Object before(Object thiz, Object[] args) {
                    prefs.refreshIfStale(30000);
                    if (!prefs.isEnabled()) return UNHANDLE;
                    String pkg = getPackageName(thiz);
                    // ★ 042 新增：无论是否目标、是否开强力模式，先记一次「被杀现场」。
                    //   放在所有 early-return 之前，是为了不漏掉任何一条死亡路径 ——
                    //   划卡片的目标未必开了强力模式，而我们要观察的正是这种情况。
                    //   注意：这里【只记账、只打日志】，不返回拦截值，行为与改动前一致。
                    noteKillTrail(thiz, args);
                    if (!prefs.isTarget(pkg)) return UNHANDLE;
                    if (!prefs.isAggressive() && !prefs.isPreventKill(pkg)) return UNHANDLE;
                    long now = System.currentTimeMillis();
                    Long last = sLastKillLog.get(pkg);
                    if (last == null || now - last > 10000) {
                        log("已拦截 ProcessRecord.killLocked: " + pkg
                                + (prefs.isAggressive() ? "（全局强力模式）" : "（应用强力模式）"));
                        sLastKillLog.put(pkg, now);
                    }
                    return null;
                }
            },
            // 5. 消息保活核心：Doze 省电白名单豁免（Android 11+ 类在 deviceidle 包，旧版兜底）
            //    返回 true 等效于把目标加入省电白名单，后台网络/闹钟不再被 Doze 延迟。
            //
            //    ★ 方法名跨版本/跨层兼容（这是一处真实踩坑）：
            //      DeviceIdleController 里同时存在「Binder 接口实现」与「内部实现」两套命名：
            //        - isPowerSaveWhitelistApp(...)            ← Binder/旧版命名
            //        - getPowerSaveWhitelistExceptIdleInternal(String) ← 新版内部命名
            //        - getPowerSaveWhitelistInternal / isPowerSaveWhitelistExceptIdleApp ...
            //      只挂单一名字在部分版本上会一个都找不到（表现为自检里 dozeWhitelist 未生效），
            //      这里把已知的几个变体全部列出，命中任意一个即可。
            new HookLogic(cl, prefs, DEVICE_IDLE, new String[]{DEVICE_IDLE_LEGACY},
                    "isPowerSaveWhitelistApp",
                    new String[]{
                            "isPowerSaveWhitelistAppLSP",
                            "getPowerSaveWhitelistExceptIdleInternal",
                            "getPowerSaveWhitelistInternal",
                            "isPowerSaveWhitelistExceptIdleApp",
                            "isPowerSaveWhitelistAppInternal"},
                    "dozeWhitelist") {
                @Override
                public Object before(Object thiz, Object[] args) {
                    prefs.refreshIfStale(30000);
                    if (!prefs.isEnabled()) return UNHANDLE;
                    String pkg = firstStringArg(args);
                    if (pkg != null && prefs.isMsgProcess(pkg)) return Boolean.TRUE;
                    return UNHANDLE;
                }
            },
            // 6. 消息保活补充：App Standby 置为 ACTIVE（防 bucket 节流 JobScheduler/网络）
            //    best-effort：类或方法缺失仅跳过，不影响其他功能。
            //    ★ 候选名：新版 AOSP 该方法带 LSP 后缀。
            new HookLogic(cl, prefs, APP_STANDBY, "getAppStandbyBucket",
                    new String[]{"getAppStandbyBucketLSP"}, "appStandby") {
                @Override
                public Object before(Object thiz, Object[] args) {
                    prefs.refreshIfStale(30000);
                    if (!prefs.isEnabled()) return UNHANDLE;
                    String pkg = firstStringArg(args);
                    if (pkg != null && prefs.isMsgProcess(pkg)) return 0; // STANDBY_BUCKET_ACTIVE
                    return UNHANDLE;
                }
            },
            // 7. 开机自启动：finishBooting
            //    ★ 候选名：历史上有 finishBooting / finishBootingLocked（AMP 化前后）。
            new HookLogic(cl, prefs, AMS, "finishBooting",
                    new String[]{"finishBootingLocked"}, "autoStart") {
                @Override
                public Object before(Object thiz, Object[] args) {
                    final Object amsInstance = thiz;
                    // 延迟到系统稳定后再执行；实际工作（IPC / PMS 查询 / startActivity）
                    // 全部放到后台线程，绝不阻塞 system_server 主线程。
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() ->
                            Prefs.runBackground(() -> {
                                prefs.loadNow(); // 后台线程同步加载，安全
                                log("开机自启动触发: enabled=" + prefs.isEnabled()
                                        + ", autoStart=" + prefs.isAutoStart()
                                        + ", targets=" + prefs.targetsCount()
                                        + ", providerLoaded=" + prefs.isProviderLoaded());
                                if (prefs.isEnabled() && prefs.isAutoStart()) {
                                    startTargets(amsInstance, prefs);
                                }
                            }), 30000);
                    return UNHANDLE;
                }
            },
            // 8. 【D 功能 · 第一防线】进程死亡事件：目标一死就立刻安排补拉，不等 20 秒轮询。
            //
            //    AMS.appDiedLocked 是进程死亡的统一收口（binderDied / SIGKILL /
            //    crash 最终都会汇到这里），在它【之后】处理：先让系统把死亡收尾跑完，
            //    我们再安排拉起，不去干扰系统自己的清理流程。
            //    签名各版本差异很大（Android 12/14/15 参数个数与类型都变过），这里
            //    靠 hookAllMethods 覆盖全部重载 + 从参数里挑 ProcessRecord 来兼容。
            //    ★ 候选名：新版 AOSP 有 appDiedLSP 形式（锁语义后缀）。
            new HookLogic(cl, prefs, AMS, "appDiedLocked",
                    new String[]{"appDiedLSP"}, "appDiedLocked") {
                @Override
                public Object after(Object thiz, Object[] args, Object result) {
                    onProcessDied(prefs, thiz, args, "appDiedLocked");
                    return result;
                }
            },
            // 8b. 【D 功能 · 第一防线】死亡路径兜底：ROM 改了入口或进程走了别的回收路径时，
            //     handleAppDiedLocked 仍可能被单独调用；与 8 共用去重表，重复通知无副作用。
            //     ★ 候选名：新版 AOSP 有 handleAppDiedLSP 形式。
            new HookLogic(cl, prefs, AMS, "handleAppDiedLocked",
                    new String[]{"handleAppDiedLSP"}, "handleAppDiedLocked") {
                @Override
                public Object after(Object thiz, Object[] args, Object result) {
                    onProcessDied(prefs, thiz, args, "handleAppDiedLocked");
                    return result;
                }
            },
            // 9. 【被杀原因 · 划卡片埋点】用户在最近任务里划掉卡片 / 点「全部清除」时登记。
            //
            //    为什么死亡现场反推不出来：本机实测死亡回调拿到的 ProcessRecord 上
            //    mRemoved / mWasForceStopped / mKillTime 全为 false / 0（现场早被清理，
            //    信号根本不在这台机器上存在）。所以改为在【移除任务这个动作发生的那一刻】
            //    主动记账，死亡回调一查即中。
            //
            //    为什么钩 AMS 侧而非 SystemUI：SystemUI 的「全部清除」和 Launcher 的
            //    划走卡片，最终都要回到 AMS/ATMS 的 removeTask 系列 Binder 接口，这里才是
            //    真正的收口；各家 ROM 的 SystemUI 改得面目全非，钩它等于自找不兼容。
            //
            //    候选名覆盖 Android 版本间反复改名的几个收口点：
            //      removeTask / removeTaskById / cleanUpRemovedTaskLocked / cleanupRemovedTaskLocked
            //    参数形态各版本不一（有的第一个参数是 int taskId，有的是 TaskInfo），
            //    这里统一走 pickTaskPackage() 从参数里把「根包名」挑出来，挑不到就跳过。
            new HookLogic(cl, prefs, AMS, "removeTask",
                    new String[]{"removeTaskById", "cleanUpRemovedTaskLocked",
                            "cleanupRemovedTaskLocked"},
                    "taskRemoved") {
                @Override
                public Object before(Object thiz, Object[] args) {
                    prefs.refreshIfStale(30000);
                    if (!prefs.isEnabled()) return UNHANDLE;
                    String pkg = pickTaskPackage(args);
                    if (pkg != null) noteTaskRemoved(pkg);
                    return UNHANDLE;
                }
            },
            // 9b. 【被杀原因 · 划卡片埋点】ATMS 侧的同一批收口方法。
            //     removeTask 在部分版本上落在 ActivityTaskManagerService（AMS 只是转发），
            //     两边都挂才不会漏；重复登记同一个包是幂等的（后写的时间戳覆盖前一个）。
            new HookLogic(cl, prefs, ATMS_FRAMEWORK, "removeTask",
                    new String[]{"removeTaskById", "cleanUpRemovedTaskLocked",
                            "cleanupRemovedTaskLocked"},
                    "taskRemovedAtms") {
                @Override
                public Object before(Object thiz, Object[] args) {
                    prefs.refreshIfStale(30000);
                    if (!prefs.isEnabled()) return UNHANDLE;
                    String pkg = pickTaskPackage(args);
                    if (pkg != null) noteTaskRemoved(pkg);
                    return UNHANDLE;
                }
            },
            // 9c. 【被杀原因 · 划卡片埋点】RecentTasks.remove：最近任务列表的移除入口，
            //     是 removeTask 之外另一条独立路径（有的 ROM 走这条）。
            new HookLogic(cl, prefs, RECENT_TASKS, "remove",
                    new String[]{"removeLSP", "cleanUpLocked"}, "taskRemovedRecent") {
                @Override
                public Object before(Object thiz, Object[] args) {
                    prefs.refreshIfStale(30000);
                    if (!prefs.isEnabled()) return UNHANDLE;
                    String pkg = pickTaskPackage(args);
                    if (pkg != null) noteTaskRemoved(pkg);
                    return UNHANDLE;
                }
            },
        };
    }

    /**
     * 从「移除任务」系列方法的参数里挑出被移除任务的根包名。
     *
     * <p>签名跨版本差异极大，不可能靠固定下标取参：
     * <ul>
     *   <li>{@code removeTask(int taskId, boolean killProcess)} —— 只有 id，读不出包名；</li>
     *   <li>{@code removeTask(TaskInfo task, ...)} —— 从 TaskInfo 的
     *       {@code realActivity} / {@code baseActivity} / {@code topActivity}
     *       里拆出包名；</li>
     *   <li>{@code cleanUpRemovedTaskLocked(TaskRecord task, ...)} —— TaskRecord 上有
     *       {@code realActivity} / {@code rootActivity} / {@code mActivityComponent}。</li>
     * </ul>
     * 所以这里不猜下标，而是【遍历所有参数】，只要某个参数是对象且能从中读出
     * 「任务根包名」，就采信。取不到就返回 null（保守放行，宁可漏标也不误标）。
     *
     * @param args 被钩方法的原始参数
     * @return 根包名；无法判定时 null
     */
    private static String pickTaskPackage(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a == null) continue;
            if (a instanceof String) {
                // 少数重载直接传包名
                String s = (String) a;
                if (s.indexOf('.') > 0) return stripProcessSuffix(s);
                continue;
            }
            String pkg = taskPackageOf(a);
            if (pkg != null) return pkg;
        }
        return null;
    }

    /**
     * 从一个任务对象（TaskInfo / TaskRecord / ActivityRecord）里读出根包名。
     *
     * <p>候选字段按「可信度」排序：realActivity 是任务根 Activity 的 ComponentName，
     * 最能代表「这个卡片属于哪个 App」；baseActivity / rootActivity / topActivity 都是
     * 各版本上的等价物。ComponentName 取 {@code getPackageName()}，字符串则截 '／' 前段。
     *
     * <p>为什么不用 {@code taskPackageName} 这类现成字段：跨版本字段名不稳定，
     * 而 ComponentName 上的 getPackageName() 是 Android 出生至今没变过的稳定契约。
     */
    private static String taskPackageOf(Object task) {
        String[] fields = {"realActivity", "baseActivity", "rootActivity",
                "topActivity", "mActivityComponent", "origActivity"};
        for (String f : fields) {
            try {
                Object v = getField(task, f);
                String pkg = packageOfComponent(v);
                if (pkg != null) return pkg;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 把 ComponentName / Intent / 字符串形态的组件标识统一取成包名。 */
    private static String packageOfComponent(Object v) {
        if (v == null) return null;
        try {
            if (v instanceof android.content.ComponentName) {
                String p = ((android.content.ComponentName) v).getPackageName();
                return stripProcessSuffix(p);
            }
            if (v instanceof android.content.Intent) {
                android.content.ComponentName cn = ((android.content.Intent) v).getComponent();
                if (cn != null) return stripProcessSuffix(cn.getPackageName());
                String p = ((android.content.Intent) v).getPackage();
                if (p != null && !p.isEmpty()) return stripProcessSuffix(p);
                return null;
            }
            if (v instanceof String) {
                // "pkg/.MainActivity" 或 "pkg/com.xx.MainActivity"
                return stripProcessSuffix((String) v);
            }
            // 反射兜底：不是上面这些类型时，试 getPackageName()
            Object p = v.getClass().getMethod("getPackageName").invoke(v);
            if (p instanceof String) return stripProcessSuffix((String) p);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** "pkg/.Act" → "pkg"；"pkg/sub.Act" → "pkg"；已是纯包名则原样返回。 */
    private static String stripProcessSuffix(String s) {
        if (s == null) return null;
        int slash = s.indexOf('/');
        String head = slash > 0 ? s.substring(0, slash) : s;
        int colon = head.indexOf(':');
        if (colon > 0) head = head.substring(0, colon);
        return head.indexOf('.') > 0 ? head : null;
    }

    // ==================================================================
    // D 功能（死后拉起）· 第一防线：死亡事件驱动
    // ==================================================================

    /**
     * 目标进程死亡的回调处理：判定 → 记账 → 延迟 {@link #DEATH_RELAUNCH_DELAY_MS} 后补拉。
     *
     * 为什么延迟而不是立刻拉：进程刚死时 AMS 还在清理它的 ActivityRecord /
     * ServiceRecord / binder 引用，此刻 startService 常撞上「进程正在死亡」的中间态被拒。
     * 1.5 秒后系统收尾已完成，成功率最高。
     *
     * 为什么在 after 而不是 before：死亡已经发生，拦不住也没必要拦（那是 A/B/C 的活），
     * 这里只负责「死后拉起」，绝不干扰系统自己的清理流程。
     *
     * @param prefs 配置源（死亡回调可能来自任意线程，prefs 的读取是只读的，安全）
     * @param ams  ActivityManagerService 实例，用于取 mContext 与判断是否正在关机
     * @param args 原始方法参数，从中挑出 ProcessRecord
     * @param from 触发来源，仅用于日志区分
     */
    private static void onProcessDied(Prefs prefs, Object ams, Object[] args, String from) {
        try {
            if (prefs == null) return;
            prefs.refreshIfStale(30000);   // 内部是异步加载，不会阻塞死亡回调
            // ★ 只看 enabled，不再要求 autoStart。
            //   原条件 `!isEnabled() || !isAutoStart()` 让「不开开机自启」的用户
            //   连「进程被杀后拉回来」也一起失去 —— 那本该是保活的核心能力，
            //   而 autoStart 的语义只是「开机时要不要主动拉起一次」。
            //   两者职责分离后：enabled 是总开关，autoStart 只作用于开机种子。
            if (!prefs.isEnabled()) return;
            // 系统正在关机/重启：此时拉起会和关机流程打架，直接放弃
            if (isShuttingDown(ams)) return;
            Object proc = findProcessRecord(args);
            if (proc == null) return;
            String pkg = getPackageName(proc);
            if (pkg == null || !prefs.isTarget(pkg)) {
                // 非目标进程死亡：也要清掉它的 pid 记账，否则这条映射会一直躺到
                // pid 被复用、或被 4096 条容量兜底冲掉为止。
                forgetPid(proc);
                return;
            }
            // 目标进程已死：立刻作废 pid → 包名映射。进程死亡到 pid 被内核复用之间
            // 有一段窗口，若不在这里清掉，setOomAdj 兜底可能拿着旧包名去改一个
            // 刚复用了同一 pid 的陌生进程的 adj。
            forgetPid(proc);

            // 去重：appDiedLocked 通常转调 handleAppDiedLocked，同一次死亡只处理一次。
            // noteDeath 内部还会顺带记一次「被杀」并写时间线。
            if (!noteDeath(pkg, proc)) return;

            final Context ctx = pickContext(ams);
            if (ctx == null) {
                logWarn("双防线·第一防线: 拿不到 systemContext，放弃补拉 " + pkg);
                return;
            }
            // 错峰：内存紧张时目标常被批量回收，若各自只延迟 1.5 秒，它们会在同一刻
            // 一起起进程——那正是开机拉起用 LAUNCH_STAGGER_MS 要避免的情况。
            // 这里改用时间轴排队，相邻两个补拉至少隔 1.5 秒。
            final long delay = nextRelaunchDelayMs();
            log("双防线·第一防线(" + from + ") 捕获目标死亡: " + pkg
                    + "，" + delay + "ms 后静默补拉");
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() ->
                    Prefs.runBackground(() -> {
                        tryRelaunch(ctx, pkg, "第一防线·死亡事件");
                    }), delay);
        } catch (Throwable t) {
            logWarn("双防线·第一防线异常(" + from + "): " + t);
        }
    }

    // ==================================================================
    // D 功能（死后拉起）· 两道防线共用的执行与去重
    // ==================================================================

    /**
     * 记一次「目标死亡/掉线」。两道防线都调它，窗口内只生效一次。
     *
     * @return true 表示这是本窗口内第一次判定（调用方应立即安排补拉）；
     *         false 表示刚刚已有别的防线处理过，本次应直接跳过。
     */
    private static boolean noteDeath(String pkg, Object proc) {
        long now = System.currentTimeMillis();
        Long last = sDeathAt.get(pkg);
        if (last != null && now - last < DEATH_DEDUP_MS) return false;
        sDeathAt.put(pkg, now);
        // 常驻/核心目标死亡：登记「托底保活」待确认。两者都把进程标成 persistent，
        // 系统都会自行重启，故都按「托底保活」归功。模块随后若自己拉起会撤销该登记；
        // 若撤销前心跳轮询发现它已复活，就判定为系统 persistent 重启（功劳归系统）。
        if (sCfgPersist.contains(pkg) || sCfgCore.contains(pkg)) sSysClaimPending.put(pkg, now);
        // 只有「跑起来过又掉线」才算被杀；开机后压根没起来过的补拉不计
        if (sEverSeen.contains(pkg)) {
            bumpKill(pkg);
            // 被杀事件：who（拉起来源）为空，新增 reason 记录【被什么杀的】。
            // 判定优先级：
            //   ① 模块自己在进程页杀的（登记过 sSelfKilledPid）→ 精确标「用户手动关闭（进程页）」；
            //   ② 用户划后台卡片（钩 AMS 移除任务的收口方法登记过）→ 「用户划卡片清除」；
            //      ★ 这条必须排在 ProcessRecord 残留字段之前：本机实测划卡片时
            //        mRemoved/mWasForceStopped/mKillTime 全为 false/0（现场字段不存在），
            //        残留字段那条路在这里永远读不出东西，靠埋点才能定性。
            //   ③ ProcessRecord 残留字段（mWasForceStopped / mRemoved）→ 用户强停；
            //   ④ 都读不到 → 按外部线索兜底推断，避免时间线上大片空标签。
            String reason = readSelfKillReason(pkg, proc);
            if (reason.isEmpty()) reason = readTaskRemovedReason(pkg);
            if (reason.isEmpty()) reason = readKillReason(proc);
            if (reason.isEmpty()) reason = inferKillReason(pkg, proc);
            dumpKillDiag(proc, pkg, reason);
            addGuardEvent(pkg, EVT_KILL, "", reason);
        }
        return true;
    }

    /**
     * 当 {@link #readKillReason} 读不到残留字段时的兜底推断。
     *
     * <p>能拿到的线索：
     * <ul>
     *   <li>{@code proc == null}：来自轮询缺席判定（进程已不在运行列表），
     *       无 ProcessRecord 可读，只能给通用标签「系统或后台清理」（无法细分）；</li>
     *   <li>{@code proc != null}：死亡回调路径，但两个布尔字段都没置位。
     *       常见于系统低内存回收（LMK）/ 系统后台清理 / 应用自行退出 ——
     *       这三者都不碰 force-stop / removeTask 标记，靠残留字段无法区分，
     *       统一标「系统或应用自行结束」，并注明无法细分。</li>
     * </ul>
     * 给标签总比空着强：用户至少能看出「不是被划卡片/强制停止」，与用户主动操作区分开。
     */
    private static String inferKillReason(String pkg, Object proc) {
        try {
            // 轮询缺席路径（proc == null）：拿不到 ProcessRecord，无从细分
            if (proc == null) return "原因不明（轮询检测到掉线）";
            // 死亡回调路径：ProcessRecord 上既无 module 杀进程登记，也无
            // mWasForceStopped / mRemoved 标记。此时可能是不碰这两标记的：
            // 系统低内存回收（LMK）、系统后台批量清理、应用自行退出/崩溃。
            // 三者靠残留字段无法区分，给一个诚实的合并标签，并说明可细分方向。
            return "系统回收或应用自退";
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * 实际执行一次静默补拉，带冷却，两道防线共用这一出口。
     *
     * 冷却期内直接跳过——第一防线刚拉完，第二防线同轮判定掉线时不会再拉一次。
     * 拉起失败（典型是目标处于 force-stop 态，startService/Provider 都被拒）会把
     * 占位撤掉，好让下一轮还能再试，不会被一次失败锁死 8 秒。
     *
     * @param why 仅用于日志区分是哪道防线拉的
     * @return true 表示真的拉了并且成功了
     */
    private static boolean tryRelaunch(Context ctx, String pkg, String why) {
        long now = System.currentTimeMillis();
        Long last = sRelaunchAt.get(pkg);
        if (last != null && now - last < RELAUNCH_COOLDOWN_MS) {
            log("双防线·" + why + " 冷却中，跳过: " + pkg);
            return false;
        }
        // ★ 连续失败退避：静默拉起失败通常是「应用处于 force-stop 态」——
        //   这种状态下 startService/Provider 都被系统拒绝，且短时间不会自愈。
        //   若每轮都重试（心跳 10 秒一轮），会变成每 10 秒一次的无用 IPC + PMS 查询，
        //   持续唤醒 CPU，纯粹耗电。按失败次数拉长重试间隔：
        //   1 次失败等 8 秒、2 次 16 秒…… 封顶 5 分钟，既不错过真正的恢复时机，
        //   也不会一直空转。成功一次即清零。
        long backoff = RELAUNCH_COOLDOWN_MS;
        Long failAt = sRelaunchFailAt.get(pkg);
        int fails = sRelaunchFailCount.containsKey(pkg) ? sRelaunchFailCount.get(pkg) : 0;
        if (fails > 0) {
            backoff = Math.min(RELAUNCH_COOLDOWN_MS << Math.min(fails, 6), RELAUNCH_FAIL_MAX_BACKOFF_MS);
        }
        if (failAt != null && now - failAt < backoff) {
            return false;   // 退避期内静默跳过（不刷日志，否则退避本身又成了噪音）
        }
        // ★ 「托底保活」判定：动手前一刻重新查存活。
        //   必须【只看存活】、不依赖 sSysClaimPending 是否还在 —— 心跳轮询在判出
        //   「托底保活」时会 remove 掉登记（表里就空了），而第一防线早在捕获死亡时
        //   就 postDelayed 排好了 1.5 秒后的补拉。若这里仍要求「登记还在」，补拉执行
        //   时表已空、条件不成立，就会在系统刚拉回后又重复拉一次（21:41:55 系统拉、
        //   21:41:56 模块又拉，正是这个竞态）。
        //   改判：目标此刻已存活 → 说明系统已把它拉回（或它自己回来了），模块不必再拉。
        //   注意只在【常驻/核心目标】上做此判定：普通目标不该被系统 persistent 重启，
        //   这里若也判存活可能因残留子进程而永久跳过补拉（子进程活着≠主进程活着，
        //   但 scanRunningApps 的子进程兜底会认）。常驻/核心目标本就有系统托底，跳过安全。
        if ((sCfgPersist.contains(pkg) || sCfgCore.contains(pkg)) && isPkgAlive(pkg)) {
            log("双防线·" + why + " 常驻/核心目标已存活（系统 persistent 抢先重启），放弃模块补拉: " + pkg);
            sSysClaimPending.remove(pkg);   // 清掉可能残留的登记
            return false;
        }
        sRelaunchAt.put(pkg, now);
        if (!silentStart(ctx, pkg)) {
            // 拉起失败：记一次失败并进入退避，不再「撤掉占位交还下一轮」——
            // 那等于每轮都重试，正是上面要避免的空转。
            sRelaunchAt.remove(pkg);
            sRelaunchFailAt.put(pkg, now);
            sRelaunchFailCount.put(pkg, fails + 1);
            if (fails == 0 || fails == 2 || fails % 10 == 9) {
                // 首次失败必报；之后每 10 次报一条，避免刷屏又能看出「一直在失败」
                logWarn("双防线·" + why + " 补拉失败(多半处于 force-stop 态)，"
                        + "已连续失败 " + (fails + 1) + " 次，退避 " + backoff + "ms: " + pkg);
            }
            return false;
        }
        // 成功：清掉失败记录，恢复正常节奏
        sRelaunchFailAt.remove(pkg);
        sRelaunchFailCount.remove(pkg);
        bumpRelaunch(pkg);
        sSysClaimPending.remove(pkg);   // 模块动的手：撤销「托底保活」待确认
        addGuardEvent(pkg, EVT_RELAUNCH, relaunchWho(pkg, why));
        log("双防线·" + why + " 补拉成功: " + pkg);
        return true;
    }

    /**
     * 组装时间线的「触发方」文案。凡是走到这里的，动作主体都是【模块自己】——
     * 第一/第二防线的补拉、开机种子拉起，模块都实实在在发出了 startService，
     * 所以前缀固定为「模块拉活」，后缀指出是哪条路径发现的。
     *
     * <p>真正由系统 persistent 重启拉起的情形<b>不经过本方法</b>：那条路径模块没有动手，
     * 由心跳轮询确认后直接写「托底保活」（见 startProcReporter 里的待确认判定）。
     * 这样「谁拉的」就与「谁动的手」严格对齐，不会再出现「托底保活 · 第二防线」
     * 这种把模块功劳贴到系统头上、还自相矛盾（系统没有第二防线）的文案。
     *
     * <p>历史教训：这里以前按「目标是否开了常驻」来决定前缀（开了就写「托底保活」），
     * 但「身份」不等于「动作」——常驻目标被模块第二防线拉起时，前缀写「托底保活」、
     * 后缀写「第二防线」，读起来就成了「系统走了第二防线」，实为张冠李戴。
     *
     * @param pkg 目标包名
     * @param why 防线来源（如「第一防线·死亡事件」「第二防线·轮询」「开机种子」）
     */
    private static String relaunchWho(String pkg, String why) {
        // 防线名简化为「第一防线」/「第二防线」，去掉后面的具体触发方式
        // （「死亡事件」「轮询」是排查用的，放在用户看的时间线上太啰嗦）
        String line = why != null && why.startsWith("第一防线")
                ? "第一防线"
                : (why != null && why.startsWith("第二防线") ? "第二防线" : why);
        return "模块拉活 · " + line;
    }

    /**
     * 批量死亡时的错峰游标：记录「下一个补拉时刻」的绝对时间戳。
     *
     * 为什么需要它：内存紧张时多个目标常被一起回收，如果各自只延迟
     * {@link #DEATH_RELAUNCH_DELAY_MS}，它们会在同一瞬间一起起进程——
     * 这恰恰是开机拉起用 {@link #LAUNCH_STAGGER_MS} 想要避免的情况。
     * 这里按时间轴排队，保证相邻两次补拉至少隔 1.5 秒。
     */
    private static final java.util.concurrent.atomic.AtomicLong sRelaunchSlot =
            new java.util.concurrent.atomic.AtomicLong(0L);

    /** 补拉排队上限：目标极多时不让队尾无限后延，超过就从头排（宁可少量重叠也不干等）。 */
    private static final long RELAUNCH_QUEUE_CAP_MS = 30_000L;

    /** 取下一次补拉相对现在的延迟，与已排队的补拉至少隔 {@link #LAUNCH_STAGGER_MS}。 */
    private static long nextRelaunchDelayMs() {
        long now = System.currentTimeMillis();
        long earliest = now + DEATH_RELAUNCH_DELAY_MS;
        while (true) {
            long prev = sRelaunchSlot.get();
            long slot = Math.max(earliest, prev + LAUNCH_STAGGER_MS);
            if (slot - now > RELAUNCH_QUEUE_CAP_MS) slot = earliest;
            if (sRelaunchSlot.compareAndSet(prev, slot)) return slot - now;
        }
    }

    /** 取一个可用的 systemContext：优先用缓存，其次从 AMS 反射拿并回填缓存。 */
    private static Context pickContext(Object ams) {
        Context c = sSystemContext;
        if (c != null) return c;
        if (ams != null) {
            try {
                Object o = getField(ams, "mContext");
                if (o instanceof Context) {
                    c = (Context) o;
                    sSystemContext = c;
                    return c;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 系统是否正在关机/重启（读不到就当没在关机，宁可多拉一次也不漏保活）。 */
    private static boolean isShuttingDown(Object ams) {
        if (ams == null) return false;
        try {
            Object v = getField(ams, "mShuttingDown");
            return v instanceof Boolean && (Boolean) v;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 开机种子拉起全局只执行一次（legacy + modern 双入口都会钩 finishBooting，不去重会拉两轮）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean sBootStartDone =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static void startTargets(Object amsInstance, Prefs prefs) {
        // 双入口防重：LSPosed 可能同时加载 legacy 与 modern 入口，finishBooting 被钩两次，
        // 若不去重，同一次开机会错峰拉两轮（时间线出现相隔数秒的两条「开机拉起」、计数翻倍）。
        if (!sBootStartDone.compareAndSet(false, true)) {
            log("开机自启动重复触发，跳过（另一入口已执行）");
            return;
        }
        try {
            Object ctxObj = getField(amsInstance, "mContext");
            if (!(ctxObj instanceof Context)) {
                logWarn("开机自启动: 获取 mContext 失败");
                return;
            }
            final Context ctx = (Context) ctxObj;
            List<String> targets = new ArrayList<>(prefs.getTargets());
            // 【去重】第二防线（轮询兜底）通常在开机后约 20 秒就先把目标拉起来，
            // 本路径在 30 秒执行时它们多半已在运行。先扫一遍正在运行的目标，把已经在跑的
            // 从待拉起名单里剔除 —— 否则同一目标会被「轮询」和「开机种子」各拉一次，
            // 时间线出现一条「拉起」+ 一条「开机拉起」、计数翻倍（就是「开机拉起两次」）。
            // 只过滤不拦截：真正没起来的目标照拉，轮询/种子互为兜底的语义不变。
            java.util.Set<String> alive = scanRunningApps(new HashSet<>(targets)).keySet();
            List<String> pending = new ArrayList<>();
            for (String t : targets) {
                if (alive.contains(t)) {
                    log("开机自启动: " + t + " 已在运行，跳过（避免与轮询兜底重复拉起）");
                    // 开机精分：常驻目标且模块本次开机没拉过它 → 是系统 persistent 在开机时
                    // 把它拉起来的，记一条「托底保活」（无防线后缀）。Blued 这类被模块轮询
                    // 抢先拉起的，sRelaunchAt 有近期记录，会被排除，不会误记成系统功劳。
                    Long ra = sRelaunchAt.get(t);
                    boolean pulledRecently = ra != null
                            && System.currentTimeMillis() - ra < 60_000L;
                    if (sCfgPersist.contains(t) && !pulledRecently) {
                        // 时间线事件名统一为「系统重启」：与「模块拉活 · 第一防线/开机种子」
                        // 平行，一列只写「谁动的手」，短且不占宽度。
                        addGuardEvent(t, EVT_RELAUNCH, "系统重启");
                        log("开机自启动: 常驻目标 " + t + " 已在运行，判定为托底保活（系统 persistent 开机拉起）");
                    }
                } else {
                    pending.add(t);
                }
            }
            if (pending.isEmpty()) {
                log("开机自启动: " + targets.size() + " 个目标均已在运行，无需拉起");
                return;
            }
            log("开机自启动开始错峰拉起，待拉起=" + pending.size()
                    + "，已在运行=" + (targets.size() - pending.size()));
            // 错峰拉起：相邻目标之间留 LAUNCH_STAGGER_MS 间隔，避免保活名单多时
            // 同一瞬间一起拉起把低端机卡爆。本方法已在后台线程调用，sleep 安全。
            // 开机种子拉起是模块亲自动手，事件统一标「模块拉活 · 开机种子」；不再按
            // 「目标是否常驻」改前缀 —— 身份不等于动作，按身份贴前缀会张冠李戴。
            silentStartStaggered(ctx, pending, true);
        } catch (Throwable t) {
            logWarn("开机自启动执行失败: " + Log.getStackTraceString(t));
        }
    }

    /** 保证上报循环全局只启动一次（legacy / modern / 自启动回调多处触发也不会重复开线程）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean sReportStarted =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 保证钩子【全局只安装一次】。
     *
     * <p>为什么必须有这道闸：本模块在 APK 里同时带着两套入口声明 ——
     * {@code assets/xposed_init}（legacy，走 {@link #install}）与
     * {@code META-INF/xposed/java_init.list}（modern，走 {@link #installModern}）。
     * LSPosed 对同时存在两种声明的模块，legacy 兼容层与 modern API 链路会各加载一次，
     * 于是两个安装器都会跑，把同一批方法各挂一遍。
     *
     * <p>重复挂载的危害不是"多此一举"，而是【副作用翻倍】：
     * <ul>
     *   <li>被 hook 的方法回调执行两次 → 「被杀/拉起」计数虚高、同一时刻重复拉起进程；</li>
     *   <li>两条 hook 链互不感知，{@code proceed()} 的返回值可能被后装的那条覆写；</li>
     *   <li>日志里每条「钩子已安装」出现两次，自检数据与排查都被干扰。</li>
     * </ul>
     *
     * <p>用 CAS 而不是 {@code synchronized}：安装发生在 system_server 启动路径上，
     * 要的是"第一个到的人干活、后来的人立刻滚蛋"，不该有排队等待的开销。
     * 后到的入口直接返回而不报错 —— 这是正常的竞态结果，不是故障。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean sHooksInstalled =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // ==================================================================
    // legacy 安装器（XposedBridge / API 82）
    // ==================================================================

    public static void install(ClassLoader cl, Prefs prefs) {
        // 全局闸：与 modern 入口抢跑，只有第一个到的人真正装钩子
        if (!sHooksInstalled.compareAndSet(false, true)) {
            log("钩子已由另一入口安装，跳过重复安装(legacy)");
            return;
        }
        log("开始安装保活钩子(legacy)，目标应用数量=" + prefs.targetsCount());
        for (HookLogic logic : createLogics(cl, prefs)) {
            installLegacyOne(cl, logic);
        }
        // 全部装完汇总自检结果：一条日志看清哪些能力在本机生效
        reportHookStatus();
        // legacy 路径同样要上报激活状态（否则未勾选「本模块」作用域时，
        // 没有任何进程写激活标记，主页会永远显示未激活）
        startActivationReport(cl);
    }

    private static void installLegacyOne(ClassLoader cl, HookLogic logic) {
        try {
            Class<?> clazz = resolveClass(cl, logic);
            if (clazz == null) {
                logWarn("legacy 类未找到(" + logic.name + "): " + logic.className);
                recordClassMissing(logic);
                return;
            }
            int count = 0;
            String hit = null;
            for (String mName : logic.methodNames) {
                // 先探明该方法在本类上是否存在：不存在就跳过，
                // 不去惊动 hook 框架（有些实现对空名字会打印噪声日志或抛异常）。
                int before = countHookedMethods(clazz, mName);
                if (before == 0) continue;
                // 命中才挂：hookAllMethods 会覆盖该方法的所有重载
                XposedBridge.hookAllMethods(clazz, mName, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        ensureContext(logic, param.thisObject);
                        Object r = logic.before(param.thisObject, param.args);
                        if (r != UNHANDLE) param.setResult(r);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object r = logic.after(param.thisObject, param.args, param.getResult());
                        if (r != param.getResult()) param.setResult(r);
                    }
                });
                count += before;
                if (hit == null) hit = mName;
            }
            if (count == 0) {
                // 静默失效是最危险的失败模式：配置全对却毫无效果，日志里却像没事。
                // 这里必须显式报错，并列出尝试过的候选名，方便对照 ROM 的实际方法名。
                logWarn("钩子未命中(legacy): " + logic.name + " 在 " + logic.className
                        + " 上找不到方法 " + java.util.Arrays.toString(logic.methodNames)
                        + " —— 该能力本次开机不会生效");
                recordHookStatus(logic, null, 0);
            } else {
                logic.hitMethodName = hit;
                log("钩子已安装(legacy): " + logic.name + " → " + hit + " (" + count + " 个重载)");
                recordHookStatus(logic, hit, count);
            }
        } catch (Throwable t) {
            logWarn("钩子安装失败(" + logic.name + "): " + t);
            recordHookStatus(logic, null, 0);
        }
    }

    /** 统计类中某名字的方法个数，用于判定候选名是否命中（0 = 该类上不存在此方法）。 */
    private static int countHookedMethods(Class<?> clazz, String methodName) {
        int n = 0;
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(methodName)) n++;
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    // ==================================================================
    // 钩子自检报告（跨 ROM / 跨版本兼容性的可见性保障）
    // ==================================================================

    /**
     * 自检结果单条：一个 hook 逻辑在本次开机的挂载结局。
     *
     * <p>为什么需要它：本模块全部能力都靠「按名字挂 AOSP 私有方法」实现，而 AOSP
     * 在版本间会改方法名（如 applyOomAdjLocked → applyOomAdjLSP）、ROM 也会魔改。
     * 一旦某个名字对不上，对应功能会【静默失效】——不崩溃、不报错，用户只觉得
     * 「功能不好用」，开发者也无从判断到底是哪台机器哪个功能没挂上。
     * 这里把每个 hook 的结局如实记下来，安装完成后汇总打印，让问题一眼可见。
     */
    private static final class HookStatus {
        final String name;          // 能力名，如「档位·降低OOM Adj」
        final String className;     // 目标类
        final String[] tried;       // 尝试过的候选方法名
        final String hit;           // 命中的方法名；null = 未命中
        final int overloads;        // 命中的重载个数

        HookStatus(String name, String className, String[] tried, String hit, int overloads) {
            this.name = name;
            this.className = className;
            this.tried = tried;
            this.hit = hit;
            this.overloads = overloads;
        }

        /** 编码为一行文本，供上报给 App 落盘展示：状态|能力名|目标类|命中名|重载数 */
        String encode() {
            return (hit != null ? "OK" : "MISS") + "|" + name + "|"
                    + shortName(className) + "|" + (hit != null ? hit : "")
                    + "|" + overloads;
        }
    }

    /** 取类名最后一段，日志与报告里更易读（com.a.b.C → C）。 */
    private static String shortName(String className) {
        if (className == null) return "?";
        int i = className.lastIndexOf('.');
        return i >= 0 ? className.substring(i + 1) : className;
    }

    /** 本次开机所有 hook 的自检结果（按安装顺序）。 */
    private static final java.util.List<HookStatus> sHookStatus =
            java.util.Collections.synchronizedList(new java.util.ArrayList<HookStatus>());

    /** 记录一条自检结果。 */
    private static void recordHookStatus(HookLogic logic, String hit, int overloads) {
        sHookStatus.add(new HookStatus(
                logic.name, logic.className, logic.methodNames, hit, overloads));
    }

    /** 类未找到（候选类名全不匹配）时也记一条，避免报告里缺项。 */
    private static void recordClassMissing(HookLogic logic) {
        sHookStatus.add(new HookStatus(
                logic.name, logic.className, logic.methodNames, null, 0));
    }

    /**
     * 安装全部结束后，把自检结果汇总成一条日志。
     *
     * <p>输出形如：{@code 钩子自检 9/11 命中；未命中: [阻止强制停止(ActivityManagerService),
     * 消息保活(AppStandbyController)]}。这样用户/开发者看日志就能立刻判断
     * 「我的机器上哪些保活能力没生效」，而不必逐个去猜。
     */
    private static void reportHookStatus() {
        int ok = 0;
        StringBuilder miss = new StringBuilder();
        synchronized (sHookStatus) {
            for (HookStatus s : sHookStatus) {
                if (s.hit != null) {
                    ok++;
                } else {
                    if (miss.length() > 0) miss.append(", ");
                    miss.append(s.name).append("(").append(shortName(s.className))
                            .append(") 候选=").append(java.util.Arrays.toString(s.tried));
                }
            }
            if (ok == sHookStatus.size()) {
                log("钩子自检: " + ok + "/" + sHookStatus.size() + " 全部命中");
            } else {
                logWarn("钩子自检: " + ok + "/" + sHookStatus.size()
                        + " 命中；未命中(该功能在本次开机不生效): [" + miss + "]");
            }
        }
    }

    /** 自检结果编码数组，供上报给模块 App 落盘（在「关于/诊断」里展示）。 */
    public static String[] hookStatusSnapshot() {
        synchronized (sHookStatus) {
            String[] out = new String[sHookStatus.size()];
            for (int i = 0; i < out.length; i++) out[i] = sHookStatus.get(i).encode();
            return out;
        }
    }

    // ==================================================================
    // modern 安装器（libxposed API）
    // ==================================================================

    public static void installModern(XposedInterface xposed, ClassLoader cl, Prefs prefs) {
        // 全局闸：与 legacy 入口抢跑，只有第一个到的人真正装钩子。
        // 注意 sXposed 必须在闸之前赋值 —— 它是 Hooker 回调里的出口引用，
        // 若因"已由 legacy 装过"而直接返回，现代链路的回调不该被用到，
        // 但保留赋值可让后续诊断代码安全读取，不会拿到 null。
        sXposed = xposed;
        if (!sHooksInstalled.compareAndSet(false, true)) {
            log("钩子已由另一入口安装，跳过重复安装(modern)");
            return;
        }
        log("开始安装保活钩子(modern)，目标应用数量=" + prefs.targetsCount());
        for (HookLogic logic : createLogics(cl, prefs)) {
            Class<?> clazz = resolveClass(cl, logic);
            if (clazz == null) {
                logWarn("modern 类未找到(" + logic.name + "): " + logic.className);
                recordClassMissing(logic);
                continue;
            }
            int count = 0;
            String hit = null;
            for (String mName : logic.methodNames) {
                // 逐个候选名尝试（跨版本改名兼容，如 applyOomAdjLocked → applyOomAdjLSP）
                boolean any = false;
                for (Method m : clazz.getDeclaredMethods()) {
                    if (!m.getName().equals(mName)) continue;
                    xposed.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(new ModernHooker(logic));
                    any = true;
                    count++;
                }
                if (any && hit == null) hit = mName;
            }
            if (count == 0) {
                logWarn("钩子未命中(modern): " + logic.name + " 在 " + logic.className
                        + " 上找不到方法 " + java.util.Arrays.toString(logic.methodNames)
                        + " —— 该能力本次开机不会生效");
                recordHookStatus(logic, null, 0);
            } else {
                logic.hitMethodName = hit;
                log("钩子已安装(modern): " + logic.name + " → " + hit + " (" + count + " 个重载)");
                recordHookStatus(logic, hit, count);
            }
        }
        // 全部装完汇总自检结果：一条日志看清哪些能力在本机生效
        reportHookStatus();
        // 诊断：枚举关键类的实际方法名与签名（Android 16 方法名有变化，
        // 例如 applyOomAdjLocked 已改名为 applyOomAdjLSP——列出实际名字便于对照）
        dumpMethods(cl, OOM_ADJUSTER, "Oom", "Adj", "LSP");
        dumpMethods(cl, PROCESS_RECORD, "kill");
        // setOomAdj 兜底的成败取决于这块能否看清真名：若本机叫 setOomAdjLSP/setOomAdjLPr
        // 之外的第三种名字，靠候选名硬猜会静默失效，列出来才能对症补候选。
        dumpMethods(cl, PROCESS_LIST, "setOomAdj", "Adj");
        dumpMethods(cl, AMS, "killPackage", "newProcessRecord", "ForceStop", "startProcess");
        // ★ 004：专门打出 applyOomAdjLSP 的**完整参数类型列表**。
        //   043 修 adj 失败就是因为不知道实参是 ProcessRecordInternal（不是 ProcessRecord）。
        //   dumpMethods 只列方法名，这里要把参数类型也打出来，才能确定该往哪个类找字段。
        dumpMethodSignatures(cl, OOM_ADJUSTER, "applyOomAdj");

        // ★ 字段枚举：markPersistent 写 persistent/maxAdj 全靠反射猜名，猜不中就是
        //   永久静默失败。028 的日志已经实测到「常驻标记失败」（真名是 mPersistent，
        //   而旧代码只试了 persistent）。这里把 ProcessRecord 的数值字段全列出来，
        //   并自动发现嵌套状态容器 —— 下次再遇到字段改名，看日志就能定位。
        dumpPersistentFieldNames(cl);

        // 上报激活状态给模块 App（后台线程执行 IPC，避免阻塞 system_server）
        startActivationReport(cl);
    }

    /**
     * 枚举 ProcessRecord 的数值字段，并顺带找出可能藏着 persistent/maxAdj 的嵌套对象。
     *
     * <p>★ 028 那版在这里犯了两个错，都值得记下来：
     * <ol>
     *   <li>按关键词过滤（persist / adj / state / pid…）。本机日志里只剩 4 个命中字段、
     *       一个含 {@code adj} 的都没有 —— 过滤把唯一的线索滤掉了。</li>
     *   <li>死认 {@code mState} 这个名字，找不到就按「ProcessStateRecord / UidRecord」
     *       类名硬猜。本机 ProcessRecord 上根本没有 mState，于是猜到了
     *       {@code UidRecord} —— 一个跟 persistent 毫无关系的类。</li>
     * </ol>
     *
     * <p>现在改成：ProcessRecord 的数值字段【全列】（也就二十几个，不刷屏），
     * 再按「类型属于 am 包的嵌套对象」自动发现状态容器，不再写死类名。
     */
    private static void dumpPersistentFieldNames(ClassLoader cl) {
        try {
            Class<?> rec = Class.forName(PROCESS_RECORD, false, cl);
            // ① ProcessRecord 自己的数值字段全列（不带关键词过滤）
            dumpFields(rec, "ProcessRecord全部");
            // ② 自动发现嵌套状态容器：字段类型属于 am 包的成员对象才可能藏着
            //    persistent/maxAdj。AMS / ProcessList 这类是「服务」不是「状态容器」，
            //    列出来只有噪音，跳过。
            int nested = 0;
            for (java.lang.reflect.Field f : rec.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                if (ft.isPrimitive() || ft == String.class) continue;
                String tn = ft.getName();
                if (!tn.startsWith("com.android.server.am.")) continue;
                if (tn.contains("Service") || tn.contains("Manager")
                        || tn.contains("ProcessList") || tn.contains("ProcessRecord")) {
                    continue;
                }
                if (++nested > 6) break;
                dumpFields(ft, "嵌套 " + f.getName());
            }
            if (nested == 0) {
                log("未发现 am 包嵌套状态容器：persistent/maxAdj 应在 ProcessRecord 自身字段上");
            }
        } catch (Throwable t) {
            logWarn("常驻字段枚举失败: " + t);
        }
    }

    /**
     * 启动激活上报（全局仅一次）。
     * 模块 App 的 ContentProvider 只有在 App 进程启动后才会发布到 system_server，
     * 开机初期调用会报 Unknown authority / NPE，因此采用「延迟启动 + 长周期重试」：
     * 先等 15 秒，之后每 15 秒尝试一次，最多 1440 次（约 6 小时）。
     * 用户开机后任意时间打开模块 App，Provider 一上线即可上报成功。
     */
    private static void startActivationReport(ClassLoader cl) {
        if (!sReportStarted.compareAndSet(false, true)) return;
        Prefs.runBackground(() -> {
            try {
                Thread.sleep(15000);
            } catch (InterruptedException e) {
                return;
            }
            markActiveViaProvider(cl);
        });
    }

    /**
     * 通过 ContentProvider 通知模块 App「本模块已在 system_server 中激活」。
     * App 侧 ConfigProvider 收到后在自身目录写激活标记，主页据此显示激活状态。
     * 该机制不依赖 LSPosed 是否注入模块 App 自身，modern 模式下也能可靠检测。
     */
    private static void markActiveViaProvider(ClassLoader cl) {
        Context ctx = getSystemContext(cl);
        if (ctx == null) {
            logWarn("激活状态上报失败: 获取 systemContext 为空");
            return;
        }
        final int maxAttempts = 1440;
        for (int i = 1; i <= maxAttempts; i++) {
            try {
                android.os.Bundle result = ctx.getContentResolver().call(
                        android.net.Uri.parse("content://" + BuildConfig.APPLICATION_ID + ".config"),
                        "markActive", null, null);
                if (result != null) {
                    log("激活状态上报: 成功" + (i > 1 ? "（第 " + i + " 次尝试）" : ""));
                    startProcReporter(ctx);
                    log("进程上报循环已启动（含 allprocs 全量快照通道）");
                    return;
                }
                if (i == maxAttempts) logWarn("激活状态上报: 第 " + i + " 次返回空");
            } catch (Throwable t) {
                // 前期失败是常态（App 未启动、Provider 未发布），只在关键节点打日志防刷屏
                if (i == 1 || i % 12 == 0 || i == maxAttempts) {
                    logWarn("激活状态上报失败(第 " + i + " 次): " + t);
                }
            }
            try {
                Thread.sleep(15000);
            } catch (InterruptedException e) {
                return;
            }
        }
        logWarn("激活状态上报: " + maxAttempts + " 次尝试均失败（6 小时内模块 App 未启动或 Provider 不可达）");
    }

    private static final java.util.concurrent.atomic.AtomicBoolean sProcReporterStarted =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 进程上报周期：App 端 90 秒内视为新鲜，这里 10 秒一次留足余量。 */
    private static final long PROC_REPORT_INTERVAL_MS = 10_000L;

    // ---- 配置缓存兜底：模块 App 进程被杀后 Provider 不可达时，沿用最后一份配置 ----
    private static volatile boolean sCfgValid = false;
    private static volatile boolean sCfgEnabled = false;
    private static volatile boolean sCfgAutoStart = false;
    /**
     * 「托底保活」（persistent）目标名单。
     *
     * <p>为什么要单独缓存：persistent 是**运行期打标**，只能在 updateOomAdjLocked 回调里
     * 对已存在的 ProcessRecord 设置。进程没跑起来时压根没有 ProcessRecord，无从打标 ——
     * 于是「目标不在跑」时必须由模块主动把它拉起来，打标才有机会发生。
     * 而要做这件事，心跳循环就得先知道「哪些目标是 persistent」。
     */
    private static volatile java.util.Set<String> sCfgPersist =
            java.util.Collections.emptySet();
    /** 托底保活 · 核心级(-1000)名单：与 sCfgPersist 同源，读 KEY_CORE_PREFIX。 */
    private static volatile java.util.Set<String> sCfgCore =
            java.util.Collections.emptySet();
    private static volatile java.util.Set<String> sCfgTargets =
            java.util.Collections.emptySet();
    /**
     * 进程「快照」通道的开关窗口：App 打开进程页时会把 proc_wanted 时间戳写到配置，
     * 这里只在该时间戳 60 秒内才去扫描全量 /proc（几百个进程 + 逐个读 status 较贵），
     * 不在看进程页时完全不扫，零额外开销。窗口内上报节奏加快到 3 秒，列表看着才「活」。
     */
    private static final long PROC_LIST_WANTED_MS = 60_000L;
    private static final long PROC_LIST_INTERVAL_MS = 3_000L;

    /**
     * 全量 /proc 扫描的独立线程节奏（045）。
     *
     * <p>扫描一次要读几百个进程的 cmdline / stat / status / oom_score_adj，是这一整套逻辑里
     * 最重的一块；原先它串行挂在心跳轮询那一轮里，把补拉、死亡检测、守护事件推回一起压后。
     * 挪到独立线程后：心跳轮询只负责稳定 3 秒一推（事件不再被拖），扫描按 {@link #PROC_SCAN_INTERVAL_MS}
     * 自己的节奏刷新缓存，两者互不阻塞。
     */
    private static final long PROC_SCAN_INTERVAL_MS = 3_000L;
    private static final java.util.concurrent.atomic.AtomicBoolean sProcScannerStarted =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 最近一次全量扫描结果（扫描线程写、心跳线程读）。 */
    private static volatile String[] sAllProcsCache = null;
    /** 进程页是否在看，以及它最近一次写 proc_wanted 的时间（心跳线程写、扫描线程读）。 */
    private static volatile boolean sProcWanted = false;
    private static volatile long sProcWantedAtMs = 0L;

    /** 杀进程指令监视线程全局只启动一次。 */
    private static final java.util.concurrent.atomic.AtomicBoolean sKillWatcherStarted =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /**
     * 错峰拉起间隔：多个目标需拉起时，相邻两个之间留 1.5 秒，避免保活名单多时
     * 同一瞬间一起拉起使低端机 CPU/IO 瞬时峰值叠加而卡顿。必须在后台线程调用。
     */
    private static final long LAUNCH_STAGGER_MS = 1_500L;

    // ==================================================================
    // D 功能（死后拉起）—— 防御层级
    //
    //   ★ 先把口径统一清楚。这段注释以前把「托底保活」也叫成「第一道」，
    //     与 UI 上的「第一防线 / 第二防线」撞名，结果看起来像有 4 道防线。
    //     实际不是，「防线」只指【模块自己】的两道：
    //
    //       普通目标：模块第一防线 → 模块第二防线            （2 道）
    //       常驻目标：系统重启（底座）+ 模块第一 → 第二防线   （2 道 + 1 个底座）
    //
    //     系统那边只有『一个』机制（AMS 发现常驻进程死了就把它重启），没有"两道"
    //     之分，所以不存在 4 道防线。
    //
    //   · 模块第一防线 · 死亡事件回调（快，依赖模块注入 system_server）：
    //       AMS.appDiedLocked / handleAppDiedLocked 是进程死亡的统一收口，
    //       1.5 秒后补拉（批量死亡时按 1.5 秒递增错峰）。把「死后多久能起来」
    //       从 ~20 秒压到 ~2 秒。ROM 改了死亡回调方法名就会哑火。
    //
    //   · 模块第二防线 · 轮询兜底（慢但绝不漏）：
    //       10 秒心跳扫目标是否还在跑，普通目标连续缺席 2 轮补拉（~20 秒），
    //       常驻目标缺席 1 轮就动手。第一防线哑火、或模块 App 进程被杀
    //       （Provider 不可达）时靠本道 + 配置缓存兜底，保证「总能拉起来」。
    //
    //   · 托底保活（不是防线，是底座；需 per-app 单独开启）：
    //       应用配置里勾选「常驻进程」后，markPersistent 把进程标为 persistent
    //       （-800 adj）。此后系统不让它被杀、被杀也会立刻重启，与模块是否存活无关。
    //       它比模块两道防线都更早介入；但打标是"运行期"动作，进程没跑起来时
    //       压根没有可标记的对象，所以模块仍要先把目标拉起来 —— 两道防线因此保留，
    //       作为系统机制没兜住时的补充。副作用：目标可能无法正常退出。
    //
    // 本注释块下方的「双防线」状态表，是模块两道防线共用的去重闸门
    // （sDeathAt / sRelaunchAt），谁先到谁拉，后到的自动跳过，
    // 不会出现「同一秒被拉两次」或「一次掉线记两次被杀」。
    // ==================================================================

    /**
     * 死亡后延迟多久补拉：进程刚死时系统还在收尾（清理 ActivityRecord / ServiceRecord /
     * binder 引用），立刻 startService 容易撞上「进程正在死亡」的中间态被拒。
     * 1.5 秒足够系统清理完，又比轮询的 20 秒快一个量级。
     */
    private static final long DEATH_RELAUNCH_DELAY_MS = 1_500L;

    /**
     * 死亡判定去重窗口（15 秒）：appDiedLocked 内部通常会转调 handleAppDiedLocked，
     * 同一次死亡会被两个钩子各通知一次；同时第二防线在 20 秒后才会判定掉线，
     * 取 15 秒既能吃掉双入口的毫秒级重复，又不会把第二防线的补拉给吞掉。
     */
    private static final long DEATH_DEDUP_MS = 15_000L;

    /**
     * 补拉冷却（8 秒）：同一个包两次实际执行补拉之间的最小间隔。
     * 两道防线都过这道闸——第一防线刚拉完，第二防线即使同轮判定掉线也不再重复拉。
     */
    private static final long RELAUNCH_COOLDOWN_MS = 8_000L;

    /** 每个包最近一次被判定「死亡/掉线」的时刻（两道防线共用，配 DEATH_DEDUP_MS 用）。 */
    private static final java.util.Map<String, Long> sDeathAt =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    /** 每个包最近一次实际执行补拉的时刻（两道防线共用，配 RELAUNCH_COOLDOWN_MS 用）。 */
    private static final java.util.Map<String, Long> sRelaunchAt =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    /** 每个包最近一次补拉【失败】的时刻，用于失败退避。 */
    private static final java.util.Map<String, Long> sRelaunchFailAt =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    /** 每个包连续补拉失败的次数（成功即清零），退避时长按它翻倍。 */
    private static final java.util.Map<String, Integer> sRelaunchFailCount =
            new java.util.concurrent.ConcurrentHashMap<String, Integer>();

    // ==================================================================
    // 「用户划后台卡片」埋点表 —— 被杀原因里最难判的一类
    // ==================================================================
    //
    // 【为什么 ProcessRecord 残留字段这条路走不通（实测实锤）】
    //   原设计指望划卡片时 AMS 会给 ProcessRecord 打上 mRemoved 标记，死亡回调据此
    //   反推「用户划卡片清除」。但 realme RMX3708 / Android 16 上打了诊断日志实测：
    //
    //     被杀诊断 idm.internet.download.manager.plus → 系统回收或应用自退:
    //       mRemoved=false mWasForceStopped=false mKillTime=0
    //       mStartUptime=54994 mDyingPid=0 mPid=0 mPersistent=false
    //
    //   字段全为 false / 0 —— 两个原因叠加：
    //     ① 死亡回调拿到的 ProcessRecord 已经是「摘链后」的实例，pid/状态早被清空，
    //        压根没有可读的现场；
    //     ② 本机 AMS 的 removeTask 路径本就不设 mRemoved（它只在 removeProcessLocked
    //        传 hostedBySystem=true 这类少数路径上置位）。
    //   也就是说，这条路的失败不是「字段名猜错了」，而是「这个信号在本机根本不存在」。
    //
    // 【改用主动埋点】
    //   既然死亡现场没有线索，就在「用户划卡片」这个【动作发生的那一刻】记下来：
    //   钩 AMS / ActivityTaskManagerService 里所有「移除任务」的收口方法，把被划掉的
    //   任务根包名 + 时间戳登记到 sTaskRemovedAt。死亡回调按包名一查即可精准定性。
    //
    //   为什么钩 ATMS 而不是 SystemUI：SystemUI 那个「全部清除」按钮最终也要调回
    //   AMS.removeTask 之类的 Binder 接口，钩 AMS 侧既是收口、又不依赖 SystemUI 实现
    //   （各家 ROM 的 SystemUI 改得面目全非，钩它等于自找不兼容）。
    //   单张卡片被划走则由 Launcher/SystemUI 调 removeTaskById，同样落在 AMS 侧。
    //
    // 【候选方法名为什么给这么多】removeTask 在 Android 版本间反复改名/加减锁后缀：
    //   removeTask → removeTaskById → cleanUpRemovedTaskLocked → cleanupRemovedTaskLocked
    //   这里全部列出，命中任意一个即可（多挂一个的代价只是多一次登记，无害）。

    /** 「移除任务」相关方法挂在哪些类上（ATMS / AMS / RecentTasks 三处都有收口）。 */
    private static final String ATMS_FRAMEWORK = "com.android.server.wm.ActivityTaskManagerService";
    private static final String RECENT_TASKS = "com.android.server.wm.RecentTasks";

    /**
     * 被移除任务的时间表（根包名 → 移除时刻）。
     *
     * <p>值带时间戳，死亡回调只认 {@link #TASK_REMOVED_TTL_MS} 内的登记：
     * 划卡片到进程真正死掉通常只隔几十毫秒到一两秒，给 15 秒余量足够覆盖
     * 「进程还得先把 onDestroy 跑完」的情况，又短到不会把「十分钟前划掉的卡」误当成本次原因。
     */
    private static final java.util.Map<String, Long> sTaskRemovedAt =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    /**
     * 划卡片埋点的有效期：超出即视为历史残留，不再作为本次被杀的原因依据。
     * 取 15 秒与 {@link #DEATH_DEDUP_MS} 同口径 —— 同一次死亡只可能被判定一次。
     */
    private static final long TASK_REMOVED_TTL_MS = 15_000L;

    /** 划卡片登记表的容量上限，防长时间运行后被大量一次性任务撑爆。 */
    private static final int TASK_REMOVED_CAP = 128;

    /**
     * 登记一次「用户移除任务」（划卡片 / 关掉最近任务）。
     *
     * @param pkg 被移除任务的根包名
     */
    private static void noteTaskRemoved(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.indexOf('.') <= 0) return;
        long now = System.currentTimeMillis();
        sTaskRemovedAt.put(pkg, now);
        // 容量兜底：超限时把最旧的一批清掉（划卡片是低频动作，走到这里说明表被灌过）
        if (sTaskRemovedAt.size() > TASK_REMOVED_CAP) {
            java.util.Iterator<java.util.Map.Entry<String, Long>> it = sTaskRemovedAt.entrySet().iterator();
            while (it.hasNext() && sTaskRemovedAt.size() > TASK_REMOVED_CAP / 2) {
                java.util.Map.Entry<String, Long> e = it.next();
                if (now - e.getValue() > TASK_REMOVED_TTL_MS) it.remove();
            }
        }
    }

    /**
     * 读出「本次死亡是否紧跟在一次任务移除之后」，是则返回标签，否则空串。
     *
     * <p>登记命中即消费掉（remove），避免同一条记录被后续别的死亡事件重复认领。
     */
    private static String readTaskRemovedReason(String pkg) {
        if (pkg == null) return "";
        Long at = sTaskRemovedAt.get(pkg);
        if (at == null) return "";
        long age = System.currentTimeMillis() - at;
        sTaskRemovedAt.remove(pkg);
        if (age <= TASK_REMOVED_TTL_MS) {
            log("被杀原因·划卡片埋点命中: " + pkg + "（任务移除于 " + age + "ms 前）");
            return "用户划卡片清除";
        }
        return "";
    }

    // ==================================================================
    // 【042 · 只观察】killLocked 调用链埋点
    //
    // 041 的划卡片埋点（钩 removeTask 系 / RecentTasks.remove）在本机【全部挂上了
    // 但一次都没被调用】—— 自检三行 taskRemoved / taskRemovedAtms / taskRemovedRecent
    // 均为「钩子已安装」，可实机划卡片后日志里连一条「划卡片埋点命中」都没有。
    // 说明本机划卡片根本不经那几个方法，041 选错了收口点。
    //
    // 【为什么改钩 killLocked】
    //   ProcessRecord.killLocked 是【所有】进程死亡的真正收口 —— 划卡片、强停、
    //   LMK 回收、应用自退、模块自己杀，最终都要走到这里。它不像 removeTask 那样
    //   只在"任务栈"语义上出现，而是每个 PID 消失前必经的一站。
    //   本机实测签名为：
    //     killLocked(String reason, String description, int pid, int uid,
    //                boolean evenPersistent, boolean setGroup)
    //   第一个参数就是【系统自己给出的杀进程原因字符串】，这正是 041 苦苦反推不到的东西。
    //
    // 【为什么是"只观察"】
    //   本版只记账 + 打日志，【不改判定链、不改标签】。原因：
    //     ① 041 已经把判定链改成 ②划卡片埋点 → ③残留字段，贸然再插一条会打乱既有顺序，
    //        可能把已经判对的场景（如「用户手动关闭（进程页）」）弄坏；
    //     ② reason 的真实字符串在没拿到实测值之前只能靠猜。041 就是"按改名前缀硬凑
    //        候选名"栽的跟头，同一个坑不能踩第二次。
    //   先拿实测数据，再决定映射表怎么写。
    //
    // 【与既有 killLocked 钩子的关系】
    //   模块已经钩着 ProcessRecord.killLocked（第 4 个钩子，供"强力模式"拦截）。
    //   那个钩子在未开强力模式时直接 return UNHANDLE 走人，什么都没记。
    //   本埋点插在它【最前面、且早于 isAggressive() 判断】，因此无论开不开强力模式
    //   都能采集到。同一次 killLocked 只记一次，不额外挂钩点。
    // ==================================================================

    /**
     * 杀进程现场登记表：包名 → 一次 {@link KillTrail}（reason + 调用栈 + 时刻）。
     *
     * <p>与 {@link #sTaskRemovedAt} 同风格：值带时间戳、有容量上限、死亡回调按包名查询。
     */
    private static final java.util.Map<String, KillTrail> sKillTrail =
            new java.util.concurrent.ConcurrentHashMap<String, KillTrail>();

    /** killLocked 现场的有效期，与 {@link #DEATH_DEDUP_MS} 同口径（同一次死亡只判一次）。 */
    private static final long KILL_TRAIL_TTL_MS = 15_000L;

    /** 登记表容量上限，防长时间运行被大量一次性进程撑爆。 */
    private static final int KILL_TRAIL_CAP = 128;

    /**
     * 一次 killLocked 的现场快照。
     *
     * <p>{@code frames} 只保留前几帧：调用栈的头部就足以区分是 AMS 的哪条路径
     * （removeTask / forceStopPackage / 模块自己），抓全栈既慢又沒有额外价值。
     */
    private static final class KillTrail {
        final String reason;
        final String description;
        final String frames;
        final long at;

        KillTrail(String reason, String description, String frames, long at) {
            this.reason = reason;
            this.description = description;
            this.frames = frames;
            this.at = at;
        }
    }

    /**
     * 登记一次 killLocked 现场（042 只观察，不参与判定）。
     *
     * <p>热路径约束：killLocked 跑在 system_server 的 AMS 锁内，这里【绝不能做 IPC】，
     * 只读入参与栈、写内存表；日志按包名节流，避免批量杀进程时刷屏拖慢系统。
     *
     * @param proc 被杀的 ProcessRecord（用于取包名）
     * @param args killLocked 的原始参数
     */
    private static void noteKillTrail(Object proc, Object[] args) {
        try {
            String pkg = getPackageName(proc);
            if (pkg == null || pkg.isEmpty()) return;

            // 参数按位置取是有风险的，但 killLocked 的签名在本机已实测确认；
            // 且这里【只读不写】—— 读错最多是日志里的 reason 不准，不会改坏系统行为。
            // 这正是把它做成"只观察"的价值：先看清真实内容，再谈怎么用。
            String reason = "";
            String description = "";
            if (args != null && args.length > 0 && args[0] instanceof String) {
                reason = (String) args[0];
            }
            if (args != null && args.length > 1 && args[1] instanceof String) {
                description = (String) args[1];
            }

            sKillTrail.put(pkg, new KillTrail(reason, description, topFrames(), System.currentTimeMillis()));
            // 容量兜底：超限时把过期的清掉
            if (sKillTrail.size() > KILL_TRAIL_CAP) {
                long now = System.currentTimeMillis();
                java.util.Iterator<java.util.Map.Entry<String, KillTrail>> it = sKillTrail.entrySet().iterator();
                while (it.hasNext() && sKillTrail.size() > KILL_TRAIL_CAP / 2) {
                    java.util.Map.Entry<String, KillTrail> e = it.next();
                    if (now - e.getValue().at > KILL_TRAIL_TTL_MS) it.remove();
                }
            }

            // 日志按包名节流 3 秒：批量 kill（如系统批量清理）时避免刷屏
            long now = System.currentTimeMillis();
            Long last = sLastKillTrailLog.get(pkg);
            if (last == null || now - last > 3000) {
                sLastKillTrailLog.put(pkg, now);
                log("被杀现场·killLocked: " + pkg
                        + " | reason=" + (reason.isEmpty() ? "(空)" : reason)
                        + " | desc=" + (description.isEmpty() ? "(空)" : description)
                        + " | 栈=" + topFrames());
            }
        } catch (Throwable t) {
            // 埋点绝不能影响系统主流程
            logWarn("被杀现场·killLocked 埋点异常: " + t);
        }
    }

    /** 抓调用栈前若干帧，用于判断是谁调的 killLocked。 */
    private static String topFrames() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            StringBuilder sb = new StringBuilder();
            // 跳过本方法 + noteKillTrail + 钩子包装，从真正的调用者开始
            int taken = 0;
            for (StackTraceElement e : st) {
                String cn = e.getClassName();
                if (cn.endsWith("KeepAliveHooks")) continue;
                if (taken > 0) sb.append(" <- ");
                sb.append(e.getClassName()).append('.').append(e.getMethodName());
                if (++taken >= KILL_TRAIL_FRAMES) break;
            }
            return sb.length() == 0 ? "(无)" : sb.toString();
        } catch (Throwable t) {
            return "(栈不可用)";
        }
    }

    /** 抓几帧调用栈就够定位路径了，再多只是浪费。 */
    private static final int KILL_TRAIL_FRAMES = 4;

    /** killLocked 埋点日志的节流表（包名 → 上次打印时刻）。 */
    private static final java.util.Map<String, Long> sLastKillTrailLog =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    /**
     * 读出并消费一条 killLocked 现场（供后续版本接判定链用；042 暂未接入判定）。
     *
     * <p>现在只被日志/自检侧调用，不改变任何标签结果 —— 这是"只观察"的硬约束。
     *
     * <p>⚠ 静态排查会把它标为「零调用死代码」，<b>但它是有意保留的</b>：
     * 042 起在 {@code ProcessRecord.killLocked} 里持续采集「被杀现场」（谁杀的、
     * 当时的 adj/persistent 状态），当前版本只做只读展示（{@code sKillTrail.get()}），
     * 本方法是配套的「读并消费」入口，留给后续把现场接进判定链时使用。
     * 删掉它等于丢弃已采集数据的使用路径。除非确定不再接判定链，否则不要清理。
     */
    private static KillTrail takeKillTrail(String pkg) {
        if (pkg == null) return null;
        KillTrail t = sKillTrail.remove(pkg);
        if (t == null) return null;
        if (System.currentTimeMillis() - t.at > KILL_TRAIL_TTL_MS) return null;
        return t;
    }

    /**
     * 「托底保活」待确认表：常驻目标被判定死亡时登记（值=死亡时刻）；模块若自己动手把它
     * 拉起就撤销登记 —— 登记还在、而进程又活了，就说明是<b>系统</b> persistent 机制把它重启的，
     * 功劳归系统，时间线记一条「托底保活」。
     *
     * <p>为什么需要它：模块只在「自己动手拉起」时才写事件，系统 persistent 重启进程时模块
     * 并不知情，光凭这一点分不清「系统拉的」还是「模块拉的」。以前干脆按「目标是否开了常驻」
     * 来贴前缀，于是把模块的功劳也写成了「托底保活 · 第二防线」（系统根本没有第二防线，
     * 语义自相矛盾）。本表把判定换成【这次到底谁动的手】。
     */
    private static final java.util.Map<String, Long> sSysClaimPending =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    /** 待确认有效期：常驻目标死亡后超过这么久仍未复活，登记作废（避免长期不回时误判）。 */
    private static final long SYS_CLAIM_TTL_MS = 120_000L;

    /** 失败退避上限（5 分钟）：再久就不合适了，否则应用恢复了也拉不回来。 */
    private static final long RELAUNCH_FAIL_MAX_BACKOFF_MS = 5 * 60 * 1000L;

    /**
     * 本次开机里「曾经跑起来过」的目标。
     * 用静态而非扫描循环内的局部集合：第一防线跑在死亡回调线程，必须能读到同一份。
     * 用途不变——区分「运行中掉线」（记被杀）与「开机后压根没起来过」（不记被杀）。
     */
    private static final java.util.Set<String> sEverSeen =
            java.util.Collections.synchronizedSet(new HashSet<String>());

    /** AMS 的 mContext 缓存：死亡回调在任何线程都可能发生，不能每次都靠反射去摸。 */
    private static volatile Context sSystemContext;

    /**
     * 本次开机累计的守护动作计数：包名 → {被杀次数, 拉起次数}。
     * system_server 内存态；跨开机的累加由 App 侧（SurvivalData）按开机次数合并落盘。
     * 口径：「被杀」= 目标本次开机跑起来过之后又掉线（开机后本就没起来的补拉不计）；
     * 「拉起」= silentStart 成功（开机种子拉起与掉线补拉同口径）。
     */
    /**
     * 守护动作计数器：{@code [0]} = 被杀次数，{@code [1]} = 拉起次数。
     *
     * <p>用 {@code AtomicLongArray} 而非 {@code long[]}：这两个计数由**多个线程**并发累加
     * （钩子回调线程、第一防线死亡回调、第二防线轮询、开机种子），而
     * {@code statOf(pkg)[0]++} 是「读 → 加 → 写」三步复合操作，不加保护会丢计数 ——
     * 内存紧张时多目标被集中回收，正是并发最高的时刻，统计却在那时最不准。
     */
    private static final java.util.Map<String, java.util.concurrent.atomic.AtomicLongArray>
            sGuardStats = new java.util.concurrent.ConcurrentHashMap<>();

    private static java.util.concurrent.atomic.AtomicLongArray statOf(String pkg) {
        java.util.concurrent.atomic.AtomicLongArray s = sGuardStats.get(pkg);
        if (s == null) {
            // putIfAbsent：两个线程同时首次见到同一包名时，只留一个实例，
            // 否则后写者会把先写者刚加的计数丢掉。
            java.util.concurrent.atomic.AtomicLongArray fresh =
                    new java.util.concurrent.atomic.AtomicLongArray(2);
            java.util.concurrent.atomic.AtomicLongArray prev = sGuardStats.putIfAbsent(pkg, fresh);
            s = (prev != null) ? prev : fresh;
        }
        return s;
    }

    /** 累加「被杀」计数。 */
    private static void bumpKill(String pkg) {
        statOf(pkg).incrementAndGet(0);
    }

    /** 累加「拉起」计数。 */
    private static void bumpRelaunch(String pkg) {
        statOf(pkg).incrementAndGet(1);
    }

    /**
     * 守护事件时间线：本次开机累计的带时间戳事件，App 侧主页按时间倒序展示。
     * 与 sGuardStats 同源（都是钩子埋点），但多带一个墙钟时间戳，便于「何时被杀/拉起」。
     * 内存态、封顶 {@link #EVT_CAP} 条，跨开机自然清空（system_server 重启即丢）。
     */
    private static final int EVT_CAP = 100;
    private static final int EVT_KILL = 0;       // 被杀（跑起来过又掉线）
    private static final int EVT_RELAUNCH = 1;   // 掉线后被静默补拉
    private static final int EVT_BOOT = 2;       // 开机后种子拉起
    private static final java.util.List<GuardEvent> sGuardEvents =
            java.util.Collections.synchronizedList(new java.util.ArrayList<GuardEvent>());

    /** 单条守护事件（system_server 侧传输用，App 侧有同名结构）。 */
    private static final class GuardEvent {
        final String pkg;
        final int type;   // EVT_KILL / EVT_RELAUNCH / EVT_BOOT
        final long time;  // 墙钟毫秒
        final String who; // 触发方（拉起来源）：空白=被杀（无触发方）；其余形如
                         // 「模块拉活 · 第一防线」「模块拉活 · 第二防线」「模块拉活 · 开机种子」
                         // 「托底保活」。
                         // 前缀就是【谁动的手】：模块主动补拉/开机种子=「模块拉活」，
                         // 系统 persistent 重启=「托底保活」（系统只有一道机制，故不带防线后缀）。
                         // 写入时间线，用户可在主页一眼看出「这次是谁把应用拉起来的」。
        final String reason; // 被杀原因标签（仅 EVT_KILL 有值）：用户划卡片清除 / 用户强制停止 /
                             // 系统低内存回收 / 系统清理后台 / 系统ANR清理 / 应用自行退出 / 其他原因。
                             // 拉起/开机事件 reason 留空。
        GuardEvent(String pkg, int type, long time, String who, String reason) {
            this.pkg = pkg;
            this.type = type;
            this.time = time;
            this.who = who;
            this.reason = reason;
        }
    }

    /**
     * 追加一条守护事件（封顶 EVT_CAP，超出丢最旧的）。who 记录触发方，时间线据此标注「谁拉的」。
     *
     * <p>整个「追加 + 裁剪」必须在一把锁里完成。虽然 {@code sGuardEvents} 是
     * {@code synchronizedList}，但单次 add 的同步**保护不了复合操作**：
     * 两个线程（第一防线死亡回调 / 第二防线轮询）同时进来时，
     * {@code while (size() > EVT_CAP) remove(0)} 各自看到的 size 可能过期，
     * 一起去 remove 同一个下标。这里用显式锁把整段包起来。
     *
     * <p>同时，读取方（{@link #pushProcessReport}）也必须在**同一把锁内**取数组长度，
     * 否则「锁外读 size → 锁内迭代」会在并发 add 时数组越界。
     */
    private static void addGuardEvent(String pkg, int type, String who) {
        addGuardEvent(pkg, type, who, "");
    }

    /**
     * 写一条守护事件。
     *
     * @param who   拉起来源（前缀），被杀事件传空串
     * @param reason 被杀原因标签（如「用户划卡片清除」「系统低内存回收」），
     *              拉起/开机事件传空串（这些事件本来就不该有被杀原因）
     */
    private static void addGuardEvent(String pkg, int type, String who, String reason) {
        synchronized (sGuardEvents) {
            sGuardEvents.add(new GuardEvent(pkg, type, System.currentTimeMillis(),
                    who != null ? who : "", reason != null ? reason : ""));
            while (sGuardEvents.size() > EVT_CAP) sGuardEvents.remove(0);
        }
    }

    /**
     * 取一份事件快照（已是 {@code String[]}，调用方可直接放进 Bundle）。
     *
     * <p>要点：长度计算与填充**都在锁内**，彻底消除「锁外读 size、锁内迭代」的越界窗口。
     * 这个方法取代了原来在 {@code pushProcessReport} 里裸写的那段 —— 那段一旦抛出
     * {@code ArrayIndexOutOfBoundsException}，会被外层空 catch 吞掉，
     * 导致该轮的心跳、目标存活判定、第二防线补拉**全部被跳过且日志无痕**。
     */
    private static String[] snapshotGuardEvents() {
        synchronized (sGuardEvents) {
            String[] ev = new String[sGuardEvents.size()];
            int k = 0;
            for (GuardEvent e : sGuardEvents) {
                ev[k++] = e.pkg + "|" + e.type + "|" + e.time + "|" + e.who + "|" + e.reason;
            }
            return ev;
        }
    }

    /**
     * 周期把「当前在跑的应用进程 → 启动时刻(tick)」推给模块 App。
     *
     * App 进程读别家的 /proc 会被 hidepid=2 挡掉、AMS 的可见性也被裁剪，
     * 它自己算不出真实的保活时长；而 system_server 这里没有这些限制。
     * 所以时长统计的权威数据源放在本侧，经 ConfigProvider 推回。
     */
    private static void startProcReporter(final Context ctx) {
        if (!sProcReporterStarted.compareAndSet(false, true)) return;
        startKillWatcher(ctx);
        Prefs.runBackground(() -> {
            final android.net.Uri uri = android.net.Uri.parse(
                    "content://" + BuildConfig.APPLICATION_ID + ".config");
            // Provider 只在 App 进程活着时可达；失败就等下一轮，App 被杀也能自动恢复
            final java.util.Map<String, Integer> miss = new java.util.HashMap<>();
            // 注：「曾经跑起来过」的目标集合已提升为静态 sEverSeen——第一防线跑在死亡
            // 回调线程，必须和这里看到同一份，否则它永远判断不出「掉线」还是「没起来过」。
            // 进程页是否在看（决定是否扫全量 /proc）；声明在外层，供循环末尾的 sleep 使用
            boolean wanted = false;
            long loopSeq = 0L;
            long cfgMissLoops = 0L;   // 连续读不到配置的轮数，仅用于日志节流
            while (true) {
                try {
                    // 顺手拿一份配置：comm 兜底匹配要对着目标名单做，
                    // 静默补拉只看 enabled（autoStart 已不再参与该决策）；proc_wanted 决定是否扫全量进程
                    android.os.Bundle cfg =
                            ctx.getContentResolver().call(uri, "getConfig", null, null);
                    // 配置缓存兜底：模块 App 进程被杀时 Provider 不可达，cfg 为 null。
                    // 若此时按默认值走，enabled 会变 false，补拉就整个停摆——
                    // 而「模块自己被杀导致保活失效」恰恰是最该避免的情况。
                    // 所以一旦读到过配置就缓存下来，之后 Provider 断线期间沿用最后一份。
                    // （第一防线靠 Prefs 的内存 snapshot 天然有同样效果，这里给它对齐。）
                    if (cfg != null) {
                        sCfgEnabled = cfg.getBoolean("enabled", true);
                        sCfgAutoStart = cfg.getBoolean("auto_start", false);
                        ArrayList<String> tl = cfg.getStringArrayList("targets");
                        if (tl != null) {
                            sCfgTargets = new java.util.HashSet<>(tl);
                            // ★ 058 P0：把这份【每 10 秒刷新一次】的新鲜名单灌进 Prefs，
                            //   让判定链（isTarget）立刻用上。否则 sCfgTargets 只写不读，
                            //   判定仍走受 30 秒节流的快照 —— 改目标最长半分钟才生效。
                            //   注：空名单（用户取消了全部目标）是有效状态，必须照样灌入，
                            //   否则「取消勾选」这条最该即时生效的操作反而会被忽略。
                            Prefs p = sPrefs;
                            if (p != null) p.setLiveTargets(sCfgTargets);
                        } else {
                            // 拿不到名单（老版本 Provider 未推 / 字段缺失）→ 退回快照口径，
                            // 保持与修复前一致的行为，不引入新的不可预期状态。
                            Prefs p = sPrefs;
                            if (p != null) p.setLiveTargets(null);
                        }
                        // 「托底保活」名单：Provider 是按 per-pkg 键推的
                        // （KEY_PERSIST_PREFIX + 包名），这里扫一遍目标名单把它挑出来。
                        java.util.Set<String> ps = new java.util.HashSet<>();
                        // 「托底保活 · 核心级」名单：读 KEY_CORE_PREFIX（core_ + 包名）。
                        java.util.Set<String> cs = new java.util.HashSet<>();
                        if (tl != null) {
                            for (String p : tl) {
                                if (cfg.getBoolean(Prefs.KEY_PERSIST_PREFIX + p, false)) ps.add(p);
                                if (cfg.getBoolean(Prefs.KEY_CORE_PREFIX + p, false)) cs.add(p);
                            }
                        }
                        sCfgPersist = ps;
                        sCfgCore = cs;
                        sCfgValid = true;
                    } else if (sCfgValid) {
                        cfgMissLoops++;
                        if (cfgMissLoops == 1 || cfgMissLoops % 60 == 0) {
                            log("配置通道不可达（模块 App 进程可能被杀），"
                                    + "沿用最后一份缓存配置继续补拉；已连续 " + cfgMissLoops + " 轮");
                        }
                    }
                    boolean enabled = sCfgEnabled;
                    // 注：autoStart 已不再参与补拉决策（它只作用于开机种子 startTargets）。
                    // 这里不再取它的局部变量，避免留下一个「看着像在用、其实没人读」的死变量。
                    long procWanted = cfg != null ? cfg.getLong("proc_wanted", 0L) : 0L;
                    wanted = procWanted > 0
                            && (System.currentTimeMillis() - procWanted) < PROC_LIST_WANTED_MS;
                    loopSeq++;
                    log("[进程页诊断] 循环#" + loopSeq
                            + " cfg=" + (cfg == null ? "null" : "ok")
                            + " proc_wanted=" + procWanted
                            + " wanted=" + wanted);
                    java.util.Set<String> targets = new HashSet<>();
                    if (cfg != null && cfg.getStringArrayList("targets") != null) {
                        targets.addAll(cfg.getStringArrayList("targets"));
                    } else if (sCfgValid) {
                        targets.addAll(sCfgTargets);   // 断线期间用缓存的目标名单
                    }

                    java.util.Map<String, Long> running = scanRunningApps(targets);
                    sEverSeen.addAll(running.keySet());
                    // ★ 「托底保活」确认：常驻目标死亡时登记过待确认，若模块始终没动手
                    //   而进程已复活，即为系统 persistent 重启（系统只有一道机制，故不带
                    //   「· 防线」后缀）。超期未复活则作废登记，避免长期不回时误判。
                    if (!sSysClaimPending.isEmpty()) {
                        long tNow = System.currentTimeMillis();
                        java.util.Iterator<java.util.Map.Entry<String, Long>> itClaim =
                                sSysClaimPending.entrySet().iterator();
                        while (itClaim.hasNext()) {
                            java.util.Map.Entry<String, Long> ce = itClaim.next();
                            long age = tNow - ce.getValue();
                            if (age > SYS_CLAIM_TTL_MS) { itClaim.remove(); continue; }
                            if (running.containsKey(ce.getKey())) {
                                itClaim.remove();
                                bumpRelaunch(ce.getKey());   // 也算一次「拉起」
                                // 双保险：占位 sRelaunchAt，让第一防线那条「已 postDelayed
                                // 排队中」的补拉任务在冷却期内被 tryRelaunch 直接跳过 ——
                                // 否则系统刚拉回、1.5 秒后模块又拉一次（21:41:55/56 那对）。
                                sRelaunchAt.put(ce.getKey(), tNow);
                                addGuardEvent(ce.getKey(), EVT_RELAUNCH, "系统重启");
                                log("托底保活(系统 persistent 重启): " + ce.getKey()
                                        + "（死亡后 " + age + "ms 由系统拉起）");
                            }
                        }
                    }
                    android.os.Bundle b = new android.os.Bundle();
                    String[] arr = new String[running.size()];
                    int i = 0;
                    for (java.util.Map.Entry<String, Long> e : running.entrySet()) {
                        arr[i++] = e.getKey() + "|" + e.getValue();
                    }
                    b.putStringArray("procs", arr);
                    // 进程页在看时才扫全量 /proc 并回传（几百进程 + 逐个读 status 较贵，
                    // 不在看时完全不扫，零额外开销）
                    if (wanted) {
                        // ★ 045 提速：全量扫描原本【串行跑在这一轮里】——几百个进程 × 逐个读
                        //   cmdline/stat/status/oom_score_adj ≈ 上千次文件 IO，耗时直接叠加到本轮，
                        //   把同循环的补拉、死亡检测、守护事件推回一起压后（sleep 3 秒是固定值，
                        //   于是「一轮 = 3 秒 + 扫描耗时」，上报被迫变慢）。用户实测的
                        //   「守护事件和进程页信息都读得慢」根因在此。
                        //   改为：扫描挪到独立线程（见 startProcScanner），这里只取缓存上报，
                        //   上报节奏恢复稳定的 3 秒，进程数据由扫描线程按自己的节奏刷新。
                        sProcWanted = true;
                        sProcWantedAtMs = procWanted;
                        startProcScannerOnce();
                        String[] all = sAllProcsCache;
                        if (all != null && all.length > 0) b.putStringArray("allprocs", all);
                    } else {
                        sProcWanted = false;
                        // 离开进程页：丢掉缓存，避免下次打开先推一份过期数据
                        sAllProcsCache = null;
                    }
                    // 守护动作计数一并推回（值为本次开机的绝对累计，App 侧按开机次数合并）
                    if (!sGuardStats.isEmpty()) {
                        String[] st = new String[sGuardStats.size()];
                        int k = 0;
                        for (java.util.Map.Entry<String,
                                java.util.concurrent.atomic.AtomicLongArray> e
                                : sGuardStats.entrySet()) {
                            java.util.concurrent.atomic.AtomicLongArray v = e.getValue();
                            st[k++] = e.getKey() + "|" + v.get(0) + "|" + v.get(1);
                        }
                        b.putStringArray("stats", st);
                    }
                    // 守护事件时间线：全量镜像推回（App 主页倒序展示；空也推，便于重启后清空旧事件）
                    // 快照在锁内完成（见 snapshotGuardEvents 注释：避免并发 add 时越界）
                    b.putStringArray("events", snapshotGuardEvents());
                    // 钩子自检报告：每次上报都推一次（内容开机内不变，代价可忽略），
                    // App 侧落盘后可在「诊断」里展示「哪些能力在本机生效」。
                    String[] caps = hookStatusSnapshot();
                    if (caps.length > 0) b.putStringArray("caps", caps);
                    ctx.getContentResolver().call(uri, "reportProcs", null, b);

                    // 【D 功能 · 第二防线·轮询兜底】
                    //
                    // ★ 门控从 `enabled && autoStart` 放开为只看 `enabled`。
                    //   原门控有个致命缺陷：autoStart 的字面语义是「开机时要不要拉起」，
                    //   但它被当成了「一切补拉的总闸」。于是不开「开机自启」时：
                    //     · 开机后新勾选的目标永远不会被拉起（没有任何拉起路径）；
                    //     · 进程被杀后也不会被拉回 —— 保活的核心能力整个失效，
                    //       用户看到的却是「模块已激活」，极具误导性。
                    //   现在的语义分工清晰：
                    //     enabled   = 保活总开关（拦截 + 补拉），关了才全停；
                    //     autoStart = 只管「开机那一次」要不要主动拉起（在 startTargets 里）。
                    //
                    //   另：目标是「托底保活」时也走这条 —— persistent 只能在
                    //   updateOomAdjLocked 里对已存在的进程打标，进程不在跑就无法自举，
                    //   必须由这里先把它拉起来，打标链才会启动。
                    //
                    // 目标不在跑：连续缺席 2 轮（约 20 秒）后静默补拉一次。
                    // 只走服务/Provider 等无 UI 通道，绝不弹 Activity 打扰用户。
                    // 同轮可能有多个目标缺席（如内存紧张被批量回收），收集起来错峰补拉，
                    // 避免同一瞬间一起拉起把低端机卡爆。
                    //
                    // 它比第一防线慢，但覆盖第一防线失效的全部场景：ROM 改了死亡回调的
                    // 方法名、进程走了厂商自研的回收路径、甚至这个钩子整体没装上——只要
                    // 「扫一遍发现目标不在」这件事还能做，兜底就在。两道防线通过
                    // noteDeath / tryRelaunch 里的去重表互相让路，不会重复拉、重复计数。
                    if (enabled) {
                        List<String> toRelaunch = new ArrayList<>();
                        for (String t : targets) {
                            if (running.containsKey(t)) {
                                miss.remove(t);
                                continue;
                            }
                            // 「托底保活」目标用更短的缺席阈值（1 轮）就动手：
                            // 它的前提是「进程中必须跑起来才能被打上 persistent 标」，
                            // 所以这类目标天生需要一次自举拉起 —— 等 2 轮纯属白等。
                            // 核心目标同样把进程标成 persistent，也需先自举，故一并 1 轮。
                            // 其余目标保持 2 轮，避免把偶发的瞬时不可见误判成死亡。
                            final int need = (sCfgPersist.contains(t) || sCfgCore.contains(t)) ? 1 : 2;
                            int n = miss.containsKey(t) ? miss.get(t) + 1 : 1;
                        if (n >= need) {
                            miss.put(t, 0);
                            // 记账去重：若第一防线在 15 秒内已判过一次死亡（并已安排补拉），
                            // noteDeath 返回 false，这里就不再重复记一次「被杀」。
                            // 轮询缺席场景进程已不在运行列表，无 proc 可读被杀原因，传 null。
                            noteDeath(t, null);
                            toRelaunch.add(t);
                        } else {
                                miss.put(t, n);
                            }
                        }
                        if (!toRelaunch.isEmpty()) {
                            silentStartStaggered(ctx, toRelaunch, false);
                        }
                    }
                } catch (Throwable t) {
                    // ★ 这一轮整轮失败：心跳上报、目标存活判定、第二防线补拉全部被跳过。
                    // 以前这里是空 catch —— 一旦有越界之类的异常，日志一个字都不留，
                    // 用户只看到「进程页突然不刷新 / 该拉回来的没拉回来」，无从排查。
                    // 不静默，但也不能每分钟刷屏：用一次性告警记录首个异常。
                    logWarnOnce("心跳循环本轮异常，已跳过本轮（存活判定与补拉均未执行）: " + t);
                }
                try {
                    // 进程页在看时加快到 3 秒一推，列表才「活」；否则维持 10 秒
                    Thread.sleep(wanted ? PROC_LIST_INTERVAL_MS : PROC_REPORT_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    /**
     * 杀进程指令通道（App → system_server）。
     *
     * 反向通道的取舍：App 进程无法直接把命令发给 system_server（只有 system_server 主动
     * 来查 App 的 ContentProvider），所以「杀进程」走这条轮询：App 把要杀的 pid 送进
     * {@code ConfigProvider} 的进程内队列，本线程每秒经 Binder 调一次
     * {@code takeKill} 把队列整体取走（出队即消费，不丢不重），
     * 取到的 pid 在这里真正 SIGKILL。system_server 是 uid 1000 带 CAP_KILL，能杀应用进程。
     *
     * <p>注意轮询间隔是 1 秒，但 kill 指令到达 Provider 队列是**立即**的 ——
     * 只是要等下一轮轮询才被取走。所以连点多个「杀进程」不会互相覆盖，
     * 会在同一轮里被一并取走执行。
     */
    private static void startKillWatcher(final Context ctx) {
        if (!sKillWatcherStarted.compareAndSet(false, true)) return;
        Prefs.runBackground(() -> {
            final android.net.Uri uri = android.net.Uri.parse(
                    "content://" + BuildConfig.APPLICATION_ID + ".config");
            while (true) {
                try {
                    android.os.Bundle b = ctx.getContentResolver().call(uri, "takeKill", null, null);
                    if (b != null) {
                        String[] pids = b.getStringArray("pids");
                        if (pids != null) {
                            for (String s : pids) {
                                if (s == null) continue;
                                try {
                                    killPid(Integer.parseInt(s.trim()));
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // App 未启动/被杀是常态，静默等下一轮
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    /**
     * 「模块自己动手杀的 pid」登记表（pid → 杀的时刻）。
     *
     * <p>用于把「用户在进程页点杀进程」这一动作和其他死亡原因区分开：进程死亡回调
     * 拿到的 {@code ProcessRecord} 上不会有 mWasForceStopped / mRemoved 标记（那不是
     * 用户划卡片、也不是强制停止），若不登记就只能落到「系统或应用自行结束」这个
     * 含混兜底里。登记后死亡回调按 pid 命中 → 精确标「用户手动关闭（进程页）」。
     * 值带时间戳，只认近期（60 秒内）的登记，防 pid 复用误判。
     */
    private static final java.util.Map<Integer, Long> sSelfKilledPid =
            new java.util.concurrent.ConcurrentHashMap<Integer, Long>();

    /**
     * 「模块自己动手杀的包名」登记表（包名 → 杀的时刻）。
     *
     * <p>★ 为什么 pid 级登记不够：实测（realme Android 16）里模块 SIGKILL 目标后，
     * 死亡回调未必能带回可用的 ProcessRecord.pid（进程已死、字段被清），
     * 于是 pid 匹配落空、标签退化成含糊的「系统回收或应用自退」，与用户划卡片无法区分。
     * 而死亡回调里<b>包名一定拿得到</b>（本模块的目标判定就用它），所以在 killPid 时
     * 顺手按 cmdline 反查包名一起登记，死亡回调优先用包名匹配 —— 稳得多。
     * 同样只认 60 秒内的近期登记，防复用误判。
     */
    private static final java.util.Map<String, Long> sSelfKilledPkg =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    /** 真正杀进程：先 Process.killProcess（SIGKILL），失败回退 libcore Os.kill。 */
    private static void killPid(int pid) {
        if (pid <= 2) return;   // init / kthreadd 之类，杀了手机就崩
        // 杀之前再核对进程名，拦掉关键系统进程，避免用户手滑把手机干停机
        String name = readCmdlineQuiet(pid);
        if (name == null || name.isEmpty()) name = readCommQuiet(pid);
        if (name != null) {
            String lc = name.toLowerCase();
            if (lc.contains("zygote") || lc.contains("system_server")
                    || lc.equals("init") || lc.equals("ueventd") || lc.equals("logd")
                    || lc.equals("servicemanager") || lc.equals("surfaceflinger")
                    || name.startsWith("[")) {
                logWarn("拒绝杀死受保护进程: pid=" + pid + " name=" + name);
                return;
            }
        }
        // 登记「模块自己杀的」，供死亡回调区分被杀原因。
        // 双登记：pid 级（精确匹配）+ 包名级（pid 读不回来时兜底，死亡回调拿包名必成功）。
        long kNow = System.currentTimeMillis();
        sSelfKilledPid.put(pid, kNow);
        if (name != null && !name.isEmpty()) {
            int colon = name.indexOf(':');
            String kpkg = colon > 0 ? name.substring(0, colon) : name;
            if (kpkg.indexOf('.') > 0) sSelfKilledPkg.put(kpkg, kNow);
        }
        // 顺手清理过期登记，防表无界增长
        if (sSelfKilledPid.size() > 64) {
            java.util.Iterator<java.util.Map.Entry<Integer, Long>> it = sSelfKilledPid.entrySet().iterator();
            while (it.hasNext()) {
                if (kNow - it.next().getValue() > 60_000L) it.remove();
            }
        }
        if (sSelfKilledPkg.size() > 64) {
            java.util.Iterator<java.util.Map.Entry<String, Long>> it = sSelfKilledPkg.entrySet().iterator();
            while (it.hasNext()) {
                if (kNow - it.next().getValue() > 60_000L) it.remove();
            }
        }
        try {
            android.os.Process.killProcess(pid);
            log("已发送 SIGKILL: pid=" + pid + (name != null ? " (" + name + ")" : ""));
        } catch (Throwable t1) {
            try {
                android.system.Os.kill(pid, android.system.OsConstants.SIGKILL);
                log("已发送 SIGKILL(Os.kill): pid=" + pid);
            } catch (Throwable t2) {
                logWarn("杀死进程失败: pid=" + pid + " " + t2);
            }
        }
    }

    /**
     * 扫一遍 /proc，收集所有应用进程的启动 tick，返回 包名 → tick（相对开机）。
     *
     * 主进程（cmdline 与包名完全一致）优先：子进程重启不该重置主进程的保活时长。
     * cmdline 被置空的应用（部分 ROM 会对「锁定/隐藏」的应用抹掉 cmdline 防跟踪），
     * 用 /proc/&lt;pid&gt;/stat 里的 comm 字段兜底 —— comm 只有 15 字节，长包名会被截断，
     * 所以只对配置里的目标名单做匹配，避免把无关进程认错。
     */
    private static java.util.Map<String, Long> scanRunningApps(java.util.Set<String> targets) {
        java.util.Map<String, Long> main = new java.util.HashMap<>();
        java.util.Map<String, Long> child = new java.util.HashMap<>();
        // 没有保活目标时整个扫描毫无意义：本方法每 10 秒被调一次，遍历 /proc 下几百个
        // 进程目录并逐个读 /proc/<pid>/cmdline，而它的产出只服务于保活目标
        // （消费端 SurvivalData 全是 rpt.map.get(pkg) / containsKey(pkg) 这种按名单点查）。
        // 名单为空还照扫，就是每 10 秒白读几百个文件、持续打断 CPU 深度休眠——
        // 这正是「什么都没开也耗电」的来源。直接跳过，省掉这整段开销。
        if (targets == null || targets.isEmpty()) return main;
        File[] dirs = new File("/proc").listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                if (!d.isDirectory()) continue;
                String name = d.getName();
                boolean numeric = !name.isEmpty();
                for (int i = 0; i < name.length() && numeric; i++) {
                    char ch = name.charAt(i);
                    if (ch < '0' || ch > '9') numeric = false;
                }
                if (!numeric) continue;
                int pid = Integer.parseInt(name);

                String cmd = readCmdlineQuiet(pid);
                String pkg = null;
                boolean isMain = false;
                if (cmd != null && !cmd.isEmpty()) {
                    int colon = cmd.indexOf(':');
                    pkg = colon > 0 ? cmd.substring(0, colon) : cmd;
                    isMain = colon <= 0;
                } else {
                    // cmdline 被清空（部分 ROM 对「锁定/隐藏」应用抹掉 cmdline 防跟踪），
                    // 退而用 /proc/<pid>/stat 的 comm 字段兜底。comm 只有 15 字节
                    // （TASK_COMM_LEN-1），内核把进程名截断到 15 字节——应用主进程的
                    // 进程名就是包名，所以 comm 实为「包名前缀」，必须用前缀匹配才认得全。
                    String comm = readCommQuiet(pid);
                    if (comm != null && !comm.isEmpty()) {
                        String matched = matchTargetByComm(comm, targets);
                        if (matched != null) {
                            pkg = matched;
                            isMain = true;   // comm 无法区分主/子进程，统一按主进程计（时长口径更稳）
                        }
                    }
                }
                if (pkg == null || pkg.indexOf('.') <= 0) continue;   // 过滤内核线程等
                // 只收录保活目标：非目标进程的数据消费端从不查询，收进来唯一的后果是
                // 把每 10 秒一次的 IPC 和 App 侧 storeProcReport 的 SharedPreferences
                // 落盘（磁盘 IO）撑大几百倍。先判断再读 starttime，连文件都省了。
                if (!targets.contains(pkg)) continue;
                long ticks = readStartTicksQuiet(pid);
                if (ticks < 0) continue;
                java.util.Map<String, Long> sink = isMain ? main : child;
                Long old = sink.get(pkg);
                if (old == null || ticks > old) sink.put(pkg, ticks);
            }
        }
        // 子进程只在主进程缺席时兜底
        java.util.Map<String, Long> merged = new java.util.HashMap<>(child);
        merged.putAll(main);
        return merged;
    }

    /**
     * 单个目标当前是否在运行（复用 {@link #scanRunningApps} 的主/子进程判定）。
     * 用于「托底保活」：模块动手补拉前先确认目标是不是已被系统 persistent 抢先重启。
     * 只在「常驻目标死亡后要补拉」这条低频路径上调用，扫一遍 /proc 的开销可接受。
     */
    private static boolean isPkgAlive(String pkg) {
        java.util.Set<String> one = new java.util.HashSet<>();
        one.add(pkg);
        return scanRunningApps(one).containsKey(pkg);
    }

    /** /proc/&lt;pid&gt;/stat 第 2 字段 comm（进程核心名，最多 15 字节）。 */
    private static String readCommQuiet(int pid) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/proc/" + pid + "/stat"))) {
            String stat = r.readLine();
            if (stat == null) return null;
            int open = stat.indexOf('(');
            int close = stat.lastIndexOf(')');
            if (open < 0 || close <= open) return null;
            return stat.substring(open + 1, close);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 用 comm（被截断到 15 字节的进程名）在目标名单里找出对应包名。
     *
     * 优先精确相等；否则用「comm 是包名前缀」兜底——这是 ROM 锁定应用 cmdline 被抹空后
     * 唯一能认出它的信号。为避免两个包名共享同一前缀时认错，前缀匹配取「最长且最具体」
     * 的那个目标；长度 &lt; 4 的 comm 太短、误伤面大，直接放弃匹配。
     */
    private static String matchTargetByComm(String comm, java.util.Set<String> targets) {
        String exact = null;
        String bestPrefix = null;
        int bestLen = 0;
        for (String t : targets) {
            if (comm.equals(t)) { exact = t; break; }
            if (comm.length() >= 4 && t.startsWith(comm) && t.length() > bestLen) {
                bestPrefix = t;
                bestLen = t.length();
            }
        }
        return exact != null ? exact : bestPrefix;
    }

    /**
     * 错峰静默拉起多个应用：依次逐个拉起，相邻两个之间 sleep {@link #LAUNCH_STAGGER_MS}，
     * 把瞬时拉起的 CPU/IO 峰值摊开，避免保活名单较多时同瞬间一起拉起卡爆手机。
     * 调用方必须已处于后台线程（开机自启动、进程上报循环均满足）。
     *
     * @param isBoot true = 开机种子拉起，不受补拉冷却限制（此时冷却表里本就没记录）；
     *               false = D 功能第二防线的掉线补拉，走 {@link #tryRelaunch} 过冷却闸。
     */
    private static void silentStartStaggered(Context ctx, List<String> pkgs, boolean isBoot) {
        int i = 0;
        for (String pkg : pkgs) {
            if (i > 0) {
                try {
                    Thread.sleep(LAUNCH_STAGGER_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (isBoot) {
                if (silentStart(ctx, pkg)) {
                    bumpRelaunch(pkg);   // 记一次「拉起」（开机种子与掉线补拉同口径）
                    sSysClaimPending.remove(pkg);   // 模块开机种子拉起：撤销「托底保活」待确认
                    // 开机种子是模块亲自动手拉的，前缀就是「模块拉活 · 开机种子」。
                    // （真正由系统 persistent 拉起的情形不在这里，由心跳轮询判系统功劳。）
                    addGuardEvent(pkg, EVT_BOOT, relaunchWho(pkg, "开机种子"));
                }
            } else {
                // 冷却与去重都在 tryRelaunch 里：第一防线刚拉过的包会被自动跳过
                tryRelaunch(ctx, pkg, "第二防线·轮询");
            }
            i++;
        }
    }

    /**
     * 后台静默拉起一个应用，绝不弹界面。
     *
     * 进程被带起来的本质是「让 system_server 替它跑一次入口」：
     *   1. startService —— 启动应用声明的任意服务，进程在后台起来，无任何 UI；
     *   2. Provider 探活 —— 访问一次它的 ContentProvider，同样能孵化进程；
     *   3. 都不行（应用没声明服务/Provider，或被 force-stop）就只能放弃，
     *      不落回 startActivity —— 那会把整屏应用甩到用户脸上。
     */
    private static boolean silentStart(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        // 1) 服务
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SERVICES);
            if (pi != null && pi.services != null) {
                for (ServiceInfo si : pi.services) {
                    try {
                        ctx.startService(new Intent().setClassName(pkg, si.name));
                        log("静默拉起(服务): " + pkg + " -> " + si.name);
                        return true;
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 2) Provider
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_PROVIDERS);
            if (pi != null && pi.providers != null) {
                for (ProviderInfo pr : pi.providers) {
                    if (pr.authority == null) continue;
                    try {
                        android.database.Cursor cur = ctx.getContentResolver().query(
                                android.net.Uri.parse("content://" + pr.authority),
                                null, null, null, null);
                        if (cur != null) {
                            cur.close();
                            log("静默拉起(Provider): " + pkg + " -> " + pr.authority);
                            return true;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        logWarn("静默拉起失败(无可用服务/Provider，或应用被强制停止): " + pkg);
        return false;
    }

    private static String readCmdlineQuiet(int pid) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/proc/" + pid + "/cmdline"))) {
            String s = r.readLine();
            if (s == null) return null;
            int z = s.indexOf('\0');
            return z > 0 ? s.substring(0, z) : s;
        } catch (Throwable t) {
            return null;
        }
    }

    /** /proc/&lt;pid&gt;/stat 第 22 字段 starttime（相对开机的 tick）。 */
    private static long readStartTicksQuiet(int pid) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/proc/" + pid + "/stat"))) {
            String stat = r.readLine();
            if (stat == null) return -1;
            int close = stat.lastIndexOf(')');
            if (close < 0 || close + 2 >= stat.length()) return -1;
            String[] f = stat.substring(close + 2).split(" ");
            if (f.length <= 19) return -1;
            return Long.parseLong(f[19]);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 全量 /proc 扫描的独立线程（045）：全局只启动一次，由心跳循环在「进程页在看」时触发。
     *
     * <p>为什么单独开线程：{@link #scanAllProcesses} 要读几百个进程的 cmdline / stat /
     * status / oom_score_adj，是整套逻辑里最重的一块；它原先串行挂在心跳那一轮里，
     * 把补拉、死亡检测、守护事件推回一起压后。独立线程后两者互不阻塞。
     *
     * <p>不看进程页时清空缓存并低频轮询 —— 保持「不看 = 零额外开销」。
     * 整轮 try/catch：扫描异常绝不能让线程静默死掉（否则进程页永远不再刷新）。
     */
    private static void startProcScannerOnce() {
        if (!sProcScannerStarted.compareAndSet(false, true)) return;
        new Thread(() -> {
            android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            while (true) {
                try {
                    long now = System.currentTimeMillis();
                    boolean on = sProcWanted
                            && (now - sProcWantedAtMs) < PROC_LIST_WANTED_MS;
                    if (on) {
                        long t0 = android.os.SystemClock.elapsedRealtime();
                        String[] all = scanAllProcesses();
                        sAllProcsCache = all;
                        log("[进程页诊断] 独立扫描线程完成 allprocs=" + all.length
                                + " 耗时 "
                                + (android.os.SystemClock.elapsedRealtime() - t0) + "ms");
                        Thread.sleep(PROC_SCAN_INTERVAL_MS);
                    } else {
                        if (sAllProcsCache != null) sAllProcsCache = null;
                        Thread.sleep(2000L);
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) {
                    logWarnOnce("进程扫描线程本轮异常，已跳过本轮: " + t);
                    try {
                        Thread.sleep(3000L);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }, "pka-procscan").start();
    }

    /**
     * 全量扫描 /proc，给「进程」页用：每个进程一条编码字符串。
     *
     * 比 scanRunningApps 读得多——除了 cmdline/comm，还要读 stat 拿 state/ppid/starttime、
     * 读 oom_score_adj 拿 adj、读 status 的 VmRSS 拿内存。几百个进程逐个读 status 较贵，
     * 所以只在 App 进程页在看（proc_wanted 窗口内）时才扫，平时完全不扫。
     */
    private static String[] scanAllProcesses() {
        java.util.List<String> out = new java.util.ArrayList<>();
        File[] dirs = new File("/proc").listFiles();
        if (dirs == null) return new String[0];
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            int pid = parsePidName(d.getName());
            if (pid <= 0) continue;

            String cmd = readCmdlineQuiet(pid);
            String pkg = null;
            String name;
            if (cmd != null && !cmd.isEmpty()) {
                name = cmd;
                int colon = cmd.indexOf(':');
                pkg = colon > 0 ? cmd.substring(0, colon) : cmd;
                // 原生二进制（如 /system/bin/some_daemon）不是应用包名，归到系统进程
                if (pkg.indexOf('.') <= 0) pkg = null;
            } else {
                String comm = readCommQuiet(pid);
                name = (comm != null && !comm.isEmpty()) ? comm : "?";
            }
            if (name == null) name = "?";

            char state = '?';
            int ppid = 0;
            long startTicks = -1;
            String stat = readFirstStatQuiet(pid);
            if (stat != null) {
                int close = stat.lastIndexOf(')');
                if (close > 0 && close + 2 < stat.length()) {
                    String[] f = stat.substring(close + 2).split(" ");
                    if (f.length >= 2) state = f[0].isEmpty() ? '?' : f[0].charAt(0);
                    if (f.length >= 4) {
                        try { ppid = Integer.parseInt(f[1]); } catch (Throwable ignored) { }
                    }
                    if (f.length >= 21) {
                        try { startTicks = Long.parseLong(f[19]); } catch (Throwable ignored) { }
                    }
                }
            }
            int oomAdj = readOomAdjQuiet(pid);
            long vmRss = readVmRssQuiet(pid);
            out.add(new ProcessInfo(pid, ppid, state, oomAdj, vmRss, startTicks, pkg, name).encode());
        }
        return out.toArray(new String[0]);
    }

    /** /proc/&lt;pid&gt;/stat 整行（含 comm，用于拆 state/ppid/starttime）。 */
    private static String readFirstStatQuiet(int pid) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/proc/" + pid + "/stat"))) {
            return r.readLine();
        } catch (Throwable t) {
            return null;
        }
    }

    /** oom_score_adj（范围约 -1000..1000，越小越不容易被回收）；读不到回退 oom_adj，再不行 -1000。 */
    private static int readOomAdjQuiet(int pid) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/proc/" + pid + "/oom_score_adj"))) {
            String s = r.readLine();
            if (s != null) return Integer.parseInt(s.trim());
        } catch (Throwable ignored) {
        }
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/proc/" + pid + "/oom_adj"))) {
            String s = r.readLine();
            if (s != null) return Integer.parseInt(s.trim());
        } catch (Throwable ignored) {
        }
        return -1000;
    }

    /** /proc/&lt;pid&gt;/status 的 VmRSS（常驻内存，KB）。读不到返回 0。 */
    private static long readVmRssQuiet(int pid) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/proc/" + pid + "/status"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    // 形如 "VmRSS:      12345 kB"
                    String num = line.replaceAll("[^0-9]", "");
                    if (!num.isEmpty()) return Long.parseLong(num);
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** /proc 目录名 → pid；非数字返回 -1。 */
    private static int parsePidName(String s) {
        if (s == null || s.isEmpty()) return -1;
        int pid = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < '0' || ch > '9') return -1;
            pid = pid * 10 + (ch - '0');
        }
        return pid;
    }

    private static void dumpMethods(ClassLoader cl, String className, String... keywords) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            StringBuilder sb = new StringBuilder();
            for (Method m : c.getDeclaredMethods()) {
                String n = m.getName();
                boolean hit = false;
                for (String kw : keywords) {
                    if (n.contains(kw)) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(n).append("(");
                Class<?>[] pt = m.getParameterTypes();
                for (int i = 0; i < pt.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append(pt[i].getSimpleName());
                }
                sb.append(")");
            }
            log("方法枚举 " + className + ": " + sb);
        } catch (Throwable t) {
            logWarn("方法枚举失败 " + className + ": " + t);
        }
    }

    /**
     * 004 新增：打出匹配方法的**完整签名**，含每个参数的**全限定类型名**。
     *
     * <p>与 {@link #dumpMethods} 的区别：那个用 {@code getSimpleName()}（只给 ProcessRecordInternal
     * 这样的短名），且不区分参数个数。本函数给全限定名 + 参数个数，
     * 目的是回答「applyOomAdjLSP 的第一个参数到底是什么类」—— 043 就是栽在这个问题上。
     */
    private static void dumpMethodSignatures(ClassLoader cl, String className, String keyword) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().contains(keyword)) continue;
                Class<?>[] pt = m.getParameterTypes();
                StringBuilder sb = new StringBuilder("方法签名 ").append(className).append('.')
                        .append(m.getName()).append(" 共 ").append(pt.length).append(" 参: [");
                for (int i = 0; i < pt.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(i).append("=").append(pt[i].getName());
                }
                sb.append("] 返回=").append(m.getReturnType().getName());
                log(sb.toString());
            }
        } catch (Throwable t) {
            logWarn("方法签名枚举失败 " + className + ": " + t);
        }
    }

    /**
     * 枚举某个类上所有 int / boolean 字段名。
     *
     * <p>★ 为什么需要它：{@code markPersistent} 要在 ProcessRecord 上写
     * {@code persistent} / {@code maxAdj}，但这两个字段在 Android 12+ 被搬进了
     * 子对象（ProcessStateRecord），且字段名随版本变动。靠"猜候选名"的方式
     * 一旦猜不中，就是永久的静默失败——钩子自检照样全绿，只有打标不生效。
     * 直接把真实字段名列出来，才能一次定位对症补候选。
     *
     * @param target 字段所在类；传 null 表示自动发现（用于 mState）
     */
    private static void dumpFields(Class<?> target, String label, String... keywords) {
        try {
            if (target == null) {
                logWarn("字段枚举 " + label + ": 类为 null，跳过");
                return;
            }
            StringBuilder sb = new StringBuilder();
            int total = 0;
            for (java.lang.reflect.Field f : target.getDeclaredFields()) {
                Class<?> t = f.getType();
                boolean wanted = t == int.class || t == boolean.class
                        || t == long.class || t == Integer.class || t == Boolean.class;
                if (!wanted) continue;
                total++;
                String n = f.getName();
                // 有关键词时只列相关的，避免字段太多刷屏
                if (keywords != null && keywords.length > 0) {
                    boolean hit = false;
                    for (String kw : keywords) {
                        if (n.toLowerCase().contains(kw.toLowerCase())) {
                            hit = true;
                            break;
                        }
                    }
                    if (!hit) continue;
                }
                if (sb.length() > 0) sb.append(", ");
                sb.append(t.getSimpleName()).append(' ').append(n);
            }
            log("字段枚举 " + label + " (" + target.getName() + ", 共 " + total
                    + " 个数值字段): " + sb);
        } catch (Throwable t) {
            logWarn("字段枚举失败 " + label + ": " + t);
        }
    }

    // ==================================================================
    // 【004 · 只诊断】applyOomAdjLSP 实参对象的真实结构
    //
    // 043 的 adj 修复没生效，原因是我犯了一个【没验证就写进代码】的错误：
    //   我假设 applyOomAdjLSP 的实参是 ProcessRecord，于是用 getField(proc, "mProfile")
    //   去取 ProcessProfileRecord。但本机实测签名是：
    //     applyOomAdjLSP(ProcessRecordInternal, boolean, long, long, int, boolean)
    //   —— 参数类型是 ProcessRecordInternal，一个本机 ROM 特有的类型，不是 ProcessRecord。
    //   那个类上有没有 mProfile、叫不叫这个名字、adj 字段叫什么，全都没验证过。
    //   这正是我批评 041「按改名前缀硬凑候选名」的同一个错误，必须用实测数据纠正，
    //   不能再猜第二次。
    //
    // 本函数只读不写：把 findProcessRecord 命中的那个对象的运行时类型、全部 int 字段
    // （名字+当前值）、以及 mProfile 是否存在一并打出来。有了这份数据，043 的修复
    // 才能按真实结构改写。
    // ==================================================================

    /** 本轮诊断已打印次数（避免热路径刷屏；只打前几次就够定位）。 */
    private static final java.util.concurrent.atomic.AtomicInteger sAdjDiagCount =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 诊断最多打印几次。applyOomAdj 是热路径，打太多会拖慢 system_server。 */
    private static final int ADJ_DIAG_MAX = 4;

    /**
     * 打出 applyOomAdjLSP 实参对象的真实结构（004 诊断，只读）。
     *
     * @param args  钩子的原始参数（用于确认哪个下标被 findProcessRecord 选中）
     * @param picked findProcessRecord 返回的对象
     */
    private static void dumpAdjRuntime(Object[] args, Object picked) {
        if (picked == null) return;
        if (sAdjDiagCount.get() >= ADJ_DIAG_MAX) return;
        int diagN = sAdjDiagCount.incrementAndGet();
        // ★ 首次诊断时顺带把 applyOomAdj 的方法签名也打一次。
        //   原版只在开机 install 时打一次，容易被 LSPosed 日志背后的 logcat 环形缓冲冲掉；
        //   现在用户只要动一下任意 app 触发一次 adj 调整，就能在 LSPosed 日志里看到，
        //   不必赶在开机那一刻去翻。picked 的 ClassLoader 即 system_server 的，能加载 OomAdjuster。
        if (diagN == 1) {
            try {
                dumpMethodSignatures(picked.getClass().getClassLoader(), OOM_ADJUSTER, "applyOomAdj");
            } catch (Throwable ignored) {
            }
        }
        try {
            StringBuilder sb = new StringBuilder("adj诊断 ");
            // ① 被选中的对象到底是什么类型
            sb.append("实参类型=").append(picked.getClass().getName());
            // ② 它在参数列表的第几位（确认 findProcessRecord 没挑错）
            sb.append(" 下标=").append(indexOfArg(args, picked));
            // ③ 该类型的全部 int 字段（名字 + 当前值）—— 这是找 adj 真身的关键
            sb.append("\n   int字段: ").append(dumpIntFieldsWithValues(picked));
            // ④ mProfile 存不存在、里面有什么
            Object profile = safeGetField(picked, "mProfile");
            if (profile != null) {
                sb.append("\n   mProfile=").append(profile.getClass().getName())
                  .append(" 的 int字段: ").append(dumpIntFieldsWithValues(profile));
            } else {
                sb.append("\n   mProfile=不存在");
            }
            log(sb.toString());
        } catch (Throwable t) {
            logWarn("adj诊断失败: " + t);
        }
    }

    /** 找出某对象在参数列表中的下标（仅用于诊断确认，找不到返回 -1）。 */
    private static int indexOfArg(Object[] args, Object target) {
        if (args == null) return -1;
        for (int i = 0; i < args.length; i++) {
            if (args[i] == target) return i;
        }
        return -1;
    }

    /**
     * 列出对象的全部 int 字段「名字=当前值」，含父类。
     *
     * <p>与 {@link #dumpFields} 的区别：那个只列字段名（静态结构），这个要连**运行时的值**
     * 一起打 —— 因为要找的不是"没有没 adj 字段"，而是"哪个字段此刻装着 adj 的值"。
     * 目标进程的 adj 在被打压时应为 0/100/200 这类小值，对照着看就能认出真身。
     */
    private static String dumpIntFieldsWithValues(Object obj) {
        if (obj == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        try {
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> t = f.getType();
                    if (t != int.class && t != Integer.class) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(f.getName()).append('=').append(v);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.length() == 0 ? "(无 int 字段)" : sb.toString();
    }

    private static class ModernHooker implements XposedInterface.Hooker {
        private final HookLogic logic;

        ModernHooker(HookLogic logic) {
            this.logic = logic;
        }

        @Override
        public Object intercept(XposedInterface.Chain chain) throws Throwable {
            Object thiz = chain.getThisObject();
            ensureContext(logic, thiz);
            List<Object> argList = chain.getArgs();
            Object[] args = argList == null ? new Object[0] : argList.toArray();
            Object r = logic.before(thiz, args);
            if (r != UNHANDLE) return r;
            // before 可能改写了参数（例如把 setOomAdj 的 adj 换成保活档位值），
            // 这里把改动同步回框架的参数列表，否则 proceed() 仍用旧值。
            if (argList != null && argList.size() == args.length) {
                try {
                    for (int i = 0; i < args.length; i++) argList.set(i, args[i]);
                } catch (Throwable ignored) {
                    // 少数实现的参数列表不可变，忽略即可（legacy 路径本就支持改写）
                }
            }
            Object result = chain.proceed();
            return logic.after(thiz, args, result);
        }
    }

    // ==================================================================
    // 工具方法（纯反射，legacy / modern 共用）
    // ==================================================================

    /**
     * 确保 Prefs 拿到了 system_server 的 systemContext，从而能通过
     * ContentProvider 跨进程读取模块配置（绕开 SELinux 对 App 数据目录的限制）。
     */
    private static void ensureContext(HookLogic logic, Object thiz) {
        if (logic == null || logic.prefs == null || logic.prefs.hasContext()) return;
        Context ctx = null;
        if (thiz != null) {
            try {
                Object c = getField(thiz, "mContext");
                if (c instanceof Context) ctx = (Context) c;
            } catch (Throwable ignored) {
            }
        }
        if (ctx == null) {
            ctx = getSystemContext(logic.cl);
        }
        if (ctx != null) {
            // 顺手缓存给 D 功能第一防线：死亡回调随时可能发生在任意线程，
            // 那里不一定有 AMS 实例可反射，直接用这份缓存最稳。
            sSystemContext = ctx;
            logic.prefs.setContext(ctx);
            logic.prefs.loadAsync();
            log("已注入 systemContext，后台异步读取配置中");
            final Prefs p = logic.prefs;
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                log("配置后台读取结果: providerLoaded=" + p.isProviderLoaded()
                        + ", targets=" + p.targetsCount()
                        + ", autoStart=" + p.isAutoStart());
            }, 8000);
        }
    }

    private static Context getSystemContext(ClassLoader cl) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread", false, cl);
            Object current = at.getMethod("currentActivityThread").invoke(null);
            Object ctx = at.getMethod("getSystemContext").invoke(current);
            if (ctx instanceof Context) return (Context) ctx;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 按 className 优先、altClassNames 兜底解析钩子目标类（兼容 Android 版本间的包名迁移）。 */
    private static Class<?> resolveClass(ClassLoader cl, HookLogic logic) {
        Class<?> c = tryClass(cl, logic.className);
        if (c != null) return c;
        for (String alt : logic.altClassNames) {
            c = tryClass(cl, alt);
            if (c != null) return c;
        }
        return null;
    }

    private static Class<?> tryClass(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 取第一个 String 参数。 */
    private static String firstStringArg(Object[] args) {
        if (args == null) return null;
        for (Object o : args) {
            if (o instanceof String) return (String) o;
        }
        return null;
    }

    /** 从参数列表中找出第一个 ProcessRecord（通过能否读到包名判断）。 */
    private static Object findProcessRecord(Object[] args) {
        if (args == null) return null;
        for (Object o : args) {
            if (o != null && getPackageName(o) != null) return o;
        }
        return null;
    }

    /** 从 ProcessRecord 读取包名（多路径兜底，兼容不同 Android 版本字段变化）。 */
    private static String getPackageName(Object proc) {
        if (proc == null) return null;
        // 路径 1：info.packageName
        try {
            Object info = getField(proc, "info");
            if (info != null) {
                Object pn = getField(info, "packageName");
                if (pn instanceof String) return (String) pn;
            }
        } catch (Throwable ignored) {
        }
        // 路径 2：pkgList 第一个元素
        try {
            Object pkgList = getField(proc, "pkgList");
            if (pkgList instanceof Set && !((Set<?>) pkgList).isEmpty()) {
                return String.valueOf(((Set<?>) pkgList).iterator().next());
            }
        } catch (Throwable ignored) {
        }
        // 路径 3：processName（可能带 :remote 后缀，取主包名）
        try {
            Object pn = getField(proc, "processName");
            if (pn instanceof String) {
                String s = (String) pn;
                int idx = s.indexOf(':');
                return idx >= 0 ? s.substring(0, idx) : s;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 读取完整进程名（含 ":" 后缀，如 "com.xx:push"）。 */
    private static String getProcessName(Object proc) {
        try {
            Object pn = getField(proc, "processName");
            if (pn instanceof String) return (String) pn;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 消息保活完全体：把进程伪造成前台 TOP 状态。
     *
     * <p>很多社交/IM 应用检测到自己在后台就会主动断开推送长连接/停止心跳，
     * 进程活着也没用——这里直接改 system_server 中该进程的
     * curAdj / curProcState / curSchedGroup / setProcState，
     * 让 App 通过 getRunningAppProcesses 等查询自身状态时看到「前台 TOP」，
     * 从而保持长连接与推送不中断。
     */
    private static void fakeForeground(ClassLoader cl, Object proc, String pkg) {
        try {
            Object state = getField(proc, "mState");   // Android 12+：状态字段在 ProcessStateRecord
            Object target = state != null ? state : proc; // Android 11-：字段在 ProcessRecord 本身
            Class<?> tc = target.getClass();
            int topState = 2; // PROCESS_STATE_TOP（历史稳定值）
            int schedTop = getStaticIntField(PROCESS_LIST, "SCHED_GROUP_TOP", 4, cl);

            invokeInt(tc, target, "setCurProcState", topState);
            invokeInt(tc, target, "setSetProcState", topState);
            invokeInt(tc, target, "setCurAdj", 0);
            invokeInt(tc, target, "setSetAdj", 0);
            invokeInt(tc, target, "setCurSchedGroup", schedTop);
            // 字段兜底（Android 11- 的 ProcessRecord 为 public int 字段）
            safeInt(target, "curProcState", topState);
            safeInt(target, "setProcState", topState);
            safeInt(target, "curAdj", 0);
            safeInt(target, "setAdj", 0);
            safeInt(target, "curSchedGroup", schedTop);
            safeInt(target, "setSchedGroup", schedTop);
            // 把前台调度组同步给应用进程，增强伪造可信度
            try {
                Object thread = getField(proc, "thread");
                if (thread != null) {
                    thread.getClass().getMethod("setSchedGroup", int.class)
                            .invoke(thread, schedTop);
                }
            } catch (Throwable ignored) {
            }
            long now = System.currentTimeMillis();
            Long last = sLastFakeLog.get(pkg);
            if (last == null || now - last > 30000) {
                log("已伪造前台状态: " + pkg);
                sLastFakeLog.put(pkg, now);
            }
        } catch (Throwable t) {
            logWarn("伪造前台状态失败(" + pkg + "): " + t);
        }
    }

    private static void invokeInt(Class<?> c, Object target, String name, int v) {
        try {
            Method m = c.getMethod(name, int.class);
            m.setAccessible(true);
            m.invoke(target, v);
        } catch (Throwable ignored) {
        }
    }

    /** setIntField 的静默版：字段不存在时跳过而不是中断整个伪造流程。 */
    private static void safeInt(Object target, String name, int v) {
        try {
            setIntField(target, name, v);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 进程状态（PROCESS_STATE_*，AOSP 历史稳定值，数值越小越「靠前」）。
     * 系统判断后台 / standby / Doze 看的是它，不是 adj。
     */
    private static final int PROCESS_STATE_TOP = 2;
    private static final int PROCESS_STATE_BOUND_TOP = 3;
    private static final int PROCESS_STATE_FOREGROUND_SERVICE = 4;

    /**
     * 保活档位 → 进程状态。
     * 与 {@link #desiredAdj} 同一套档位：前台级伪装成 TOP，可见级伪装成绑定前台，
     * 可感知级退到前台服务级（仍能避免被判为 cached 而进入 standby）。
     */
    private static int desiredProcState(int mode) {
        switch (mode) {
            case 0:
                return PROCESS_STATE_TOP;
            case 2:
                return PROCESS_STATE_FOREGROUND_SERVICE;
            case 1:
            default:
                return PROCESS_STATE_BOUND_TOP;
        }
    }

    /**
     * 读进程当前的最小 adj（跨 ProcessRecord 本体与 mProfile 两条候选路径）。
     * 用于区分「档位写入失败」的两种成因：字段全读不到(真失败) vs 当前已 ≤ 目标(被底座覆盖)。
     *
     * @return 读到的最小 adj；所有候选字段都读不到时返回 {@code Integer.MAX_VALUE}
     */
    private static int currentMinAdj(Object proc) {
        int min = Integer.MAX_VALUE;
        // ★ mCurRawAdj 排在最前：Android 15/16 的 mProfile 真名就是它（065 实测）。
        String[] names = {"mCurRawAdj", "curRawAdj", "mCurAdj", "curAdj", "mSetAdj", "setAdj"};
        for (String n : names) {
            Integer v = tryIntField(proc, n);
            if (v != null) min = Math.min(min, v);
        }
        try {
            Object profile = getField(proc, "mProfile");
            if (profile != null) {
                for (String n : names) {
                    Integer v = tryIntField(profile, n);
                    if (v != null) min = Math.min(min, v);
                }
            }
        } catch (Throwable ignored) {
        }
        return min;
    }

    /** 同时下调 adj 与进程状态（两者都只在「当前值更靠后」时才改）。 */
    private static void applyKeepAlive(Object proc, int adj, int procState) {
        int hit = lowerAdj(proc, adj);
        if (hit == 0) {
            // 一个字段都没写进去。但「没写」可能是两种截然不同的原因：
            // (a) 字段名改版、全部读不到 → 真正的失败，必须报警；
            // (b) 当前 adj 已经 ≤ 目标档位（典型是被「托底保活」压到 -900 的常驻目标，
            //     -900 比任何档位都靠前，lowerAdj 的「只降不升」逻辑自然一条都写不上）——
            //     这不是失败，是档位被更强的底座覆盖，无需写入。
            int cur = currentMinAdj(proc);
            if (cur != Integer.MAX_VALUE && cur <= adj) {
                logWarnOnce("档位已满足: 当前 adj=" + cur + " ≤ 目标 " + adj
                        + "（或被托底保活等更强底座覆盖），无需写入");
            } else {
                // (a) 字段全读不到 → 真正的失败，必须报警（只报一次防刷屏）
                logWarnOnce("档位写入失败: 所有候选字段均未命中，该目标 adj 未生效"
                        + "（系统可能改版了 ProcessRecord/ProcessProfileRecord 的字段名）");
            }
        }
        Object holder = null;
        try {
            holder = getField(proc, "mState");   // Android 12+：状态字段在 ProcessStateRecord
        } catch (Throwable ignored) {
        }
        Object target = holder != null ? holder : proc;
        lowerIntFieldMulti(target,
                new String[]{"curProcState", "mCurProcState", "setProcState", "mSetProcState"},
                procState);
        if (holder != null) {
            lowerIntFieldMulti(proc,
                    new String[]{"curProcState", "mCurProcState", "setProcState", "mSetProcState"},
                    procState);
        }
        // ★ 043：与 lowerAdj 同理 —— 本机没有 mState，procState 真身在
        //   mProfile（ProcessProfileRecord）里，实测字段为 mSetProcState / mPssProcState。
        //   adj 修好了但 procState 还是旧值的话，系统判后台时仍可能按旧状态把进程降级，
        //   档位效果会被打折，所以这条要一起补。
        try {
            Object profile = getField(proc, "mProfile");
            if (profile != null) {
                lowerIntFieldMulti(profile,
                        new String[]{"mCurProcState", "curProcState",
                                "mSetProcState", "setProcState", "mPssProcState"},
                        procState);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * pid → (包名, 进程启动 tick)，供 {@code setOomAdj} 反查
     * （它只拿得到 pid，拿不到 ProcessRecord）。
     *
     * <p>★ 为什么必须连 tick 一起存 —— pid 复用问题：
     * Linux 的 pid 是回收复用的。目标应用 A 被杀后，它的 pid（比如 12345）会在
     * 很短时间内被分配给我们毫不关心的新进程 B。如果这里只记 {@code pid → 包名}，
     * 那么 {@code setOomAdj(12345, ...)} 进来时查到的仍是"A"，于是模块会把【A 的
     * 保活档位强行写给 B】—— 表现为某个毫不相干的进程突然拿到 adj 0，扛住了系统
     * 内存回收；而真正想保活的 A 反而丢了记账。
     *
     * <p>解析办法：启动 tick（{@code /proc/<pid>/stat} 第 22 字段 starttime）是内核
     * 记录的「该进程创建时刻」，同一个 pid 被复用后 tick 必然变大。取用时比对 tick，
     * 对不上就判定为「pid 已被别的进程占用」，直接丢弃这条映射，绝不拿旧包名去改
     * 新进程的 adj。
     */
    private static final java.util.Map<Integer, PidRec> sPidToPkg =
            new java.util.concurrent.ConcurrentHashMap<Integer, PidRec>();

    /** pid 记账条目：包名 + 该进程的启动 tick（用于识别 pid 复用）。 */
    private static final class PidRec {
        final String pkg;
        final long startTick;   // /proc/<pid>/stat 的 starttime，-1 表示读取失败

        PidRec(String pkg, long startTick) {
            this.pkg = pkg;
            this.startTick = startTick;
        }
    }

    /**
     * 从 ProcessRecord 读 pid。
     *
     * <p>★ 为什么不能只写死 "pid" —— Android 12 起 ProcessRecord 把几乎所有
     * 运行期状态字段（含 pid/uid）都挪进了 {@code mState}（ProcessStateRecord），
     * 外层只留了一个包着它的引用。此时 {@code getDeclaredField("pid")} 会直接抛
     * {@code NoSuchFieldException}。这在真机日志里已经实锤过一次：
     * {@code pid记账失败(com.danlan.xiaolan): java.lang.NoSuchFieldException: pid}。
     *
     * <p>记账失败的后果是隐性的、但很致命：{@code setOomAdj} 兜底线全靠 pid 反查
     * 包名，账面一空它整条线空转，而且它【静默】—— 钩子自检照样全绿。
     * 所以这里按「外层 → mState → 子类」逐层找，候选名覆盖 AOSP 的 pid / mPid。
     *
     * @return pid；三种路径都读不到时返回 -1
     */
    private static int readPid(Object proc) {
        if (proc == null) return -1;
        // 1) 外层直接找（Android 11- 的标准布局）
        Integer v = tryIntField(proc, "pid", "mPid");
        if (v != null && v > 0) return v;
        // 2) 进 mState 找（Android 12+ 的标准布局）
        try {
            Object state = getField(proc, "mState");
            if (state != null) {
                v = tryIntField(state, "mPid", "pid");
                if (v != null && v > 0) return v;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** 依次尝试若干候选字段名，返回第一个读到的 int；都读不到返回 null。 */
    private static Integer tryIntField(Object obj, String... names) {
        if (obj == null) return null;
        for (String name : names) {
            try {
                java.lang.reflect.Field f = findField(obj, name);
                // mPid 在 ProcessStateRecord 里是 public int，直接用老式 get() 更稳
                Object raw = f.get(obj);
                if (raw instanceof Integer) return (Integer) raw;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 判断这次死亡是否为「模块自己在进程页杀的」，是则返回对应标签，否则空串。
     *
     * <p>死亡回调拿到的 ProcessRecord 上不会留下 mWasForceStopped/mRemoved 标记
     * （进程页的 SIGKILL 既不是划卡片也不是强制停止），只能靠 {@link #sSelfKilledPid}
     * 的 pid 登记反查。只认 60 秒内的近期登记，防 pid 复用误判。
     */
    private static String readSelfKillReason(String pkg, Object proc) {
        long now = System.currentTimeMillis();
        try {
            // ① pid 精确匹配（能读回 pid 时最准）
            if (proc != null) {
                int pid = readPid(proc);
                if (pid > 0) {
                    Long at = sSelfKilledPid.get(pid);
                    if (at != null && now - at <= 60_000L) {
                        sSelfKilledPid.remove(pid);
                        sSelfKilledPkg.remove(pkg);
                        return "用户手动关闭（进程页）";
                    }
                }
            }
            // ② 包名兜底匹配（死亡回调拿不到 pid 时的主要通路）
            if (pkg != null) {
                Long at = sSelfKilledPkg.get(pkg);
                if (at != null) {
                    if (now - at <= 60_000L) {
                        sSelfKilledPkg.remove(pkg);
                        return "用户手动关闭（进程页）";
                    }
                    sSelfKilledPkg.remove(pkg);   // 过期：清掉
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /**
     * 诊断：把死亡瞬间 ProcessRecord 上与「被杀原因」相关的字段实际值全打出来。
     *
     * <p>为什么需要它：用户划后台卡片杀掉目标后，本机（realme Android 16）依然落
     * 「系统回收或应用自退」兜底 —— 说明预想的 {@code mRemoved} 标记没置位，或置位时机
     * 晚于死亡回调。到底是「字段名不对」「值仍是 false」还是「划卡片走的是别的路径」，
     * 光靠猜解决不了，所以把实测值打出来一次性定位（上限 8 条，防刷屏）。
     */
    private static final java.util.concurrent.atomic.AtomicInteger sKillDiagCount =
            new java.util.concurrent.atomic.AtomicInteger(0);

    private static void dumpKillDiag(Object proc, String pkg, String picked) {
        if (proc == null) return;
        if (sKillDiagCount.get() >= 8) return;
        sKillDiagCount.incrementAndGet();
        try {
            StringBuilder sb = new StringBuilder("被杀诊断 ").append(pkg).append(" → ").append(picked).append(": ");
            sb.append("mRemoved=").append(tryBoolFieldOn(proc, "mRemoved"))
              .append(" mWasForceStopped=").append(tryBoolFieldOn(proc, "mWasForceStopped"))
              .append(" mKillTime=").append(tryLongFieldOn(proc, "mKillTime"))
              .append(" mStartUptime=").append(tryLongFieldOn(proc, "mStartUptime"))
              .append(" mDyingPid=").append(tryIntFieldOn(proc, "mDyingPid"))
              .append(" mPid=").append(tryIntFieldOn(proc, "mPid"))
              .append(" mPersistent=").append(tryBoolFieldOn(proc, "mPersistent"))
              .append(" now=").append(System.currentTimeMillis());
            // ★ 042：把 killLocked 采到的现场拼进这条诊断一起打。
            //   为什么合并而不是各打一条：诊断日志有 8 条上限，分两条会提前用光配额；
            //   且"残留字段全空 + killLocked 说了什么"两相对照才看得出结论。
            //   这里用 get 而非 takeKillTrail ——【只读不消费】，避免影响后续版本接判定链。
            KillTrail trail = sKillTrail.get(pkg);
            if (trail != null) {
                long age = System.currentTimeMillis() - trail.at;
                if (age <= KILL_TRAIL_TTL_MS) {
                    sb.append("\n  [killLocked现场 ").append(age).append("ms前] reason=")
                      .append(trail.reason.isEmpty() ? "(空)" : trail.reason)
                      .append(" desc=").append(trail.description.isEmpty() ? "(空)" : trail.description)
                      .append(" | 栈=").append(trail.frames);
                } else {
                    sb.append("\n  [killLocked现场] 登记已过期(").append(age).append("ms)");
                }
            } else {
                sb.append("\n  [killLocked现场] 未采集到"
                        + "（该进程死亡未经 ProcessRecord.killLocked —— 说明还有第四种死亡路径）");
            }
            log(sb.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 静默读 int 字段（不抛异常，读不到返回 null）。 */
    private static Integer tryIntFieldOn(Object obj, String name) {
        if (obj == null) return null;
        try {
            Object raw = findField(obj, name).get(obj);
            return raw instanceof Integer ? (Integer) raw : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 读取进程被杀原因，翻译成中文标签；读不到或无法判定返回空串。
     *
     * <p><b>本机字段实测（realme RMX3708 / Android 16 / SDK 36）</b>：
     * {@code ProcessRecord} 上只有 {@code mWasForceStopped} / {@code mRemoved} 两个布尔字段
     * 与死亡相关，<b>没有 mKillReason / killReason 字段</b>（Android 的 kill reason 是
     * {@code ProcessRecord.killLocked(String reason, ...)} 的<b>入参</b>，不落到 ProcessRecord
     * 上，我们钩不到那个入参就只能从残留状态反推）。所以之前用
     * {@code mForceStop/mForceStopped} 猜名字全落空 —— 实测名字是 {@code mWasForceStopped}。
     *
     * <p>反推策略（按可信度排序）：
     * <ol>
     *   <li>{@code mWasForceStopped=true} → 用户强制停止（设置里点「强制停止」）；</li>
     *   <li>{@code mRemoved=true} 且死亡时刻贴近 {@code mKillTime} → 用户划卡片清除；
     *       否则 mRemoved 可能只是历史残留，不据此下结论；</li>
     *   <li>都不满足 → 交给调用方按「模块是否在补拉」「进程是否为 cached/empty adj」
     *       等外部线索兜底（返回空串，由 {@link #noteDeath} 侧再补通用标签）。</li>
     * </ol>
     *
     * @param proc AMS 里的 ProcessRecord 实例（onProcessDied 时还活着的那份）
     */
    private static String readKillReason(Object proc) {
        if (proc == null) return "";
        try {
            // 1) 强制停止：字段名是 mWasForceStopped（实测），并兼容其他 ROM 的写法
            Boolean forceStop = tryBoolField(proc,
                    "mWasForceStopped", "mForceStopped", "mForceStop", "forceStop");
            if (Boolean.TRUE.equals(forceStop)) return "用户强制停止";

            // 2) 划卡片清除：mRemoved=true 且与 mKillTime 时间吻合才认定，
            //    避免把「上次被划掉留下的残留标记」误当成本次原因。
            Boolean removed = tryBoolField(proc, "mRemoved", "removed");
            if (Boolean.TRUE.equals(removed)) {
                Long killTime = tryLongField(proc, "mKillTime");
                Long startUp = tryLongField(proc, "mStartUptime");
                long now = System.currentTimeMillis();
                // mKillTime 是墙钟毫秒（Android 用 System.currentTimeMillis 记），
                // 死亡回调发生在近期；超过 30 秒说明是历史残留，不认。
                boolean recent = killTime == null || killTime <= 0
                        || (now - killTime) < 30_000L;
                if (recent) return "用户划卡片清除";
                // 有 mStartUptime（开机毫秒）时也做一次合理性检查，进一步排除残留
                if (startUp != null && startUp > 0) {
                    long upMs = android.os.SystemClock.uptimeMillis();
                    if (upMs - startUp < 300_000L) return "用户划卡片清除";
                }
            }

            // 3) 兼容 AOSP 其他分支：若存在 killReason，仍走反射描述映射
            Integer reason = tryIntFieldAny(proc, "mKillReason", "killReason");
            if (reason != null) {
                String desc = killReasonToString(proc, reason);
                if (desc != null && !desc.isEmpty()) {
                    String d = desc.toLowerCase(java.util.Locale.ROOT);
                    if (d.contains("force") && d.contains("stop")) return "用户强制停止";
                    if (d.contains("remove") || d.contains("user")) return "用户划卡片清除";
                    if (d.contains("low memory") || d.contains("oom") || d.contains("cached"))
                        return "系统低内存回收";
                    if (d.contains("background")) return "系统清理后台";
                    if (d.contains("anr")) return "系统ANR清理";
                    if (d.contains("crash") || d.contains("exit") || d.contains("self"))
                        return "应用自行退出";
                    return desc;
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 依次尝试候选名读 long（外层 + mState 两层兜底），都读不到返回 null。 */
    private static Long tryLongField(Object obj, String... names) {
        Long v = tryLongFieldOn(obj, names);
        if (v != null) return v;
        try {
            Object state = getField(obj, "mState");
            if (state != null) v = tryLongFieldOn(state, names);
        } catch (Throwable ignored) {
        }
        return v;
    }

    private static Long tryLongFieldOn(Object obj, String... names) {
        if (obj == null) return null;
        for (String name : names) {
            try {
                Object raw = findField(obj, name).get(obj);
                if (raw instanceof Long) return (Long) raw;
                if (raw instanceof Integer) return ((Integer) raw).longValue();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 依次尝试候选名，返回第一个读到的 boolean；外层与 mState 都试，都读不到返回 null。 */
    private static Boolean tryBoolField(Object obj, String... names) {
        Boolean v = tryBoolFieldOn(obj, names);
        if (v != null) return v;
        try {
            Object state = getField(obj, "mState");
            if (state != null) v = tryBoolFieldOn(state, names);
        } catch (Throwable ignored) {
        }
        return v;
    }

    private static Boolean tryBoolFieldOn(Object obj, String... names) {
        if (obj == null) return null;
        for (String name : names) {
            try {
                Object raw = findField(obj, name).get(obj);
                if (raw instanceof Boolean) return (Boolean) raw;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 依次尝试候选名，返回第一个读到的 int；外层与 mState 两层兜底，都读不到返回 null。 */
    private static Integer tryIntFieldAny(Object obj, String... names) {
        Integer v = tryIntField(obj, names);
        if (v != null) return v;
        try {
            Object state = getField(obj, "mState");
            if (state != null) v = tryIntField(state, names);
        } catch (Throwable ignored) {
        }
        return v;
    }

    /** 反射调用 killReasonToString(int)（ProcessRecord / ProcessStateRecord 上都有），拿英文描述。 */
    private static String killReasonToString(Object proc, int reason) {
        if (proc == null) return null;
        for (Class<?> c = proc.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod("killReasonToString", int.class);
                m.setAccessible(true);
                Object r = java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        ? m.invoke(null, reason) : m.invoke(proc, reason);
                if (r instanceof String) return (String) r;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 记录 pid → 包名（连带该进程的启动 tick，供后续识别 pid 复用）。 */
    private static void rememberPid(Object proc, String pkg) {
        int pid = readPid(proc);
        if (pid <= 0) {
            // 记账失败会让下面的 setOomAdj 兜底整条线空转，必须留痕
            logWarnOnce("pid记账失败(" + pkg + "): ProcessRecord 及其 mState 均未找到 pid/mPid 字段");
            return;
        }
        if (sPidToPkg.size() > 4096) sPidToPkg.clear();   // 纯粹的容量兜底
        sPidToPkg.put(pid, new PidRec(pkg, readStartTick(pid)));
    }

    /**
     * 按 pid 反查包名，并校验该 pid 是否仍属于当初记录的那个进程。
     *
     * @return 命中的包名；pid 未被记录、或 pid 已被复用作他用时返回 null
     */
    private static String lookupPkgByPid(int pid) {
        PidRec rec = sPidToPkg.get(pid);
        if (rec == null) return null;
        long now = readStartTick(pid);
        // 双方都能读到 tick 且不一致 → pid 已被复用，这条映射作废
        if (rec.startTick > 0 && now > 0 && rec.startTick != now) {
            sPidToPkg.remove(pid);
            return null;
        }
        return rec.pkg;
    }

    /** 目标进程死亡时清掉它的 pid 记账，避免残留映射被复用的 pid 命中。 */
    private static void forgetPid(Object proc) {
        try {
            int pid = readPid(proc);
            if (pid > 0) sPidToPkg.remove(pid);
        } catch (Throwable ignored) {
        }
    }

    /** 读 /proc/&lt;pid&gt;/stat 第 22 字段 starttime（进程启动 tick）；失败返回 -1。 */
    private static long readStartTick(int pid) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/proc/" + pid + "/stat"))) {
            String line = r.readLine();
            if (line == null) return -1;
            // comm 字段可能含空格/括号，从最后一个 ')' 之后开始切，避免错位
            int close = line.lastIndexOf(')');
            if (close < 0) return -1;
            String[] f = line.substring(close + 1).trim().split("\\s+");
            // 跳过 ')' 之后的状态位后，starttime 是第 20 个（整体第 22 个）
            if (f.length < 20) return -1;
            return Long.parseLong(f[19]);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 在 {@code ProcessList.setOomAdj} 的参数里定位 pid 的下标。
     *
     * <p>★ 为什么不用"第一个 int" 这种偷懒写法：整体长这样
     * {@code (int pid, int uid, int adj)}。若某个重载把 uid 排在了 pid 前面，
     * 或者首参根本不是 pid，那么"第一个 int" 就会拿到 uid（一个 10000+ 的大数），
     * 拿去查 {@code sPidToPkg} 必然查不到 —— 表现为兜底线静默空转；
     * 更糟的是某些进程 uid 恰好与某个 pid 数值撞上，就会【改错进程的 adj】。
     *
     * <p>pid 的判据：Linux pid 恒为正、且绝大多数应用进程的 pid 都远小于 uid
     * 起点（AOSP 的 uid 从 10000 起）。所以这里只把「在合法 pid 范围内的 int」
     * 当作候选，并按位置取最靠前的那个。
     *
     * @return pid 的下标；无法确定时返回 -1（调用方应放弃改写，不动参数）
     */
    private static int pickPidArgIndex(Object[] args) {
        if (args == null) return -1;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Integer) {
                int v = (Integer) args[i];
                // pid 必为正；uid 从 10000 起，这里按 0 < v < 100000 取
                // （/proc/sys/kernel/pid_max 默认为 32768 或 4194304）
                if (v > 0 && v < 100000) return i;
            }
        }
        return -1;
    }

    /**
     * 在 {@code ProcessList.setOomAdj} 的参数里定位 adj 的下标。
     *
     * <p>为什么不能"取最后一个 int"：AOSP 各版本/各 ROM 上这个方法存在多种重载，
     * 例如 {@code (int pid, int uid, int adj)}、{@code (int pid, int adj)}，以及
     * 带额外字段的变体。若一律改「最后一个 int」，在末位不是 adj 的重载上就会
     * 改错字段 —— 最坏情况是把 uid 改成 0/100/200，导致这次回调作用到完全错误的
     * 进程上。
     *
     * <p>这里按已知签名结构定位：pid 恒在 index 0、uid 恒在 index 1（若有），
     * 因此 adj 只可能是 index 1（两参版）或 index 2（三参及以上版）。判据是
     * 「index 2 是 Integer 就取 2，否则取 1」，比"取末位"稳得多。
     *
     * @return adj 的下标；无法确定时返回 -1（调用方应放弃改写，不动参数）
     */
    private static int pickAdjArgIndex(Object[] args) {
        if (args == null) return -1;
        // 三参及以上：(pid, uid, adj, ...) → adj 在 index 2
        if (args.length >= 3 && args[2] instanceof Integer) return 2;
        // 两参：(pid, adj) → adj 在 index 1
        if (args.length >= 2 && args[1] instanceof Integer) return 1;
        return -1;
    }

    /**
     * 043 新增：用「实测到的真实方法签名」校验 {@code setOomAdj} 的参数结构，
     * 确认「位置法」这次是真的落在 adj 上，而不是别的 int。
     *
     * <p>为什么还要这层：{@link #pickAdjArgIndex} 本质仍是按位置推断。位置推断的
     * 前提是"pid 在 0、uid 在 1、adj 在 2"这个 AOSP 惯例成立。本机实测
     * {@code ProcessList.setOomAdj(int,int,int)} 成立；但一旦某个 ROM 加入
     * {@code (int pid, int uid, int adj, int reason)} 甚至调换顺序，位置法就会
     * 改到 {@code reason} 或 {@code uid} 上 —— 后者后果严重（改了别的进程的优先级）。
     *
     * <p>校验规则（保守优先，宁可放弃改写）：
     * <ol>
     *   <li>参数个数必须与某个已知合法形态一致（2 或 3 个 int）；</li>
     *   <li>pid 下标必须真的是 pid（已在调用方校验过，这里再确认取值合理）；</li>
     *   <li>adj 下标上的值必须是「合法 adj 区间」内的数 —— adj 恒在
     *       [-1000, 1001]（AOSP 的 UNKNOWN_ADJ..SYSTEM_ADJ 边界），而 uid 从
     *       10000 起、reason 常见为大整数位掩码。用这个区间能把 uid/reason
     *       与 adj 区分开，这正是"位置猜错"最容易露馅的地方。</li>
     * </ol>
     *
     * @return true 表示参数结构符合已知签名、可以安全改写 adj
     */
    private static boolean adjSignatureVerified(Object thiz, int pid, Object[] args) {
        if (args == null || args.length < 2) return false;
        int adjIdx = pickAdjArgIndex(args);
        if (adjIdx < 0) return false;
        Object raw = args[adjIdx];
        if (!(raw instanceof Integer)) return false;
        int v = (Integer) raw;
        // adj 的合法值域（AOSP ProcessList：UNKNOWN_ADJ=-1000 ～ SYSTEM_ADJ=1001）
        return v >= -1000 && v <= 1001;
    }

    private static int desiredAdj(int mode) {
        switch (mode) {
            case 0:
                return 0;   // FOREGROUND_APP_ADJ
            case 2:
                return 200; // PERCEPTIBLE_APP_ADJ
            case 1:
            default:
                return 100; // VISIBLE_APP_ADJ
        }
    }

    private static int getStaticIntField(String className, String name, int fallback, ClassLoader cl) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            return c.getField(name).getInt(null);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * 下调进程的 OOM Adj（多字段名兼容：Android 12+ 字段移入 mState）。
     *
     * @return 实际命中的字段个数；0 表示所有候选名都没对上（调用方应报警）
     */
    private static int lowerAdj(Object proc, int adj) {
        int hit = lowerIntFieldMulti(proc,
                new String[]{"curAdj", "mCurAdj", "curRawAdj", "mCurRawAdj", "setAdj", "mSetAdj"},
                adj);
        try {
            Object state = getField(proc, "mState");
            if (state != null) {
                hit += lowerIntFieldMulti(state,
                        new String[]{"mCurAdj", "curAdj", "mCurRawAdj", "curRawAdj", "mSetAdj", "setAdj"},
                        adj);
            }
        } catch (Throwable ignored) {
        }
        // ★ 043 修复：本机（realme RMX3708 / Android 16 / SDK 36）的 adj 真身既不在
        //   ProcessRecord 上，也不在 mState（ProcessStateRecord）上 —— 030 的字段枚举
        //   已实证：
        //     字段枚举 ProcessRecord全部 (ProcessRecord, 共 23 个数值字段): ...
        //       int mDyingPid, boolean mPersistent, int mPid, int mStartUid ...  ← 无 adj 字段
        //     嵌套 mErrorState / mHostingRecord / mInstr / mOptRecord / mPkgList / mProfile
        //     嵌套 mProfile (ProcessProfileRecord, 共 21 个数值字段):
        //       int mCurRawAdj, ... int mSetAdj, int mSetProcState ...
        //   即 adj 挂在【mProfile → ProcessProfileRecord】里，而日志显示 `mState` 这个字段
        //   在本机【根本不存在】（grep 日志 0 命中）。于是上面两条路径在本机全部落空，
        //   lowerIntFieldMulti 返回 0，模块每一轮都打「档位写入失败: 所有候选字段均未命中」
        //   —— 用户看到的「档位:可见」标签只是"配置选了可见"，adj 从未被改过。
        //
        //   这里补上 mProfile 路径，与 mState 路径并存：别的 ROM 可能真的是 mState 布局，
        //   两条都留着才能跨 ROM 通吃（AOSP 12+ 用 mState，本机 ROM 用 mProfile）。
        hit += lowerAdjInProfile(proc, adj);
        // ★ 结构化路径全 miss 时，按字段名扫描兜底 —— 沿用 markPersistent 已验证有效的
        //   findFieldLike() 思路：不猜名字，直接找「名字含 adj、类型是 int」的字段。
        //   这是"字段名再变也不用改代码"的根治手段，也是本模块自己立的规矩
        //   （挂不上必须显式报警、绝不静默失效）在写路径上的落地。
        //
        // ★ 059：这里加了「已达标短路」。lowerIntFieldMulti 改成只计真写入后，
        //   "字段读到了但当前值已 ≤ 目标"（典型是被托底保活压到 -900）也会返回 0，
        //   于是每轮都白跑一次全字段反射扫描 —— 对已达标进程是纯开销。
        //   判断依据用 currentMinAdj：能读到值且已达标，说明结构路径**是通的**，
        //   只是无需写入，此时没有"字段名失效"这回事，跳过扫描即可。
        //   只有"连值都读不到"（cur == MAX_VALUE）才是真的需要扫描兜底。
        if (hit == 0) {
            int cur = currentMinAdj(proc);
            if (cur == Integer.MAX_VALUE) {
                hit += lowerAdjByScan(proc, adj);
            }
        }
        return hit;
    }

    /**
     * 从 {@code ProcessRecord.mProfile}（{@code ProcessProfileRecord}）里下调 adj。
     *
     * <p>本机布局：{@code mProfile} 里同时有 {@code mCurRawAdj} 与 {@code mSetAdj}，
     * 以及 {@code mPssProcState} / {@code mSetProcState}。两个 adj 字段都尝试下调 ——
     * {@code mCurRawAdj} 是"系统算出来的原始 adj"、{@code mSetAdj} 是"实际写给内核的"，
     * 清理策略读哪个在本机实测看不出来，两边都压住最稳。
     *
     * @return 写入成功的字段个数
     */
    private static int lowerAdjInProfile(Object proc, int adj) {
        if (proc == null) return 0;
        try {
            Object profile = getField(proc, "mProfile");
            if (profile == null) return 0;
            return lowerIntFieldMulti(profile,
                    new String[]{"mCurAdj", "curAdj", "mCurRawAdj", "curRawAdj",
                            "mSetAdj", "setAdj"},
                    adj);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 按「名字含 adj 的 int 字段」扫描兜底下调，供结构化路径全部 miss 时使用。
     *
     * <p>与 {@link #findFieldLike} 同思路（那是 {@code markPersistent} 救回打标链的手段）：
     * 不依赖具体字段名，只在类型与命名局部特征上匹配。为避免误伤：
     * <ul>
     *   <li>只认 {@code int} 类型（adj 是 int）；</li>
     *   <li>字段名里必须含 {@code adj}，且【不含】{@code reason} / {@code seq} /
     *       {@code change} 这类明显不是"当前值"的后缀定语；</li>
     *   <li>只在当前值更大时下调（与 {@link #lowerIntFieldMulti} 同口径）。</li>
     * </ul>
     *
     * @return 写入成功的字段个数
     */
    private static int lowerAdjByScan(Object proc, int adj) {
        if (proc == null) return 0;
        int written = 0;
        // 外层 + mProfile 两层都扫，覆盖两种布局
        Object[] targets = new Object[]{proc, safeGetField(proc, "mProfile")};
        for (Object target : targets) {
            written += lowerAdjByScanOn(target, adj);
        }
        // 扫到并写了才打日志；全 miss 由调用方 applyKeepAlive 统一报警
        if (written > 0) {
            logOnce("adj字段扫描兜底生效: " + written + " 个含 adj 的 int 字段被下调（结构路径未命中）");
        }
        return written;
    }

    /** 在单个对象上做「含 adj 的 int 字段」扫描下调。 */
    private static int lowerAdjByScanOn(Object target, int adj) {
        if (target == null) return 0;
        int written = 0;
        try {
            for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (f.getType() != int.class) continue;
                    String lower = f.getName().toLowerCase(java.util.Locale.ROOT);
                    // 必须与 adj 相关
                    if (!lower.contains("adj")) continue;
                    // 排除明显不是"当前 adj 值"的字段（如 mLastOomAdjChangeReason / ...Seq）
                    if (lower.contains("reason") || lower.contains("seq")
                            || lower.contains("change") || lower.contains("time")) continue;
                    try {
                        f.setAccessible(true);
                        int cur = f.getInt(target);
                        if (cur > adj) {
                            f.setInt(target, adj);
                            written++;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return written;
    }

    /** {@code getField} 的静默版：取不到返回 null，不抛异常。 */
    private static Object safeGetField(Object obj, String name) {
        try {
            return getField(obj, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 仅当目标字段当前值更大（优先级更低）时才下调。
     *
     * <p>★ 为什么要把「命中数」暴露出来：这是本模块【档位是否真正生效】的关键
     * 写操作。候选字段名一旦全部对不上（典型是新系统又改了 ProcessStateRecord
     * 的字段名），旧实现会静默返回 —— 结果是「钩子自检显示绿色 OK（方法确实挂上了），
     * 但 adj 从没被改过」，用户只觉得「三个档位没区别」，开发者隔着屏幕完全查不到。
     * 这正是模块自己立的规矩最不该被违反的地方：挂不上必须显式报警，绝不静默失效。
     *
     * @return 实际写入成功的字段个数；0 表示一个都没写进去（调用方可据此报警）
     */
    /**
     * 把目标字段下调到 adj（仅当当前值更大时写）。
     *
     * @return 实际写入成功的字段个数；0 表示一个都没写进去（调用方可据此报警）
     *
     * ★ 059 P1 修复：原实现在「字段存在但当前值已经 ≤ 目标」时也 {@code written++}，
     *   注释理由是"字段可用"。但这让返回值**不再表示"真的写进去了"**，
     *   而调用方 {@link #lowerAdj} 恰恰拿 {@code hit == 0} 当**字段名全部失效**的
     *   触发条件 —— 于是出现最糟的组合：
     *     · 字段名全对不上（真正的挂载失败）→ 走到下面，hit 仍是 0 → 侥幸会报警；
     *     · 但只要有一段路径"读到字段却无需写入"（例如已被更强的底座压到 -900），
     *       hit 就被抬成非 0 → **跳过 lowerAdjByScan 兜底扫描**、也不再报警。
     *   结果是「明明没写成功，看起来却一切正常」，正是本模块最忌讳的静默失效。
     *
     *   现在严格区分：只有 {@code f.setInt(...)} 真的执行了才计数。
     *   当前值已达标的情况不算"写入失败"，由调用方读 {@code currentMinAdj} 自行判断
     *   （那段逻辑本来就在 applyKeepAlive 里，不受本次改动影响）。
     */
    private static int lowerIntFieldMulti(Object obj, String[] fields, int adj) {
        if (obj == null) return 0;
        int written = 0;
        for (String name : fields) {
            try {
                java.lang.reflect.Field f = findField(obj, name);
                int cur = f.getInt(obj);
                if (cur > adj) {
                    f.setInt(obj, adj);
                    written++;   // ★ 只有真写进去才计数（059）
                }
                // 注：cur <= adj 时【不计数】—— 字段虽存在但本次并未写入，
                //     把它算作命中会让 lowerAdj 误判"挂载正常"而跳过扫描兜底。
            } catch (Throwable ignored) {
            }
        }
        return written;
    }

    /**
     * 把进程标记为常驻（persistent），运行期设置也会在进程死亡后被系统重启。
     *
     * <p>★ 这里踩过两个坑，都记下来当教训：
     * <ol>
     *   <li><b>谎报</b>：旧实现在两个 {@code catch (Throwable ignored)} 之后
     *       【无条件】打印「已将目标标记为常驻进程」。两个 setXxx 全抛
     *       NoSuchFieldException 被静默吞掉，然后照样报「成功」—— 日志在说谎。</li>
     *   <li><b>猜错字段名</b>：028 那版认定「Android 12 起 persistent/maxAdj 搬进了
     *       {@code mState}」，于是 ProcessRecord 这层只试了不带前缀的
     *       {@code persistent} / {@code maxAdj}。本机实测（realme RMX3708 /
     *       Android 16 / SDK 36）恰恰相反：ProcessRecord 上<b>根本没有 mState</b>，
     *       {@code boolean mPersistent} 就挂在 ProcessRecord 自己身上。
     *       两条路径全部落空 —— 打标整条链静默失败，所谓「托底保活」名存实亡。</li>
     * </ol>
     *
     * <p>现在的做法：ProcessRecord / mState 两条路径都试 → 都不中就按字段名扫描兜底
     * → 只有【真的写进去至少一个字段】才报成功，并把命中的字段名一并打出来。
     * 一个都没写进去就明确报警，绝不谎报。
     */
    private static void markPersistent(ClassLoader cl, Object proc, String pkg) {
        if (proc == null) return;
        final int adj = getStaticIntField(PROCESS_LIST, "PERSISTENT_PROC_ADJ", -800, cl);
        java.util.List<String> got = new java.util.ArrayList<>();
        // ★ 漂移检测（动作【之前】读）：本轮进来时 adj 已经被系统重算走。
        //   典型现象：上一轮我们钉到 -800，系统下一轮 oom_adj 把它改回正数
        //   （064 实测进程页看到 500 = 缓存级），底座等于没站住。
        //   以前这里完全静默——只在【首次】成功时打一条日志（sPersistLogged 去重），
        //   于是「首次钉成功、之后每轮都被夹回来」这种失败在日志里毫无痕迹，
        //   用户界面却明明显示「常驻级」。现在每轮都 detect + throttled 报警。
        int curBefore = currentMinAdj(proc);

        // 路径 1：字段直接挂在 ProcessRecord 上（本机实测布局：mPersistent / mPid，
        //         且【没有】mState）。028 只试了不带 m 前缀的两个名字，全部落空。
        markOn(proc, "", adj, got);

        // 路径 2：AOSP 12+ 把运行期状态搬进了 mState（ProcessStateRecord）。
        //         本机没有这个字段，但换一台机器就可能走这条 —— 两条路径都保留。
        Object state = null;
        try {
            state = getField(proc, "mState");
        } catch (Throwable ignored) {
        }
        if (state != null) markOn(state, "mState.", adj, got);

        // 路径 2b ★ 核心修复（065）：Android 15/16 把运行期 adj 全搬进了
        //   mProfile(ProcessProfileRecord)，真名 mCurRawAdj / mSetAdj。
        //   064 日志的字段枚举证实：ProcessRecord 上只有 mPersistent，没有任何 adj
        //   字段；adj 全在 mProfile 里。以前的代码只看 proc / mState，
        //   于是 adj 一个都写不进去，进程页始终是 0/500 —— 托底保活形同虚设。
        Object profile = null;
        try {
            profile = getField(proc, "mProfile");
        } catch (Throwable ignored) {
        }
        if (profile != null) markOn(profile, "mProfile.", adj, got);

        // 路径 3：一个都没命中 → 改成「按字段名扫描」兜底。
        //         字段命名在 AOSP 与各家 ROM 之间反复变（pid↔mPid、persistent↔
        //         mPersistent、maxAdj↔mMaxAdj↔mCurMaxAdj…），每换一茬候选名就静默
        //         失效一次。扫描之后，下次再改名也不用改代码。
        if (got.isEmpty()) {
            markOnScan(proc, "", adj, got);
            if (state != null) markOnScan(state, "mState.", adj, got);
            if (profile != null) markOnScan(profile, "mProfile.", adj, got);
        }

        if (!got.isEmpty()) {
            // ★ 成功判定要更严：只有 adj 类字段也写进去了才算真成功。
            //   mPersistent 单独命中不算 —— 064 就是 mPersistent 写成功、
            //   adj 三个字段全落空，却因为 got 非空而报「标记成功」，
            //   掩盖了「进程页 adj 一直是 0」这一核心失败。
            boolean adjHit = false;
            for (String g : got) {
                if (g.contains("Adj")) { adjHit = true; break; }
            }
            if (sPersistLogged.add(pkg)) {
                log("已将目标标记为常驻进程: " + pkg
                        + "（命中字段: " + String.join(", ", got)
                        + (adjHit ? "）" : "）⚠未命中任何 adj 字段，进程页 adj 可能仍是原值"));
            }
            // ★ 写入后回读校验：写成功 ≠ 生效。字段找得到、set 也不抛异常，但如果这份
            //   ProcessRecord 不是系统真正在用的那一份（钩子拿错对象 / ROM 又包了一层
            //   代理），回读马上就会露馅 —— 上一轮写 -800、这一轮进来还是 0，就是这个。
            //   没有这条证据时，只能对着「进程页不动」瞎猜；有了它，一眼就能区分是
            //   「我们没写进去」还是「系统下一轮又算回去了」。
            int after = currentMinAdj(proc);
            if (after != Integer.MAX_VALUE && after > adj + 100) {
                logThrottled("回读未生效·常驻级:" + pkg, 60000,
                        "写入已成功（命中 " + String.join(", ", got) + "），但立即回读 adj="
                                + after + " 未达到 -800：该对象很可能不是本 ROM 生效的那份"
                                + " ProcessRecord，需要核对钩子取参路径");
            }
            // ★ 漂移报警： adj 已被系统拉回（比目标底座弱 100 以上）才算漂。
            //   不是每次相遇都刷屏 —— 60 秒节流，同一目标一分钟内最多一条。
            //   这条日志是解决「用户界面显示常驻、进程页却是 500」的关键证据。
            if (curBefore != Integer.MAX_VALUE && curBefore > adj + 100) {
                logThrottled("漂移·常驻级:" + pkg, 60000,
                        "检测到 adj 被系统重夹，已重新钉回 -800: " + pkg
                                + "（进入前 adj=" + curBefore + "）");
            }
        } else {
                logWarnOnce("常驻标记失败(" + pkg + "): ProcessRecord"
                        + (state != null ? " 与 mState" : "")
                        + " 上均未找到 persistent/maxAdj 字段，该目标不会获得系统级重启保护");
        }
    }

    /**
     * 托底保活 · 核心级（adj -1000，实验性）：把进程钉在系统核心级，比常驻(-800)更强。
     * 复用 markPersistent 的三路径写法（直接字段 / mState / 扫描兜底），仅把 adj 换成 -1000。
     * 每轮 applyOomAdjLSP 都会调用本方法，靠 markOn 里对 curAdj/setAdj 的重钉对抗系统重夹。
     */
    private static void markCore(ClassLoader cl, Object proc, String pkg) {
        if (proc == null) return;
        final int adj = getStaticIntField(PROCESS_LIST, "NATIVE_ADJ", -1000, cl);
        java.util.List<String> got = new java.util.ArrayList<>();
        // ★ 与 markPersistent 同源的漂移检测：核心级更容易被 ROM 每轮夹回
        //   -800/-900，这里同样在写入前读一次，便于定位「掉了是哪一轮开始的」。
        int curBefore = currentMinAdj(proc);
        markOn(proc, "", adj, got);
        Object state = null;
        try {
            state = getField(proc, "mState");
        } catch (Throwable ignored) {
        }
        if (state != null) markOn(state, "mState.", adj, got);
        // ★ 与 markPersistent 同因修复（065）：Android 15/16 的 adj 在 mProfile 里
        Object profile = null;
        try {
            profile = getField(proc, "mProfile");
        } catch (Throwable ignored) {
        }
        if (profile != null) markOn(profile, "mProfile.", adj, got);
        if (got.isEmpty()) {
            markOnScan(proc, "", adj, got);
            if (state != null) markOnScan(state, "mState.", adj, got);
            if (profile != null) markOnScan(profile, "mProfile.", adj, got);
        }
        if (!got.isEmpty()) {
            boolean adjHit = false;
            for (String g : got) {
                if (g.contains("Adj")) { adjHit = true; break; }
            }
            if (sCoreLogged.add(pkg)) {
                log("已将目标钉为系统核心级(adj=-1000, 实验性): " + pkg
                        + "（命中字段: " + String.join(", ", got)
                        + (adjHit ? "）" : "）⚠未命中任何 adj 字段，-1000 可能未生效"));
            }
            // 与常驻级同理：写成功也要回读确认，否则「日志说钉上了、进程页纹丝不动」
            // 这种最误导人的现象没法归因。
            int afterCore = currentMinAdj(proc);
            if (afterCore != Integer.MAX_VALUE && afterCore > adj + 100) {
                logThrottled("回读未生效·核心级:" + pkg, 60000,
                        "写入已成功（命中 " + String.join(", ", got) + "），但立即回读 adj="
                                + afterCore + " 未达到 -1000：ROM 可能在本轮之后立即重算了 adj");
            }
            if (curBefore != Integer.MAX_VALUE && curBefore > adj + 100) {
                logThrottled("漂移·核心级:" + pkg, 60000,
                        "检测到 adj 被系统重夹，已重新钉回 -1000: " + pkg
                                + "（进入前 adj=" + curBefore + "，多为 ROM 每轮重夹）");
            }
        } else {
            logWarnOnce("系统核心标记失败(" + pkg + "): ProcessRecord"
                    + (state != null ? " 与 mState" : "")
                    + " 上均未找到 persistent/adj 字段，该目标不会获得 -1000 钉值");
        }
    }

    /**
     * 在一个对象上按候选名写 persistent / maxAdj，命中名追加进 {@code got}。
     *
     * @param prefix 命中位置的日志前缀（{@code ""} 或 {@code "mState."}）
     */
    private static void markOn(Object obj, String prefix, int adj, java.util.List<String> got) {
        for (String n : new String[]{"mPersistent", "persistent"}) {
            if (setBooleanFieldQuiet(obj, n, true) > 0) {
                got.add(prefix + n);
                break;
            }
        }
        // 上限(最弱允许)：maxAdj 把进程钉在该 adj —— maxAdj=-1000 ⇒ 永远 ≤ -1000 ⇒ 即 -1000
        // ★ 候选名扩展（065）：Android 15/16 把 adj 全搬进 mProfile(ProcessProfileRecord)，
        //   且改名成 mCurRawAdj；老名字在新布局上一个都不存在（064 实测字段枚举证实）。
        for (String n : new String[]{"mMaxAdj", "maxAdj", "mCurMaxAdj", "curMaxAdj"}) {
            if (setIntFieldQuiet(obj, n, adj) > 0) {
                got.add(prefix + n);
                break;
            }
        }
        // 实际值：每轮重钉 cur/raw/set adj，对抗系统 oom_adj 重算把值夹回去。
        // ★ mCurRawAdj / mCurAdj 都要试：前者是 Android 15/16 的 mProfile 真名，
        //   后者是旧布局名。缺一个就有一半机型钉不住（064 的 adj 掉回 0/500 即此因）。
        for (String n : new String[]{"mCurAdj", "curAdj", "mCurRawAdj", "curRawAdj",
                "mSetAdj", "setAdj"}) {
            if (setIntFieldQuiet(obj, n, adj) > 0) got.add(prefix + n);
        }
    }

    /**
     * 字段名兜底扫描：按「名字含 persistent 的 boolean」「名字含 maxadj 的 int」直接找。
     *
     * <p>为什么不再继续堆候选名：本机真名是 {@code mPersistent}，而旧代码只试了
     * {@code persistent}。字段命名在 AOSP 与各家 ROM 之间反复变，硬编码永远追不上；
     * 按名扫描写一次，之后改名也不用再动代码。
     */
    private static void markOnScan(Object obj, String prefix, int adj, java.util.List<String> got) {
        java.lang.reflect.Field pf = findFieldLike(obj, "persistent", boolean.class);
        if (pf != null) {
            try {
                pf.setBoolean(obj, true);
                got.add(prefix + "扫描:" + pf.getName());
            } catch (Throwable ignored) {
            }
        }
        java.lang.reflect.Field af = findFieldLike(obj, "maxadj", int.class);
        if (af != null) {
            try {
                af.setInt(obj, adj);
                got.add(prefix + "扫描:" + af.getName());
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 在对象（含父类）上按「名字包含关键词、类型匹配」找字段；命中多个时取名字最短的
     * （{@code mMaxAdj} 比 {@code mCurMaxAdj} 更贴近 maxAdj 本身）。
     */
    private static java.lang.reflect.Field findFieldLike(
            Object obj, String lowerKey, Class<?> type) {
        if (obj == null) return null;
        java.lang.reflect.Field best = null;
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType() != type) continue;
                if (!f.getName().toLowerCase(java.util.Locale.ROOT).contains(lowerKey)) continue;
                if (best == null || f.getName().length() < best.getName().length()) best = f;
            }
        }
        if (best != null) {
            try {
                best.setAccessible(true);
            } catch (Throwable ignored) {
            }
        }
        return best;
    }

    /** 写入 boolean 字段，成功返回 1、字段不存在或写入失败返回 0（不抛异常）。 */
    private static int setBooleanFieldQuiet(Object obj, String name, boolean value) {
        try {
            findField(obj, name).setBoolean(obj, value);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 写入 int 字段，成功返回 1、字段不存在或写入失败返回 0（不抛异常）。 */
    private static int setIntFieldQuiet(Object obj, String name, int value) {
        try {
            findField(obj, name).setInt(obj, value);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ===== ★066 batchSetOomAdj 批量通道支持（元素类型未知的通用处理） =====

    /** batch 元素结构只打印一次（热路径防刷屏）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean sBatchDumped =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 首个 batch 元素的结构留痕：类名 + 全部 int 字段名与值。 */
    private static void dumpBatchElemOnce(Object e) {
        if (!sBatchDumped.compareAndSet(false, true)) return;
        try {
            log("batchSetOomAdj 元素结构: " + e.getClass().getName()
                    + " int字段: " + dumpIntFieldsWithValues(e));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 从 batch 元素反查包名：先按常见 pid 字段名取；失败则扫描全部 int 字段，
     * 只有「恰好一个值能反查到 pid 记账」才认定，避免把 uid 误当 pid。
     */
    private static String lookupPkgByElemPid(Object e) {
        for (String n : new String[]{"mPid", "pid"}) {
            try {
                Object v = getField(e, n);
                if (v instanceof Integer && (Integer) v > 0) {
                    String pkg = lookupPkgByPid((Integer) v);
                    if (pkg != null) return pkg;
                }
            } catch (Throwable ignored) {
            }
        }
        String only = null;
        try {
            for (Class<?> c = e.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType() != int.class) continue;
                    f.setAccessible(true);
                    int v = f.getInt(e);
                    if (v <= 0 || v > 100000) continue;
                    String pkg = lookupPkgByPid(v);
                    if (pkg != null) {
                        if (only != null && !only.equals(pkg)) return null;
                        only = pkg;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return only;
    }

    /**
     * 把 batch 元素里所有 adj 类 int 字段钳到目标值。
     * 只钳「当前值比目标更弱」的字段（adj 语义：越小越强），已达标的不动。
     * 名字含 adj（不区分大小写）的 int 字段都会被处理，覆盖 mAdj/mOomAdj/mMaxAdj/
     * mCurRawAdj 等一切现名与未来名；maxAdj 一并下调与 markOn 行为一致。
     */
    private static boolean clampElemAdj(Object e, int want) {
        boolean hit = false;
        try {
            for (Class<?> c = e.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType() != int.class) continue;
                    if (!f.getName().toLowerCase().contains("adj")) continue;
                    f.setAccessible(true);
                    int cur = f.getInt(e);
                    if (cur <= want) continue;
                    f.setInt(e, want);
                    hit = true;
                }
            }
        } catch (Throwable ignored) {
        }
        return hit;
    }

    private static Object getField(Object obj, String name) throws Throwable {
        java.lang.reflect.Field f = findField(obj, name);
        return f.get(obj);
    }

    // ★ 059 P2：删除零调用的 setBooleanField(Object,String,boolean) throws Throwable。
    //   它是 setBooleanFieldQuiet 的前身；后者（返回 1/0、不抛异常）才是打标链实际在用的。
    //   留着这个会抛版本，很容易被误当成"另一个可用入口"而绕开 Quiet 的静默语义。

    private static void setIntField(Object obj, String name, int value) throws Throwable {
        findField(obj, name).setInt(obj, value);
    }

    private static java.lang.reflect.Field findField(Object obj, String name) throws Throwable {
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }
}
