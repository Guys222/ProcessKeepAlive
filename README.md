# 进程保活（ProcessKeepAlive）

> 项目地址：https://github.com/Guys222/ProcessKeepAlive

一个基于 **LSPosed / Xposed** 的 Android 后台保活模块，主要面向 **LineageOS（AOSP 系）**。
它在 `system_server` 进程内部修改系统行为，让目标应用不被低内存杀手（LMK）或「强行停止」杀掉。

> ⚠️ 仅限个人设备、自己安装的应用使用。保活会让应用持续占用内存与电量，请勿用于追踪、监控他人设备等用途。

## 下载

到 [Releases](../../releases)（release 签名，已用本项目专用 keystore 签名），可直接安装，无需自行编译。

## 它能做什么

| 能力 | 说明 | 默认 |
| --- | --- | --- |
| 降低 OOM Adj | 把目标进程优先级压低到「可见级」，LMK 不优先杀它 | 开 |
| 阻止强制停止 | 拦截 `forceStopPackage`，设置里的「强行停止」失效 | 开 |
| 阻止后台杀死 | 拦截 `killBackgroundProcesses` | 开 |
| 强力模式 | 拦截 `ProcessRecord.kill` / `killPackageProcessesLocked` | 关 |
| 常驻实验 | 把目标进程标记为 `persistent`，被杀后自动重启 | 关 |
| 开机自启动 | 重启手机后自动拉起已勾选的应用（system_server 内拉起，绕过后台限制） | 关 |

## 原理（钩子点）

全部注入 `system_server`（包名 `android`），作用域只需「系统框架」：

- `ActivityManagerService.forceStopPackage`
- `ActivityManagerService.killBackgroundProcesses`
- `ActivityManagerService.killPackageProcessesLocked`
- `OomAdjuster.computeOomAdjLocked`（Android 11+，核心；老版本回退 `updateOomAdjLocked`）
- `ProcessRecord.kill`
- `ActivityManagerService.newProcessRecordLocked`（常驻实验）
- `ActivityManagerService.finishBooting`（开机自启动，拉起目标应用）

OOM Adj 只「下调、不上调」：绝不会把正在前台运行的进程优先级调高，避免误伤。

## 编译

1. 用 **Android Studio** 打开本目录（会自动配置 Gradle）。
2. 需要 **JDK 17**、**compileSdk 34**。
3. 直接 `Build → Build APK(s)`，产物在 `app/build/outputs/apk/`。

命令行（已装好 JDK17 + Android SDK 时）：

```bash
./gradlew assembleDebug
```

依赖说明：Xposed API 使用 `compileOnly 'de.robv.android.xposed:api:82'`，
仅编译期生效，运行时由 LSPosed 提供，不会打进 APK。

> **镜像说明**：本工程已按国内网络环境把 Maven 仓库配置为**阿里云镜像**
> （`maven.aliyun.com`）。如果你在海外或想用官方源，把 `settings.gradle` 与
> `build.gradle` 里的 `maven.aliyun.com/...` 换回 `google()` / `mavenCentral()` 即可。

## 安装与使用

1. 手机已解锁 Bootloader、刷入 **Magisk**，并安装 **LSPosed**（Zygisk 版）。
2. 安装本模块 APK。
3. 打开 LSPosed Manager → 模块 → 启用「进程保活」。
4. **作用域勾选「系统框架（android）」和「本模块（com.processkeepalive）」**——
   前者执行保活钩子，后者用于主页的「模块激活状态」检测。
5. 打开模块 App（底部三页导航，所有改动即时保存）：
   - **应用**：搜索、勾选要保活的应用；右上角菜单可开关「显示系统应用」、全部开启/关闭；
   - **主页**：查看「模块激活状态」与「启用状态」；
   - **设置**：开关各保活能力、调整优先级档位、查看作者/仓库/版本。
6. 重启系统（或等约 30 秒让配置热刷新）。
7. 用 `adb shell "top -b -n1 | head -n 30"` 或开发者选项「正在运行的服务」验证进程常驻。

## 重要提示

- **LineageOS 本身不像 MIUI/ColorOS 那样激进杀后台**，本模块主要解决的是
  「内存紧张时被 LMK 杀掉」「被强行停止」两类场景，效果不像在国产 ROM 上那么戏剧化。
- **省电（Doze/待机）限制是另一回事**，本模块不处理省电白名单。建议手动加白名单：
  ```bash
  adb shell dumpsys deviceidle whitelist +com.example.app
  ```
  或在「设置 → 电池 → 电池优化」里把目标应用设为「不优化」。
- **第三方应用列表**：模块 `targetSdk 34`，已在 Manifest 中声明 `<queries>`（`MAIN`/`LAUNCHER`），
  配置界面才能列出带桌面图标的第三方应用。若日后需要列出**无桌面图标**的后台应用，需额外申请
  `QUERY_ALL_PACKAGES` 权限（Play 上架受限，仅自用可开）。
- **常驻实验有风险**：可能让应用在卸载/更新时异常、崩溃后无限重启、增加耗电，默认关闭，
  需要时再开。
- 修改任意设置（含主开关、强力模式、常驻进程）后约 30 秒自动热刷新，无需重启；若长时间不生效，重启系统再试。
- 若日志中某钩子提示「安装失败」，说明该 Android 版本的方法签名有差异（详见 logcat，
  tag 为 `ProcessKeepAlive`），可据此微调 `KeepAliveHooks.java`。

## 验证

```bash
# 查看模块日志
adb logcat -s ProcessKeepAlive

# 查看某个进程的 adj / oom_score_adj（值越低越不容易被杀）
adb shell "ps -A | grep com.example.app"
adb shell "cat /proc/<pid>/oom_score_adj"
```

## 目录结构

```
ProcessKeepAlive/
├── app/src/main/
│   ├── assets/xposed_init               # Xposed 入口声明
│   ├── java/com/processkeepalive/
│   │   ├── HookEntry.java               # 入口：注入 system_server
│   │   ├── KeepAliveHooks.java          # 核心钩子
│   │   ├── Prefs.java                   # 配置读取
│   │   ├── ActivationMark.java          # 激活标记（供主页检测）
│   │   ├── ConfigProvider.java          # 跨进程配置读取
│   │   ├── ModernEntry.java             # 新版入口适配
│   │   ├── MainActivity.java            # 主界面（ViewPager2 + 底部导航）
│   │   ├── AppsFragment.java            # 应用页
│   │   ├── HomeFragment.java            # 主页
│   │   ├── SettingsFragment.java        # 设置页
│   │   └── AppInfo.java / AppConfig.java
│   └── res/                             # 布局与资源
├── build.gradle / settings.gradle
└── gradle.properties
```

## 许可

本项目仅供个人学习与自用设备使用。
