package io.github.guys222.processkeepalive;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 周期存活采样器：每小时探测一次各目标进程是否存活，
 * 写入 {@link SurvivalData} 的 24 槽环形缓冲。由 WorkManager 周期调度。
 *
 * 仅运行在模块 App 进程；不触碰 system_server 快照路径。
 */
public class SurvivalWorker extends Worker {

    public SurvivalWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        SharedPreferences prefs = ctx.getSharedPreferences(Prefs.PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> targets = prefs.getStringSet(Prefs.KEY_TARGETS, new HashSet<>());

        int alive = 0;
        // 优先用 hook 的周期上报（App 进程读别家 /proc 被 hidepid 挡住，本地探测在
        // Android 9+ 上基本是瞎的）；报告过期才退回本地扫描
        SurvivalData.ProcReport rpt = SurvivalData.procReport(ctx);
        Map<String, Long> running = rpt.fresh ? rpt.map : SurvivalData.processStarts(ctx);
        int n = 0;
        for (String pkg : targets) {
            Long start = running.get(pkg);
            boolean up = start != null && start >= 0;
            SurvivalData.pushSample(ctx, pkg, up ? 1f : 0f);
            if (up) {
                // 更新兜底打点：换了次开机、或进程启动时刻比打点还新，都让它重新起算
                SurvivalData.noteAlive(ctx, pkg, start);
                alive++;
            }
            n++;
        }

        float overall = n == 0 ? 1f : (float) alive / n;
        SurvivalData.pushOverall(ctx, overall);
        return Result.success();
    }
}
