package io.github.guys222.processkeepalive;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

public class SettingsFragment extends Fragment {

    private SharedPreferences prefs;
    /** 刷新开关态时避免回写 prefs。 */
    private boolean mSilent;

    private final TextView[] prioSegs = new TextView[3];

    /**
     * 分段按钮「左→右」对应的 mode 值。
     * 按钮按保守→均衡→激进排列，而 mode 0 是最强的激进档，故顺序相反。
     */
    private static final int[] SEG_MODES = {2, 1, 0};

    // ---- 关于区三条外链 ----
    private static final String URL_AUTHOR = "https://github.com/Guys222";
    private static final String URL_REPO = "https://github.com/Guys222/ProcessKeepAlive";
    private static final String URL_QQ_GROUP = "https://qm.qq.com/q/pZ1GatemOI";
    /** QQ 群号，单独存一份：行上只显示这个，长按复制也是它，比整条 URL 直观。 */
    private static final String QQ_GROUP = "687457195";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    /**
     * 备份/恢复的两个文件选择器。
     *
     * 一律走 SAF（系统文件选择器）而不是自己申请存储权限：不需要 READ/WRITE_EXTERNAL_STORAGE，
     * 用户明确知道文件落在哪，也顺便绕开了 Android 11+ 分区存储的一堆坑。
     */
    private final ActivityResultLauncher<String> exportLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"),
                    uri -> {
                        if (uri == null) return;   // 用户在文件选择器里点了取消
                        doExport(uri);
                    });

    private final ActivityResultLauncher<String[]> importLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri == null) return;
                        readAndConfirmImport(uri);
                    });

    /**
     * Android 13+ 起通知要运行权限，开关点开的那一刻才去要 —— 冷启动就弹权限框
     * 属于典型的应用自嗨，用户根本不知道为什么要授权。
     */
    private final ActivityResultLauncher<String> notifyPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                // ★ 权限弹窗是系统级的、会长期驻留，期间用户完全可能转屏导致 Fragment
                //   被销毁重建，回调随之落在已 detach 的旧实例上 —— 此时任何
                //   requireContext() 都会抛 IllegalStateException 直接崩溃。
                //   注意：这份 prefs 是旧实例在 onViewCreated 时拿的，仍可安全写
                //   （SharedPreferences 是进程级单例）；只有需要 Context 的操作要跳过，
                //   它们会在重建后的 onViewCreated 里按开关状态重新执行。
                if (!isAdded()) return;
                if (Boolean.TRUE.equals(granted)) {
                    prefs.edit().putBoolean(Prefs.KEY_NOTIFY, true).apply();
                    GuardService.start(requireContext());
                } else {
                    prefs.edit().putBoolean(Prefs.KEY_NOTIFY, false).apply();
                    mSilent = true;
                    View v = getView();
                    if (v != null) {
                        SwitchCapsuleView sw = v.findViewById(R.id.switch_notify);
                        if (sw != null) sw.setChecked(false, false);
                    }
                    mSilent = false;
                    Toast.makeText(requireContext(),
                            "没有通知权限，常驻通知无法开启", Toast.LENGTH_SHORT).show();
                }
            });

    /** 常驻通知开关：勾选即起前台服务，取消即停（服务本身不做任何保活动作）。 */
    private void bindNotifyToggle(View view) {
        SwitchCapsuleView sw = view.findViewById(R.id.switch_notify);
        sw.setChecked(prefs.getBoolean(Prefs.KEY_NOTIFY, false), false);
        sw.setOnCheckedChangeListener((v, checked) -> {
            if (mSilent) return;
            if (!checked) {
                prefs.edit().putBoolean(Prefs.KEY_NOTIFY, false).apply();
                GuardService.stop(requireContext());
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && ContextCompat.checkSelfPermission(requireContext(),
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notifyPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
                return;
            }
            prefs.edit().putBoolean(Prefs.KEY_NOTIFY, true).apply();
            GuardService.start(requireContext());
        });
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        prefs = requireContext().getSharedPreferences(Prefs.PREFS_NAME, Context.MODE_PRIVATE);

        // 外观：深色模式（胶囊开关）
        SwitchCapsuleView switchDark = view.findViewById(R.id.switch_dark);
        switchDark.setChecked(ThemeManager.isDark(requireContext()), false);
        switchDark.setOnCheckedChangeListener((v, checked) -> {
            if (mSilent) return;
            ThemeManager.setMode(requireContext(),
                    checked ? ThemeManager.MODE_DARK : ThemeManager.MODE_LIGHT);
        });

        // 保活能力
        bindToggle(view.findViewById(R.id.switch_master), Prefs.KEY_ENABLED, true);
        bindToggle(view.findViewById(R.id.switch_aggressive), Prefs.KEY_AGGRESSIVE, false);
        bindToggle(view.findViewById(R.id.switch_auto_start), Prefs.KEY_AUTO_START, false);

        // 常驻通知（顺带驱动桌面小部件的刷新）
        bindNotifyToggle(view);

        // 保活档位（预览稿 .seg 自绘分段）
        // 这里的档位是「新勾选应用的默认值」，应用自身的档位在应用配置弹窗里单独调。
        // 注意 mode 0 = 最强（激进），2 = 最弱（保守）；分段按钮按「保守→均衡→激进」
        // 的左到右顺序排列，所以按钮索引与 mode 值正好相反，靠 SEG_MODES 映射。
        prioSegs[0] = view.findViewById(R.id.prio_conservative);
        prioSegs[1] = view.findViewById(R.id.prio_balanced);
        prioSegs[2] = view.findViewById(R.id.prio_aggressive);
        for (int i = 0; i < prioSegs.length; i++) {
            final int mode = SEG_MODES[i];
            prioSegs[i].setText(Prefs.Priority.policyName(mode));
            prioSegs[i].setOnClickListener(v -> selectPrio(mode));
        }
        int prio = Prefs.Priority.clamp(prefs.getInt(Prefs.KEY_PRIORITY, 1));
        styleSeg(prio);
        updatePrioHint(prio);

        // 关于：三行都是「点一下跳转、长按复制」
        bindLinkRow(view, R.id.row_author, R.id.author_value,
                URL_AUTHOR, "@Guys222");
        bindLinkRow(view, R.id.row_qq, R.id.qq_value,
                URL_QQ_GROUP, QQ_GROUP);
        bindLinkRow(view, R.id.row_repo, R.id.repo_value,
                URL_REPO, "https://github.com/Guys222/ProcessKeepAlive");

    // 备份与恢复：点击走文件，长按走剪贴板（没装文件管理器的设备也能用）
        bindBackupRow(view, R.id.row_export, true);
        bindBackupRow(view, R.id.row_import, false);

        // 诊断：能力自检详情页（逐项展示每个 hook 的挂载结局）
        bindCapabilityRow(view);

        TextView version = view.findViewById(R.id.version_value);
        version.setText("v" + BuildConfig.VERSION_NAME);

        // 版本行：点击检查更新，长按复制当前版本号
        bindVersionRow(view, version);
    }

    /**
     * 版本行：点击向 GitHub 查询最新版本；长按复制版本号。
     *
     * <p>检查期间把状态文字改成「检查中…」并屏蔽重复点击（{@code checking} 标志），
     * 避免连点发起多次请求。无论成功失败都恢复可点击：
     * 网络抖动导致的失败不锁死入口，用户可再点一次。
     */
    private void bindVersionRow(View root, TextView versionValue) {
        View row = root.findViewById(R.id.row_version);
        TextView status = root.findViewById(R.id.version_check);

        final boolean[] checking = {false};

        row.setOnClickListener(v -> {
            if (checking[0]) return;
            checking[0] = true;
            status.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorOnSurfaceVariant));
            status.setText("检查中…");

            final String local = BuildConfig.VERSION_NAME;
            UpdateChecker.checkLatest((tag, error) -> {
                // 回调已在主线程；Fragment 可能已销毁，先判存活再碰视图
                if (!isAdded()) return;
                checking[0] = false;

                if (tag == null) {
                    status.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorOnSurfaceVariant));
                    status.setText("检查失败");
                    Toast.makeText(requireContext(),
                            "检查更新失败：" + (error == null ? "未知原因" : error),
                            Toast.LENGTH_SHORT).show();
                    return;
                }

                if (UpdateChecker.isNewer(tag, local)) {
                    status.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorPrimary));
                    status.setText("有新版 " + tag);
                    new AlertDialog.Builder(requireContext())
                            .setTitle("发现新版本 " + tag)
                            .setMessage("当前版本：v" + local
                                    + "\n最新版本：" + tag
                                    + "\n\n是否前往 GitHub 查看并下载？")
                            .setPositiveButton("前往下载", (d, w) -> openUrl(UpdateChecker.URL_RELEASES))
                            .setNegativeButton("稍后", null)
                            .show();
                } else {
                    status.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorOnSurfaceVariant));
                    status.setText("已是最新");
                    Toast.makeText(requireContext(), "已是最新版本（v" + local + "）",
                            Toast.LENGTH_SHORT).show();
                }
            });
        });

        // 长按复制版本号（与「关于」区其它行的「点跳转、长按复制」交互一致）
        row.setOnLongClickListener(v -> {
            copy(versionValue.getText().toString());
            return true;
        });
    }

    /**
     * 能力自检入口行。
     *
     * <p>副标题直接给出「N/M 项生效」的实时结论——这样用户不用进详情页就能知道
     * 有没有能力静默失效；有未生效项时整行转红，比单纯的文字提示更抓眼。
     * 点击进 {@link CapabilityDiagActivity} 看逐项明细。
     */
    private void bindCapabilityRow(View root) {
        View row = root.findViewById(R.id.row_capability);
        TextView summary = root.findViewById(R.id.capability_summary);

        java.util.List<SurvivalData.Capability> caps = SurvivalData.hookCaps(requireContext());
        if (caps.isEmpty()) {
            summary.setText("暂无数据 · 等待系统框架上报");
            summary.setTextColor(ContextCompat.getColor(requireContext(),
                    R.color.colorOnSurfaceVariant));
        } else {
            int ok = 0;
            for (SurvivalData.Capability cap : caps) {
                if (cap.ok) ok++;
            }
            boolean allOk = ok == caps.size();
            summary.setText(ok + " / " + caps.size() + " 项生效"
                    + (allOk ? " · 全部正常" : " · 点击查看未生效项"));
            summary.setTextColor(ContextCompat.getColor(requireContext(),
                    allOk ? R.color.colorOk : R.color.colorDanger));
        }

        row.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), CapabilityDiagActivity.class)));
    }

    /**
     * 备份/恢复行：点击 = 文件（走 SAF），长按 = 剪贴板。
     *
     * 长按这条路不是凑数：有些设备（尤其是国产 ROM 的「无文件管理器」精简版）从系统
     * 选择器里翻不到下载目录，而剪贴板 + QQ/微信发给自己是每个人都会的兜底通道。
     */
    private void bindBackupRow(View root, int rowId, boolean export) {
        View row = root.findViewById(rowId);
        row.setOnClickListener(v -> {
            if (export) {
                exportLauncher.launch(ConfigBackup.suggestName());
            } else {
                importLauncher.launch(new String[]{"application/json", "text/*", "*/*"});
            }
        });
        row.setOnLongClickListener(v -> {
            if (export) {
                copyJsonToClipboard();
            } else {
                String txt = readClipboard();
                if (txt == null) {
                    Toast.makeText(requireContext(), "剪贴板里没有文本内容", Toast.LENGTH_SHORT).show();
                } else {
                    confirmImport(txt);
                }
            }
            return true;
        });
    }

    private void copyJsonToClipboard() {
        try {
            copy(ConfigBackup.build(requireContext()));
            Toast.makeText(requireContext(), "配置 JSON 已复制到剪贴板", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "生成备份失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String readClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || cm.getPrimaryClip() == null) return null;
            if (cm.getPrimaryClip().getItemCount() <= 0) return null;
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).getText();
            return cs == null ? null : cs.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 真正把配置写进用户选好的位置。 */
    private void doExport(Uri uri) {
        try {
            String json = ConfigBackup.build(requireContext());
            ConfigBackup.writeText(requireContext(), uri, json);
            Toast.makeText(requireContext(), "配置已导出", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "导出失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 读文件内容后进入二次确认（恢复会整体替换当前配置，不能手滑就执行）。 */
    private void readAndConfirmImport(Uri uri) {
        try {
            String txt = ConfigBackup.readText(requireContext(), uri);
            confirmImport(txt);
        } catch (Exception e) {
            Toast.makeText(requireContext(), "读取失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmImport(String json) {
        // describeOrReason 会给出「不可恢复」的精确原因（不是本模块备份 / 格式版本过新 /
        // 解析失败），避免笼统一句「缺少配置字段」把「该升级模块」误导成「文件有问题」。
        ConfigBackup.Described d = ConfigBackup.describeOrReason(json);
        if (!d.ok()) {
            Toast.makeText(requireContext(),
                    "无法恢复：" + d.reason, Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle("恢复配置？")
                .setMessage(d.text)
                .setPositiveButton("恢复", (dd, w) -> doImport(json))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doImport(String json) {
        try {
            ConfigBackup.Result r = ConfigBackup.apply(requireContext(), json);
            refreshToggles();
            Toast.makeText(requireContext(), "已恢复：" + r.summary(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "恢复失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 恢复/外部改动后把本页开关状态重新拉一遍（mSilent 防止回写 prefs）。 */
    private void refreshToggles() {
        View v = getView();
        if (v == null) return;
        mSilent = true;
        bindToggleSilent(v, R.id.switch_master, Prefs.KEY_ENABLED, true);
        bindToggleSilent(v, R.id.switch_aggressive, Prefs.KEY_AGGRESSIVE, false);
        bindToggleSilent(v, R.id.switch_auto_start, Prefs.KEY_AUTO_START, false);
        bindToggleSilent(v, R.id.switch_notify, Prefs.KEY_NOTIFY, false);
        int prio = Prefs.Priority.clamp(prefs.getInt(Prefs.KEY_PRIORITY, 1));
        styleSeg(prio);
        updatePrioHint(prio);
        mSilent = false;
    }

    private void bindToggleSilent(View v, int id, String key, boolean def) {
        SwitchCapsuleView sw = v.findViewById(id);
        if (sw != null) sw.setChecked(prefs.getBoolean(key, def), false);
    }

    /**
     * 把一行绑成「点一下用浏览器打开、长按复制到剪贴板」。
     *
     * 三行（作者 / QQ 群 / 仓库）都走这里，点击是主要动作（用户要的是直接跳转），
     * 复制降为长按——既保留了原来的复制能力，又不用再摆一个 34dp 的复制按钮占位。
     * 仓库地址显示时省掉了 https://github.com/ 前缀，复制出来仍是完整 URL。
     */
    private void bindLinkRow(View root, int rowId, int valueId, String url, String copyText) {
        View row = root.findViewById(rowId);
        row.setOnClickListener(v -> openUrl(url));
        row.setOnLongClickListener(v -> {
            copy(copyText);
            return true;
        });
    }

    /** 用系统浏览器打开外部链接。没装浏览器时退化成复制，避免点了没反应。 */
    private void openUrl(String url) {
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            it.addCategory(Intent.CATEGORY_BROWSABLE);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Exception e) {
            copy(url);
            Toast.makeText(requireContext(), "无法打开链接，已复制链接", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // 主题切换后回到本页时同步开关状态
        View v = getView();
        if (v == null || prefs == null) return;
        mSilent = true;
        ((SwitchCapsuleView) v.findViewById(R.id.switch_dark))
                .setChecked(ThemeManager.isDark(requireContext()), false);
        mSilent = false;
        // 自检数据可能在页面打开后（开机上报完成）才落盘，回来时重刷一次结论
        bindCapabilityRow(v);
    }

    /** 选中档位并落盘。 */
    private void selectPrio(int mode) {
        int cur = Prefs.Priority.clamp(prefs.getInt(Prefs.KEY_PRIORITY, 1));
        if (mode == cur) return;
        prefs.edit().putInt(Prefs.KEY_PRIORITY, mode).apply();
        styleSeg(mode);
        updatePrioHint(mode);
        Toast.makeText(requireContext(),
                "默认档位：" + Prefs.Priority.both(mode) + " · " + Prefs.Priority.adjDetail(mode),
                Toast.LENGTH_SHORT).show();
    }

    /**
     * 档位提示：只保留「这个档位对用户意味着什么操作后果」。
     *
     * 刻意不再拼 {@code Prefs.Priority.meaning(mode)} —— 那句话已经逐档列在上面的
     * 对照表里，当前档位那行还是紫色高亮的，再抄一遍纯属重复。
     */
    private void updatePrioHint(int mode) {
        View v = getView();
        if (v == null) return;
        buildPrioLegend(v);
        TextView hint = v.findViewById(R.id.prio_hint);
        hint.setText("新勾选的应用默认采用此档位；已勾选的应用可在「应用」页点进去单独调整");
    }

    /**
     * 三档对照表：每行 = 档位名（强度 · 系统术语） + adj 值 + 系统意愿。
     * 当前生效的档位用紫色加粗 + 紫点标记，其余置灰，避免再单开一行「当前档位」造成重复。
     */
    private void buildPrioLegend(View root) {
        LinearLayout box = root.findViewById(R.id.prio_legend);
        if (box == null) return;
        box.removeAllViews();
        int accent = ContextCompat.getColor(requireContext(), R.color.colorPrimary);
        int text = ContextCompat.getColor(requireContext(), R.color.colorOnSurface);
        int muted = ContextCompat.getColor(requireContext(), R.color.colorOnSurfaceVariant);
        float d = getResources().getDisplayMetrics().density;
        int active = Prefs.Priority.clamp(prefs.getInt(Prefs.KEY_PRIORITY, 1));

        // 从强到弱展示，与用户的直觉顺序一致
        for (int mode = 0; mode < Prefs.Priority.COUNT; mode++) {
            boolean on = (mode == active);

            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (mode > 0) rlp.topMargin = (int) (7 * d);
            row.setLayoutParams(rlp);

            // 档位名（强度 · 系统术语）：96dp 刚好放得下最长的「保守 · 可感知级」
            TextView name = new TextView(requireContext());
            name.setText(Prefs.Priority.both(mode));
            name.setTextSize(12);
            name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            name.setTextColor(on ? accent : text);
            name.setSingleLine(true);
            name.setLayoutParams(new LinearLayout.LayoutParams(
                    (int) (96 * d), LinearLayout.LayoutParams.WRAP_CONTENT));
            row.addView(name);

            // adj 值：必须单行，否则「adj 100」会折成两行把对照表撑歪
            TextView adj = new TextView(requireContext());
            adj.setText("adj " + Prefs.Priority.adjValue(mode));
            adj.setTextSize(12);
            adj.setTypeface(android.graphics.Typeface.MONOSPACE);
            adj.setTextColor(on ? accent : muted);
            adj.setSingleLine(true);
            adj.setLayoutParams(new LinearLayout.LayoutParams(
                    (int) (56 * d), LinearLayout.LayoutParams.WRAP_CONTENT));
            row.addView(adj);

            // 系统意愿
            TextView mean = new TextView(requireContext());
            mean.setText(Prefs.Priority.meaning(mode));
            mean.setTextSize(11.5f);
            mean.setTextColor(muted);
            mean.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            row.addView(mean);

            box.addView(row);
        }
    }

    /**
     * 分段样式：激活项紫粉渐变 + 白字，其余透明 + muted
     * （预览稿 .seg button / .seg button.active）。
     * 入参是 mode 值，需反查它在分段里的位置（SEG_MODES 是倒序的）。
     */
    private void styleSeg(int activeMode) {
        for (int i = 0; i < prioSegs.length; i++) {
            if (prioSegs[i] == null) continue;
            boolean on = (SEG_MODES[i] == Prefs.Priority.clamp(activeMode));
            prioSegs[i].setBackgroundResource(on ? R.drawable.bg_seg_active : 0);
            prioSegs[i].setTextColor(ContextCompat.getColor(requireContext(),
                    on ? android.R.color.white : R.color.colorOnSurfaceVariant));
        }
    }

    private void bindToggle(SwitchCapsuleView sw, String key, boolean def) {
        sw.setChecked(prefs.getBoolean(key, def), false);
        sw.setOnCheckedChangeListener((v, checked) -> {
            if (mSilent) return;
            prefs.edit().putBoolean(key, checked).apply();
            if (Prefs.KEY_ENABLED.equals(key)) {
                if (checked) {
                    // 与主页总开关保持同一套语义：重新启用后保活时长从 0 起算
                    SurvivalData.onMasterEnabled(requireContext());
                } else {
                    // 保活全停：被杀/拉起计数一并清零，下次开启从 0 重新统计
                    SurvivalData.clearAllGuardStats(requireContext());
                }
            }
        });
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("text", text));
        }
        Toast.makeText(requireContext(), "已复制：" + text, Toast.LENGTH_SHORT).show();
    }
}
