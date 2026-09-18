package io.github.guys222.processkeepalive;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「检查更新」——向 GitHub releases API 查询最新版本，与本地版本比对。
 *
 * <p><b>设计约束（刻意保持最小）</b>：
 * <ul>
 *   <li>只在用户【主动点击】时发起请求，不做后台自动轮询、开机不联网。</li>
 *   <li>只访问 {@code api.github.com} 这一个域名，不引入任何第三方 SDK。</li>
 *   <li>失败一律静默降级为「检查失败」，绝不弹错误弹窗骚扰用户。</li>
 * </ul>
 *
 * <p><b>为什么解析用字符串截取而不是 JSON 库</b>：本项目零第三方依赖
 * （纯 Android SDK），为一条更新提示引入 Gson/org.json 依赖不值当。
 * GitHub 的 {@code tag_name} 字段格式稳定，用简单的定位+截取足够可靠，
 * 且解析失败会走「检查失败」分支，不会误报。
 */
final class UpdateChecker {

    /** GitHub releases 最新版 API（latest 会自动跳过 draft / prerelease）。 */
    private static final String API_LATEST =
            "https://api.github.com/repos/Guys222/ProcessKeepAlive/releases/latest";

    /** 发布页地址：有新版本时用于跳转下载。 */
    static final String URL_RELEASES =
            "https://github.com/Guys222/ProcessKeepAlive/releases";

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;

    /** 结果回调（在主线程执行）。 */
    interface Callback {
        /**
         * @param newestTag 远端最新 tag（如 "v3.3.0"）；检查失败时为 null
         * @param error     失败原因（人类可读）；成功时为 null
         */
        void onResult(String newestTag, String error);
    }

    /** 单线程池足够（同一时刻只会有一次检查），且避免在主线程做网络。 */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "update-check");
        t.setDaemon(true);
        return t;
    });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UpdateChecker() {
    }

    /** 异步查询最新版本；回调保证在主线程。 */
    static void checkLatest(Callback cb) {
        IO.execute(() -> {
            String tag = null;
            String err = null;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(API_LATEST).openConnection();
                conn.setRequestMethod("GET");
                // GitHub API 要求带 User-Agent，否则返回 403
                conn.setRequestProperty("User-Agent", "ProcessKeepAlive-Updater");
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);

                int code = conn.getResponseCode();
                if (code == 200) {
                    tag = parseTagName(readAll(conn.getInputStream()));
                    if (tag == null) err = "响应格式无法识别";
                } else if (code == 404) {
                    err = "尚无正式发布版";
                } else if (code == 403) {
                    err = "请求过于频繁，请稍后再试";
                } else {
                    err = "服务返回 " + code;
                }
            } catch (Exception e) {
                // 网络不可达 / 超时 / DNS 失败等，统一归为「检查失败」
                err = "网络不可用";
            } finally {
                if (conn != null) conn.disconnect();
            }

            final String fTag = tag;
            final String fErr = err;
            MAIN.post(() -> cb.onResult(fTag, fErr));
        });
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    /**
     * 从 releases JSON 里取出 {@code "tag_name":"..."} 的值。
     * 用定位截取而非完整 JSON 解析：格式稳定、零依赖，失败返回 null 由调用方归为「失败」。
     */
    private static String parseTagName(String json) {
        if (json == null) return null;
        final String key = "\"tag_name\"";
        int i = json.indexOf(key);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + key.length());
        if (colon < 0) return null;
        int firstQuote = json.indexOf('"', colon + 1);
        if (firstQuote < 0) return null;
        int endQuote = json.indexOf('"', firstQuote + 1);
        if (endQuote < 0) return null;
        String tag = json.substring(firstQuote + 1, endQuote).trim();
        return tag.isEmpty() ? null : tag;
    }

    /**
     * 版本比较：远端 tag（如 "v3.3.0"）是否比本地 versionName（如 "3.3.0"）新。
     *
     * <p>逐段按数字比较（避免字符串比较把 "3.10.0" 判成小于 "3.9.0"）；
     * 段数不一致时缺失段补 0。任一段含非数字（如 "3.3.0-beta"）则剥掉后缀再比。
     */
    static boolean isNewer(String remoteTag, String localVersion) {
        if (remoteTag == null || localVersion == null) return false;
        int[] r = parseVersion(remoteTag);
        int[] l = parseVersion(localVersion);
        if (r == null || l == null) return false;
        int n = Math.max(r.length, l.length);
        for (int i = 0; i < n; i++) {
            int rv = i < r.length ? r[i] : 0;
            int lv = i < l.length ? l[i] : 0;
            if (rv != lv) return rv > lv;
        }
        return false;
    }

    /** "v3.3.0" / "3.3.0-debug-b061" → [3,3,0]；解析不出返回 null。 */
    private static int[] parseVersion(String s) {
        String v = s.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        // 剥掉 -debug / -beta 之类后缀，只保留数字与点
        int cut = v.indexOf('-');
        if (cut >= 0) v = v.substring(0, cut);
        String[] parts = v.split("\\.");
        if (parts.length == 0) return null;
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.isEmpty()) return null;
            try {
                out[i] = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                return null;  // 出现非数字段，视为无法比较
            }
            if (out[i] < 0) return null;
        }
        return out;
    }
}
