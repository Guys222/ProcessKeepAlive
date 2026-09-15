package com.processkeepalive;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Collections;

public class HomeFragment extends Fragment {

    /** 标记刷新开关时避免回写 prefs 的临时开关。 */
    private boolean mSilent;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ImageView appIcon = view.findViewById(R.id.app_icon);
        appIcon.setImageDrawable(requireContext().getPackageManager()
                .getApplicationIcon(requireContext().getApplicationInfo()));

        view.findViewById(R.id.btn_info).setOnClickListener(v ->
                Toast.makeText(requireContext(),
                        "进程保活 v" + BuildConfig.VERSION_NAME,
                        Toast.LENGTH_SHORT).show());

        // 重新检测 / 刷新状态
        view.findViewById(R.id.btn_recheck).setOnClickListener(v -> refreshAll());
        view.findViewById(R.id.action_recheck).setOnClickListener(v -> refreshAll());

        // 跳转到「应用」页管理保活目标
        view.findViewById(R.id.action_apps).setOnClickListener(v -> {
            if (requireActivity() instanceof MainActivity) {
                ((MainActivity) requireActivity()).goToPage(0);
            }
        });

        // 未激活时，尝试直接打开 LSPosed Manager
        MaterialButton gotoLsposed = view.findViewById(R.id.btn_goto_lsposed);
        gotoLsposed.setOnClickListener(v -> openLSPosedManager());

        // 主页快捷开关（与「设置」页共用同一份 prefs，即时生效）
        SwitchMaterial swMaster = view.findViewById(R.id.switch_master_home);
        SwitchMaterial swAuto = view.findViewById(R.id.switch_autostart_home);
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(Prefs.PREFS_NAME, Context.MODE_PRIVATE);

        swMaster.setOnCheckedChangeListener((v, checked) -> {
            if (mSilent) return;
            prefs.edit().putBoolean(Prefs.KEY_ENABLED, checked).apply();
            refreshStats();
        });
        swAuto.setOnCheckedChangeListener((v, checked) -> {
            if (mSilent) return;
            prefs.edit().putBoolean(Prefs.KEY_AUTO_START, checked).apply();
            refreshStats();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshAll();
    }

    private void refreshAll() {
        refresh();
        refreshStats();
    }

    private void refresh() {
        View v = getView();
        if (v == null) return;

        LinearLayout row = v.findViewById(R.id.status_row);
        TextView dot = v.findViewById(R.id.status_dot);
        TextView title = v.findViewById(R.id.status_title);
        TextView sub = v.findViewById(R.id.status_sub);
        TextView badge = v.findViewById(R.id.status_badge);
        MaterialButton gotoLsposed = v.findViewById(R.id.btn_goto_lsposed);
        View diagCard = v.findViewById(R.id.diag_card);
        TextView diagText = v.findViewById(R.id.diag_text);

        StringBuilder diag = new StringBuilder();
        boolean activated = detectActivated(diag);

        if (activated) {
            setRowBg(row, Color.rgb(0xc9, 0xf2, 0xf0));
            dot.setTextColor(Color.rgb(0x00, 0x69, 0x6a));
            title.setText("模块已激活");
            title.setTextColor(Color.rgb(0x00, 0x20, 0x1f));
            sub.setText("已在系统框架中注入");
            sub.setTextColor(Color.rgb(0x00, 0x37, 0x37));
            badge.setText("已激活");
            badge.setTextColor(Color.rgb(0x00, 0x69, 0x6a));
            gotoLsposed.setVisibility(View.GONE);
            diagCard.setVisibility(View.GONE);
        } else {
            setRowBg(row, Color.rgb(0xff, 0xe8, 0xe8));
            dot.setTextColor(Color.rgb(0xba, 0x1a, 0x1a));
            title.setText("模块未激活");
            title.setTextColor(Color.rgb(0x41, 0x00, 0x02));
            sub.setText("请在 LSPosed 中启用本模块，并勾选「系统框架」");
            sub.setTextColor(Color.rgb(0x68, 0x00, 0x03));
            badge.setText("未激活");
            badge.setTextColor(Color.rgb(0xba, 0x1a, 0x1a));
            // 未激活时给出去启用的入口 + 诊断信息（显示链路断在哪一环）
            gotoLsposed.setVisibility(View.VISIBLE);
            diagCard.setVisibility(View.VISIBLE);
            diagText.setText(diag.toString());
        }
    }

    /**
     * 是否已激活：读取激活标记（files/activated）。
     * 新格式为 bc=<开机次数>，与当前 Settings.Global.BOOT_COUNT 一致即为本次开机已激活
     * （BOOT_COUNT 单调递增，不受 NTP 校时导致的时钟跳变影响）。
     * 旧格式（纯墙钟毫秒）做兼容判断。
     * 同时把链路状态写入 diag，未激活时展示给用户，方便定位断在哪一环。
     */
    private boolean detectActivated(StringBuilder diag) {
        int curBc = ActivationMark.getBootCount(requireContext());
        diag.append("本次开机次数：").append(curBc >= 0 ? curBc : "未知").append("\n");
        try {
            File f = new File(requireContext().getFilesDir(), ActivationMark.FILE_NAME);
            if (!f.exists()) {
                diag.append("激活标记：不存在\n")
                    .append("结论：本次开机还没有任何进程上报激活。\n")
                    .append("处理：在 LSPosed 启用本模块并勾选「系统框架」，然后重启手机。");
                return false;
            }
            String content = readFile(f).trim();
            int bc = parseField(content, "bc");
            long wc = parseFieldLong(content, "wc");
            long bootWallClock = System.currentTimeMillis()
                    - android.os.SystemClock.elapsedRealtime();
            if (bc >= 0) {
                diag.append("激活标记：存在（第 ").append(bc).append(" 次开机写入");
                if (wc > 0) diag.append("，").append(fmtTime(wc));
                diag.append("）\n");
                if (curBc >= 0 && bc == curBc) {
                    return true;
                }
                if (curBc < 0) {
                    // 读不到当前开机次数时退回墙钟窗口判断
                    return wc >= bootWallClock - 60000;
                }
                diag.append("结论：标记是第 ").append(bc)
                    .append(" 次开机写入的，本次开机（第 ").append(curBc)
                    .append(" 次）尚未上报。\n")
                    .append("处理：确认 LSPosed 已启用并勾选「系统框架」，勾选后必须重启手机；")
                    .append("若刚重启，等 1 分钟后点「重新检测」。");
                return false;
            }
            if (wc > 0) {
                // 写入端没拿到开机次数（bc=-1）：按写入时间是否落在本机开机窗口内判断
                diag.append("激活标记：存在（写入于 ").append(fmtTime(wc))
                    .append("，未含开机次数）\n");
                boolean ok = wc >= bootWallClock - 60000; // 1 分钟容差
                if (!ok) {
                    diag.append("结论：标记不是本次开机写入的。\n")
                        .append("处理：重启手机后重新检测。");
                }
                return ok;
            }
            // 旧格式：纯墙钟时间
            long written = Long.parseLong(content);
            boolean ok = written >= bootWallClock - 60000; // 1 分钟容差
            if (!ok) {
                diag.append("激活标记：旧格式，写入于 ").append(fmtTime(written)).append("\n")
                    .append("结论：标记不是本次开机写入的。\n")
                    .append("处理：重启手机后重新检测。");
            }
            return ok;
        } catch (Exception e) {
            diag.append("激活标记：读取失败（").append(e).append("）");
            return false;
        }
    }

    /** 从 "bc=12\nwc=1715000000000" 格式中解析指定字段，缺失返回 -1。 */
    private static int parseField(String content, String key) {
        return (int) parseFieldLong(content, key);
    }

    /** 同上，返回 long（wc 是 13 位毫秒时间戳，int 会溢出截断）。 */
    private static long parseFieldLong(String content, String key) {
        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.startsWith(key + "=")) {
                try {
                    return Long.parseLong(line.substring(key.length() + 1));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return -1;
    }

    private static String fmtTime(long ms) {
        return new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
                .format(new java.util.Date(ms));
    }

    /** 刷新统计卡片与快捷开关（直接读本地 prefs，无 IPC，安全且即时）。 */
    private void refreshStats() {
        View v = getView();
        if (v == null) return;
        SharedPreferences prefs = requireContext()
                .getSharedPreferences(Prefs.PREFS_NAME, Context.MODE_PRIVATE);

        int count = prefs.getStringSet(Prefs.KEY_TARGETS, Collections.emptySet()).size();
        boolean enabled = prefs.getBoolean(Prefs.KEY_ENABLED, true);
        boolean autoStart = prefs.getBoolean(Prefs.KEY_AUTO_START, false);

        ((TextView) v.findViewById(R.id.stat_protected)).setText(String.valueOf(count));
        ((TextView) v.findViewById(R.id.stat_master)).setText(enabled ? "开" : "关");
        ((TextView) v.findViewById(R.id.stat_autostart)).setText(autoStart ? "开" : "关");

        mSilent = true;
        ((SwitchMaterial) v.findViewById(R.id.switch_master_home)).setChecked(enabled);
        ((SwitchMaterial) v.findViewById(R.id.switch_autostart_home)).setChecked(autoStart);
        mSilent = false;
    }

    private void openLSPosedManager() {
        try {
            Intent i = requireContext().getPackageManager()
                    .getLaunchIntentForPackage("org.lsposed.manager");
            if (i != null) {
                startActivity(i);
            } else {
                Toast.makeText(requireContext(),
                        "未检测到 LSPosed Manager，请手动启用本模块", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(requireContext(),
                    "未检测到 LSPosed Manager，请手动启用本模块", Toast.LENGTH_SHORT).show();
        }
    }

    private static void setRowBg(View row, int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        float r = row.getResources().getDisplayMetrics().density * 14f;
        gd.setCornerRadius(r);
        row.setBackground(gd);
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
