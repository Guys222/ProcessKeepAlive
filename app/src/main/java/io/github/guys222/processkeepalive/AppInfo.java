package io.github.guys222.processkeepalive;

/**
 * 应用列表条目。
 *
 * 刻意不持有图标：图标解码开销大（一次 Binder IPC + PNG 解码），若在装载列表时对
 * 全部已安装应用逐个加载，上百个应用会阻塞主线程一秒以上，表现为切到应用页卡顿。
 * 现在图标由 AppsFragment 在绑定可见行时异步加载并走内存缓存，见其 loadIconInto()。
 */
public class AppInfo {
    public final String packageName;
    public final String label;
    public final boolean system;

    public AppInfo(String packageName, String label, boolean system) {
        this.packageName = packageName;
        this.label = label;
        this.system = system;
    }
}
