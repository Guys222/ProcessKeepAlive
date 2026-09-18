package io.github.guys222.processkeepalive;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final String SURVIVAL_WORK = "survival_sampler";
    private static final String KEY_SAMPLER_ENQUEUED = "sampler_enqueued";

    /** 页面顺序：0=应用 1=主页 2=进程 3=设置（与 ViewPager2 的位置对应，进程在主页右边）。 */
    private static final int PAGE_APPS = 0;
    private static final int PAGE_HOME = 1;
    private static final int PAGE_PROCESS = 2;
    private static final int PAGE_SETTINGS = 3;

    private ViewPager2 pager;
    /** 四个自绘 tab 的容器 + 图标 + 文字，按页面顺序排列。 */
    private final LinearLayout[] tabs = new LinearLayout[4];
    private final ImageView[] tabIcons = new ImageView[4];
    private final TextView[] tabTexts = new TextView[4];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 应用保存的外观主题（必须在 super.onCreate 前设置）
        ThemeManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 换了一次开机就把上一轮的存活计时作废，让所有目标重新起算
        try {
            SurvivalData.resetForNewBoot(getApplicationContext());
        } catch (Throwable ignored) {
        }
        scheduleSampler();
        ensureGuardService();

        pager = findViewById(R.id.pager);

        tabs[PAGE_APPS] = findViewById(R.id.nav_apps);
        tabs[PAGE_HOME] = findViewById(R.id.nav_home);
        tabs[PAGE_SETTINGS] = findViewById(R.id.nav_settings);
        tabs[PAGE_PROCESS] = findViewById(R.id.nav_process);
        tabIcons[PAGE_APPS] = findViewById(R.id.nav_apps_icon);
        tabIcons[PAGE_HOME] = findViewById(R.id.nav_home_icon);
        tabIcons[PAGE_SETTINGS] = findViewById(R.id.nav_settings_icon);
        tabIcons[PAGE_PROCESS] = findViewById(R.id.nav_process_icon);
        tabTexts[PAGE_APPS] = findViewById(R.id.nav_apps_text);
        tabTexts[PAGE_HOME] = findViewById(R.id.nav_home_text);
        tabTexts[PAGE_SETTINGS] = findViewById(R.id.nav_settings_text);
        tabTexts[PAGE_PROCESS] = findViewById(R.id.nav_process_text);

        for (int i = 0; i < tabs.length; i++) {
            final int page = i;
            tabs[i].setOnClickListener(v -> pager.setCurrentItem(page, false));
        }

        pager.setAdapter(new PagerAdapter(this));
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                styleTabs(position);
            }
        });

        // 打开 App 默认显示「主页」（页面里有状态卡、统计与快捷开关，比应用列表更适合做首屏）
        pager.setCurrentItem(PAGE_HOME, false);
        styleTabs(PAGE_HOME);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 主题可能在「设置」页被切换，回来时刷新 tab 配色
        styleTabs(pager == null ? PAGE_HOME : pager.getCurrentItem());
    }

    /**
     * 常驻通知：开关开着就保证服务在跑。
     *
     * 每次进 App 都兜一遍，是为了覆盖那些「服务早被系统回收了、但开关还开着」的情况
     * （长时间没开过 App、或 ROM 把后台服务按掉了）。重复 startForegroundService 是幂等的。
     */
    private void ensureGuardService() {
        try {
            boolean on = getSharedPreferences(Prefs.PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(Prefs.KEY_NOTIFY, false);
            if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return;   // 权限被用户撤掉了：不打扰，等他下次在设置页重新打开
            }
            if (on) GuardService.start(this);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 自绘 tab 选中态（预览稿 .nav .tab / .tab.active）：
     * 选中用 accent 紫 + 半透明紫底，未选中用 muted 灰、无底。
     */
    private void styleTabs(int active) {
        int accent = ContextCompat.getColor(this, R.color.colorPrimary);
        int muted = ContextCompat.getColor(this, R.color.colorOnSurfaceVariant);
        for (int i = 0; i < tabs.length; i++) {
            if (tabs[i] == null) continue;
            boolean on = (i == active);
            tabs[i].setBackgroundResource(on ? R.drawable.bg_nav_tab_active : 0);
            int color = on ? accent : muted;
            tabIcons[i].setImageTintList(ColorStateList.valueOf(color));
            tabTexts[i].setTextColor(color);
        }
    }

    /** 供其它 Fragment 跳转到指定页（0=应用 1=主页 2=进程 3=设置）。 */
    public void goToPage(int index) {
        if (pager != null) pager.setCurrentItem(index, false);
        styleTabs(index);
    }

    /**
     * 调度 24h 存活采样器：每小时探测一次目标存活状态，写入环形缓冲。
     * KEEP 策略保证进程重启/多次进入时不会重复累积任务。
     */
    private void scheduleSampler() {
        try {
            SharedPreferences sp = getSharedPreferences(Prefs.PREFS_NAME, Context.MODE_PRIVATE);
            // 已存在同名周期任务时，KEEP 会直接复用，无需再判断
            PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                    SurvivalWorker.class, 1, TimeUnit.HOURS)
                    .setInitialDelay(2, TimeUnit.MINUTES)
                    .build();
            WorkManager.getInstance(this)
                    .enqueueUniquePeriodicWork(SURVIVAL_WORK,
                            ExistingPeriodicWorkPolicy.KEEP, req);
            sp.edit().putBoolean(KEY_SAMPLER_ENQUEUED, true).apply();

            // ★ 046：开机后立即补一次采样。周期任务虽设了 2 分钟初延迟，但 WorkManager
            //   实际首跑常拖到十几分钟后；而 046 起 24h 存活率缓冲在新开机时会被清空
            //   （跨开机旧样本作废），不清的话主页拿上次开机的旧数据算出「刚开机就 100%」。
            //   清了就必须尽快有第一个真实样本，否则主页一直「—」像坏了。
            //   用 bootId 去重：同一次开机只补一次（标记随 surv_ 前缀在新开机时被清）。
            long boot = SurvivalData.bootId(this);
            String kickKey = SurvivalData.KICK_KEY_PREFIX + boot;
            if (boot >= 0 && !sp.getBoolean(kickKey, false)) {
                sp.edit().putBoolean(kickKey, true).apply();
                WorkManager.getInstance(this).enqueueUniqueWork(
                        SURVIVAL_WORK + "_kick", ExistingWorkPolicy.KEEP,
                        new OneTimeWorkRequest.Builder(SurvivalWorker.class)
                                .setInitialDelay(20, TimeUnit.SECONDS)
                                .build());
            }
        } catch (Throwable ignored) {
            // WorkManager 不可用时静默失败，不影响主界面
        }
    }

    private static class PagerAdapter extends FragmentStateAdapter {
        PagerAdapter(FragmentActivity fa) {
            super(fa);
        }

        @Override
        public int getItemCount() {
            return 4;
        }

        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case PAGE_HOME:
                    return new HomeFragment();
                case PAGE_SETTINGS:
                    return new SettingsFragment();
                case PAGE_PROCESS:
                    return new ProcessesFragment();
                case PAGE_APPS:
                default:
                    return new AppsFragment();
            }
        }
    }
}
