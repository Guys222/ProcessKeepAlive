package io.github.guys222.processkeepalive;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppsFragment extends Fragment {

    private SharedPreferences prefs;
    private final List<AppInfo> allApps = new ArrayList<>();
    private final List<AppInfo> shown = new ArrayList<>();
    /** 已启用的应用：包名 -> 该应用的完整配置。 */
    private final Map<String, AppConfig> configs = new HashMap<>();
    private boolean showSystem = false;
    /** 仅看已守护目标。 */
    private boolean onlyProtected = false;

    private AppAdapter adapter;
    private EditText searchBox;
    private TextView chipUser, chipSystem, chipProtected, countLabel;
    private RecyclerView list;

    /** 应用清单装载线程（枚举应用 + 读标签）。守护线程，随进程回收。 */
    private static final ExecutorService LOAD_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pka-apps");
        t.setDaemon(true);
        return t;
    });
    /** 装载序号：只接受最后一次装载的回填，避免快速重复刷新时结果乱序。 */
    private int loadSeq;
    /** 清单正在后台装载（计数行显示「读取中…」，而不是假的「共 0 个应用」）。 */
    private boolean loadingApps;


    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_apps, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        prefs = requireContext().getSharedPreferences(Prefs.PREFS_NAME, Context.MODE_PRIVATE);
        loadSelection();
        showSystem = prefs.getBoolean(Prefs.KEY_SHOW_SYSTEM, false);

        searchBox = view.findViewById(R.id.search);
        list = view.findViewById(R.id.list);
        ImageButton btnMore = view.findViewById(R.id.btn_more);
        SwipeRefreshLayout swipe = view.findViewById(R.id.swipe);
        chipUser = view.findViewById(R.id.chip_user);
        chipSystem = view.findViewById(R.id.chip_system);
        chipProtected = view.findViewById(R.id.chip_protected);
        TextView chipCloseAll = view.findViewById(R.id.chip_closeall);
        countLabel = view.findViewById(R.id.apps_count);

        loadApps();

        adapter = new AppAdapter();
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);

        swipe.setOnRefreshListener(() -> {
            // 下拉是用户主动要看最新状态，别让 700ms 的进程快照缓存挡着
            SurvivalData.invalidateProcCache();
            // 装载是异步的，刷新圈要等列表真正刷新完再收，否则一松手就消失、像没反应
            loadApps(() -> swipe.setRefreshing(false));
        });

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { rebuild(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        chipUser.setOnClickListener(v -> setSys(false));
        chipSystem.setOnClickListener(v -> setSys(true));
        chipProtected.setOnClickListener(v -> setOnly(!onlyProtected));
        chipCloseAll.setOnClickListener(v -> selectNone());

        btnMore.setOnClickListener(this::showMenu);

        syncChips();
        rebuild();
    }

    /** 用户应用 / 显示系统应用（互斥）。 */
    private void setSys(boolean v) {
        showSystem = v;
        onlyProtected = false;
        prefs.edit().putBoolean(Prefs.KEY_SHOW_SYSTEM, v).apply();
        syncChips();
        rebuild();
    }

    /** 仅看已守护（开启时自动包含系统应用）。 */
    private void setOnly(boolean v) {
        onlyProtected = v;
        if (v) showSystem = true;
        syncChips();
        rebuild();
    }

    /** 一键关闭全部守护目标。 */
    private void selectNone() {
        configs.clear();
        saveTargets();
        // 全部取消保活：所有被杀/拉起计数一并清零
        SurvivalData.clearAllGuardStats(requireContext());
        rebuild();
        syncChips();
        android.widget.Toast.makeText(requireContext(), "已关闭全部", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void syncChips() {
        if (chipUser == null) return;
        styleChip(chipUser, !showSystem && !onlyProtected);
        styleChip(chipSystem, showSystem && !onlyProtected);
        styleChip(chipProtected, onlyProtected);
    }

    /** chip 行样式（预览稿 .chip / .chip.active）。 */
    private void styleChip(TextView chip, boolean active) {
        chip.setBackgroundResource(active ? R.drawable.bg_chip_filter_active : R.drawable.bg_chip);
        chip.setTextColor(ContextCompat.getColor(requireContext(),
                active ? R.color.colorOnSurface : R.color.colorOnSurfaceVariant));
    }

    private void loadSelection() {
        configs.clear();
        Set<String> targets = prefs.getStringSet(Prefs.KEY_TARGETS, new HashSet<>());
        // 老数据（或没有单独设过档位的应用）回退到设置页的默认档位，保证两处一致
        int fallbackAdj = Prefs.Priority.clamp(prefs.getInt(Prefs.KEY_PRIORITY, 1));
        for (String pkg : targets) {
            AppConfig c = new AppConfig();
            c.adj = Prefs.Priority.clamp(
                    prefs.getInt(Prefs.KEY_ADJ_PREFIX + pkg, fallbackAdj));
            // 拦截类开关的默认值 = false（与 AppConfig 字段默认一致）。
            // 注意：这里读不到键时给 true 会让「老用户已存过 false」被无视吗——不会，
            // containsKey 为真时读到的就是存的 false；只有「从未配置过」的新目标才走默认。
            c.forceStop = prefs.getBoolean(Prefs.KEY_FORCE_PREFIX + pkg, false);
            c.killBackground = prefs.getBoolean(Prefs.KEY_KILLBG_PREFIX + pkg, false);
            c.kill = prefs.getBoolean(Prefs.KEY_KILL_PREFIX + pkg, false);
            c.persistent = prefs.getBoolean(Prefs.KEY_PERSIST_PREFIX + pkg, false);
            // ★ core 必须在打开对话框时就回填，否则 cfg.core 恒为 false：
            //   界面上的「核心级」会显示为关闭，用户只要再点一次「确定」，就会被
            //   cfg.core = swCore.isChecked() 静默写回 false —— 这就是「开了核心级、
            //   重新进去看就掉了」的直接原因。
            c.core = prefs.getBoolean(Prefs.KEY_CORE_PREFIX + pkg, false);
            c.msg = prefs.getBoolean(Prefs.KEY_MSG_PREFIX + pkg, false);
            configs.put(pkg, c);
        }
    }

    /**
     * 装载应用清单（后台线程）。
     *
     * 两处刻意的处理：
     * 1. 不加载图标——图标解码最贵，推迟到绑定可见行时由 {@link AppIcons#into} 异步解码；
     * 2. 枚举已安装应用 + 逐个读标签在主线程也要上百毫秒，切页时会掉帧，所以整个搬到后台，
     *    装载完再回主线程回填。这期间计数行显示「读取中…」，不留一个假的「共 0 个应用」。
     */
    private void loadApps() {
        loadApps(null);
    }

    /**
     * @param onDone 装载结束时在主线程回调（下拉刷新用它收起刷新圈）。
     *               注意它在每次装载结束时都会执行，即便这次结果已经过期被丢弃——
     *               否则快速重复刷新时，被丢弃的那次不回调，刷新圈会一直转。
     */
    private void loadApps(@Nullable final Runnable onDone) {
        final Context ctx = requireContext().getApplicationContext();
        final int seq = ++loadSeq;   // 连续下拉刷新时，只认最后一次装载的结果
        loadingApps = true;
        LOAD_EXEC.execute(() -> {
            List<AppInfo> loaded = new ArrayList<>();
            PackageManager pm = ctx.getPackageManager();
            String self = ctx.getPackageName();
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if (ai.packageName.equals(self)) continue;
                loaded.add(new AppInfo(ai.packageName, pm.getApplicationLabel(ai).toString(),
                        (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                                || (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0));
            }
            loaded.sort((a, b) -> a.label.compareToIgnoreCase(b.label));

            View root = getView();
            if (root == null) return;
            root.post(() -> {
                if (onDone != null) onDone.run();
                // 期间可能已经离屏、销毁，或又发起了一次装载
                if (!isAdded() || seq != loadSeq) return;
                allApps.clear();
                allApps.addAll(loaded);
                loadingApps = false;
                rebuild();
            });
        });
    }

    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        popup.getMenu().add(showSystem ? "隐藏系统应用" : "显示系统应用");
        popup.getMenu().add("全部关闭");
        popup.setOnMenuItemClickListener(item -> {
            String title = item.getTitle() == null ? "" : item.getTitle().toString();
            if ("隐藏系统应用".equals(title)) {
                showSystem = false;
                prefs.edit().putBoolean(Prefs.KEY_SHOW_SYSTEM, false).apply();
                syncChips();
                rebuild();
                return true;
            } else if ("显示系统应用".equals(title)) {
                showSystem = true;
                prefs.edit().putBoolean(Prefs.KEY_SHOW_SYSTEM, true).apply();
                syncChips();
                rebuild();
                return true;
            } else if ("全部关闭".equals(title)) {
                selectNone();
                return true;
            }
            return false;
        });
        popup.show();
    }

    /** 点击应用条目，弹出该应用的完整配置对话框。 */
    private void showConfigDialog(AppInfo a) {
        AppConfig c = configs.get(a.packageName);
        if (c == null) {
            // 新应用从设置页的「默认档位」起步，而不是硬编码值
            c = AppConfig.withDefaultPriority(prefs.getInt(Prefs.KEY_PRIORITY, 1));
            configs.put(a.packageName, c);
        }
        final AppConfig cfg = c;

        View v = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_app_config, null);
        SwitchCapsuleView swForce = v.findViewById(R.id.sw_force);
        SwitchCapsuleView swKillBg = v.findViewById(R.id.sw_killbg);
        SwitchCapsuleView swKill = v.findViewById(R.id.sw_kill);
        SwitchCapsuleView swPersist = v.findViewById(R.id.sw_persist);
        SwitchCapsuleView swCore = v.findViewById(R.id.sw_core);
        SwitchCapsuleView swMsg = v.findViewById(R.id.sw_msg);

        // 档位名称 + 系统意愿说明：让用户直接看到这个 adj 值意味着什么
        // （mode 0 最强 → 2 最弱，与 KeepAliveHooks.desiredAdj 的换算一致）
        final int[] rowIds = {R.id.row_adj_0, R.id.row_adj_1, R.id.row_adj_2};
        final int[] dotIds = {R.id.dot_adj_0, R.id.dot_adj_1, R.id.dot_adj_2};
        final int[] labelIds = {R.id.label_adj_0, R.id.label_adj_1, R.id.label_adj_2};
        final int[] descIds = {R.id.desc_adj_0, R.id.desc_adj_1, R.id.desc_adj_2};
        // 方案 B：档位整组（三行）与标题，锁定时整体隐藏，只留绿色「实际生效」框
        final View adjRows = v.findViewById(R.id.adj_rows);
        final TextView adjHead = v.findViewById(R.id.adj_head);

        // 不用 RadioGroup：它的互斥依赖直接子 View 是 CompoundButton，中间夹一层
        // LinearLayout 就会失效（三项同时可选）。这里自己维护唯一选中项。
        final int[] selected = {Prefs.Priority.clamp(cfg.adj)};
        // 常驻/核心开启时，系统接管 adj，档位被底座覆盖 → 隐藏整组不可选（方案 B）。
        // ★ 060：消息保活【不再】纳入档位锁定。057 曾让「保活消息进程」开启时屏蔽档位，
        //   目的是单独测消息保活链路；实测后确认两者机制不同、可叠加、不冲突：
        //     档位 → 改 adj/procState，让【内核】少杀（解决「被杀」）
        //     消息保活 → Doze/Standby 豁免 + 主进程伪装前台，让【应用自己】别断长连接
        //   所以恢复为：档位只受常驻/核心影响，与消息保活各自独立、同时可用。
        final boolean[] locked = { cfg.persistent || cfg.core };
        final TextView realAdj = v.findViewById(R.id.adj_real);
        final TextView hint = v.findViewById(R.id.adj_hint);
        final int globalDefault = Prefs.Priority.clamp(prefs.getInt(Prefs.KEY_PRIORITY, 1));

        // 刷新档位区：可点/灰掉 + 「实际生效」提示（方案 B 直接显示真实值）
        final Runnable refreshAdjLock = () -> {
            for (int j = 0; j < Prefs.Priority.COUNT; j++) {
                v.findViewById(dotIds[j]).setBackgroundResource(
                        j == selected[0] ? R.drawable.bg_radio_dot_on : R.drawable.bg_radio_dot);
                ((TextView) v.findViewById(labelIds[j])).setTextColor(ContextCompat.getColor(
                        requireContext(),
                        j == selected[0] ? R.color.colorPrimary : R.color.colorOnSurface));
            }
            if (locked[0]) {
                // 方案 B：整个隐藏三行选项（不再灰着占位），标题标明已被系统接管
                adjHead.setText("保活档位（已由系统接管）");
                adjRows.setVisibility(View.GONE);
                String tag;
                if (cfg.core) {
                    tag = "托底保活 · 核心级（adj -1000）";
                } else if (cfg.persistent) {
                    tag = "托底保活 · 常驻级（adj -800）";
                } else {
                    tag = "托底保活";  // 理论上不会到这里，兜底防 NPE 式文案错乱
                }
                realAdj.setVisibility(View.VISIBLE);
                realAdj.setText("实际生效：" + tag + "。你选的模块档位（"
                        + Prefs.Priority.policyName(Prefs.Priority.clamp(selected[0]))
                        + "）被系统兜底覆盖，无需调整。");
                hint.setTextColor(0xfff0b34a);
                hint.setText("已开托底保活：档位由系统接管，下方显示真实生效值。");
            } else {
                adjHead.setText("保活档位");
                adjRows.setVisibility(View.VISIBLE);
                realAdj.setVisibility(View.GONE);
                hint.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorOnSurfaceVariant));
                hint.setText("设置页默认档位：" + Prefs.Priority.policyName(globalDefault)
                        + "（" + Prefs.Priority.adjName(globalDefault) + "）"
                        + "，此处可按应用单独覆盖");
            }
        };

        // ★ 053：档位说明改成「隐藏式」—— 与下方「托底保活」的功能说明同一模式：
        //   收起时只留一行纯文字摘要（adj 数值 · 常量名），完整的效果/代价解释藏进
        //   det_adj_*（默认 GONE），点摘要小字展开/收起。
        //   点整行 row_adj_* 仍是选中档位，互不冲突（子 View clickable 后事件被自己消费）。
        //   全弹窗统一：隐藏式说明 + 顶部一条文字提醒，逐项不挂任何箭头标记。
        final int[] detAdjIds = {R.id.det_adj_0, R.id.det_adj_1, R.id.det_adj_2};
        for (int i = 0; i < Prefs.Priority.COUNT; i++) {
            ((TextView) v.findViewById(labelIds[i])).setText(Prefs.Priority.both(i));
            final int mode = i;
            final TextView desc = v.findViewById(descIds[i]);
            final TextView det = v.findViewById(detAdjIds[i]);
            det.setText(Prefs.Priority.meaning(mode)
                    + "\n进程类型：" + Prefs.Priority.kind(mode));
            // 053：摘要就是一行纯文字（adj 数值 · 常量名），全弹窗统一隐藏式 + 顶部文字提醒。
            // ★ 054：隐藏块 det 自身也必须可点收起 —— 否则说明展开后，用户第二下自然
            //   点在说明文字上，det 不可点击会把事件冒泡给整行 row_adj_*，被当成
            //   「选中档位」消费掉，看起来就是「点第二下不收起」（实测反馈）。
            desc.setText(Prefs.Priority.adjDetail(mode));
            View.OnClickListener toggleDet = view -> det.setVisibility(
                    det.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            desc.setOnClickListener(toggleDet);
            det.setOnClickListener(toggleDet);
            v.findViewById(rowIds[i]).setOnClickListener(view -> {
                if (locked[0]) return; // 灰掉时不可选
                selected[0] = mode;
                refreshAdjLock.run();
            });
        }
        // 功能说明（det_*）本来就是隐藏式；053 给摘要小字补上同样的「展开 ▼」尾巴 ——
        // 之前只有档位有可见标记，下面六个功能光秃秃的，用户不知道也能点（本轮反馈）。
        // 点摘要小字或点整行都会展开；点开关本身仍只切开关，不误触。
        final int[] wrapIds = {R.id.row_wrap_force, R.id.row_wrap_killbg, R.id.row_wrap_kill,
                R.id.row_wrap_persist, R.id.row_wrap_core, R.id.row_wrap_msg};
        final int[] detIds = {R.id.det_force, R.id.det_killbg, R.id.det_kill,
                R.id.det_persist, R.id.det_core, R.id.det_msg};
        final int[] sumIds = {R.id.sum_force, R.id.sum_killbg, R.id.sum_kill,
                R.id.sum_persist, R.id.sum_core, R.id.sum_msg};
        for (int i = 0; i < wrapIds.length; i++) {
            final TextView det = v.findViewById(detIds[i]);
            // 053：摘要保持布局里的原文字，不加任何尾巴（与档位一致的隐藏式）。
            View.OnClickListener toggle = view -> det.setVisibility(
                    det.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            v.findViewById(wrapIds[i]).setOnClickListener(toggle);
            v.findViewById(sumIds[i]).setOnClickListener(toggle);
        }

        // 049：顶部提示条本身可点一下收起 —— 老用户天天开这个弹窗，不需要每次都读一遍。
        final TextView dialogTip = v.findViewById(R.id.dialog_tip);
        dialogTip.setOnClickListener(view -> dialogTip.setVisibility(View.GONE));

        refreshAdjLock.run(); // 初始选中态 + 锁定态

        swForce.setChecked(cfg.forceStop, false);
        swKillBg.setChecked(cfg.killBackground, false);
        swKill.setChecked(cfg.kill, false);
        swPersist.setChecked(cfg.persistent, false);
        // ★ 回填核心开关：之前漏了这行 —— 重开弹窗时核心开关显示「未开启」，
        //   但绿色「实际生效 -1000」读的是 cfg 又显示对，自相矛盾；更糟的是
        //   此时直接按「确定」，cfg.core = swCore.isChecked() 会把核心静默关掉。
        swCore.setChecked(cfg.core, false);
        swMsg.setChecked(cfg.msg, false);
        // 「保活消息进程」是豁免系统省电限制的开关，用 accent 渐变强调（预览稿 .sw.acc）
        swMsg.setAccent(true);

        // 常驻开关：开启即锁定档位；关闭后是否仍锁取决于核心是否开
        swPersist.setOnCheckedChangeListener((view, checked) -> {
            cfg.persistent = checked;
            locked[0] = cfg.persistent || cfg.core;
            refreshAdjLock.run();
        });
        // ★ 057→060：「保活消息进程」与档位是两条独立链路，开关【不影响】档位可选性。
        //   档位改 adj/procState（内核少杀），消息保活走 Doze/Standby 豁免 + 伪装前台
        //   （让应用别自己断长连接），机制不同、可同时开启、互不覆盖。
        swMsg.setOnCheckedChangeListener((view, checked) -> {
            cfg.msg = checked;
        });
        // 系统核心开关：开启自动连带开常驻并锁定常驻；关闭解锁（常驻回到用户控制）
        swCore.setOnCheckedChangeListener((view, checked) -> {
            cfg.core = checked;
            if (checked) {
                cfg.persistent = true;
                swPersist.setChecked(true, false);
                swPersist.setEnabled(false);
            } else {
                swPersist.setEnabled(true);
            }
            locked[0] = cfg.persistent || cfg.core;
            refreshAdjLock.run();
        });
        // 初始：核心已开 → 常驻锁定不可点
        if (cfg.core) swPersist.setEnabled(false);

        new AlertDialog.Builder(requireContext())
                .setTitle(a.label)
                .setView(v)
                .setPositiveButton("确定", (d, which) -> {
                    cfg.adj = selected[0];
                    cfg.forceStop = swForce.isChecked();
                    cfg.killBackground = swKillBg.isChecked();
                    cfg.kill = swKill.isChecked();
                    cfg.persistent = swPersist.isChecked();
                    cfg.core = swCore.isChecked();
                    cfg.msg = swMsg.isChecked();
                    configs.put(a.packageName, cfg);
                    saveTargets();
                    rebuild();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void rebuild() {
        String q = searchBox == null ? "" : searchBox.getText().toString().trim().toLowerCase();
        shown.clear();
        for (AppInfo a : allApps) {
            if (a.system && !showSystem) continue;
            if (onlyProtected && !configs.containsKey(a.packageName)) continue;
            if (!q.isEmpty()
                    && !a.label.toLowerCase().contains(q)
                    && !a.packageName.toLowerCase().contains(q)) {
                continue;
            }
            shown.add(a);
        }
        // 已启用应用置顶，其余按名称排序
        shown.sort((a, b) -> {
            boolean sa = configs.containsKey(a.packageName);
            boolean sb = configs.containsKey(b.packageName);
            if (sa != sb) return sa ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        if (adapter != null) adapter.notifyDataSetChanged();
        if (countLabel != null) {
            // 后台装载期间先给个「读取中…」，别先闪一个「共 0 个应用」再跳成真实数量
            countLabel.setText(loadingApps ? "读取中…" : "共 " + shown.size() + " 个应用");
        }
    }

    private void saveTargets() {
        // ★ 前置守卫：Fragment 已 detach 时下面几处 requireContext() 会抛
        //   IllegalStateException。配置本身用 prefs 写不依赖 Context，
        //   但 rearmFallbackTimer 需要 Context —— 拿不到就跳过打点（下次进页面会补）。
        final Context appCtx = isAdded() ? requireContext().getApplicationContext() : null;
        SharedPreferences.Editor e = prefs.edit();
        e.putStringSet(Prefs.KEY_TARGETS, new HashSet<>(configs.keySet()));
        for (Map.Entry<String, AppConfig> en : configs.entrySet()) {
            String pkg = en.getKey();
            AppConfig c = en.getValue();
            e.putInt(Prefs.KEY_ADJ_PREFIX + pkg, c.adj);
            e.putBoolean(Prefs.KEY_FORCE_PREFIX + pkg, c.forceStop);
            e.putBoolean(Prefs.KEY_KILLBG_PREFIX + pkg, c.killBackground);
            e.putBoolean(Prefs.KEY_KILL_PREFIX + pkg, c.kill);
            e.putBoolean(Prefs.KEY_PERSIST_PREFIX + pkg, c.persistent);
            e.putBoolean(Prefs.KEY_CORE_PREFIX + pkg, c.core);
            e.putBoolean(Prefs.KEY_MSG_PREFIX + pkg, c.msg);
            // 兜底打点：记录「本次开机内的时刻」而不是墙钟时间 —— 后者不会因为关机而
            // 停顿，手机放一夜再开机仍会显示十几小时。同时记下开机次数，供读取端判定失效。
            if (appCtx != null && !prefs.contains(Prefs.KEY_GUARD_START_PREFIX + pkg)) {
                SurvivalData.rearmFallbackTimer(appCtx, pkg);
            }
        }
        e.apply();
    }

    /**
     * 档位说明小字：数值 + 常量名 + 一句「效果 + 代价」的人话。
     * 例：adj 0 · FOREGROUND_APP_ADJ
     *     保活最强：系统按「正在前台使用」对待，几乎不会被回收。代价是更耗电、更占资源。
     *
     * 刻意不再带「前台应用 / 可见应用 / 可感知应用」这类档位名 —— 它已经写在标题行里，
     * 副标题重复一遍纯属噪音。
     */
    // ★ 053：withToggleTail（「展开 ▼ / 收起 ▲」彩色尾巴）已整体移除。
    //   弹窗统一为「隐藏式 + 顶部一条文字提醒」，逐项不再挂任何可见标记 ——
    //   用户明确要求：全部改回隐藏式，只保留文字提醒。
    //   档位摘要 = Prefs.Priority.adjDetail(mode)（一行：adj 数值 · 常量名），
    //   完整解释进 det_adj_*（默认 GONE）；六个功能摘要沿用布局原文 + det_*（默认 GONE）。

    private int typeColor(int mode) {
        switch (mode) {
            case 0: return Color.rgb(0xc6, 0x28, 0x28);
            case 2: return Color.rgb(0x2e, 0x7d, 0x32);
            default: return Color.rgb(0x15, 0x65, 0xc0);
        }
    }

    private GradientDrawable badgeBg(int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        float r = requireContext().getResources().getDisplayMetrics().density * 8f;
        gd.setCornerRadius(r);
        return gd;
    }

    private class AppAdapter extends RecyclerView.Adapter<AppAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_app, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            AppInfo a = shown.get(position);
            // 异步 + 缓存：只为可见的十几行解码，不再预解码全部已安装应用
            AppIcons.into(requireContext(), a.packageName, h.icon);
            h.name.setText(a.label);
            h.pkg.setText(a.packageName);

            boolean sel = configs.containsKey(a.packageName);
            h.switchApp.setOnCheckedChangeListener(null);
            h.switchApp.setChecked(sel, false);
            h.switchApp.setOnCheckedChangeListener((v, checked) -> {
                // ★ 用 v.getContext()（即 View 自己的 Context），不要用 requireContext()。
                //   监听器可能在该行已被回收、Fragment 尚未重新 attach 时被触发
                //   （RecyclerView 复用时切换开关），此时 requireContext() 会抛
                //   IllegalStateException: Fragment not attached to a context 而崩溃。
                //   View 只要还在，它的 Context 就一定有效。
                final android.content.Context ctx = v.getContext();
                if (checked) {
                    if (!configs.containsKey(a.packageName)) {
                        // 列表开关直接勾选：采用设置页的默认档位
                        configs.put(a.packageName, AppConfig.withDefaultPriority(
                                prefs.getInt(Prefs.KEY_PRIORITY, 1)));
                    }
                } else {
                    configs.remove(a.packageName);
                    // 取消保活：该应用的被杀/拉起计数一并清零（重新勾选从 0 重新统计）
                    SurvivalData.clearGuardStats(ctx, a.packageName);
                }
                saveTargets();
                notifyItemChanged(h.getAdapterPosition());
            });

            // 点击整行：弹出该应用的完整配置
            h.itemView.setOnClickListener(v -> showConfigDialog(a));

            // 条目底部分隔线：最后一行不画（预览稿 .approw:last-child{border-bottom:none}）
            boolean last = position == shown.size() - 1;
            h.divider.setVisibility(last ? View.GONE : View.VISIBLE);

            // 徽标（档位/省电）已删除：完整功能标签统一在主页「已守护应用」下方
            // 展示（更醒目的彩色版），列表行只留名称/包名/开关
            h.badge.setVisibility(View.GONE);
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name, pkg, badge;
            final View divider;
            final SwitchCapsuleView switchApp;

            VH(View v) {
                super(v);
                icon = v.findViewById(R.id.app_icon);
                name = v.findViewById(R.id.app_name);
                pkg = v.findViewById(R.id.app_pkg);
                badge = v.findViewById(R.id.type_badge);
                divider = v.findViewById(R.id.row_divider);
                switchApp = v.findViewById(R.id.switch_app);
            }
        }
    }
}
