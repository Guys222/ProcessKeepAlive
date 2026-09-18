package io.github.guys222.processkeepalive;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.app.ActivityManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 进程页（第 4 个 tab）。D 版（守护视角）布局：
 *
 * 数据来自 system_server 经 ConfigProvider 推回的全量 /proc 快照（App 自己读不到别家 /proc）。
 * 布局分三层，各归其位：
 *   ① 守护卡（绿色）：把本模块配置里的守护目标（Prefs.targets）置顶成状态卡，
 *      每张卡显示「钉在核心级 / 守护中」徽章 + 已守护时长 + 进程数 + 内存 + 每进程 chip，
 *      直接回答「钉没钉住」这个保活工具最核心的问题。
 *   ② 「其他进程」折叠行：其余近 200 个非守护进程默认收成一行，点开才展开。
 *   ③ 展开后的排查列表：复用原有的应用分组 + 进程行（功能、杀进程逻辑完全保留，
 *      只是把可见的红色/橙色杀按钮收进长按菜单，减少刷屏）。
 * 系统/内核进程单独归到「系统进程」组，随「其他进程」一起折叠。
 * 打开页面时写 proc_wanted 让 system_server 开始扫描，3 秒心跳持续刷新。
 */
public class ProcessesFragment extends Fragment {

    /** 进程页「在看」心跳：持续把 proc_wanted 顶到最新，system_server 才会一直扫全量 /proc。 */
    private static final long TICK_MS = 3000L;
    private final Handler tickerHandler = new Handler(Looper.getMainLooper());
    /**
     * ★ 059 P2：后台构建结果回主线程专用的 Handler。
     * 原本在每轮 rebuild 的 post 处 {@code new Handler(Looper.getMainLooper())} ——
     * 3 秒一次、每次一个新对象，纯属浪费；提为成员复用即可（主线程 Looper 全局唯一）。
     */
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            markWanted();
            rebuild();
            tickerHandler.postDelayed(this, TICK_MS);
        }
    };

    private SharedPreferences prefs;
    private EditText searchBox;
    private TextView chipAll, chipApp, chipSort, summary;
    private TextView emptyTitle, emptySub;
    private View emptyView;
    private RecyclerView list;
    private SwipeRefreshLayout swipe;
    private ImageButton btnMore;

    /**
     * 应用名缓存（pkg → label）。
     *
     * <p>★ 卡顿根因修复：原先 {@code rebuild()} 在主线程对每个应用组调
     * {@code getApplicationLabel()}，那是 PackageManager 的 <b>IPC</b> 调用；
     * 全机上百个应用组 + 每 3 秒重建一次 = 主线程被 IPC 拖住，切页/滚动就会卡。
     * 应用页（{@code AppsFragment}）早就把这类重活丢到后台线程做了，进程页照搬。
     * label 基本不变，缓存后每轮重建只查一次 HashMap，零 IPC。
     */
    // ★ 059 P2：HashMap → ConcurrentHashMap。
    //   本缓存由后台 BUILD_EXEC 线程读写（rebuild 内的 label()），而 rebuild 的触发源
    //   有好几个（ticker 每 3 秒、onViewCreated、onResume、下拉刷新），
    //   rebuildInFlight 只挡「同一条流水线在途」，挡不住不同来源并发进入。
    //   一旦两线程同时 put，plain HashMap 可能出现环形链表 / 丢更新（Java 8 前甚至死循环）。
    //   缓存本身只是加速用的旁路，用并发容器是零成本消除风险。
    private final Map<String, String> labelCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** 进程页后台构建线程：分组/排序/取 label 全部挪出主线程，只在最后回主线程刷 UI。 */
    private static final java.util.concurrent.ExecutorService BUILD_EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "pka-procs");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });

    /** 是否有一次后台重建在途（避免每 3 秒叠多个任务）。 */
    private volatile boolean rebuildInFlight = false;

    private ProcAdapter adapter;
    /** 当前搜索词（小写）。 */
    private String query = "";
    /** 仅看应用（隐藏系统进程组）。 */
    private boolean onlyApp = false;
    /** 组内进程排序：false=按内存降序，true=按名称升序。 */
    private boolean sortByName = false;
    /** 已折叠的组 key 集合（跨刷新保留用户的展开/折叠选择）。 */
    private final Set<String> collapsedKeys = new java.util.HashSet<>();
    /** D 版：「其他进程」是否展开（默认折叠，点开才看排查列表）。 */
    private boolean othersExpanded = false;

    /** D 版守护目标数据（来自 keepalive_prefs，直接读 SharedPreferences，主线程安全）。 */
    private Set<String> guardTargets = new java.util.HashSet<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_processes, container, false);
    }

    /** 进程快照导出：走系统文件选择器，不需要存储权限。 */
    private final ActivityResultLauncher<String> exportLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"),
                    uri -> {
                        if (uri == null) return;
                        doExport(uri);
                    });

    /** 把当前快照写成文本报告存到用户选的位置。 */
    private void doExport(Uri uri) {
        try {
            String text = ProcSnapshotExport.build(requireContext());
            if (text == null) {
                Toast.makeText(requireContext(), "当前没有可用快照，先等 system_server 扫描完成",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            ConfigBackup.writeText(requireContext(), uri, text);
            Toast.makeText(requireContext(), "进程快照已导出", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "导出失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        prefs = requireContext().getSharedPreferences(Prefs.PREFS_NAME, Context.MODE_PRIVATE);

        searchBox = view.findViewById(R.id.search);
        chipAll = view.findViewById(R.id.chip_all);
        chipApp = view.findViewById(R.id.chip_app);
        chipSort = view.findViewById(R.id.chip_sort);
        summary = view.findViewById(R.id.proc_summary);
        list = view.findViewById(R.id.list);
        emptyView = view.findViewById(R.id.empty_view);
        emptyTitle = view.findViewById(R.id.empty_title);
        emptySub = view.findViewById(R.id.empty_sub);
        swipe = view.findViewById(R.id.swipe);
        btnMore = view.findViewById(R.id.btn_more);

        // D 版：载入守护目标包名集合（core 级 / 守护起始时间按 pkg 在绑定卡片时惰性读取）
        Set<String> ts = prefs.getStringSet(Prefs.KEY_TARGETS, java.util.Collections.emptySet());
        guardTargets = new java.util.HashSet<>(ts != null ? ts : java.util.Collections.emptySet());

        adapter = new ProcAdapter();
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString().trim().toLowerCase();
                rebuild();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        chipAll.setOnClickListener(v -> setOnlyApp(false));
        chipApp.setOnClickListener(v -> setOnlyApp(true));
        chipSort.setOnClickListener(v -> {
            sortByName = !sortByName;
            chipSort.setText(sortByName ? "名称↑" : "内存↓");
            syncChips();
            rebuild();
        });

        swipe.setOnRefreshListener(() -> {
            markWanted();
            rebuild();
            swipe.setRefreshing(false);
        });

        btnMore.setOnClickListener(this::showMenu);

        markWanted();
        rebuild();
        syncChips();
    }

    @Override
    public void onResume() {
        super.onResume();
        markWanted();
        rebuild();
        tickerHandler.postDelayed(ticker, TICK_MS);
    }

    @Override
    public void onPause() {
        tickerHandler.removeCallbacks(ticker);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        tickerHandler.removeCallbacks(ticker);
        super.onDestroyView();
    }

    /** 告诉 system_server「进程页在看」，让它开始扫全量 /proc 并回传快照。 */
    private void markWanted() {
        try {
            prefs.edit().putLong(Prefs.KEY_PROC_WANTED, System.currentTimeMillis()).apply();
            android.util.Log.d("PKA", "markWanted 写 proc_wanted=" + System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
    }

    private void setOnlyApp(boolean v) {
        onlyApp = v;
        syncChips();
        rebuild();
    }

    private void syncChips() {
        styleChip(chipAll, !onlyApp);
        styleChip(chipApp, onlyApp);
        styleChip(chipSort, false);
        chipSort.setText(sortByName ? "名称↑" : "内存↓");
    }

    private void styleChip(TextView chip, boolean active) {
        chip.setBackgroundResource(active ? R.drawable.bg_chip_filter_active : R.drawable.bg_chip);
        chip.setTextColor(ContextCompat.getColor(requireContext(),
                active ? R.color.colorOnSurface : R.color.colorOnSurfaceVariant));
    }

    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        popup.getMenu().add("刷新进程列表");
        popup.getMenu().add("导出进程快照");
        popup.getMenu().add(onlyApp ? "显示系统进程" : "仅看应用进程");
        popup.setOnMenuItemClickListener(item -> {
            String title = item.getTitle() == null ? "" : item.getTitle().toString();
            if ("刷新进程列表".equals(title)) {
                markWanted();
                rebuild();
                return true;
            } else if ("导出进程快照".equals(title)) {
                exportLauncher.launch(ProcSnapshotExport.suggestName());
                return true;
            } else if ("仅看应用进程".equals(title)) {
                setOnlyApp(true);
                return true;
            } else if ("显示系统进程".equals(title)) {
                setOnlyApp(false);
                return true;
            }
            return false;
        });
        popup.show();
    }

    // ---- 分组模型 ----

    private static final class Group {
        String key;        // pkg 或 "__sys__"
        String label;      // 应用名或 "系统进程"
        String pkg;        // null 表示系统/内核进程
        boolean isSystem;
        final List<ProcessInfo> procs = new ArrayList<>();
        long totalRssKb;
    }

    private static final class Item {
        static final int GROUP = 0;
        static final int PROC = 1;
        static final int GUARD = 2;     // D 版：守护中绿色状态卡
        static final int OTHERS = 3;    // D 版：其他进程折叠行
        final int type;
        final Group group;
        final ProcessInfo proc;
        int othersCount;                // OTHERS 用
        long othersMem;                 // OTHERS 用
        boolean othersOpen;             // OTHERS 用：当前是展开态（行文案/箭头随之切换）
        Item(int type, Group group, ProcessInfo proc) {
            this.type = type;
            this.group = group;
            this.proc = proc;
        }
    }

    /** 当前渲染的扁平条目（守护卡 + 其他折叠行 + 展开后的组头/进程行交替）。 */
    private final List<Item> items = new ArrayList<>();

    /**
     * 重算整份列表：读快照 → 分组 → 排序 → 扁平化 → 刷新摘要 + 适配器。
     * 心跳、搜索、筛选、排序、展开/折叠都调它。
     *
     * <p>★ 卡顿修复：分组/排序/取 label 这些重活全部挪到 {@link #BUILD_EXEC} 后台线程，
     * 只在最后把算好的 {@code items} 回主线程交给适配器 —— 与应用页同一套做法。
     * 原先全在主线程做（含每组一次 PackageManager IPC），切页/滚动会被拖卡。
     * 每轮只允许一个在途任务，避免每 3 秒堆积。
     */
    private void rebuild() {
        if (rebuildInFlight) return;   // 上一轮还没算完，跳过本轮，避免叠任务
        rebuildInFlight = true;
        final Context appCtx = requireContext().getApplicationContext();
        final String q = query;
        final boolean onlyAp = onlyApp;
        final boolean byName = sortByName;
        final Set<String> collapsed = new java.util.HashSet<>(collapsedKeys);
        // ★ 059 P1：每轮 rebuild 都重读目标名单，不能复用 onViewCreated 时的旧快照。
        //   原实现在 :179 只读一次存进 guardTargets，之后每轮都拿这份陈旧副本 ——
        //   用户在「应用」页新勾选/取消守护目标后回到进程页，卡片分组**不更新**，
        //   要杀掉页面重进才生效。（后端 isTarget 已在 058 改成 10 秒新鲜度，
        //   进程页这份前端副本却还停在 30 秒级更旧的状态，两边对不上。）
        //   读 SharedPreferences 是线程安全的，且本段在 rebuild 里、3 秒一次，开销可忽略。
        Set<String> latest = prefs.getStringSet(Prefs.KEY_TARGETS,
                java.util.Collections.emptySet());
        guardTargets = new java.util.HashSet<>(
                latest != null ? latest : java.util.Collections.emptySet());
        final Set<String> targets = new java.util.HashSet<>(guardTargets);
        final boolean expand = othersExpanded;

        BUILD_EXEC.execute(() -> {
            // ★ 045 提速：快照的读取 + 逐行 decode 原先在主线程做（每 3 秒一次、近 200 个进程
            //   split("\n") + ProcessInfo.decode），滚动/切页会被它拖出掉帧。
            //   SharedPreferences 读取是线程安全的，appCtx 也不会随 Fragment 销毁失效，
            //   整块挪进后台线程即可；snap 经 Handler.post 回主线程，可见性由 post 保证。
            final SurvivalData.ProcessSnapshot snap = SurvivalData.processSnapshot(appCtx);
            final BuildResult r = buildItemsSync(appCtx, snap, q, onlyAp, byName, collapsed,
                    targets, expand);
            // ★ 059 P2：删除后台线程里的 isAdded() 判定。
            //   Fragment.isAdded() 读的是 mHost 字段，并非设计给后台线程使用的 API
            //   （无同步保证），在 BUILD_EXEC 上调用属未定义行为。
            //   生命周期判定统一收进下面主线程的回调里 —— 那里读 isAdded() 才是正确的。
            //   同时把每轮 new Handler 提为成员（见 uiHandler），3 秒一次的重建不再产生垃圾。
            uiHandler.post(() -> {
                rebuildInFlight = false;
                if (!isAdded()) return;   // 主线程读 isAdded()：安全且语义正确
                items.clear();
                items.addAll(r.items);
                adapter.notifyDataSetChanged();
                applySummary(snap, r);
            });
        });
    }

    /** 后台构建结果：扁平条目 + 统计。 */
    private static final class BuildResult {
        final List<Item> items = new ArrayList<>();
        int procCount, appCount;
    }

    /** 后台线程执行：分组 / 排序 / 过滤 / 取 label（带缓存）。只读快照，不碰 UI。 */
    private BuildResult buildItemsSync(Context appCtx, SurvivalData.ProcessSnapshot snap,
                                       String q, boolean onlyAp, boolean byName,
                                       Set<String> collapsed,
                                       Set<String> targets, boolean expandOthers) {
        BuildResult res = new BuildResult();
        List<ProcessInfo> all = snap.list;

        // 分组：应用按 pkg，系统/内核归到独立的「系统进程」组
        Map<String, Group> appGroups = new LinkedHashMap<>();
        Group sysGroup = null;
        for (ProcessInfo p : all) {
            if (p.isSystem()) {
                if (sysGroup == null) {
                    sysGroup = new Group();
                    sysGroup.key = "__sys__";
                    sysGroup.label = "系统进程";
                    sysGroup.isSystem = true;
                }
                sysGroup.procs.add(p);
            } else {
                Group g = appGroups.get(p.pkg);
                if (g == null) {
                    g = new Group();
                    g.key = p.pkg;
                    g.pkg = p.pkg;
                    g.isSystem = false;
                    appGroups.put(p.pkg, g);
                }
                g.procs.add(p);
            }
        }
        // 标签走缓存（首次仍会 IPC，但只一次；之后每 3 秒重建零 IPC）
        for (Group g : appGroups.values()) {
            g.label = cachedAppLabel(appCtx, g.pkg);
            g.totalRssKb = sumRss(g.procs);
        }
        if (sysGroup != null) sysGroup.totalRssKb = sumRss(sysGroup.procs);

        // 组内进程排序
        Comparator<ProcessInfo> procCmp = byName
                ? Comparator.comparing((ProcessInfo p) -> p.name.toLowerCase())
                : (a, b) -> Long.compare(b.vmRssKb, a.vmRssKb);
        for (Group g : appGroups.values()) Collections.sort(g.procs, procCmp);
        if (sysGroup != null) Collections.sort(sysGroup.procs, procCmp);

        // 组顺序：应用按总内存降序，系统组置底
        List<Group> groups = new ArrayList<>(appGroups.values());
        groups.sort((a, b) -> Long.compare(b.totalRssKb, a.totalRssKb));
        if (sysGroup != null) groups.add(sysGroup);
        if (onlyAp && sysGroup != null) groups.remove(sysGroup);

        // D 版分层：守护目标抽出来置顶，其余归入「其他进程」
        List<Group> guardGroups = new ArrayList<>();
        List<Group> otherGroups = new ArrayList<>();
        for (Group g : groups) {
            if (!g.isSystem && targets.contains(g.pkg)) guardGroups.add(g);
            else otherGroups.add(g);
        }
        // 守护卡内部按内存降序
        guardGroups.sort((a, b) -> Long.compare(b.totalRssKb, a.totalRssKb));

        // 搜索时强制展开（与「其他进程」展开等价）
        boolean showFlat = !q.isEmpty() || expandOthers;

        // ① 守护卡：只有真正出现在快照里的守护目标才渲染
        for (Group g : guardGroups) {
            if (g.procs.isEmpty()) continue;
            res.items.add(new Item(Item.GUARD, g, null));
        }

        // ② 「其他进程」折叠行：无论展开与否都渲染 —— 展开时它就是「收起」按钮，
        //    否则展开后页面上再没有任何入口能收回去（022 的实测反馈）。
        int oc = 0;
        long om = 0;
        for (Group g : otherGroups) {
            oc += g.procs.size();
            om += g.totalRssKb;
        }
        Item oh = new Item(Item.OTHERS, null, null);
        oh.othersCount = oc;
        oh.othersMem = om;
        oh.othersOpen = showFlat;
        res.items.add(oh);

        if (showFlat) {
            // ③ 展开后的排查列表（分组 + 进程行），复用原有逻辑
            int procCount = 0;
            for (Group g : otherGroups) {
                boolean groupHit = q.isEmpty()
                        || (g.label != null && g.label.toLowerCase().contains(q))
                        || (g.pkg != null && g.pkg.toLowerCase().contains(q));
                List<ProcessInfo> visible = new ArrayList<>();
                for (ProcessInfo p : g.procs) {
                    if (q.isEmpty()
                            || p.name.toLowerCase().contains(q)
                            || (p.pkg != null && p.pkg.toLowerCase().contains(q))) {
                        visible.add(p);
                    }
                }
                // 组名命中但组内无进程名命中：仍展示整组，避免「搜包名却空列表」
                if (visible.isEmpty() && groupHit) visible = new ArrayList<>(g.procs);
                if (visible.isEmpty()) continue;

                procCount += visible.size();
                res.items.add(new Item(Item.GROUP, g, null));
                boolean expanded = !collapsed.contains(g.key) || !q.isEmpty();
                if (expanded) {
                    for (ProcessInfo p : visible) res.items.add(new Item(Item.PROC, g, p));
                }
            }
            res.procCount = procCount;
        }
        res.appCount = appGroups.size();
        return res;
    }

    /** 带缓存取应用名：命中缓存零 IPC；未命中才查 PackageManager 并写回。 */
    private String cachedAppLabel(Context ctx, String pkg) {
        if (pkg == null) return "";
        String hit = labelCache.get(pkg);
        if (hit != null) return hit;
        String label = getAppLabel(ctx, pkg);
        labelCache.put(pkg, label);
        return label;
    }

    /** 主线程：按快照新鲜度 + 构建结果刷摘要与空状态（三种情况分开说，别让用户对着空列表猜）。 */
    private void applySummary(SurvivalData.ProcessSnapshot snap, BuildResult res) {
        Context ctx = requireContext();
        if (!snap.fresh) {
            if (!snap.ever) {
                // 从未收到过任何上报。先用「基础进程报告」探测钩子是否在线：
                // 在线 = system_server 里跑的是旧版钩子（有基础上报、没有进程快照通道），重启手机即可；
                // 不在线 = 新钩子压根没生效，查 LSPosed 作用域 / 是否重启过。
                if (SurvivalData.procReport(ctx).fresh) {
                    summary.setText("钩子在线 · system_server 是旧版代码");
                    summary.setTextColor(ContextCompat.getColor(ctx, R.color.colorWarn));
                    showEmpty("旧版钩子 · 重启手机后生效",
                            "system_server 正在持续上报基础进程数据，说明钩子已注入；"
                                    + "但本次开机加载的钩子不含进程快照通道 —— "
                                    + "安装/更新模块后必须重启手机才会加载新钩子代码。现在重启即可。");
                } else {
                    summary.setText("未收到任何上报 · 模块钩子未生效");
                    summary.setTextColor(ContextCompat.getColor(ctx, R.color.colorDanger));
                    showEmpty("未收到任何上报",
                            "system_server 从未推送过任何进程数据，说明新钩子没有生效。\n"
                                    + "请确认 LSPosed 已启用本模块并勾选「系统框架」作用域，"
                                    + "且安装/更新模块后重启过手机。");
                }
            } else {
                // 收到过但过期了：等下一轮扫描就行
                summary.setText("数据过期 · 等待 system_server 扫描…");
                summary.setTextColor(ContextCompat.getColor(ctx, R.color.colorWarn));
                showEmpty("正在等待扫描…",
                        "已收到过进程上报，但数据刚过期。\n"
                                + "system_server 正在重新扫描全量 /proc，通常几秒内恢复，下拉可加速。");
            }
        } else {
            // D 版：摘要压成一行，避免两行小字刷屏
            long[] sys = readSysMemKb(ctx);
            String memPart = sys != null
                    ? " · 已用 " + fmtMem(sys[0]) + " / " + fmtMem(sys[1]) + "（内核可见）"
                    : "";
            summary.setText("共 " + res.appCount + " 个应用 · " + res.procCount + " 个进程" + memPart);
            summary.setTextColor(ContextCompat.getColor(ctx, R.color.colorOnSurfaceVariant));
            if (res.items.isEmpty()) {
                if (!query.isEmpty()) {
                    showEmpty("无匹配进程", "没有进程或应用命中「" + query + "」，换个关键词试试。");
                } else {
                    showEmpty("暂无进程", "快照是新的，但当前没有可展示的进程。");
                }
            } else {
                emptyView.setVisibility(View.GONE);
            }
        }
    }

    /** 空列表占位：设置标题/副标题并显示（有数据时由调用方隐藏）。 */
    private void showEmpty(String title, String sub) {
        emptyTitle.setText(title);
        emptySub.setText(sub);
        emptyView.setVisibility(View.VISIBLE);
    }

    private static long sumRss(List<ProcessInfo> procs) {
        long s = 0;
        for (ProcessInfo p : procs) s += p.vmRssKb;
        return s;
    }

    // ---- 适配器 ----

    private class ProcAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int VH_GROUP = 0;
        private static final int VH_PROC = 1;
        private static final int VH_GUARD = 2;
        private static final int VH_OTHERS = 3;

        @Override
        public int getItemViewType(int position) {
            switch (items.get(position).type) {
                case Item.GUARD:  return VH_GUARD;
                case Item.OTHERS: return VH_OTHERS;
                case Item.PROC:   return VH_PROC;
                default:          return VH_GROUP;
            }
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            switch (viewType) {
                case VH_GUARD:  return new GuardVH(inf.inflate(R.layout.item_guard_card, parent, false));
                case VH_OTHERS: return new OthersVH(inf.inflate(R.layout.item_others_header, parent, false));
                case VH_PROC:   return new ProcVH(inf.inflate(R.layout.item_proc_row, parent, false));
                default:        return new GroupVH(inf.inflate(R.layout.item_proc_group, parent, false));
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            Item it = items.get(position);
            switch (it.type) {
                case Item.GUARD:  bindGuard((GuardVH) h, it.group); break;
                case Item.OTHERS: bindOthers((OthersVH) h, it); break;
                case Item.PROC:   bindProc((ProcVH) h, it.group, it.proc); break;
                default:          bindGroup((GroupVH) h, it.group); break;
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        // ---- 守护卡 ----
        private void bindGuard(GuardVH h, Group g) {
            Context ctx = requireContext();
            if (g.isSystem) {
                h.icon.setImageDrawable(AppIcons.placeholder(g.key));
            } else {
                AppIcons.into(ctx, g.pkg, h.icon);
            }
            h.name.setText(g.label);
            // ★ 051：一个应用只留一套徽章（用户拍板：留下方一排）。
            //   右上钉值徽章整个撤掉，「核心级 / 常驻级 / 档位 / 消息保活…」全部由
            //   下方功能标签行独家承担，与主页「已守护应用」完全同一套（GuardTags 口径）。
            //   钉值信息不丢：说明行第一行仍写「钉在常驻级 adj -800」；
            //   050 的「不谎报」修正保留 —— 没开常驻/核心的目标，文字如实写「档位 前台级 adj 0」。
            h.badge.setVisibility(View.GONE);
            boolean isCore = prefs.getBoolean(Prefs.KEY_CORE_PREFIX + g.pkg, false);
            boolean isPersist = prefs.getBoolean(Prefs.KEY_PERSIST_PREFIX + g.pkg, false);
            int adjMode = Prefs.Priority.clamp(prefs.getInt(Prefs.KEY_ADJ_PREFIX + g.pkg, 1));
            String pin;
            if (isCore) {
                pin = "钉在核心级 adj -1000";
            } else if (isPersist) {
                pin = "钉在常驻级 adj -800";
            } else {
                pin = "档位 " + Prefs.Priority.adjName(adjMode)
                        + " adj " + Prefs.Priority.adjValue(adjMode);
            }

            // ★ 052：守护时长不再在这里显示 —— 主页「已守护应用」列表行已经在右边
            //   显示了同一个 SurvivalData.uptimeMs 口径的时长（HomeFragment:703），
            //   进程页再放一份属于重复信息。这里只留钉值 + 进程数。
            //   （049 的两行拆分随之收回：去掉时长后一行就装得下，布局 maxLines=2 留作兜底。）
            h.meta.setText(pin + " · " + g.procs.size() + " 进程");
            h.mem.setText(fmtMem(g.totalRssKb));

            // ★ 055：功能标签行整体删除（用户要求）。
            //   进程页守护卡回到最简：图标 + 名字 + 钉值说明 + 进程明细 chips。
            //   钉值信息没丢 —— 说明行仍写「钉在常驻级 adj -800 · N 进程」；
            //   功能标签只在主页「已守护应用」看（那里仍是 GuardTags 同一口径）。
            // ★ 059 P2：原 h.tags.removeAllViews()/setVisibility 已删 —— 容器本身
            //   连同布局节点一并移除，不再需要每轮做无意义的清空。

            // 进程明细 chips（动态生成）
            h.chips.removeAllViews();
            for (ProcessInfo p : g.procs) {
                h.chips.addView(makeChip(ctx, p));
            }

            // 长按：杀整个应用（守护目标被杀会被自动重新拉起）
            h.itemView.setOnLongClickListener(v -> {
                showGroupMenu(g);
                return true;
            });
        }

        /** 一个被守护进程的 chip：进程名 · 内存 · 钉住徽章。 */
        private View makeChip(Context ctx, ProcessInfo p) {
            TextView t = new TextView(ctx);
            t.setText(p.name + "  " + fmtMem(p.vmRssKb));
            t.setTextSize(11.5f);
            t.setTextColor(ContextCompat.getColor(ctx, R.color.colorOnSurface));
            t.setPadding(0, 0, 0, 0);
            Drawable left = ContextCompat.getDrawable(ctx, R.drawable.bg_dot);
            if (left != null) {
                left.setTintList(android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.colorOk)));
                t.setCompoundDrawablesWithIntrinsicBounds(left, null, null, null);
                t.setCompoundDrawablePadding(6);
            }
            LinearLayout wrap = new LinearLayout(ctx);
            wrap.setOrientation(LinearLayout.HORIZONTAL);
            wrap.setGravity(android.view.Gravity.CENTER_VERTICAL);
            wrap.setPadding(0, 3, 0, 3);
            TextView pin = new TextView(ctx);
            pin.setText("钉住");
            pin.setTextSize(9.5f);
            pin.setTextColor(ContextCompat.getColor(ctx, R.color.colorOk));
            pin.setBackgroundResource(R.drawable.bg_pill);
            pin.setPadding(6, 1, 6, 1);
            LinearLayout.MarginLayoutParams mp = new LinearLayout.MarginLayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            mp.setMargins(8, 0, 0, 0);
            pin.setLayoutParams(mp);
            wrap.addView(t);
            wrap.addView(pin);
            // ★ 043：守护卡进程 chip 也给长按杀入口 —— 原先只有「其他进程」展开后的
            //   行能杀单个进程，守护目标只能整体关闭，不对称（用户反馈）。
            //   注意：杀守护目标的进程会被自动重新拉起（确认弹窗已说明）；
            //   要真正停掉某个守护应用，应先在应用页关掉它的守护开关。
            wrap.setOnLongClickListener(v -> {
                showProcMenu(p, wrap);
                return true;
            });
            return wrap;
        }

        // ---- 其他进程折叠行（展开时变「收起」按钮）----
        private void bindOthers(OthersVH h, Item it) {
            h.title.setText(it.othersOpen ? "收起其他进程" : "其他进程");
            h.mem.setText(it.othersCount + " 个 · " + fmtMem(it.othersMem));
            h.chevron.setRotation(it.othersOpen ? 180f : 0f);
            h.itemView.setOnClickListener(v -> {
                othersExpanded = !othersExpanded;
                rebuild();
            });
        }

        // ---- 应用组（展开态）----
        private void bindGroup(GroupVH h, Group g) {
            Context ctx = requireContext();
            if (g.isSystem) {
                h.icon.setImageDrawable(AppIcons.placeholder(g.key));
            } else {
                AppIcons.into(ctx, g.pkg, h.icon);
            }
            h.name.setText(g.label);
            h.sub.setText((g.pkg != null ? g.pkg : "系统 / 内核进程")
                    + " · " + g.procs.size() + " 个进程");
            h.mem.setText(fmtMem(g.totalRssKb));
            boolean collapsed = collapsedKeys.contains(g.key);
            h.chevron.setRotation(collapsed ? -90f : 0f);
            h.itemView.setOnClickListener(v -> {
                if (collapsedKeys.contains(g.key)) collapsedKeys.remove(g.key);
                else collapsedKeys.add(g.key);
                rebuild();
            });
            // D 版：去掉红色「关闭整个应用」按钮，改长按弹出，减少刷屏
            h.killAll.setVisibility(View.GONE);
            h.itemView.setOnLongClickListener(v -> {
                showGroupMenu(g);
                return true;
            });
        }

        /** 组内是否存在至少一个可杀（非系统保护）进程。 */
        private boolean hasKillable(Group g) {
            for (ProcessInfo p : g.procs) {
                if (!isProtected(p)) return true;
            }
            return false;
        }

        // ---- 进程行（展开态）----
        private void bindProc(ProcVH h, Group g, ProcessInfo p) {
            Context ctx = requireContext();
            h.name.setText(p.name);
            long up = SurvivalData.ticksToUptimeMs(p.startTicks);
            String meta = "pid " + p.pid + " · " + ProcessInfo.stateLabel(p.state)
                    + " · adj " + p.oomAdj
                    + (up >= 0 ? " · " + SurvivalData.fmtUp(up) : "");
            h.meta.setText(meta);
            // 状态色：运行绿、睡眠蓝、僵尸/暂停灰、不可中断橙
            h.meta.setTextColor(stateColor(ctx, p.state));
            h.mem.setText(fmtMem(p.vmRssKb));

            // D 版：去掉橙色杀按钮，改长按弹「杀进程」
            h.kill.setVisibility(View.GONE);
            h.itemView.setOnLongClickListener(v -> {
                showProcMenu(p, v);
                return true;
            });
        }
    }

    private static class GroupVH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name, sub, mem;
        final ImageView chevron;
        final ImageButton killAll;
        GroupVH(View v) {
            super(v);
            icon = v.findViewById(R.id.g_icon);
            name = v.findViewById(R.id.g_name);
            sub = v.findViewById(R.id.g_sub);
            mem = v.findViewById(R.id.g_mem);
            chevron = v.findViewById(R.id.g_chevron);
            killAll = v.findViewById(R.id.g_killall);
        }
    }

    private static class ProcVH extends RecyclerView.ViewHolder {
        final TextView name, meta, mem;
        final ImageButton kill;
        ProcVH(View v) {
            super(v);
            name = v.findViewById(R.id.p_name);
            meta = v.findViewById(R.id.p_meta);
            mem = v.findViewById(R.id.p_mem);
            kill = v.findViewById(R.id.p_kill);
        }
    }

    private static class GuardVH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name, badge, meta, mem;
        // ★ 059 P2：删除 tags 字段（原 gc_tags 容器）。
        //   055 起进程页已不再展示功能标签行，该视图固定 GONE，属纯僵尸代码；
        //   留着只会让后来者以为"这页还有标签行"而误接回去。
        final ViewGroup chips;
        GuardVH(View v) {
            super(v);
            icon = v.findViewById(R.id.gc_icon);
            name = v.findViewById(R.id.gc_name);
            badge = v.findViewById(R.id.gc_badge);
            meta = v.findViewById(R.id.gc_meta);
            mem = v.findViewById(R.id.gc_mem);
            chips = v.findViewById(R.id.gc_chips);
        }
    }

    private static class OthersVH extends RecyclerView.ViewHolder {
        final TextView title, mem;
        final ImageView chevron;
        OthersVH(View v) {
            super(v);
            title = v.findViewById(R.id.oh_title);
            mem = v.findViewById(R.id.oh_mem);
            chevron = v.findViewById(R.id.oh_chevron);
        }
    }

    // ---- 杀进程（长按菜单触发，保持与原逻辑一致）----

    /** 关键系统/内核进程：禁止被「杀进程」误杀，避免把手机干停机。与 system_server 侧保护一致。 */
    private static boolean isProtected(ProcessInfo p) {
        if (p.pid <= 2) return true;
        if (p.name == null) return false;
        String lc = p.name.toLowerCase();
        return lc.startsWith("[")
                || lc.contains("zygote") || lc.contains("system_server")
                || lc.equals("init") || lc.equals("ueventd") || lc.equals("logd")
                || lc.equals("servicemanager") || lc.equals("surfaceflinger");
    }

    /**
     * 进程行长按 → 杀进程菜单。
     *
     * <p>★ 024 修复「菜单漂移」：进程页每 3 秒 rebuild 一次且按内存重排，
     * 行视图会被复用/挪位；原先的 PopupMenu 锚定在行视图上，锚点一动菜单就跟着漂
     * （实测：长按弹出后松手，菜单飘到别的行中间）。
     * 改为长按<b>瞬间</b>取行在屏幕上的绝对坐标，用 PopupWindow 钉死在固定位置，
     * 之后的列表刷新/重排不再影响它。
     */
    private void showProcMenu(ProcessInfo p, View anchor) {
        if (isProtected(p)) {
            Toast.makeText(requireContext(), "系统关键进程，禁止结束", Toast.LENGTH_SHORT).show();
            return;
        }
        Context ctx = requireContext();

        int[] xy = new int[2];
        anchor.getLocationOnScreen(xy);
        final int rowBottom = xy[1] + anchor.getHeight();

        TextView item = new TextView(ctx);
        item.setText("杀进程");
        item.setTextSize(15f);
        item.setTextColor(ContextCompat.getColor(ctx, R.color.colorDanger));
        int padH = dp2px(16), padV = dp2px(12);
        item.setPadding(padH, padV, padH, padV);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp2px(4), dp2px(4), dp2px(4), dp2px(4));
        box.addView(item, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ★ 043 修复弹窗样式：042 给 item 套了 android.R.drawable.list_selector_background，
        //   那是 Holo 时代的 selector，item 一获得焦点就整块渲染成橙色大色块（用户截图实锤）。
        //   改为：容器 = 圆角卡片底（colorSurface），item = 圆角水波纹按压反馈。
        GradientDrawable card = new GradientDrawable();
        card.setCornerRadius(dp2px(12));
        card.setColor(ContextCompat.getColor(ctx, R.color.colorSurface));
        box.setBackground(card);

        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp2px(10));
        mask.setColor(Color.WHITE);
        item.setBackground(new RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33000000), null, mask));

        final PopupWindow pw = new PopupWindow(box, dp2px(176),
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        // 透明底 + outsideTouchable：点外面/按返回都能关掉
        pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pw.setOutsideTouchable(true);
        pw.setElevation(dp2px(8));

        item.setOnClickListener(v -> {
            pw.dismiss();
            confirmKill(p);
        });

        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int w = box.getMeasuredWidth() > 0 ? box.getMeasuredWidth() : dp2px(176);
        // 水平：对齐被长按那行的中心，夹在屏幕内不出边；垂直：紧贴该行下缘
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        int x = Math.max(dp2px(8), Math.min(xy[0] + anchor.getWidth() / 2 - w / 2,
                screenW - w - dp2px(8)));
        pw.showAtLocation(requireActivity().getWindow().getDecorView(),
                Gravity.NO_GRAVITY, x, rowBottom + dp2px(2));
    }

    private static int dp2px(float v) {
        return Math.round(v * android.content.res.Resources.getSystem()
                .getDisplayMetrics().density);
    }

    /** 应用组长按 → 关闭整个应用确认。 */
    private void showGroupMenu(Group g) {
        if (g.isSystem) {
            Toast.makeText(requireContext(), "系统 / 内核进程组，禁止整体结束", Toast.LENGTH_SHORT).show();
            return;
        }
        confirmKillGroup(g);
    }

    private void confirmKill(ProcessInfo p) {
        Context ctx = requireContext();
        String msg = p.name + "（pid " + p.pid + "）\n内存 " + fmtMem(p.vmRssKb)
                + "\n\n将向系统框架发送 SIGKILL 强制结束该进程。"
                + (p.pkg != null ? "\n它是本模块守护目标时，结束后会被自动重新拉起。" : "");
        new AlertDialog.Builder(ctx)
                .setTitle("杀进程？")
                .setMessage(msg)
                .setPositiveButton("杀进程", (d, which) -> queueKill(p))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 关闭整个应用：确认后遍历该组所有「可杀」进程，逐个投进杀进程队列。 */
    private void confirmKillGroup(Group g) {
        Context ctx = requireContext();
        java.util.List<ProcessInfo> killable = new java.util.ArrayList<>();
        for (ProcessInfo p : g.procs) {
            if (!isProtected(p)) killable.add(p);
        }
        if (killable.isEmpty()) {
            Toast.makeText(ctx, "该应用没有可结束的进程", Toast.LENGTH_SHORT).show();
            return;
        }
        long totalRss = 0;
        for (ProcessInfo p : killable) totalRss += p.vmRssKb;
        String msg = "将结束「" + g.label + "」的全部 " + killable.size() + " 个进程"
                + "（约 " + fmtMem(totalRss) + "）。\n\n"
                + "它是本模块守护目标时，结束后会被自动重新拉起。";
        new AlertDialog.Builder(ctx)
                .setTitle("关闭整个应用？")
                .setMessage(msg)
                .setPositiveButton("全部结束", (d, which) -> {
                    for (ProcessInfo p : killable) {
                        try {
                            ConfigProvider.queueKill(p.pid);
                        } catch (Throwable ignored) {
                        }
                    }
                    markWanted();
                    Toast.makeText(requireContext(),
                            "已发送 " + killable.size() + " 个杀进程请求，稍候自动刷新",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 把 pid 送进杀进程队列，system_server 的 kill 监视线程会取走并 SIGKILL。
     *
     * <p>走的是进程内并发队列（见 {@link ConfigProvider#queueKill}），
     * 而不是 SharedPreferences 读改写 —— 后者在「读旧值 + remove」之间会把
     * 并发写入的指令一起抹掉，导致连点多个「杀进程」时部分指令永久丢失。
     */
    private void queueKill(ProcessInfo p) {
        try {
            ConfigProvider.queueKill(p.pid);
        } catch (Throwable ignored) {
        }
        // 顶一下 wanted，让 system_server 尽快把「进程已消失」的新快照推回来
        markWanted();
        Toast.makeText(requireContext(), "已发送杀进程请求，稍候自动刷新", Toast.LENGTH_SHORT).show();
    }

    // ---- 工具 ----

    private static int stateColor(Context ctx, char s) {
        int c;
        switch (s) {
            case 'R': c = R.color.colorOk; break;
            case 'S': c = R.color.colorPrimary; break;
            case 'D': c = R.color.colorWarn; break;
            case 'Z':
            case 'T': c = R.color.colorOnSurfaceVariant; break;
            default: c = R.color.colorOnSurfaceVariant;
        }
        return ContextCompat.getColor(ctx, c);
    }

    private String getAppLabel(Context ctx, String pkg) {
        try {
            return ctx.getPackageManager()
                    .getApplicationLabel(ctx.getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    // ★ 059 P2：删除零调用的 buildSummary(...)。
    //   055 把顶部汇总改成单行直接拼串后，本方法再无人调用，且其内部回退文案
    //   （「进程内存 X」）与现用文案（「已用 X / Y（内核可见）」）不一致 ——
    //   留着会让后来者照抄到错误口径。readSysMemKb / fmtMem 等公共件仍在用，保留。

    /**
     * 读系统真实内存：返回 [已用 KB, 总 KB, 可回收缓存 KB]，读不到返回 null。
     *
     * 优先读 /proc/meminfo（三个字段同源，口径一致；/proc/meminfo 全局可读，
     * 不像 /proc/&lt;pid&gt; 那样受限制）：
     *   已用       = MemTotal - MemAvailable（即不可回收的真实占用）
     *   可回收缓存 = MemAvailable - MemFree
     * 两者相加 = MemTotal - MemFree，与系统设置里的「已使用」一致，便于用户对账。
     *
     * 总量就是 MemTotal（内核可见容量）。它比厂商标称少 4~8% —— 内核保留、DMA buffer、
     * 基带占用等不经过内核内存管理，不计入 MemTotal（标称 16 GB 的机器实测只有 14.6 GB）。
     * 这里【不做】向标称档位的取整换算，保持真实值。
     *
     * 个别 ROM 可能裁掉 MemAvailable，此时退回 ActivityManager（拿不到缓存量）。
     */
    private static long[] readSysMemKb(Context c) {
        long[] m = readMemInfoKb();
        if (m != null) return m;
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return null;
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            if (mi.totalMem <= 0) return null;
            long used = mi.totalMem - mi.availMem;
            if (used < 0) used = 0;
            return new long[]{used / 1024, mi.totalMem / 1024, -1};
        } catch (Throwable t) {
            return null;
        }
    }

    /** 解析 /proc/meminfo；字段不全返回 null。 */
    private static long[] readMemInfoKb() {
        long total = -1, free = -1, avail = -1;
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (total < 0 && line.startsWith("MemTotal:")) total = memInfoVal(line);
                else if (free < 0 && line.startsWith("MemFree:")) free = memInfoVal(line);
                else if (avail < 0 && line.startsWith("MemAvailable:")) avail = memInfoVal(line);
                if (total >= 0 && free >= 0 && avail >= 0) break;
            }
        } catch (Throwable t) {
            return null;
        }
        if (total <= 0 || free < 0 || avail < 0) return null;
        long used = total - avail;
        long reclaimable = avail - free;
        if (used < 0) used = 0;
        if (reclaimable < 0) reclaimable = 0;
        return new long[]{used, total, reclaimable};
    }

    /** 从 "MemTotal:    12345678 kB" 取数值；单位已是 kB。 */
    private static long memInfoVal(String line) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(line);
            return m.find() ? Long.parseLong(m.group()) : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * KB → 友好内存字符串（>=1GB 显示 GB，>=1MB 显示 MB，否则 KB）。
     * 格式与进程快照导出（ProcSnapshotExport）保持一致，避免「页面显示 MB、导出是 GB」的割裂。
     */
    private static String fmtMem(long kb) {
        if (kb >= 1024 * 1024) return String.format(Locale.CHINA, "%.1f GB", kb / 1048576.0);
        if (kb >= 1024) return String.format(Locale.CHINA, "%.1f MB", kb / 1024.0);
        return kb + " KB";
    }
}
