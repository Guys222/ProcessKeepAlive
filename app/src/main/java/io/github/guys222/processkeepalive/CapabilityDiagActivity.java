package io.github.guys222.processkeepalive;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.List;

/**
 * 能力自检详情页。
 *
 * <p>为什么需要它：本模块的核心能力全部靠「按名挂载 AOSP 私有方法」实现——比如把
 * {@code OomAdjuster.applyOomAdjLocked} 换成 {@code applyOomAdjLSP} 才能改
 * OOM adj，把 {@code ProcessRecord.killLocked} 拦下来才能挡住强力清理。
 * 而 AOSP 在版本间会改方法名，厂商 ROM 还会自己魔改，一旦某个名字对不上，
 * 对应功能就【静默失效】：不崩溃、不报错，用户只觉得「这个功能好像没用」，
 * 开发者隔着屏幕也无从判断到底是哪台设备、哪个能力没挂上。
 *
 * <p>所以 hook 侧在安装完成后会把每个能力的挂载结局（命中没命中、挂到哪个方法名、
 * 有几个重载）上报给 App 落盘；本页把它逐项展示出来，让「哪些能力生效」一眼可见，
 * 也顺便把原本只有看 logcat 才能拿到的信息放进了 App。
 *
 * <p>展示形态刻意做成「一项一行」而不是一整段文字：14 项能力平铺成纯文本时，
 * 用户根本分不清哪行属于哪项，更别说去定位未生效项。逐项卡片 + 状态徽标 +
 * 可展开的明细，才是真正能自查的形态。
 */
public class CapabilityDiagActivity extends AppCompatActivity {

    /**
     * 能力名 → 人话解释。
     *
     * <p>hook 侧上报的能力名是代码里的标识符（如 {@code applyOomAdjLocked}），
     * 直接摆给用户看等于没解释。这里翻译成「这项能力是干什么的」，
     * 未生效时用户才能判断「对我的使用场景有没有影响」。
     */
    private static final String[][] CAP_META = {
            // { 能力名, 一句话说明 }
            {"forceStopPackage", "阻止系统「强制停止」目标应用"},
            {"killBackgroundProcesses", "阻止系统后台清理目标应用"},
            {"applyOomAdjLocked", "降低目标应用的 OOM 优先级（档位生效关键）"},
            {"setOomAdj", "兜底改写写入内核的 adj 值"},
            {"updateOomAdjLocked", "改写进程状态，避免被当成后台回收"},
            {"ProcessRecord.killLocked", "强力模式：拦截进程强杀"},
            {"dozeWhitelist", "消息保活：免于 Doze 省电限制"},
            {"appStandby", "消息保活：避免 App Standby 节流"},
            {"autoStart", "开机自启动"},
            {"appDiedLocked", "进程死亡时记录并准备恢复"},
            {"handleAppDiedLocked", "进程死亡处理链路（恢复兜底）"},
    };

    private LinearLayout list;
    private TextView summaryTitle;
    private TextView summaryDesc;
    private TextView emptyHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_capability_diag);

        list = findViewById(R.id.cap_list);
        summaryTitle = findViewById(R.id.summary_title);
        summaryDesc = findViewById(R.id.summary_desc);
        emptyHint = findViewById(R.id.empty_hint);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_recheck).setOnClickListener(v -> {
            render();
            Toast.makeText(this, "已刷新自检数据", Toast.LENGTH_SHORT).show();
        });

        render();
    }

    /** 读取落盘的自检结果并渲染整页。 */
    private void render() {
        list.removeAllViews();
        List<SurvivalData.Capability> caps = SurvivalData.hookCaps(this);

        if (caps.isEmpty()) {
            emptyHint.setVisibility(View.VISIBLE);
            summaryTitle.setText("暂无自检数据");
            summaryTitle.setTextColor(ContextCompat.getColor(this, R.color.colorOnSurface));
            summaryDesc.setText("系统框架尚未上报。请确认已在 LSPosed 启用本模块并勾选「系统框架」，"
                    + "然后重启手机；上报通常在开机后数十秒内完成。");
            return;
        }
        emptyHint.setVisibility(View.GONE);

        int okCount = 0;
        for (SurvivalData.Capability cap : caps) {
            if (cap.ok) okCount++;
        }
        boolean allOk = okCount == caps.size();
        int okColor = ContextCompat.getColor(this, R.color.colorOk);
        int dangerColor = ContextCompat.getColor(this, R.color.colorDanger);

        summaryTitle.setText(okCount + " / " + caps.size() + " 项能力已生效");
        summaryTitle.setTextColor(allOk ? okColor : dangerColor);
        summaryDesc.setText(allOk
                ? "全部保活能力均已在本次开机挂载成功。"
                : "红色项在本次开机的系统版本上未找到对应入口（多为系统改版改名所致），"
                + "点击该项可查看尝试过的候选名称；这些项不生效不会影响其余功能。");

        LayoutInflater infl = LayoutInflater.from(this);
        for (SurvivalData.Capability cap : caps) {
            list.addView(buildRow(infl, cap));
        }
    }

    /** 渲染单项：状态徽标 + 能力名 + 详情；点击行展开候选/命中明细。 */
    private View buildRow(LayoutInflater infl, SurvivalData.Capability cap) {
        View row = infl.inflate(R.layout.item_capability, list, false);

        TextView badge = row.findViewById(R.id.cap_badge);
        TextView name = row.findViewById(R.id.cap_name);
        TextView detail = row.findViewById(R.id.cap_detail);
        TextView expand = row.findViewById(R.id.cap_expand);
        ImageView arrow = row.findViewById(R.id.cap_arrow);
        View head = row.findViewById(R.id.cap_head);

        int okColor = ContextCompat.getColor(this, R.color.colorOk);
        int dangerColor = ContextCompat.getColor(this, R.color.colorDanger);
        int muted = ContextCompat.getColor(this, R.color.colorOnSurfaceVariant);

        // 能力名优先显示人话解释，副行补上代码里的标识符（便于反馈问题时对日志）
        String human = humanName(cap.name);
        name.setText(human != null ? human : cap.name);
        name.setTextColor(ContextCompat.getColor(this, R.color.colorOnSurface));

        if (cap.ok) {
            badge.setText("✓");
            badge.setTextColor(okColor);
            badge.getBackground().mutate().setTint(alpha(okColor, 0x1f));
            detail.setText("已生效 · 命中 " + cap.hit
                    + (cap.overloads > 1 ? "（" + cap.overloads + " 个重载）" : ""));
            detail.setTextColor(muted);
        } else {
            badge.setText("✕");
            badge.setTextColor(dangerColor);
            badge.getBackground().mutate().setTint(alpha(dangerColor, 0x1f));
            detail.setText("未生效 · 本机系统未找到该方法（目标类 " + cap.cls + "）");
            detail.setTextColor(dangerColor);
        }

        // 展开区：命中项给方法名 + 重载数；未命中项给出「下一步怎么办」
        StringBuilder ex = new StringBuilder();
        ex.append("能力标识：").append(cap.name).append('\n');
        ex.append("目标类　：").append(cap.cls).append('\n');
        if (cap.ok) {
            ex.append("命中方法：").append(cap.hit).append('\n');
            ex.append("重载数量：").append(cap.overloads).append(" 个");
        } else {
            ex.append('\n');
            ex.append("可能原因：\n");
            ex.append("· 系统版本升级后方法被改名或移除\n");
            ex.append("· 厂商 ROM 改写了该类实现\n");
            ex.append("· 模块作用域未勾选「系统框架」\n");
            ex.append("处理建议：更新模块到最新版本；若仍如此，可在反馈时附上本页截图。");
        }
        expand.setText(ex.toString());
        // 未命中项的展开区用等宽字 + 稍暗的红，方便一眼区分
        expand.setTextColor(cap.ok ? muted : dangerColor);

        final boolean[] open = {false};
        head.setOnClickListener(v -> {
            open[0] = !open[0];
            expand.setVisibility(open[0] ? View.VISIBLE : View.GONE);
            arrow.setRotation(open[0] ? 180f : 0f);
        });

        return row;
    }

    /** 能力标识符 → 人话名称；查不到就返回 null（调用方回退显示原标识符）。 */
    private String humanName(String key) {
        for (String[] meta : CAP_META) {
            if (meta[0].equals(key)) return meta[1];
        }
        return null;
    }

    /** 给颜色套上指定 alpha（用于徽标淡色底）。 */
    private static int alpha(int rgb, int a) {
        return (rgb & 0x00ffffff) | ((a & 0xff) << 24);
    }
}
