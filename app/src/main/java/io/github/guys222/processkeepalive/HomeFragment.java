package io.github.guys222.processkeepalive;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HomeFragment extends Fragment {

    /**
     * 保活时长实时走动用的心跳。
     *
     * 之前只能下拉刷新才更新，是因为列表/标签里的时长写在一次性的 build 过程里，
     * 之后没人再去碰它。这里改成页面可见时每 tick 主动把时长刷一遍（脏芯片不算预算，
     * 只改可见行的文字）。1s 一次肉眼就能看到秒级走动，代价可以忽略。
     */
    private static final long TICK_MS = 1000L;
    private final Handler tickerHandler = new Handler(Looper.getMainLooper());

    /**
     * 心跳是否还在跑（即页面当前可见）。
     *
     * ★ 为什么需要这个标志：光靠 {@code removeCallbacks} 挡不住自我续期。
     * 若销毁恰好发生在 run() 执行到一半时，removeCallbacks 打不到「正在执行」的
     * Runnable，而 run() 末尾的 postDelayed 照旧把它塞回队列 —— 心跳就永久活下来了，
     * 每秒空跑一次并持有着 Fragment / 整棵 View 树，谁也不清不掉。
     * 所以让 Runnable 在续期前先自查这个标志，一旦被停就用「自己不再排队」退出。
     */
    private volatile boolean tickerRunning = false;

    /** 心跳计数器：用于「事件列表降频刷新」——每秒跑心跳，但每 5 拍（5 秒）才重建一次事件列表。 */
    private int tickCount = 0;

    /** 事件列表降频刷新间隔（拍数）：心跳 1 秒 1 拍，5 拍 = 5 秒重建一次事件列表。 */
    private static final int EVENT_REFRESH_EVERY = 5;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            // 先判后跑：被停掉后不再执行、也不再续期，彻底退出循环
            if (!tickerRunning) return;
            refreshUptimes();
            if (!tickerRunning) return;   // refreshUptimes 期间可能已被销毁
            // 事件列表降频刷新：事件本身变化慢（分钟级），不必每秒重建 DOM；
            // 但完全不刷就会出现「新事件要切页才看得到」。每 5 秒重建一次，兼顾
            // 及时性与开销（事件列表通常十几条，重建很轻）。
            tickCount++;
            if (tickCount % EVENT_REFRESH_EVERY == 0) {
                buildTimeline(requireContext());
                if (!tickerRunning) return;
            }
            tickerHandler.postDelayed(this, TICK_MS);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 副标题带版本号（预览稿 appbar .sub）
        TextView sub = view.findViewById(R.id.appbar_sub);
        sub.setText("ProcessKeepAlive · v" + BuildConfig.VERSION_NAME);

        // 主题切换
        TextView btnTheme = view.findViewById(R.id.btn_theme);
        btnTheme.setText(ThemeManager.isDark(requireContext()) ? "☀️" : "🌙");
        btnTheme.setOnClickListener(v -> {
            ThemeManager.toggle(requireContext());
            btnTheme.setText(ThemeManager.isDark(requireContext()) ? "☀️" : "🌙");
            Toast.makeText(requireContext(),
                    ThemeManager.isDark(requireContext()) ? "已切换深色主题" : "已切换浅色主题",
                    Toast.LENGTH_SHORT).show();
        });

        // 运行中大卡 → 跳应用页
        view.findViewById(R.id.b_running).setOnClickListener(v -> {
            if (requireActivity() instanceof MainActivity) ((MainActivity) requireActivity()).goToPage(0);
        });

        // 手动刷新守护事件：立刻重建一次事件列表并重置降频计数，让用户不必切页。
        // 按钮放「守护事件」标题行右侧（不随滚动被顶栏盖住，也不会被长列表顶走）。
        view.findViewById(R.id.btn_event_refresh).setOnClickListener(v -> {
            tickCount = 0;
            buildTimeline(requireContext());
            // ★ 「刷新」文字 + ⟳ 图标整体一起转（用户明确要求：整体转的视觉反馈更强烈）。
            //   曾经只转图标、理由是「文字跟着歪不好看」，但那个方案动效太弱、几乎看不见，
            //   实际体验下来整体转更像「刷新」这个动作，所以改回整体旋转。
            //   用 rotation(360) + withEndAction 归零，而不是 rotationBy：后者累加角度，
            //   动画被打断时会停在半途，表现为「一进页面刷新按钮就是歪的」。
            //   360 的整数倍终点与起点视觉等价，归零时不会有跳变。
            v.animate().cancel();
            v.setRotation(0f);
            v.animate().rotation(360f).setDuration(450)
                    .withEndAction(() -> v.setRotation(0f)).start();
            Toast.makeText(requireContext(), "守护事件已刷新", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshAll();
        tickerRunning = true;
        // removeCallbacks 先去重，避免 onResume 被多次触发时排进多个同名 Runnable
        tickerHandler.removeCallbacks(ticker);
        tickerHandler.postDelayed(ticker, TICK_MS);
    }

    @Override
    public void onPause() {
        // 页面不可见就停表，避免后台空转
        stopTicker();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        // ★ 必须先落标志再 removeCallbacks：顺序反过来的话，如果此刻 run() 正在执行，
        //   removeCallbacks 打不到它，它末尾又会 postDelayed 把自己续上。
        //   先落标志则 run() 自查时直接返回，不会续期。
        stopTicker();
        // ★ 清掉对整棵 View 树的强引用（uptimeViews/statViews 持有 TextView）。
        //   不清的话 Fragment 即便 onDestroyView 了，这些 TextView 仍被静态/成员列表引用，
        //   连带其所在整页 View 一起泄漏。
        uptimeViews.clear();
        statViews.clear();
        super.onDestroyView();
    }

    /** 停掉心跳：先落标志（让可能在执行的 run() 自查退出），再清掉已排队的回调。 */
    private void stopTicker() {
        tickerRunning = false;
        tickerHandler.removeCallbacks(ticker);
    }

    /** 上一轮构建出来的时长 TextView（但不缓存数据），供心跳直接改字。 */
    private final List<TextView> uptimeViews = new ArrayList<>();
    /** 应用名后面的「被杀 / 拉起次数」徽标，心跳一并刷新。 */
    private final List<TextView> statViews = new ArrayList<>();

    /**
     * 心跳：只重算并重绘「时长」文字，不动列表结构。
     * 进程此刻真的没在跑就显示「未运行」，而不是留着一个假数字。
     */
    private void refreshUptimes() {
        View root = getView();
        if (root == null || !isAdded()) return;
        Context ctx = root.getContext();
        for (TextView tv : uptimeViews) {
            Object tag = tv.getTag();
            if (!(tag instanceof String)) continue;
            String pkg = (String) tag;
            tv.setText(SurvivalData.statusText(ctx, pkg));
            tv.setTextColor(ContextCompat.getColor(ctx, SurvivalData.isCounting(ctx, pkg)
                    ? R.color.colorPrimary2 : R.color.colorOnSurfaceVariant));
        }
        for (TextView tv : statViews) {
            Object tag = tv.getTag();
            if (!(tag instanceof String)) continue;
            String pkg = (String) tag;
            long[] gs = SurvivalData.guardStats(ctx, pkg);
            // 方案 14：计数是行右下角的小字，有杀过就转暖黄（.ks.hot）
            tv.setText("被杀 " + gs[0] + " · 拉起 " + gs[1]);
            tv.setTypeface(null, gs[0] > 0
                    ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            tv.setTextColor(ContextCompat.getColor(ctx, gs[0] > 0
                    ? R.color.colorWarn : R.color.colorOnSurfaceVariant));
        }
        // 顶部两块实时区（守护中 N/N、本次开机守护进度条）一并跟上，
        // 否则进程被杀/被拉起后要切走再切回才看得到变化
        refreshLiveStats();
    }

    /**
     * 心跳里的「实时区」刷新：守护中数量 + 本次开机守护进度条。
     *
     * 只有这两块需要每秒重算——它们取决于进程此刻是否还在跑。
     * 另两条（24h 存活率、最长连续存活）来自每小时一次的采样，每秒重算是白烧 CPU，
     * 留给 {@link #refreshStats()} 在进页时更新即可。
     */
    private void refreshLiveStats() {
        View root = getView();
        if (root == null || !isAdded()) return;
        Context ctx = root.getContext();
        SharedPreferences prefs = ctx.getSharedPreferences(Prefs.PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> targets = prefs.getStringSet(Prefs.KEY_TARGETS, new HashSet<>());

        int running = countRunning(ctx, targets);
        ((TextView) root.findViewById(R.id.b_running)).setText(running + " / " + targets.size());

        // 本次开机守护：取所有目标里最长的一个，满格按 24h 算
        long bootUp = 0;
        for (String pkg : targets) {
            long up = SurvivalData.uptimeMs(ctx, pkg);
            if (up > bootUp) bootUp = up;
        }
        int bootPct = (int) Math.min(100, bootUp * 100 / (24L * 3600_000L));
        bindBar(root, R.id.p_bar3_track, R.id.p_bar3_fill, bootPct, R.color.progressFill3);
        ((TextView) root.findViewById(R.id.p_bar3_val)).setText(
                targets.isEmpty() ? "—" : SurvivalData.fmtUp(bootUp));
    }

    private void refreshAll() {
        refresh();
        refreshStats();
    }

    // ---- 模块激活状态（预览稿 .pill：半透明底 + 同色描边 + 圆点 + 文字）----
    private void refresh() {
        View v = getView();
        if (v == null) return;
        StringBuilder diag = new StringBuilder();
        boolean activated = detectActivated(diag);

        LinearLayout pill = v.findViewById(R.id.status_pill);
        View dot = v.findViewById(R.id.status_dot);
        TextView text = v.findViewById(R.id.status_text);

        int tint = activated ? c(R.color.colorOk) : Color.rgb(0xba, 0x1a, 0x1a);
        int fill = activated ? hexA(0x26, tint) : hexA(0x26, tint);
        int border = activated ? hexA(0x66, tint) : hexA(0x66, tint);

        // 预览稿 .pill 的 rgba 底/描边在深浅色下都取 ok 色，未激活改用红色同透明度
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setColor(fill);
        gd.setStroke(dp(1), border);
        gd.setCornerRadius(dp(20));
        pill.setBackground(gd);

        dot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(tint));
        text.setTextColor(tint);
        text.setText(activated
                ? "模块已激活 · 系统框架作用域已启用"
                : "未激活 · 请在 LSPosed 启用并勾选「系统框架」");

        // 点状态胶囊 → 弹出诊断详情（含激活链路 + 能力自检）。
        // 为什么挂这里：激活状态是用户最关心、也最常点开看的地方，把诊断信息
        // 放同一入口最自然；能力自检结果也随之一并展示，用户点一下就能自查
        // 「我这台机器上哪些保活能力生效了」。点击时重新构建 diag，拿到最新数据。
        pill.setOnClickListener(x -> {
            StringBuilder fresh = new StringBuilder();
            detectActivated(fresh);
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("模块诊断")
                    .setMessage(fresh.toString())
                    .setPositiveButton("能力自检详情", (d, w) ->
                            startActivity(new android.content.Intent(
                                    requireContext(), CapabilityDiagActivity.class)))
                    .setNegativeButton("知道了", null)
                    .show();
        });
    }

    /** 给颜色套上 alpha（a 为 0..255）。 */
    private static int hexA(int a, int rgb) {
        return (rgb & 0x00ffffff) | ((a & 0xff) << 24);
    }

    /**
     * 是否已激活：读取激活标记（files/activated）。与当前 BOOT_COUNT 一致即本次开机已激活。
     * 链路状态写入 diag 供排查（未激活时可在「重新检测」toast 中查看）。
     */
    private boolean detectActivated(StringBuilder diag) {
        try {
            return detectActivatedInner(diag);
        } finally {
            // 无论激活与否，都附上能力自检结果（成功/失败路径都要看到）
            appendCapabilityDiag(diag);
        }
    }

    /** 激活状态判定的主体；出口统一被 {@link #detectActivated} 包住以附加能力自检。 */
    private boolean detectActivatedInner(StringBuilder diag) {
        int curBc = ActivationMark.getBootCount(requireContext());
        diag.append("本次开机次数：").append(curBc >= 0 ? curBc : "未知").append("\n");
        try {
            File f = new File(requireContext().getFilesDir(), ActivationMark.FILE_NAME);
            if (!f.exists()) {
                diag.append("激活标记：不存在\n处理：在 LSPosed 启用本模块并勾选「系统框架」，然后重启手机。");
                return false;
            }
            String content = readFile(f).trim();
            int bc = parseField(content, "bc");
            long wc = parseFieldLong(content, "wc");
            long bootWallClock = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
            if (bc >= 0) {
                diag.append("激活标记：存在（第 ").append(bc).append(" 次开机写入");
                if (wc > 0) diag.append("，").append(fmtTime(wc));
                diag.append("）\n");
                if (curBc >= 0 && bc == curBc) return true;
                if (curBc < 0) return wc >= bootWallClock - 60000;
                diag.append("结论：标记是第 ").append(bc).append(" 次开机写入，本次开机尚未上报。\n处理：确认 LSPosed 已启用并勾选「系统框架」，勾选后必须重启手机。");
                return false;
            }
            if (wc > 0) {
                diag.append("激活标记：存在（写入于 ").append(fmtTime(wc)).append("，未含开机次数）\n");
                boolean ok = wc >= bootWallClock - 60000;
                if (!ok) diag.append("结论：标记不是本次开机写入的。\n处理：重启手机后重新检测。");
                return ok;
            }
            long written = Long.parseLong(content);
            boolean ok = written >= bootWallClock - 60000;
            if (!ok) diag.append("激活标记：旧格式，写入于 ").append(fmtTime(written)).append("\n处理：重启手机后重新检测。");
            return ok;
        } catch (Exception e) {
            diag.append("激活标记：读取失败（").append(e).append("）");
            return false;
        }
    }

    private static int parseField(String content, String key) {
        return (int) parseFieldLong(content, key);
    }

    /**
     * 把「能力自检」结果追加进诊断信息。
     *
     * <p>为什么需要它：本模块靠「按名挂 AOSP 私有方法」实现，AOSP 会在版本间改方法名
     * （如 applyOomAdjLocked → applyOomAdjLSP），ROM 也会魔改。若某个名字对不上，
     * 对应功能会【静默失效】——不崩溃、不报错，用户只觉得「不好用」。
     * 这里把 hook 侧上报的自检结果展示出来：哪些能力生效、哪些没生效、以及
     * 到底命中了哪个方法名。用户点「重新检测」即可自查，也便于反馈问题时定位。
     */
    private void appendCapabilityDiag(StringBuilder diag) {
        List<SurvivalData.Capability> caps = SurvivalData.hookCaps(requireContext());
        if (caps.isEmpty()) {
            diag.append("\n能力自检：暂无数据（等待系统框架上报，通常开机后数十秒内完成）");
            return;
        }
        int okCount = 0;
        StringBuilder miss = new StringBuilder();
        for (SurvivalData.Capability cap : caps) {
            if (cap.ok) {
                okCount++;
            } else {
                if (miss.length() > 0) miss.append("、");
                miss.append(cap.name).append("（").append(cap.cls).append("）");
            }
        }
        diag.append("\n能力自检：").append(okCount).append("/").append(caps.size()).append(" 项生效");
        if (miss.length() > 0) {
            diag.append("\n未生效：").append(miss)
                    .append("\n说明：这些能力在本机系统版本上未找到对应入口（多为系统改版导致），")
                    .append("其余功能不受影响。");
        }
    }

    private static long parseFieldLong(String content, String key) {
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.startsWith(key + "=")) {
                try { return Long.parseLong(line.substring(key.length() + 1)); }
                catch (NumberFormatException ignored) { }
            }
        }
        return -1;
    }

    private static String fmtTime(long ms) {
        return new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
                .format(new java.util.Date(ms));
    }

    private static String fmtClock(long ms) {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
                .format(new java.util.Date(ms));
    }

    // ---- 统计 + 曲线 + 列表 ----
    private void refreshStats() {
        View v = getView();
        if (v == null) return;
        Context ctx = requireContext();
        SharedPreferences prefs = ctx.getSharedPreferences(Prefs.PREFS_NAME, Context.MODE_PRIVATE);

        Set<String> targets = prefs.getStringSet(Prefs.KEY_TARGETS, new HashSet<>());
        int count = targets.size();

        // 24h 存活采样数据（整体口径；单应用统计看下方列表）
        float[] series = SurvivalData.getOverallSeries(ctx);
        float ratio = SurvivalData.avg(series);
        if (ratio < 0) ratio = 0f;
        int survivePct = (int) (ratio * 100);

        // ★ 056：开机未满 1 小时 → 24h 存活率一律显示「—」。
        //   采样是小时级的（SurvivalWorker 每小时一次 + 046 的开机即时 kick 一次），
        //   开机 20 秒就会写入第 1 个样本，于是「24 个槽只填了 1 个」也敢显示 100% ——
        //   用户实测开机 9 分钟就满格（056）。满 1 小时（第一个小时级周期走完）才出数字。
        //   另外 24h 曲线天生测不到秒级闪断：被杀 1 秒后拉起，下一个采样点又是活的。
        //   秒级中断请看「守护事件」与「被杀 / 拉起」计数，这条曲线是小时级健康度。
        boolean warmup = SurvivalData.sinceBootMs() < 3600_000L;
        boolean noData = warmup || series.length == 0;

        // ---- 前两条进度条（方案 14 核心：把健康度量化成可视进度）----
        bindBar(v, R.id.p_bar1_track, R.id.p_bar1_fill, noData ? 0 : survivePct, R.color.progressFill);
        ((TextView) v.findViewById(R.id.p_bar1_val)).setText(noData ? "—" : survivePct + "%");
        ((TextView) v.findViewById(R.id.p_bar1_label)).setText(noData
                ? "24h 存活率 · 满 1 小时后统计" : "24h 存活率");

        // 最长连续存活：按 24h 满格换算进度条
        // ★ 046 口径修正：槽粒度是 1 小时，刚开机采到 1 个存活样本就显示「1h / 24h」
        //   属于虚进位（实际才开机 1 分 41 秒）。把连续存活换算成毫秒后与
        //   本次开机时长取小，显示统一走 fmtUp —— 观察期多久就显示多久。
        int longestH = SurvivalData.longestContinuous(series);
        long streakMs = Math.min(longestH * 3600_000L, SurvivalData.sinceBootMs());
        int longestPct = (int) Math.min(100, streakMs * 100 / (24 * 3600_000L));
        bindBar(v, R.id.p_bar2_track, R.id.p_bar2_fill, longestPct, R.color.progressFill2);
        ((TextView) v.findViewById(R.id.p_bar2_val)).setText(series.length == 0 || streakMs <= 0
                ? "—" : SurvivalData.fmtUp(streakMs) + " / 24h");

        // 旧引用随列表重建作废；这里清空后由 buildHomeList 重新登记
        uptimeViews.clear();
        statViews.clear();
        buildHomeList(ctx, targets);
        buildTimeline(ctx);
        // 刚建好的行立刻填字，避免首帧空一拍；第三条进度条与守护中数量也在这里一起刷
        refreshUptimes();

        // hero 小字：跟随激活状态（预览稿 .k14 .big .s）
        // ★ 056：存活率同样受「开机满 1 小时才开始统计」约束，warmup 时显示「—」
        TextView heroSmall = v.findViewById(R.id.hero_small);
        if (count == 0) {
            heroSmall.setText("尚未选择应用 · 去「应用」页勾选");
        } else {
            heroSmall.setText("模块已激活 · 存活率 " + (noData ? "—" : survivePct + "%"));
        }
    }

    /**
     * 把进度条填充宽度设成轨道宽度的 pct%。
     *
     * 轨道是 FrameLayout、填充是它的子 View，所以这里要等轨道测量完才能换算像素宽度；
     * 未测量时（首帧）用 post 丢到下一条消息再算，避免拿到 0 宽导致进度条不显示。
     */
    private void bindBar(View root, int trackId, int fillId, int pct, int colorRes) {
        final View track = root.findViewById(trackId);
        final View fill = root.findViewById(fillId);
        if (track == null || fill == null) return;
        final int clamped = Math.max(0, Math.min(100, pct));
        fill.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(c(colorRes)));
        final Runnable apply = () -> {
            int tw = track.getWidth();
            if (tw <= 0) return;
            ViewGroup.LayoutParams lp = fill.getLayoutParams();
            lp.width = (int) (tw * clamped / 100f);
            fill.setLayoutParams(lp);
        };
        if (track.getWidth() > 0) apply.run();
        else track.post(apply);
    }

    /** 主页「已守护应用」列表。 */
    private void buildHomeList(Context ctx, Set<String> targets) {
        // ★ 058 P0：必须判空。本方法被 ticker 每秒链路间接调用，
        //   而 tickerRunning 只能挡住「下一拍不再排队」，挡不住「本拍执行到一半被销毁」——
        //   那一瞬间 getView() 会返回 null，随即 NPE 崩溃（Fragment 已 detach）。
        //   同类判空 refreshUptimes / refreshLiveStats 早有，这两处是漏网的。
        View root = getView();
        if (root == null) return;
        LinearLayout box = root.findViewById(R.id.home_list);
        if (box == null) return;
        box.removeAllViews();
        List<String> list = new ArrayList<>(targets);
        Collections.sort(list);
        if (list.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("尚未选择任何应用，去「应用」页勾选");
            empty.setTextSize(12.5f);
            empty.setTextColor(c(R.color.colorOnSurfaceVariant));
            empty.setPadding(dp(14), dp(14), dp(14), dp(14));
            box.addView(empty);
            return;
        }
        for (String pkg : list) {
            box.addView(makeHomeRow(ctx, pkg));
        }
    }

    /** 主页「守护事件」时间线：默认只展示最近 N 条，其余收拢，用户点按钮展开。 */
    private static final int EVENTS_COLLAPSED = 4;

    /** 事件列表是否已展开（false=只显示最近几条 + 「展开全部」按钮）。 */
    private boolean eventsExpanded = false;

    /** 主页「守护事件」时间线：倒序渲染最近事件（时间 · 应用名 · 类型徽标）。 */
    private void buildTimeline(Context ctx) {
        // ★ 058 P0：判空理由同 buildHomeList —— getView() 在销毁窗口中会返回 null。
        View root = getView();
        if (root == null) return;
        LinearLayout box = root.findViewById(R.id.home_timeline);
        if (box == null) return;
        box.removeAllViews();
        List<SurvivalData.GuardEvent> evs = SurvivalData.guardEvents(ctx);
        if (evs.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无守护事件（应用被杀 / 被拉起会在这里记录）");
            empty.setTextSize(12.5f);
            empty.setTextColor(c(R.color.colorOnSurfaceVariant));
            empty.setPadding(dp(14), dp(14), dp(14), dp(14));
            box.addView(empty);
            return;
        }
        // 折叠时只渲染最近几条；展开时全部渲染（上限 200 条，防极端情况把页面撑爆）
        int show = eventsExpanded ? Math.min(evs.size(), 200)
                : Math.min(evs.size(), EVENTS_COLLAPSED);
        for (int i = 0; i < show; i++) {
            box.addView(makeTimelineRow(ctx, evs.get(i)));
        }
        // 还有被收拢的事件 → 底部给一个展开/收起按钮
        if (evs.size() > EVENTS_COLLAPSED) {
            box.addView(makeExpandToggle(ctx, evs.size()));
        }
    }

    /** 「展开全部 N 条 / 收起」按钮：点击切换 eventsExpanded 并重建时间线。 */
    private View makeExpandToggle(Context ctx, int total) {
        TextView btn = new TextView(ctx);
        btn.setText(eventsExpanded ? "收起 ▲" : ("展开全部 " + total + " 条 ▼"));
        btn.setTextSize(11.5f);
        btn.setTextColor(c(R.color.colorPrimary2));
        btn.setGravity(android.view.Gravity.CENTER);
        btn.setPadding(dp(12), dp(9), dp(12), dp(9));
        btn.setBackgroundResource(R.drawable.bg_row_card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        btn.setLayoutParams(lp);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setOnClickListener(v -> {
            eventsExpanded = !eventsExpanded;
            buildTimeline(requireContext());
        });
        return btn;
    }

    /** 时间线单行（时间 + 应用名 + 类型徽标，类型用红/绿/青区分）。 */
    private View makeTimelineRow(Context ctx, SurvivalData.GuardEvent e) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.setBackgroundResource(R.drawable.bg_row_card);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(6);
        row.setLayoutParams(rowLp);

        // 时间（等宽，HH:mm:ss）
        TextView time = new TextView(ctx);
        time.setText(fmtClock(e.time));
        time.setTextSize(11);
        time.setTypeface(android.graphics.Typeface.MONOSPACE);
        time.setTextColor(c(R.color.colorOnSurfaceVariant));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tl.setMargins(0, 0, dp(10), 0);
        time.setLayoutParams(tl);
        row.addView(time);

        // 应用名
        TextView name = new TextView(ctx);
        name.setText(getAppLabel(ctx, e.pkg));
        name.setTextSize(12.5f);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTextColor(c(R.color.colorOnSurface));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        nl.setMargins(dp(8), 0, dp(8), 0);
        name.setLayoutParams(nl);
        row.addView(name);

        // 触发方（拉起来源）：「模块拉活 · 第一防线/第二防线/开机种子」或「托底保活」。
        // 前缀直接是【谁动的手】——模块主动补拉 vs 系统 persistent 重启；
        // 被杀事件 who 为空，不显示。让用户一眼看出这次是谁把应用拉起来的。
        if (e.who != null && !e.who.isEmpty()) {
            TextView who = new TextView(ctx);
            who.setText(e.who);
            who.setTextSize(10.5f);
            who.setMaxLines(1);
            who.setEllipsize(android.text.TextUtils.TruncateAt.END);
            who.setTextColor(c(R.color.colorOnSurfaceVariant));
            LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            wl.setMargins(0, 0, dp(8), 0);
            who.setLayoutParams(wl);
            row.addView(who);
        }

        // 被杀原因标签：仅被杀事件（type=0）有值，如「用户划卡片清除」「系统低内存回收」
        // 「用户强制停止」等。让用户一眼看出这次是被谁/什么杀的，而不是只看到笼统的「被杀」。
        if (e.reason != null && !e.reason.isEmpty()) {
            TextView r = new TextView(ctx);
            r.setText(e.reason);
            r.setTextSize(10.5f);
            r.setMaxLines(1);
            r.setEllipsize(android.text.TextUtils.TruncateAt.END);
            r.setTextColor(c(R.color.colorDanger));
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rl.setMargins(0, 0, dp(8), 0);
            r.setLayoutParams(rl);
            row.addView(r);
        }

        // 类型徽标：0=被杀(红) 1=拉起(绿) 2=开机拉起(青)
        String[] labels = {"被杀", "拉起", "开机拉起"};
        int[] colors = {R.color.colorDanger, R.color.colorOk, R.color.colorPrimary2};
        int t = e.type >= 0 && e.type < labels.length ? e.type : 0;
        TextView badge = new TextView(ctx);
        badge.setText(labels[t]);
        badge.setTextSize(10.5f);
        badge.setTypeface(null, android.graphics.Typeface.BOLD);
        badge.setPadding(dp(8), dp(2), dp(8), dp(2));
        int tc = c(colors[t]);
        android.graphics.drawable.GradientDrawable gdb = new android.graphics.drawable.GradientDrawable();
        gdb.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gdb.setColor(hexA(0x1f, tc));
        gdb.setCornerRadius(dp(10));
        badge.setBackground(gdb);
        badge.setTextColor(tc);
        row.addView(badge);
        return row;
    }

    /** 主页「已守护应用」列表行（预览稿 .k14 .row：独立卡片，右对齐时长 + 计数）。 */
    private View makeHomeRow(Context ctx, String pkg) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        // .row{padding:10px 12px;margin-bottom:8px;border-radius:13px}
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackgroundResource(R.drawable.bg_row_card);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(8);
        row.setLayoutParams(rowLp);
        // 点整行任意位置：弹出该应用的全量守护信息（不再是跳转应用页）
        row.setOnClickListener(v -> showGuardDialog(ctx, pkg));

        // .ic
        ImageView iv = new ImageView(ctx);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(38)));
        Drawable d = getAppIcon(ctx, pkg);
        if (d != null) iv.setImageDrawable(d);
        else {
            iv.setBackgroundResource(R.drawable.bg_icon);
            iv.setBackgroundTintList(android.content.res.ColorStateList.valueOf(colorForPkg(pkg)));
        }
        row.addView(iv);

        // .meta：应用名 + 包名
        LinearLayout meta = new LinearLayout(ctx);
        meta.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        // .row{gap:11px}
        mlp.setMargins(dp(11), 0, dp(11), 0);
        meta.setLayoutParams(mlp);
        TextView name = new TextView(ctx);
        name.setText(getAppLabel(ctx, pkg));
        name.setTextSize(13);
        name.setTextColor(c(R.color.colorOnSurface));
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView pkgV = new TextView(ctx);
        pkgV.setText(pkg);
        pkgV.setTextSize(9.5f);
        pkgV.setTextColor(c(R.color.colorOnSurfaceVariant));
        pkgV.setMaxLines(1);
        pkgV.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(2);
        pkgV.setLayoutParams(plp);
        meta.addView(name);
        meta.addView(pkgV);
        // 已启用功能标签行（位置 A）：把该目标开启的保活能力平铺成小标签，
        // 一进主页就能横向扫到各应用「开了什么功能」，不必逐个点进弹窗看。
        LinearLayout tags = buildFuncTags(ctx, pkg);
        if (tags != null) meta.addView(tags);
        row.addView(meta);

        // .right：保活时长 + 「被杀 / 拉起」计数，整体右对齐
        LinearLayout right = new LinearLayout(ctx);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(android.view.Gravity.END);
        TextView st = new TextView(ctx);
        st.setText(SurvivalData.fmtUp(SurvivalData.uptimeMs(ctx, pkg)));
        st.setTextSize(12);
        st.setTypeface(null, android.graphics.Typeface.BOLD);
        st.setTextColor(c(R.color.colorPrimary2));
        st.setGravity(android.view.Gravity.END);
        st.setTag(pkg);
        uptimeViews.add(st);
        TextView badge = new TextView(ctx);
        badge.setTextSize(9.5f);
        badge.setSingleLine(true);
        badge.setTag(pkg);
        badge.setGravity(android.view.Gravity.END);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(3);
        badge.setLayoutParams(blp);
        statViews.add(badge);
        right.addView(st);
        right.addView(badge);
        row.addView(right);
        return row;
    }

    /**
     * 生成某目标「已启用功能」的标签行；一个功能都没开（理论上不会，防强停默认开）返回 null。
     *
     * <p>标签来源是 {@link Prefs} 里的 per-app 开关，全部读内存快照、无 IPC：
     * 防强停 / 拦后台清理（默认开）、强杀拦截、常驻、消息保活、保活档位。
     * 只用短词，避免撑爆行宽；太多时靠 HorizontalScrollView 横向滚动。
     */
    private LinearLayout buildFuncTags(Context ctx, String pkg) {
        // ★ 048：标签口径抽到 GuardTags，主页与进程页守护卡共用同一份实现，
        //   避免「同一个功能两个叫法 / 一边有一边没有」的不对标再次出现。
        return GuardTags.makeRow(ctx, pkg);
    }

    /**
     * 「已守护应用」行点击：弹出该应用的全量守护信息。
     *
     * 计数口径（均为本次开机累计）：系统框架侧钩子检测到「跑起来过又掉线」记「被杀」
     * 一次（开机后本就没起来的首次拉起不计），静默拉起成功记「拉起」一次；
     * 关总开关 / 取消该应用保活 / 重启手机后归零。
     */
    private void showGuardDialog(Context ctx, String pkg) {
        String label = getAppLabel(ctx, pkg);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(6), dp(20), 0);
        // 先建行再取值 View：四行是动态数据（状态/时长/被杀/被拉起），弹窗开着要跟着走
        LinearLayout rowState = (LinearLayout) dialogRow(ctx, "当前状态", "…");
        LinearLayout rowUp = (LinearLayout) dialogRow(ctx, "连续保活时长", "…");
        LinearLayout rowK = (LinearLayout) dialogRow(ctx, "被杀死次数", "…");
        LinearLayout rowS = (LinearLayout) dialogRow(ctx, "被拉起次数", "…");
        box.addView(rowState);
        box.addView(rowUp);
        box.addView(rowK);
        box.addView(rowS);
        box.addView(dialogRow(ctx, "包名", pkg));

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(label)
                .setIcon(getAppIcon(ctx, pkg))
                .setView(box)
                .setPositiveButton("好的", null)
                .create();
        dialog.show();

        // 弹窗内的心跳：和主页行同一套 1s 节拍。
        // 之前值全是打开瞬间写死的快照，「连续保活时长」停着不走，被杀/拉起次数
        // 和运行状态也不会跟着钩子的最新报告变。这里每秒重读一遍；关掉弹窗即停表。
        TextView vState = (TextView) rowState.getChildAt(1);
        TextView vUp = (TextView) rowUp.getChildAt(1);
        TextView vK = (TextView) rowK.getChildAt(1);
        TextView vS = (TextView) rowS.getChildAt(1);
        final Handler dh = new Handler(Looper.getMainLooper());
        final Runnable dtick = new Runnable() {
            @Override
            public void run() {
                if (!dialog.isShowing()) return;   // 已关闭：不再续拍
                long[] g = SurvivalData.guardStats(ctx, pkg);
                long u = SurvivalData.uptimeMs(ctx, pkg);
                vState.setText(SurvivalData.isAlive(ctx, pkg) ? "运行中" : "未运行");
                vUp.setText(u > 0 ? SurvivalData.fmtUp(u) : "—");
                vK.setText(g[0] + " 次");
                vS.setText(g[1] + " 次");
                dh.postDelayed(this, TICK_MS);
            }
        };
        dh.post(dtick);
        dialog.setOnDismissListener(d -> dh.removeCallbacksAndMessages(null));
    }

    /** 详情页一行：左标签（次要色）+ 右内容（主文字色）。 */
    private View dialogRow(Context ctx, String key, String value) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(7), 0, dp(7));
        TextView k = new TextView(ctx);
        k.setText(key);
        k.setTextSize(13);
        k.setTextColor(c(R.color.colorOnSurfaceVariant));
        k.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView v = new TextView(ctx);
        v.setText(value);
        v.setTextSize(13);
        v.setTextColor(c(R.color.colorOnSurface));
        v.setMaxLines(2);
        r.addView(k);
        r.addView(v);
        return r;
    }

    // ---- 工具 ----
    /**
     * 已守护目标里「当前在跑」的数量。
     *
     * 口径必须和时长统计一致：权威数据源是 system_server 钩子每 10 秒经 Provider 推回的
     * 进程报告（App 自身受 hidepid=2 限制读不到别家 /proc，AMS 的可见性也被裁剪）。
     * 这里统一走 {@link SurvivalData#isAlive}，它在报告新鲜时直接用报告、过期才退回本地探测，
     * 避免主页「运行中 0/3」和「未运行」文案打架。
     */
    private int countRunning(Context ctx, Set<String> targets) {
        if (targets.isEmpty()) return 0;
        int n = 0;
        for (String pkg : targets) {
            if (SurvivalData.isAlive(ctx, pkg)) n++;
        }
        return n;
    }

    /**
     * 取应用图标。走 {@link AppIcons} 的进程级缓存——反复切页不会反复解码，
     * 应用页那边装载列表时也是同一份缓存，两边互相命中。
     */
    @Nullable
    private Drawable getAppIcon(Context ctx, String pkg) {
        return AppIcons.get(ctx, pkg);
    }

    private String getAppLabel(Context ctx, String pkg) {
        try {
            return ctx.getPackageManager()
                    .getApplicationLabel(ctx.getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private int colorForPkg(String pkg) {
        int[] palette = {c(R.color.colorPrimary), c(R.color.colorPrimary2),
                c(R.color.colorPrimary3), c(R.color.colorOk), c(R.color.colorWarn)};
        return palette[Math.abs(pkg.hashCode()) % palette.length];
    }

    private int c(int resId) {
        return ContextCompat.getColor(requireContext(), resId);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static String readFile(File f) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }
}
