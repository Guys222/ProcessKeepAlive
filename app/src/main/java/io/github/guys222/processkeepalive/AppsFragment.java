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
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AppsFragment extends Fragment {

    private SharedPreferences prefs;
    private final List<AppInfo> allApps = new ArrayList<>();
    private final List<AppInfo> shown = new ArrayList<>();
    /** 已启用的应用：包名 -> 该应用的完整配置。 */
    private final Map<String, AppConfig> configs = new HashMap<>();
    private boolean showSystem = true;

    private AppAdapter adapter;
    private EditText searchBox;

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
        showSystem = prefs.getBoolean(Prefs.KEY_SHOW_SYSTEM, true);

        searchBox = view.findViewById(R.id.search);
        RecyclerView list = view.findViewById(R.id.list);
        ImageButton btnSearch = view.findViewById(R.id.btn_search);
        ImageButton btnMore = view.findViewById(R.id.btn_more);
        SwipeRefreshLayout swipe = view.findViewById(R.id.swipe);

        loadApps();

        adapter = new AppAdapter();
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);

        swipe.setOnRefreshListener(() -> {
            loadApps();
            rebuild();
            swipe.setRefreshing(false);
        });

        btnSearch.setOnClickListener(v -> {
            if (searchBox.getVisibility() == View.GONE) {
                searchBox.setVisibility(View.VISIBLE);
                searchBox.requestFocus();
            } else {
                searchBox.setVisibility(View.GONE);
                searchBox.setText("");
                rebuild();
            }
        });

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { rebuild(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        btnMore.setOnClickListener(this::showMenu);

        rebuild();
    }

    private void loadSelection() {
        configs.clear();
        Set<String> targets = prefs.getStringSet(Prefs.KEY_TARGETS, new HashSet<>());
        for (String pkg : targets) {
            AppConfig c = new AppConfig();
            c.adj = prefs.getInt(Prefs.KEY_ADJ_PREFIX + pkg, 1);
            c.forceStop = prefs.getBoolean(Prefs.KEY_FORCE_PREFIX + pkg, true);
            c.killBackground = prefs.getBoolean(Prefs.KEY_KILLBG_PREFIX + pkg, true);
            c.kill = prefs.getBoolean(Prefs.KEY_KILL_PREFIX + pkg, false);
            c.persistent = prefs.getBoolean(Prefs.KEY_PERSIST_PREFIX + pkg, false);
            c.msg = prefs.getBoolean(Prefs.KEY_MSG_PREFIX + pkg, false);
            configs.put(pkg, c);
        }
    }

    private void loadApps() {
        allApps.clear();
        PackageManager pm = requireContext().getPackageManager();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if (ai.packageName.equals(requireContext().getPackageName())) continue;
            String label = pm.getApplicationLabel(ai).toString();
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    || (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            allApps.add(new AppInfo(ai.packageName, label, pm.getApplicationIcon(ai), system));
        }
        allApps.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
    }

    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        popup.getMenu().add(showSystem ? "隐藏系统应用" : "显示系统应用");
        popup.getMenu().add("全部开启");
        popup.getMenu().add("全部关闭");
        popup.setOnMenuItemClickListener(item -> {
            String title = item.getTitle() == null ? "" : item.getTitle().toString();
            if ("隐藏系统应用".equals(title)) {
                showSystem = false;
                prefs.edit().putBoolean(Prefs.KEY_SHOW_SYSTEM, false).apply();
                rebuild();
                return true;
            } else if ("显示系统应用".equals(title)) {
                showSystem = true;
                prefs.edit().putBoolean(Prefs.KEY_SHOW_SYSTEM, true).apply();
                rebuild();
                return true;
            } else if ("全部开启".equals(title)) {
                for (AppInfo a : shown) {
                    if (!configs.containsKey(a.packageName)) {
                        configs.put(a.packageName, new AppConfig());
                    }
                }
                saveTargets();
                rebuild();
                return true;
            } else if ("全部关闭".equals(title)) {
                for (AppInfo a : shown) configs.remove(a.packageName);
                saveTargets();
                rebuild();
                return true;
            }
            return false;
        });
        popup.show();
    }

    /** 点击应用条目，弹出该应用的完整配置对话框。 */
    private void showConfigDialog(AppInfo a) {
        AppConfig cfg = configs.get(a.packageName);
        if (cfg == null) cfg = new AppConfig();
        final AppConfig c = cfg;

        View v = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_app_config, null);
        RadioGroup rg = v.findViewById(R.id.rg_adj);
        RadioButton rbFg = v.findViewById(R.id.rb_fg);
        RadioButton rbVis = v.findViewById(R.id.rb_vis);
        RadioButton rbPer = v.findViewById(R.id.rb_per);
        SwitchMaterial swForce = v.findViewById(R.id.sw_force);
        SwitchMaterial swKillBg = v.findViewById(R.id.sw_killbg);
        SwitchMaterial swKill = v.findViewById(R.id.sw_kill);
        SwitchMaterial swPersist = v.findViewById(R.id.sw_persist);
        SwitchMaterial swMsg = v.findViewById(R.id.sw_msg);

        switch (c.adj) {
            case 0: rbFg.setChecked(true); break;
            case 2: rbPer.setChecked(true); break;
            default: rbVis.setChecked(true); break;
        }
        swForce.setChecked(c.forceStop);
        swKillBg.setChecked(c.killBackground);
        swKill.setChecked(c.kill);
        swPersist.setChecked(c.persistent);
        swMsg.setChecked(c.msg);

        new AlertDialog.Builder(requireContext())
                .setTitle(a.label)
                .setView(v)
                .setPositiveButton("确定", (d, which) -> {
                    c.adj = rbFg.isChecked() ? 0 : (rbPer.isChecked() ? 2 : 1);
                    c.forceStop = swForce.isChecked();
                    c.killBackground = swKillBg.isChecked();
                    c.kill = swKill.isChecked();
                    c.persistent = swPersist.isChecked();
                    c.msg = swMsg.isChecked();
                    configs.put(a.packageName, c);
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
    }

    private void saveTargets() {
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
            e.putBoolean(Prefs.KEY_MSG_PREFIX + pkg, c.msg);
        }
        e.apply();
    }

    private String typeLabel(int mode) {
        switch (mode) {
            case 0: return "前台级";
            case 2: return "可感知级";
            case 1:
            default: return "可见级";
        }
    }

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
            h.icon.setImageDrawable(a.icon);
            h.name.setText(a.label);
            h.pkg.setText(a.packageName);

            boolean sel = configs.containsKey(a.packageName);
            h.switchApp.setOnCheckedChangeListener(null);
            h.switchApp.setChecked(sel);
            h.switchApp.setOnCheckedChangeListener((v, checked) -> {
                if (checked) {
                    if (!configs.containsKey(a.packageName)) {
                        configs.put(a.packageName, new AppConfig());
                    }
                } else {
                    configs.remove(a.packageName);
                }
                saveTargets();
                notifyItemChanged(h.getAdapterPosition());
            });

            // 点击整行：弹出该应用的完整配置
            h.itemView.setOnClickListener(v -> showConfigDialog(a));

            if (sel) {
                h.badge.setVisibility(View.VISIBLE);
                AppConfig c = configs.get(a.packageName);
                int mode = c == null ? 1 : c.adj;
                h.badge.setText(c != null && c.msg ? "消息" : typeLabel(mode));
                h.badge.setBackground(badgeBg(typeColor(mode)));
                h.badge.setTextColor(Color.WHITE);
            } else {
                h.badge.setVisibility(View.GONE);
            }
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name, pkg, badge;
            final SwitchMaterial switchApp;

            VH(View v) {
                super(v);
                icon = v.findViewById(R.id.app_icon);
                name = v.findViewById(R.id.app_name);
                pkg = v.findViewById(R.id.app_pkg);
                badge = v.findViewById(R.id.type_badge);
                switchApp = v.findViewById(R.id.switch_app);
            }
        }
    }
}
