package com.processkeepalive;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private ViewPager2 pager;
    private BottomNavigationView nav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        pager = findViewById(R.id.pager);
        nav = findViewById(R.id.bottom_nav);

        pager.setAdapter(new PagerAdapter(this));

        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_apps) {
                pager.setCurrentItem(0, false);
            } else if (id == R.id.nav_home) {
                pager.setCurrentItem(1, false);
            } else if (id == R.id.nav_settings) {
                pager.setCurrentItem(2, false);
            }
            return true;
        });

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                nav.getMenu().getItem(position).setChecked(true);
            }
        });

        // 打开 App 默认显示「主页」（页面里有状态卡、统计与快捷开关，比应用列表更适合做首屏）
        pager.setCurrentItem(1, false);
    }

    /** 供其它 Fragment 跳转到指定页（0=应用 1=主页 2=设置）。 */
    public void goToPage(int index) {
        if (pager != null) pager.setCurrentItem(index, false);
        if (nav != null) nav.getMenu().getItem(index).setChecked(true);
    }

    private static class PagerAdapter extends FragmentStateAdapter {
        PagerAdapter(FragmentActivity fa) {
            super(fa);
        }

        @Override
        public int getItemCount() {
            return 3;
        }

        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                case 1:
                    return new HomeFragment();
                case 2:
                    return new SettingsFragment();
                case 0:
                default:
                    return new AppsFragment();
            }
        }
    }
}
