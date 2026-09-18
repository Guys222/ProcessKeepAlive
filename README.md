简体中文 | [English](README_en.md)

# 进程保活（ProcessKeepAlive）

> 项目地址：https://github.com/Guys222/ProcessKeepAlive

一个基于 **LSPosed / Xposed** 的 Android 后台保活模块，主要面向 **LineageOS（AOSP 系）**。
它在 `system_server` 进程内部修改系统行为，让目标应用不被低内存杀手（LMK）或「强行停止」杀掉。

> ⚠️ 仅限个人设备、自己安装的应用使用。保活会让应用持续占用内存与电量，请勿用于追踪、监控他人设备等用途。

## 下载

到 [Releases](https://github.com/Guys222/ProcessKeepAlive/releases) 页面下载最新的 `ProcessKeepAlive-v3.3.0-release.apk`（release 签名，已用本项目专用 keystore 签名），可直接安装，无需自行编译。

## 截图

| 主页 | 应用 | 设置 |
| --- | --- | --- |
| ![主页](home.jpg) | ![应用](app.jpg) | ![设置](settings.jpg) |

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

全部注入 `system_server`（包名 `android`），保活功能只需「系统框架」这一项作用域：

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

正式签名包（`key.properties` 与 keystore 均就位时）：

```bash
./gradlew assembleRelease
```

若 keystore 缺失，构建会打印 `[签名]` 警告并退回未签名包，不会报错中断。

依赖说明：Xposed API 使用 `compileOnly 'de.robv.android.xposed:api:82'`，
仅编译期生效，运行时由 LSPosed 提供，不会打进 APK。

### 安全打包源码

外发源码前请先跑打包脚本，它会自动剔除密钥、签名配置和构建产物，并在打包后
**拆包自检**——发现敏感文件就删除 zip 并报错，确保不会误发：

```bash
./pack-source.sh              # 产出 ProcessKeepAlive-src-<日期>.zip
./pack-source.sh --dry-run    # 只看会打包哪些文件，不生成 zip
./pack-source.sh --list       # 打包完成后打印完整文件清单
```

> 直接 `zip -r` 会绕过 `.gitignore`，把 `key.properties` 一起打进包里。
> 已打包好的 zip 可用 `unzip -l xxx.zip | grep -iE 'jks|key.properties|apk'` 自行复查。

> **镜像说明**：本工程已按国内网络环境把 Maven 仓库配置为**阿里云镜像**
> （`maven.aliyun.com`）。如果你在海外或想用官方源，把 `settings.gradle` 与
> `build.gradle` 里的 `maven.aliyun.com/...` 换回 `google()` / `mavenCentral()` 即可。

## 安装与使用

1. 手机已解锁 Bootloader、刷入 **Magisk**，并安装 **LSPosed**（Zygisk 版）。
2. 安装本模块 APK。
3. 打开 LSPosed Manager → 模块 → 启用「进程保活」。
4. **作用域勾选「系统框架（android）」**——这是保活功能必需的一项，钩子全部注入
   `system_server`，不勾这一项模块不工作。
   - **（可选）同时勾选「本模块（io.github.guys222.processkeepalive）」**：仅用于主页「模块激活状态」
     的实时检测。不勾也不影响保活，但主页可能偶发显示「未激活」（当 App 被强制停止/冻结、
     system_server 经 ContentProvider 上报激活状态时 Provider 不可达）。勾上后 App 启动即能
     立刻确认自身被注入，状态显示最可靠。
5. 打开模块 App（底部四个 tab：**应用 / 主页 / 进程 / 设置**，所有改动即时保存，支持深色/浅色主题）：
   - **应用**：搜索、勾选要保活的应用；筛选 chip（用户应用 / 显示系统应用 / 仅看已守护 / 全部关闭）；
     每个已守护应用显示**真实存活时长**（运行中 · 已保活 X 小时 Y 分）与「被杀 / 拉起」次数徽标；
   - **主页**：模块激活状态 pill、Bento 概览（守护中 / 运行中 / 消息保活）、
     **24 小时存活曲线**（可点下方应用图标下钻查看单个应用的 24h 存活率）、后台保活总开关、已守护应用列表，
     底部是**守护事件时间线**——倒序记录「被杀 / 拉起 / 开机拉起」三类事件，带时间戳与彩色类型徽标；
   - **进程**：按应用分组的实时进程看板，每行显示 `pid / 状态 / oom_adj / 内存 RSS / 运行时长`，
     带**杀进程**按钮（SIGKILL，二次确认，系统关键进程自动置灰保护）；系统/内核进程单独归入「系统进程」组；
     右上角 ⋮ → **导出进程快照**（纯文本报告，走系统文件选择器）；页面打开期间每 3 秒心跳，
     驱动 system_server 持续扫描全量 `/proc`；
   - **设置**：深色模式、各保活能力开关、优先级档位、**常驻显示**（前台服务常驻通知，约 30 秒刷新，
     通知带「关闭常驻」按钮）、**备份与恢复**（点击 = 通过系统文件选择器导入/导出 JSON，长按 = 剪贴板）、
     作者/仓库/版本。
6. 重启系统（或等约 30 秒让配置热刷新）。
7. 用 `adb shell "top -b -n1 | head -n 30"` 或开发者选项「正在运行的服务」验证进程常驻。

## 重要提示

- **真实存活时长的数据来源**：模块在 `AppsFragment.saveTargets()` 勾选应用时打点写入
  `gstart_<包名>`（守护起始时间戳，毫秒），经 `ConfigProvider` 透传给 system_server 的
  `Prefs` 快照（`guardStartOf(pkg)` 热路径只读）。前端用 `now - guardStartOf(pkg)` 即可算真实存活时长。
- **24h 存活率曲线的数据来源**：由 `SurvivalWorker`（WorkManager 周期任务，每小时一次）在 App 进程内
  用 `ActivityManager.getRunningAppProcesses()` 探测各目标进程是否存活，写入 `SurvivalData` 的
  24 槽环形缓冲（每小时一个采样点，滚动保留最近 24 小时）。主页曲线、平均存活率、最长连续存活均由
  该真实数据计算；未满 24 小时时前导空缺用最早已知值填充以保持曲线连续。
- **LineageOS 本身不像 MIUI/ColorOS 那样激进杀后台**，本模块主要解决的是
  「内存紧张时被 LMK 杀掉」「被强行停止」两类场景，效果不像在国产 ROM 上那么戏剧化。
- **省电（Doze/待机）限制**：本模块对开启「消息保活」的应用会**自动豁免** Doze 与 App Standby 节流——在 `system_server` 内 hook `DeviceIdleController.isPowerSaveWhitelistApp` 与 `AppStandbyController.getAppStandbyBucket`，让系统将其视为已加入省电白名单 / 处于 ACTIVE 桶，无需手动操作。但模块**不提供全局省电白名单管理界面**。若想给未开启消息保活的应用手动加白名单：
  ```bash
  adb shell dumpsys deviceidle whitelist +com.example.app
  ```
  或在「设置 → 电池 → 电池优化」里把目标应用设为「不优化」。
- **第三方应用列表**：模块 `targetSdk 34`，Manifest 已声明 `QUERY_ALL_PACKAGES`，
  因此**包括无桌面图标的后台服务型应用在内的全部已安装应用**都能在配置界面列出。
  该权限在 Google Play 上架受限，本模块为自用分发，不受影响。
- **常驻实验有风险**：可能让应用在卸载/更新时异常、崩溃后无限重启、增加耗电，默认关闭，
  需要时再开。
- **常驻通知需要通知权限**：Android 13+ 首次开启「常驻显示」会申请 `POST_NOTIFICATIONS`，
  拒绝后通知无法显示，可在系统设置中重新授予。该前台服务只做状态展示与定时刷新，
  **本身不执行任何保活动作**。
- **桌面小部件的刷新频率**：AppWidget 自带的 `updatePeriodMillis` 最短只有 30 分钟，
  因此 4×1 小部件由常驻通知的前台服务驱动刷新（约 30 秒一次）。
  **未开启「常驻显示」时，小部件最多可能滞后 30 分钟**——这是系统限制，不是故障。
  小部件 4×1 取自 `targetCellWidth` / `targetCellHeight`（Android 12+）；
  老启动器按 `minWidth` / `minHeight`（180×72dp）回退，约 3×1。
- **进程页的杀进程是单向通道**：App 无法直接向 system_server 发命令，
  「杀进程」是把 pid 写进 `kill_queue`，由 system_server 的监视线程每秒轮询取走后执行 SIGKILL，
  因此点击到进程消失会有约 1 秒延迟。
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
│   ├── assets/xposed_init               # Xposed 入口声明（legacy）
│   ├── resources/META-INF/xposed/
│   │   ├── java_init.list               # 新版入口声明（modern，与 legacy 并存）
│   │   ├── module.prop                  # 模块元信息（版本号在此，需与 build.gradle 同步）
│   │   └── scope.list                   # 推荐作用域
│   ├── java/io/github/guys222/processkeepalive/
│   │   ├── HookEntry.java               # 入口：注入 system_server
│   │   ├── ModernEntry.java             # 新版入口适配
│   │   ├── KeepAliveHooks.java          # 核心钩子 + 进程扫描/上报/杀进程监视/守护事件队列
│   │   ├── Prefs.java                   # system_server 侧配置快照（热路径只读，禁止 IPC）
│   │   ├── ActivationMark.java          # 激活标记（供主页检测）
│   │   ├── ConfigProvider.java          # 双向通道：getConfig / reportProcs / takeKill
│   │   ├── MainActivity.java            # 主界面（ViewPager2 + 自绘 4 tab）
│   │   ├── AppsFragment.java            # 应用页
│   │   ├── HomeFragment.java            # 主页（24h 存活曲线、下钻、守护事件时间线）
│   │   ├── ProcessesFragment.java       # 进程页（3 秒心跳 + 快照导出 + 杀进程）
│   │   ├── SettingsFragment.java        # 设置页（含常驻显示、备份与恢复）
│   │   ├── ConfigBackup.java            # 配置备份/恢复（JSON，文件 + 剪贴板）
│   │   ├── ProcSnapshotExport.java      # 进程快照导出（纯文本报告）
│   │   ├── GuardStatus.java             # 常驻通知与小部件的共用取数
│   │   ├── GuardService.java            # 前台服务（驱动通知 + 小部件刷新）
│   │   ├── GuardWidget.java             # 桌面小部件
│   │   ├── SurvivalChartView.java       # 24h 存活曲线自定义控件
│   │   ├── SurvivalData.java            # 存活率环形缓冲、守护计数、事件时间线落盘
│   │   ├── SurvivalWorker.java          # 每小时存活采样（WorkManager）
│   │   ├── SwitchCapsuleView.java       # 开关控件
│   │   ├── ThemeManager.java            # 深色/浅色主题
│   │   ├── AppIcons.java / AppInfo.java / AppConfig.java / ProcessInfo.java
│   │   └── BootReceiver.java / StopReceiver.java
│   └── res/                             # 布局与资源（含 values-night 深色、小部件布局）
├── build.gradle / settings.gradle
├── gradle.properties
├── pack-source.sh                       # 安全打包源码（自动剔除密钥/构建产物，并自检产物）
├── build-debug.sh                       # 构建 debug 测试包，文件名带递增序号（001/002…）
└── key.properties                       # 签名配置（不随公开仓库分发）
```

## 更新日志

### v3.3.0（versionCode 34）

新增功能：

- **进程页**（第 3 个 tab，位于「主页」与「设置」之间）：按应用分组展示 `pid / 状态 / oom_adj /
  内存 RSS / 运行时长`，支持 SIGKILL 杀进程（二次确认 + 关键进程保护），可导出进程快照。
- **守护事件时间线**：主页底部倒序展示「被杀 / 拉起 / 开机拉起」三类事件，带时间戳。
- **配置备份与恢复**：设置页新增。点击走系统文件选择器导出/导入 JSON，长按走剪贴板，均无需存储权限。
- **常驻通知**：设置页「常驻显示」，前台服务驱动，约 30 秒刷新，通知带「关闭常驻」按钮。
- **桌面小部件**：4×1 守护看板，显示「守护中 N / M」与本次开机的守护动作，由常驻服务驱动刷新。

其他：

- 版本号从 3.2.2（versionCode 33）升到 3.3.0（versionCode 34）。
- 同步更新了中英文 README 的功能说明与目录结构。

### v3.2.2（versionCode 33）

- 包名由 `com.processkeepalive` 改为 `io.github.guys222.processkeepalive`，绕过 LSPosed 仓库的
  域名抢注校验。已装旧版的用户升级后会被视为新模块，需重新勾选作用域并重启。

## 打赏

如果这个模块帮到了你，欢迎请作者喝杯奶茶 ☕

![微信收款码](wechat.jpg)

## 许可

本项目仅供个人学习与自用设备使用。
