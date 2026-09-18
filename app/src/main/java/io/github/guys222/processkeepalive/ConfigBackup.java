package io.github.guys222.processkeepalive;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 配置备份 / 恢复（JSON 文件）。
 *
 * 备份内容覆盖「一份配置换台设备也能原样还原」所需的全部数据：
 *   1. 全局开关：总开关 / 开机拉起+死后拉起 / 强力模式 / 默认档位 / 主题 / 是否显示系统应用；
 *   2. 目标应用清单及其逐项配置：保活档位、防强停、防清后台、防杀、托底保活（常驻级/核心级）、消息保活。
 *
 * 刻意不备份的部分：
 *   · 守护起始打点（gstart_）、计费/统计类数据 —— 这些是「本机的运行时状态」，跨设备还原没有意义，
 *     新导入的目标会在落地后由 SurvivalData.rearmFallbackTimer 重新起算保活时长；
 *   · proc_wanted / kill_queue —— 瞬时通信通道，不是配置。
 *
 * 写盘走 SAF（系统文件选择器），无需任何存储权限；读取端 ConfigurationProvider 下一次
 * getConfig（钩子 30 秒内刷新一次）自动生效，不需要用户再做什么。
 *
 * 本类只运行在模块 App 进程，绝不进入 system_server 的钩子路径。
 */
public final class ConfigBackup {

    /** 备份格式版本：将来字段结构变化时靠它决定是否兼容。 */
    public static final int FORMAT = 1;

    private static final String K_APP = "app";
    private static final String K_FORMAT = "format";
    private static final String K_VERSION = "version";
    private static final String K_TIME = "time";
    private static final String K_SETTINGS = "settings";
    private static final String K_TARGETS = "targets";

    private ConfigBackup() {}

    /** 恢复到 SharedPreferences 的摘要，给 UI 展示「导了几个应用」。 */
    public static final class Result {
        /** 导入后的目标应用数量。 */
        public final int targets;
        /** 相对导入前新增的数量。 */
        public final int added;
        /** 相对导入前被移除的数量。 */
        public final int removed;
        /** 导入元的开关布尔值是否有变化（用于 UI 提示）。 */
        public final boolean settingsChanged;

        Result(int targets, int added, int removed, boolean settingsChanged) {
            this.targets = targets;
            this.added = added;
            this.removed = removed;
            this.settingsChanged = settingsChanged;
        }

        /** 一行人话摘要，例如「恢复 12 个目标 · 新增 3 · 移除 1」。 */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("目标应用 ").append(targets).append(" 个");
            if (added > 0) sb.append(" · 新增 ").append(added);
            if (removed > 0) sb.append(" · 移除 ").append(removed);
            if (!settingsChanged) sb.append(" · 全局开关无变化");
            return sb.toString();
        }
    }

    /**
     * 不动配置，只把一份备份描述成人话（供恢复前二次确认）。
     * 解析失败时返回 null，调用方据此提示「文件无法识别」。
     *
     * <p>★ 这里的校验口径必须与 {@link #apply} 完全一致，否则会出现
     * 「描述成可恢复、点确认才报错」的欺骗性提示：
     * 典型是用新版模块导出的备份（format 更高）在旧版模块里导入 ——
     * describe 只看了「有没有 targets/settings」就放行，apply 才拿格式版本拦截。
     * 所以格式版本上限检查前移到这里，两个入口同一判据。
     */
    public static String describe(String json) {
        return describeOrReason(json).text;
    }

    /**
     * {@link #describe} 的完整版：除了描述文本，还把「为什么不能恢复」一并带出。
     *
     * <p>拆成这个方法是因为「不可恢复」其实有两种截然不同的原因，
     * 混成一句「缺少配置字段」会误导用户：
     *   · 文件根本不是本模块的备份（缺 targets/settings）
     *   · 文件是备份，但格式版本比当前模块新（该升级模块，而不是怀疑文件）
     * 调用方据此给出精确提示。
     */
    public static final class Described {
        public final String text;    // 描述文本；不可恢复时为 null
        public final String reason;  // 不可恢复的原因；可恢复时为 null
        Described(String text, String reason) { this.text = text; this.reason = reason; }
        public boolean ok() { return text != null; }
    }

    public static Described describeOrReason(String json) {
        try {
            JSONObject root = new JSONObject(json);
            // 与 apply 的第一道判据一致：必须像本模块的备份
            if (!root.has(K_TARGETS) && !root.has(K_SETTINGS)) {
                return new Described(null, "不像是本模块的备份：既没有 targets 也没有 settings");
            }
            // 与 apply 的第二道判据一致：格式版本不能比本版本新
            int fmt = root.optInt(K_FORMAT, 0);
            if (fmt > FORMAT) {
                return new Described(null, "备份格式 v" + fmt + " 比本版本支持的 v" + FORMAT
                        + " 更新，请升级模块后再恢复");
            }
            StringBuilder sb = new StringBuilder();
            sb.append("目标应用 ").append(root.optJSONArray(K_TARGETS) == null
                    ? 0 : root.optJSONArray(K_TARGETS).length()).append(" 个");
            if (fmt > 0) sb.append(" · 格式 v").append(fmt);
            String ver = root.optString(K_VERSION, "");
            if (!TextUtils.isEmpty(ver)) sb.append(" · 由 v").append(ver).append(" 导出");
            String t = root.optString(K_TIME, "");
            if (!TextUtils.isEmpty(t)) sb.append("\n导出时间：").append(t);
            sb.append("\n\n恢复将替换当前的保活清单与全局开关，且不可撤销。");
            return new Described(sb.toString(), null);
        } catch (Throwable ignored) {
            return new Described(null, "文件内容无法解析（不是合法的 JSON）");
        }
    }

    /** 备份文件建议名，如 pka-config-20260917-0012.json。 */
    public static String suggestName() {
        String ts = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(new Date());
        return "pka-config-" + ts + ".json";
    }

    /**
     * 序列化当前配置（缩进 2 空格，人可以直接读改）。
     *
     * @throws Exception JSON 组装或读取配置失败
     */
    public static String build(Context ctx) throws Exception {
        SharedPreferencesSafe sp = new SharedPreferencesSafe(ctx);

        JSONObject settings = new JSONObject();
        settings.put("enabled", sp.bool(Prefs.KEY_ENABLED, true));
        settings.put("auto_start", sp.bool(Prefs.KEY_AUTO_START, false));
        settings.put("aggressive", sp.bool(Prefs.KEY_AGGRESSIVE, false));
        settings.put("priority", Prefs.Priority.clamp(sp.integer(Prefs.KEY_PRIORITY, 1)));
        settings.put("theme", ThemeManager.getMode(ctx));
        settings.put("show_system_apps", sp.bool(Prefs.KEY_SHOW_SYSTEM, false));

        JSONArray targets = new JSONArray();
        for (String pkg : sortedTargets(sp.targets())) {
            JSONObject o = new JSONObject();
            o.put("pkg", pkg);
            o.put("adj", Prefs.Priority.clamp(sp.integer(Prefs.KEY_ADJ_PREFIX + pkg, 1)));
            // 拦截类开关的默认值同样取 false，与 AppConfig/Prefs/AppsFragment 对齐。
            // 这里读不到键说明该目标从未显式配置过拦截项 —— 那它在运行时的实际行为
            // 就是「不拦截」，导出时也必须如实写成 false，否则备份会把语义放大：
            // 恢复后凭空多出两个拦截项。
            o.put("force_stop", sp.bool(Prefs.KEY_FORCE_PREFIX + pkg, false));
            o.put("kill_bg", sp.bool(Prefs.KEY_KILLBG_PREFIX + pkg, false));
            o.put("kill", sp.bool(Prefs.KEY_KILL_PREFIX + pkg, false));
            o.put("persist", sp.bool(Prefs.KEY_PERSIST_PREFIX + pkg, false));
            // 「核心级」同样要进出备份：漏了的话备份恢复会把这一档整体抹掉，
            // 且用户无从察觉（其余开关看上去都还在）。
            o.put("core", sp.bool(Prefs.KEY_CORE_PREFIX + pkg, false));
            o.put("msg", sp.bool(Prefs.KEY_MSG_PREFIX + pkg, false));
            targets.put(o);
        }

        JSONObject root = new JSONObject();
        root.put(K_APP, "io.github.guys222.processkeepalive");
        root.put(K_FORMAT, FORMAT);
        root.put(K_VERSION, BuildConfig.VERSION_NAME);
        root.put(K_TIME, new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date()));
        root.put(K_SETTINGS, settings);
        root.put(K_TARGETS, targets);
        return root.toString(2);
    }

    /**
     * 把一份备份写进配置：整体替换目标清单与逐项开关。
     *
     * 完整性策略：先取「旧的 ∪ 新的」目标并集，再逐个写入新值；凡不在新清单里的应用，
     * 把它残留的 adj_/fs_/kb_/kill_/persist_/msg_/gstart_ 一并清掉，避免出现「列表里
     * 看不到了、配置却还在」的幽灵数据。
     *
     * @throws Exception JSON 解析失败或不是本模块的备份
     */
    public static Result apply(Context ctx, String json) throws Exception {
        if (TextUtils.isEmpty(json)) throw new Exception("内容为空");
        JSONObject root = new JSONObject(json);
        if (!root.has(K_TARGETS) && !root.has(K_SETTINGS)) {
            throw new Exception("不像是本模块的备份：既没有 targets 也没有 settings");
        }
        int fmt = root.optInt(K_FORMAT, 0);
        if (fmt > FORMAT) {
            throw new Exception("备份格式 v" + fmt + " 比本版本支持的 v" + FORMAT + " 更新，请升级模块后再恢复");
        }

        SharedPreferencesSafe sp = new SharedPreferencesSafe(ctx);
        Set<String> old = sp.targets();

        // ---- 目标清单与逐项配置：先算出并集，再逐个写入 / 清理 ----
        java.util.List<JSONObject> incomingItems = new java.util.ArrayList<>();
        Set<String> incoming = new HashSet<>();
        JSONArray arr = root.optJSONArray(K_TARGETS);
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String pkg = o.optString("pkg", "");
                if (TextUtils.isEmpty(pkg)) continue;
                if (!incoming.add(pkg)) continue;   // 去重：同名包以第一条为准
                incomingItems.add(o);
            }
        }

        Set<String> union = new HashSet<>(old);
        union.addAll(incoming);

        android.content.SharedPreferences.Editor e = sp.edit();
        boolean settingsChanged = false;
        JSONObject st = root.optJSONObject(K_SETTINGS);
        if (st != null) {
            settingsChanged |= putBool(e, Prefs.KEY_ENABLED, st, "enabled", true, sp);
            settingsChanged |= putBool(e, Prefs.KEY_AUTO_START, st, "auto_start", false, sp);
            settingsChanged |= putBool(e, Prefs.KEY_AGGRESSIVE, st, "aggressive", false, sp);
            settingsChanged |= putBool(e, Prefs.KEY_SHOW_SYSTEM, st, "show_system_apps", false, sp);
            if (st.has("priority")) {
                int v = Prefs.Priority.clamp(st.optInt("priority", 1));
                if (v != sp.integer(Prefs.KEY_PRIORITY, 1)) settingsChanged = true;
                e.putInt(Prefs.KEY_PRIORITY, v);
            }
        }
        int themeBefore = ThemeManager.getMode(ctx);
        int themeNew = st != null ? st.optInt("theme", themeBefore) : themeBefore;
        if (themeNew < 0 || themeNew > 2) themeNew = ThemeManager.MODE_SYSTEM;

        for (JSONObject o : incomingItems) {
            String pkg = o.optString("pkg", "");
            e.putInt(Prefs.KEY_ADJ_PREFIX + pkg,
                    Prefs.Priority.clamp(o.optInt("adj", 1)));
            // 导入侧的兜底刻意用 false，与导出侧/运行时默认一致。
            // 若某个旧备份里缺这个字段，说明它导出时该目标也没显式配过 —— 按运行时
            // 实际行为（不拦截）恢复才是忠实的；给 true 反而会凭空多出拦截项。
            e.putBoolean(Prefs.KEY_FORCE_PREFIX + pkg, o.optBoolean("force_stop", false));
            e.putBoolean(Prefs.KEY_KILLBG_PREFIX + pkg, o.optBoolean("kill_bg", false));
            e.putBoolean(Prefs.KEY_KILL_PREFIX + pkg, o.optBoolean("kill", false));
            e.putBoolean(Prefs.KEY_PERSIST_PREFIX + pkg, o.optBoolean("persist", false));
            e.putBoolean(Prefs.KEY_CORE_PREFIX + pkg, o.optBoolean("core", false));
            e.putBoolean(Prefs.KEY_MSG_PREFIX + pkg, o.optBoolean("msg", false));
        }
        for (String pkg : union) {
            if (incoming.contains(pkg)) continue;
            e.remove(Prefs.KEY_ADJ_PREFIX + pkg);
            e.remove(Prefs.KEY_FORCE_PREFIX + pkg);
            e.remove(Prefs.KEY_KILLBG_PREFIX + pkg);
            e.remove(Prefs.KEY_KILL_PREFIX + pkg);
            e.remove(Prefs.KEY_PERSIST_PREFIX + pkg);
            e.remove(Prefs.KEY_CORE_PREFIX + pkg);
            e.remove(Prefs.KEY_MSG_PREFIX + pkg);
        }
        e.putStringSet(Prefs.KEY_TARGETS, incoming);
        // 用 commit：写入必须立刻对其他线程/system_server 可见，异步 apply 会让紧随其后的读取读到旧值
        e.commit();

        // ---- 落地后的副作用：新增/移除目标的运行时状态 ----
        int added = 0, removed = 0;
        for (String pkg : incoming) {
            if (!old.contains(pkg)) {
                added++;
                SurvivalData.rearmFallbackTimer(ctx, pkg);   // 新目标：保活时长从现在起算
            }
        }
        for (String pkg : old) {
            if (!incoming.contains(pkg)) {
                removed++;
                SurvivalData.forgetTarget(ctx, pkg);         // 不再是目标：计数与保活打点全清
            }
        }

        // 主题：值变了才写，setMode 会触发 Activity 重建（不要在 choice 前多写一次无谓的重建）
        if (themeNew != themeBefore) {
            ThemeManager.setMode(ctx, themeNew);
        }

        return new Result(incoming.size(), added, removed, settingsChanged);
    }

    /**
     * 把文本写进 SAF 选出的目标文件。
     *
     * @throws Exception 写失败（用户选了不支持写入的位置、目录只读等）
     */
    public static void writeText(Context ctx, Uri uri, String text) throws Exception {
        try (OutputStream os = ctx.getContentResolver().openOutputStream(uri, "w")) {
            if (os == null) throw new Exception("无法写入所选位置");
            os.write(text.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }

    /**
     * 从 SAF 选出的文件读出文本（上限 8 MB，防止选到几个 G 的东西把自己憋死）。
     *
     * @throws Exception 读失败或文件过大
     */
    public static String readText(Context ctx, Uri uri) throws Exception {
        java.io.InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("无法读取所选文件");
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 8 * 1024 * 1024) throw new Exception("文件过大（>8MB），不像配置文件");
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    // ---- 内部小工具 ----

    /** 写布尔值，返回「它与现有值是否不同」。 */
    private static boolean putBool(android.content.SharedPreferences.Editor e, String key,
                                   JSONObject src, String field, boolean def,
                                   SharedPreferencesSafe sp) {
        if (!src.has(field)) return false;
        boolean v = src.optBoolean(field, def);
        boolean changed = v != sp.bool(key, def);
        e.putBoolean(key, v);
        return changed;
    }

    private static java.util.List<String> sortedTargets(Set<String> s) {
        java.util.List<String> l = new java.util.ArrayList<>(s);
        java.util.Collections.sort(l);
        return l;
    }

    /** SharedPreferences 的极小包装：带默认值读取，省得外面到处写 getBoolean(..., def)。 */
    private static final class SharedPreferencesSafe {
        private final android.content.SharedPreferences sp;

        SharedPreferencesSafe(Context ctx) {
            sp = ctx.getSharedPreferences(Prefs.PREFS_NAME, Context.MODE_PRIVATE);
        }

        boolean bool(String key, boolean def) {
            return sp.getBoolean(key, def);
        }

        int integer(String key, int def) {
            return sp.getInt(key, def);
        }

        Set<String> targets() {
            return new HashSet<>(sp.getStringSet(Prefs.KEY_TARGETS, new HashSet<>()));
        }

        android.content.SharedPreferences.Editor edit() {
            return sp.edit();
        }
    }
}
