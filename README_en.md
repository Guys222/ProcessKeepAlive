[简体中文](README.md) | English

# ProcessKeepAlive (进程保活)

> Project: https://github.com/Guys222/ProcessKeepAlive

An LSPosed / Xposed-based Android background keep-alive module, mainly targeting **LineageOS (AOSP-based)**. It modifies system behavior inside the `system_server` process so target apps are not killed by the Low Memory Killer (LMK) or "Force stop".

> ⚠️ For personal devices and your own apps only. Keep-alive keeps apps occupying memory and battery — do not use it to track or monitor others' devices.

## Download

Download the latest `ProcessKeepAlive-v3.3.0-release.apk` from the [Releases](https://github.com/Guys222/ProcessKeepAlive/releases) page (release-signed with this project's dedicated keystore). Install directly — no need to build it yourself.

## Screenshots

| Home | Apps | Settings |
| --- | --- | --- |
| ![Home](home.jpg) | ![Apps](app.jpg) | ![Settings](settings.jpg) |

## What it does

| Capability | Description | Default |
| --- | --- | --- |
| Lower OOM Adj | Push target process priority down to "visible" level so LMK won't kill it first | On |
| Block force-stop | Hook `forceStopPackage` so "Force stop" in Settings does nothing | On |
| Block background kill | Hook `killBackgroundProcesses` | On |
| Aggressive mode | Hook `ProcessRecord.kill` / `killPackageProcessesLocked` | Off |
| Persistent experiment | Mark target as `persistent`, auto-restart after kill | Off |
| Boot auto-start | Auto-launch checked apps after reboot (launched inside system_server, bypassing background limits) | Off |

## How it works (hook points)

All hooks are injected into `system_server` (package `android`); keep-alive only needs the "System framework" scope:

- `ActivityManagerService.forceStopPackage`
- `ActivityManagerService.killBackgroundProcesses`
- `ActivityManagerService.killPackageProcessesLocked`
- `OomAdjuster.computeOomAdjLocked` (Android 11+, core; older versions fall back to `updateOomAdjLocked`)
- `ProcessRecord.kill`
- `ActivityManagerService.newProcessRecordLocked` (persistent experiment)
- `ActivityManagerService.finishBooting` (boot auto-start)

OOM Adj is only ever lowered, never raised — foreground apps are never promoted, so they are never hurt.

## Build

1. Open this folder in **Android Studio** (Gradle is configured automatically).
2. Requires **JDK 17** and **compileSdk 34**.
3. Then `Build → Build APK(s)`; the artifact is in `app/build/outputs/apk/`.

CLI (with JDK17 + Android SDK installed):

```bash
./gradlew assembleDebug
```

Dependency note: the Xposed API uses `compileOnly 'de.robv.android.xposed:api:82'`, compile-time only — LSPosed provides it at runtime, so it is not bundled into the APK.

> **Mirror**: This project uses the **Alibaba Cloud** Maven mirror (`maven.aliyun.com`) for CN networks. If you're overseas or prefer the official sources, replace `maven.aliyun.com/...` in `settings.gradle` and `build.gradle` with `google()` / `mavenCentral()`.

## Install & usage

1. Bootloader unlocked, **Magisk** flashed, and **LSPosed** (Zygisk) installed.
2. Install this module APK.
3. Open LSPosed Manager → Modules → enable "进程保活".
4. **Tick the "System framework (android)" scope** — this is required; all hooks go into `system_server`. Without it the module does nothing.
   - **(Optional) Also tick this module (io.github.guys222.processkeepalive)**: only for the home screen's live activation-status indicator. Off does not affect keep-alive, but the home screen may occasionally show "not activated" (when the app is force-stopped/frozen and the system_server's ContentProvider report is unreachable). Tick it so the app confirms injection immediately on launch — most reliable status.
5. Open the module app (four bottom tabs: **Apps / Home / Processes / Settings**, all changes saved
   instantly, light & dark themes):
   - **Apps**: search and tick apps to keep alive; filter chips (user apps / show system apps /
     guarded only / all off). Each guarded app shows its **real survival time**
     (running · kept alive X h Y m) and a "killed / relaunched" count badge.
   - **Home**: module activation pill, Bento overview (guarded / running / message keep-alive),
     the **24-hour survival chart** (tap an app icon below it to drill into a single app's 24h
     survival rate), the master keep-alive switch, the guarded app list, and at the bottom the
     **guard event timeline** — newest first, recording "killed / relaunched / boot-launched"
     events with timestamps and colour-coded badges.
   - **Processes**: live process board grouped by app, showing `pid / state / oom_adj / memory RSS /
     uptime`, each row with a **kill** button (SIGKILL, confirmation required, critical system
     processes greyed out and protected); system/kernel processes are grouped separately.
     The ⋮ menu exports a **process snapshot** (plain-text report) via the system file picker.
     While the page is open a 3-second heartbeat drives system_server to scan the full `/proc`.
   - **Settings**: dark mode, per-feature keep-alive switches, priority level,
     **persistent display** (foreground-service notification refreshing about every 30s, with a
     "stop persistent" action), **backup & restore** (tap = import/export JSON via the system file
     picker, long-press = clipboard), author/repo/version.
6. Reboot (or wait ~30s for the config to hot-refresh).
7. Verify persistence with `adb shell "top -b -n1 | head -n 30"` or Developer Options → "Running services".

## Important notes

- **LineageOS does not kill backgrounds as aggressively as MIUI/ColorOS.** This module mainly handles "killed by LMK under memory pressure" and "force-stopped" — effects are subtler than on Chinese OEM ROMs.
- **Where the real survival time comes from**: when you tick an app in `AppsFragment.saveTargets()`,
  the module stamps `gstart_<package>` (guard start timestamp, ms). It is passed through
  `ConfigProvider` into the system_server `Prefs` snapshot (`guardStartOf(pkg)`, read-only on the hot
  path). The UI computes `now - guardStartOf(pkg)` for the real survival time.
- **Where the 24h survival chart comes from**: `SurvivalWorker` (a WorkManager periodic task, hourly)
  probes whether each target process is alive via `ActivityManager.getRunningAppProcesses()` from
  inside the app process, and writes into `SurvivalData`'s 24-slot ring buffer (one sample per hour,
  keeping a rolling 24 hours). The home chart, average survival rate and longest continuous survival
  are all computed from this real data; before 24 hours have elapsed, leading gaps are filled with the
  earliest known value to keep the curve continuous.
- **Doze/standby restrictions**: for apps with "message keep-alive" enabled, this module **automatically
  exempts them** from Doze and App Standby throttling — it hooks `DeviceIdleController.isPowerSaveWhitelistApp`
  and `AppStandbyController.getAppStandbyBucket` inside system_server so the system treats them as
  already whitelisted / in the ACTIVE bucket, with no manual steps. The module does **not** provide a
  global battery-whitelist management UI; to whitelist other apps manually:
  ```bash
  adb shell dumpsys deviceidle whitelist +com.example.app
  ```
  Or set the target app to "Not optimized" in Settings → Battery → Battery optimization.
- **Third-party app list**: the manifest declares `QUERY_ALL_PACKAGES`, so **every installed app,
  including icon-less background ones**, is listed in the config UI. That permission is restricted on
  Google Play; this module is distributed for personal use, so it is unaffected.
- **Persistent experiment is risky**: may misbehave on uninstall/update, loop-restart after crash, and drain battery. Off by default — enable only when needed.
- **The persistent notification needs the notification permission**: on Android 13+, enabling
  "persistent display" requests `POST_NOTIFICATIONS`; if denied, the notification cannot show —
  re-grant it in system settings. That foreground service only displays status and refreshes
  periodically; **it performs no keep-alive action itself**.
- **Widget refresh rate**: the AppWidget's own `updatePeriodMillis` cannot go below 30 minutes, so the
  4×1 widget is driven by the persistent-notification foreground service (about every 30s).
  **With persistent display off, the widget can lag by up to 30 minutes** — a system limit, not a bug.
  The 4×1 size comes from `targetCellWidth` / `targetCellHeight` (Android 12+); older launchers fall
  back to `minWidth` / `minHeight` (180×72dp), roughly 3×1.
- **Killing a process from the Processes tab is one-way**: the app cannot send commands directly to
  system_server. "Kill" writes the pid into `kill_queue`, and a system_server watcher thread polls it
  every second and issues SIGKILL, so the process disappears about a second after you tap.
- Any setting change (incl. main switch, aggressive mode, persistent) hot-refreshes in ~30s; reboot if it doesn't take effect.
- If a hook logs "install failed", the method signature differs on that Android version (see logcat, tag `ProcessKeepAlive`); tweak `KeepAliveHooks.java` accordingly.

## Verify

```bash
# module logs
adb logcat -s ProcessKeepAlive

# check a process's adj / oom_score_adj (lower = safer)
adb shell "ps -A | grep com.example.app"
adb shell "cat /proc/<pid>/oom_score_adj"
```

## Structure

```
ProcessKeepAlive/
├── app/src/main/
│   ├── assets/xposed_init               # Xposed entry declaration (legacy)
│   ├── resources/META-INF/xposed/
│   │   ├── java_init.list               # modern entry declaration (coexists with legacy)
│   │   ├── module.prop                  # module metadata (version here — keep in sync with build.gradle)
│   │   └── scope.list                   # recommended scope
│   ├── java/io/github/guys222/processkeepalive/
│   │   ├── HookEntry.java               # entry: inject into system_server
│   │   ├── ModernEntry.java             # modern entry adapter
│   │   ├── KeepAliveHooks.java          # core hooks + proc scan/report/kill watcher/guard event queue
│   │   ├── Prefs.java                   # system_server-side config snapshot (read-only on hot path, no IPC)
│   │   ├── ActivationMark.java          # activation mark (for home detection)
│   │   ├── ConfigProvider.java          # two-way channel: getConfig / reportProcs / takeKill
│   │   ├── MainActivity.java            # main UI (ViewPager2 + 4 custom-drawn tabs)
│   │   ├── AppsFragment.java            # apps tab
│   │   ├── HomeFragment.java            # home tab (24h survival chart, drill-down, event timeline)
│   │   ├── ProcessesFragment.java       # processes tab (3s heartbeat + snapshot export + kill)
│   │   ├── SettingsFragment.java        # settings tab (persistent display, backup & restore)
│   │   ├── ConfigBackup.java            # config backup/restore (JSON, file + clipboard)
│   │   ├── ProcSnapshotExport.java      # process snapshot export (plain-text report)
│   │   ├── GuardStatus.java             # shared data source for notification and widget
│   │   ├── GuardService.java            # foreground service (drives notification + widget refresh)
│   │   ├── GuardWidget.java             # home-screen widget
│   │   ├── SurvivalChartView.java       # 24h survival chart custom view
│   │   ├── SurvivalData.java            # survival ring buffer, guard counters, event timeline storage
│   │   ├── SurvivalWorker.java          # hourly survival sampling (WorkManager)
│   │   ├── SwitchCapsuleView.java       # switch widget
│   │   ├── ThemeManager.java            # light/dark theme
│   │   ├── AppIcons.java / AppInfo.java / AppConfig.java / ProcessInfo.java
│   │   └── BootReceiver.java / StopReceiver.java
│   └── res/                             # layouts & resources (incl. values-night dark + widget layout)
├── build.gradle / settings.gradle
├── gradle.properties
└── key.properties                       # signing config (not distributed with the public repo)
```

## Changelog

### v3.3.0 (versionCode 34)

New features:

- **Processes tab** (3rd tab, between Home and Settings): processes grouped by app showing
  `pid / state / oom_adj / memory RSS / uptime`, with SIGKILL (confirmation required, critical
  processes protected) and process-snapshot export.
- **Guard event timeline**: bottom of Home, newest first, showing "killed / relaunched /
  boot-launched" events with timestamps.
- **Config backup & restore**: new in Settings. Tap to export/import JSON via the system file picker,
  long-press to use the clipboard. No storage permission needed.
- **Persistent notification**: Settings → "persistent display", driven by a foreground service,
  refreshing about every 30s, with a "stop persistent" action.
- **Home-screen widget**: 4×1 guard board showing "guarded N / M" and this boot's guard actions,
  refreshed by the persistent service.

Other:

- Version bumped from 3.2.2 (versionCode 33) to 3.3.0 (versionCode 34).
- Chinese and English READMEs updated with the new feature descriptions and directory structure.

### v3.2.2 (versionCode 33)

- Package renamed from `com.processkeepalive` to `io.github.guys222.processkeepalive` to bypass
  LSPosed's domain-squatting check. Users upgrading from the old package are treated as a new module
  and must re-tick the scope and reboot.

## Donation

If this module helped you, feel free to buy the author a coffee ☕

![WeChat QR](wechat.jpg)

## License

This project is for personal study and your own devices only.
