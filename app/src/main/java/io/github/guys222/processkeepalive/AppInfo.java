package io.github.guys222.processkeepalive;

import android.graphics.drawable.Drawable;

/** 应用列表条目。 */
public class AppInfo {
    public final String packageName;
    public final String label;
    public final Drawable icon;
    public final boolean system;

    public AppInfo(String packageName, String label, Drawable icon, boolean system) {
        this.packageName = packageName;
        this.label = label;
        this.icon = icon;
        this.system = system;
    }
}
