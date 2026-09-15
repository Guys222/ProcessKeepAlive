package io.github.guys222.processkeepalive;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * 通过 Binder 向 system_server 暴露模块配置。
 *
 * system_server 受 SELinux 限制无法直接读 App 的 /data/data 目录，但可以
 * 通过 ContentProvider 跨进程查询。这里用 call() 一次性返回全部配置。
 */
public class ConfigProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        // system_server 钩子安装成功后上报激活状态：在 App 自己目录写激活标记
        if ("markActive".equals(method)) {
            try {
                ActivationMark.write(getContext());
            } catch (Throwable ignored) {
            }
            Bundle ok = new Bundle();
            ok.putBoolean("ok", true);
            return ok;
        }
        if (!"getConfig".equals(method)) return null;
        try {
            SharedPreferences prefs = getContext()
                    .getSharedPreferences(Prefs.PREFS_NAME, Context.MODE_PRIVATE);
            Bundle b = new Bundle();
            b.putBoolean("enabled", prefs.getBoolean(Prefs.KEY_ENABLED, true));
            b.putBoolean("auto_start", prefs.getBoolean(Prefs.KEY_AUTO_START, false));

            Set<String> targets = prefs.getStringSet(Prefs.KEY_TARGETS, new HashSet<String>());
            b.putStringArrayList("targets", new ArrayList<>(targets));
            for (String pkg : targets) {
                b.putInt(Prefs.KEY_ADJ_PREFIX + pkg, prefs.getInt(Prefs.KEY_ADJ_PREFIX + pkg, 1));
                b.putBoolean(Prefs.KEY_FORCE_PREFIX + pkg,
                        prefs.getBoolean(Prefs.KEY_FORCE_PREFIX + pkg, true));
                b.putBoolean(Prefs.KEY_KILLBG_PREFIX + pkg,
                        prefs.getBoolean(Prefs.KEY_KILLBG_PREFIX + pkg, true));
                b.putBoolean(Prefs.KEY_KILL_PREFIX + pkg,
                        prefs.getBoolean(Prefs.KEY_KILL_PREFIX + pkg, false));
                b.putBoolean(Prefs.KEY_PERSIST_PREFIX + pkg,
                        prefs.getBoolean(Prefs.KEY_PERSIST_PREFIX + pkg, false));
                b.putBoolean(Prefs.KEY_MSG_PREFIX + pkg,
                        prefs.getBoolean(Prefs.KEY_MSG_PREFIX + pkg, false));
            }
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
