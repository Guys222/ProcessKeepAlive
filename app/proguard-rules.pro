# =====================================================================
# Xposed / LSPosed 入口 keep 规则
# =====================================================================
#
# 当前 release 的 minifyEnabled = false，所以下面这些规则**暂时不生效**；
# 但一旦有人开启混淆，入口类会被重命名，后果是**模块彻底失效**：
#   · LSPosed 按 assets/xposed_init 里的**类名字符串**反射加载入口类；
#   · java_init.list 同理，写的是 ModernEntry 的全限定名；
#   · 两个类里的 install()/installModern() 也是按名字被入口框架调用的。
# 名字一改，框架就找不到类 —— 而这类失败是静默的：用户只会看到
# 「模块装了但什么都不生效」，连日志都不会有。属于潜伏的致命项，提前补上。

# legacy 入口：由 assets/xposed_init 指定
-keep class io.github.guys222.processkeepalive.HookEntry { *; }

# modern 入口：由 META-INF/xposed/java_init.list 指定
-keep class io.github.guys222.processkeepalive.ModernEntry { *; }

# 钩子实现体：被上面两个入口直接引用，且内部大量使用反射访问系统私有字段
# （findField/getField 按字段名查找），方法体不能被裁剪或内联改写。
-keep class io.github.guys222.processkeepalive.KeepAliveHooks { *; }

# 被 system_server 侧通过 Binder 调用的 ContentProvider（跨进程按名字解析）
-keep class io.github.guys222.processkeepalive.ConfigProvider { *; }

# 所有在 AndroidManifest 里按类名注册的组件：混淆后类名被改，系统找不到。
# 逐个列名而非用通配 —— 漏一个就是一个「功能静默失效」的坑。
-keep class io.github.guys222.processkeepalive.MainActivity { *; }
-keep class io.github.guys222.processkeepalive.CapabilityDiagActivity { *; }
-keep class io.github.guys222.processkeepalive.GuardService { *; }
-keep class io.github.guys222.processkeepalive.ConfigProvider { *; }
-keep class io.github.guys222.processkeepalive.BootReceiver { *; }
-keep class io.github.guys222.processkeepalive.StopReceiver { *; }
-keep class io.github.guys222.processkeepalive.GuardWidget { *; }

# 反射用到的成员：KeepAliveHooks 通过 getDeclaredField/反射调用访问系统类，
# 这里保留本模块自身的注解与泛型信息，避免反射辅助逻辑被优化掉。
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# 静默失败要不得：保留异常堆栈里的行号，混淆后日志仍可定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 注：本模块不依赖任何需要 keep 的第三方库；JSONObject 等来自系统 framework，
# 不参与混淆，无需额外规则。
