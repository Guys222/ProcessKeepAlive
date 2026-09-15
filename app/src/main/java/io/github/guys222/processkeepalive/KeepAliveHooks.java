package io.github.guys222.processkeepalive;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.lang.reflect.Method;
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

    private KeepAliveHooks() {
    }

    // ==================================================================
    // 钩子逻辑层（legacy / modern 共用）
    // ==================================================================

    public static abstract class HookLogic {
        public final String className;
        public final String[] altClassNames;
        public final String methodName;
        public final String name;
        final ClassLoader cl;
        final Prefs prefs;

        protected HookLogic(ClassLoader cl, Prefs prefs,
                            String className, String methodName, String name) {
            this(cl, prefs, className, null, methodName, name);
        }

        protected HookLogic(ClassLoader cl, Prefs prefs,
                            String className, String[] altClassNames,
                            String methodName, String name) {
            this.cl = cl;
            this.prefs = prefs;
            this.className = className;
            this.altClassNames = altClassNames == null ? new String[0] : altClassNames;
            this.methodName = methodName;
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

    public static HookLogic[] createLogics(ClassLoader cl, Prefs prefs) {
        return new HookLogic[] {
            // 1. 阻止 forceStopPackage
            new HookLogic(cl, prefs, AMS, "forceStopPackage", "forceStopPackage") {
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
            new HookLogic(cl, prefs, AMS, "killBackgroundProcesses", "killBackgroundProcesses") {
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
            // 3. 降低 OOM Adj + 常驻标记
            //    Android 15/16 中 computeOomAdjLocked 已改名/移除，核心入口是
            //    OomAdjuster.updateOomAdjLocked（存在多个重载，参数里含 ProcessRecord）。
            new HookLogic(cl, prefs, OOM_ADJUSTER, "updateOomAdjLocked", "updateOomAdjLocked") {
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
                    if (!prefs.isTarget(pkg)) return result;

                    String pname = getProcessName(proc);
                    int adj = desiredAdj(prefs.getAdjMode(pkg));
                    lowerAdj(proc, adj);
                    // 消息保活：仅对主进程伪装前台——很多 App 检测到自己在后台会主动
                    // 断开推送长连接/停止心跳，让 App 查询自身状态时看到「前台」。
                    if (prefs.isMsgProcess(pkg) && pname != null && pname.equals(pkg)) {
                        fakeForeground(cl, proc, pkg);
                    }
                    if (prefs.isPersistent(pkg)) {
                        markPersistent(cl, proc, pkg);
                    }
                    return result;
                }
            },
            // 4. 强力模式：拦截 ProcessRecord.killLocked
            //    Android 15+ 中 ProcessRecord.kill 已改名为 killLocked。
            new HookLogic(cl, prefs, PROCESS_RECORD, "killLocked", "ProcessRecord.killLocked") {
                @Override
                public Object before(Object thiz, Object[] args) {
                    prefs.refreshIfStale(30000);
                    if (!prefs.isEnabled()) return UNHANDLE;
                    String pkg = getPackageName(thiz);
                    if (!prefs.isTarget(pkg) || !prefs.isPreventKill(pkg)) return UNHANDLE;
                    long now = System.currentTimeMillis();
                    Long last = sLastKillLog.get(pkg);
                    if (last == null || now - last > 10000) {
                        log("已拦截 ProcessRecord.killLocked: " + pkg);
                        sLastKillLog.put(pkg, now);
                    }
                    return null;
                }
            },
            // 5. 消息保活核心：Doze 省电白名单豁免（Android 11+ 类在 deviceidle 包，旧版兜底）
            //    返回 true 等效于把目标加入省电白名单，后台网络/闹钟不再被 Doze 延迟。
            new HookLogic(cl, prefs, DEVICE_IDLE, new String[]{DEVICE_IDLE_LEGACY},
                    "isPowerSaveWhitelistApp", "dozeWhitelist") {
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
            new HookLogic(cl, prefs, APP_STANDBY, "getAppStandbyBucket", "appStandby") {
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
            new HookLogic(cl, prefs, AMS, "finishBooting", "autoStart") {
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
        };
    }

    private static void startTargets(Object amsInstance, Prefs prefs) {
        try {
            Object ctxObj = getField(amsInstance, "mContext");
            if (!(ctxObj instanceof Context)) {
                logWarn("开机自启动: 获取 mContext 失败");
                return;
            }
            final Context ctx = (Context) ctxObj;
            log("开机自启动开始拉起，目标数量=" + prefs.targetsCount());
            for (String pkg : prefs.getTargets()) {
                Intent intent = resolveLaunchIntent(ctx, pkg);
                if (intent == null) {
                    logWarn("无法找到可启动组件: " + pkg);
                    continue;
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    ctx.startActivity(intent);
                    log("已拉起目标应用: " + pkg);
                } catch (Throwable t) {
                    logWarn("拉起失败(" + pkg + "): " + Log.getStackTraceString(t));
                }
            }
        } catch (Throwable t) {
            logWarn("开机自启动执行失败: " + Log.getStackTraceString(t));
        }
    }

    /** 依次尝试多种方式找到应用的启动 Intent（兼容无桌面图标/隐藏图标的应用）。 */
    private static Intent resolveLaunchIntent(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        // 1. 标准 LAUNCHER 入口
        try {
            Intent i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) return i;
        } catch (Throwable ignored) {
        }
        // 2. 查询处理 ACTION_MAIN 的 Activity（不限定 LAUNCHER）
        try {
            Intent q = new Intent(Intent.ACTION_MAIN);
            q.setPackage(pkg);
            List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
            if (list != null && !list.isEmpty()) {
                ResolveInfo ri = list.get(0);
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.setClassName(pkg, ri.activityInfo.name);
                return i;
            }
        } catch (Throwable ignored) {
        }
        // 3. 从 PackageInfo 找第一个导出的 Activity
        try {
            android.content.pm.PackageInfo pi =
                    pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
            if (pi.activities != null) {
                for (android.content.pm.ActivityInfo ai : pi.activities) {
                    if (ai.exported && ai.name != null) {
                        Intent i = new Intent();
                        i.setClassName(pkg, ai.name);
                        return i;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 保证上报循环全局只启动一次（legacy / modern / 自启动回调多处触发也不会重复开线程）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean sReportStarted =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // ==================================================================
    // legacy 安装器（XposedBridge / API 82）
    // ==================================================================

    public static void install(ClassLoader cl, Prefs prefs) {
        log("开始安装保活钩子(legacy)，目标应用数量=" + prefs.targetsCount());
        for (HookLogic logic : createLogics(cl, prefs)) {
            installLegacyOne(cl, logic);
        }
        // legacy 路径同样要上报激活状态（否则未勾选「本模块」作用域时，
        // 没有任何进程写激活标记，主页会永远显示未激活）
        startActivationReport(cl);
    }

    private static void installLegacyOne(ClassLoader cl, HookLogic logic) {
        try {
            Class<?> clazz = resolveClass(cl, logic);
            if (clazz == null) {
                logWarn("legacy 类未找到(" + logic.name + "): " + logic.className);
                return;
            }
            XposedBridge.hookAllMethods(clazz, logic.methodName, new XC_MethodHook() {
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
            log("钩子已安装(legacy): " + logic.name);
        } catch (Throwable t) {
            logWarn("钩子安装失败(" + logic.name + "): " + t);
        }
    }

    // ==================================================================
    // modern 安装器（libxposed API）
    // ==================================================================

    public static void installModern(XposedInterface xposed, ClassLoader cl, Prefs prefs) {
        sXposed = xposed;
        log("开始安装保活钩子(modern)，目标应用数量=" + prefs.targetsCount());
        for (HookLogic logic : createLogics(cl, prefs)) {
            Class<?> clazz = resolveClass(cl, logic);
            if (clazz == null) {
                logWarn("modern 类未找到(" + logic.name + "): " + logic.className);
                continue;
            }
            int count = 0;
            for (Method m : clazz.getDeclaredMethods()) {
                if (!m.getName().equals(logic.methodName)) continue;
                xposed.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(new ModernHooker(logic));
                count++;
            }
            log("钩子已安装(modern): " + logic.name + " (" + count + " 个方法)");
        }
        // 诊断：枚举关键类的实际方法名与签名（Android 16 方法名有变化）
        dumpMethods(cl, OOM_ADJUSTER, "Oom", "Adj");
        dumpMethods(cl, PROCESS_RECORD, "kill");
        dumpMethods(cl, AMS, "killPackage", "newProcessRecord", "ForceStop", "startProcess");

        // 上报激活状态给模块 App（后台线程执行 IPC，避免阻塞 system_server）
        startActivationReport(cl);
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

    /** 下调进程的 OOM Adj（多字段名兼容：Android 12+ 字段移入 mState）。 */
    private static void lowerAdj(Object proc, int adj) {
        lowerIntFieldMulti(proc,
                new String[]{"curAdj", "mCurAdj", "curRawAdj", "mCurRawAdj", "setAdj", "mSetAdj"},
                adj);
        try {
            Object state = getField(proc, "mState");
            if (state != null) {
                lowerIntFieldMulti(state,
                        new String[]{"mCurAdj", "curAdj", "mCurRawAdj", "curRawAdj", "mSetAdj", "setAdj"},
                        adj);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 仅当目标字段当前值更大（优先级更低）时才下调。 */
    private static void lowerIntFieldMulti(Object obj, String[] fields, int adj) {
        for (String name : fields) {
            try {
                java.lang.reflect.Field f = findField(obj, name);
                int cur = f.getInt(obj);
                if (cur > adj) {
                    f.setInt(obj, adj);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 把进程标记为常驻（persistent），运行期设置也会在进程死亡后被系统重启。 */
    private static void markPersistent(ClassLoader cl, Object proc, String pkg) {
        try {
            setBooleanField(proc, "persistent", true);
        } catch (Throwable ignored) {
        }
        try {
            setIntField(proc, "maxAdj",
                    getStaticIntField(PROCESS_LIST, "PERSISTENT_PROC_ADJ", -800, cl));
        } catch (Throwable ignored) {
        }
        if (sPersistLogged.add(pkg)) {
            log("已将目标标记为常驻进程: " + pkg);
        }
    }

    private static Object getField(Object obj, String name) throws Throwable {
        java.lang.reflect.Field f = findField(obj, name);
        return f.get(obj);
    }

    private static void setBooleanField(Object obj, String name, boolean value) throws Throwable {
        findField(obj, name).setBoolean(obj, value);
    }

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
