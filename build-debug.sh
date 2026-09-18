#!/usr/bin/env bash
#
# 构建 debug 测试包，输出文件名带【构建时间戳】，一眼看出哪个是最新改的。
#
#   ★ 命名与正式包统一用版本号体系（不再用 001~062 递增序号）：
#       ProcessKeepAlive-v3.3.0-debug-20260918-0935.apk     ← debug 测试包
#       ProcessKeepAlive-v3.3.0-release.apk                 ← 正式包
#
#   为什么用时间戳而非序号：序号要跨「根目录 + 归档目录」扫描维护，脚本一改动
#   就容易错位（曾出现 debug 序号与正式包编号并行、看着像两套编号的混乱）。
#   时间戳天然单调递增、无需维护，且与正式包同属一套命名。
#
# 用法：
#   ./build-debug.sh              # 构建，产出 ProcessKeepAlive-v3.3.0-debug-<时间戳>.apk
#   ./build-debug.sh --no-clean   # 跳过 clean，增量构建（更快）
#
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

export JAVA_HOME="${JAVA_HOME:-/root/.sdkman/candidates/java/current}"
# ★ JAVA_HOME 失效兜底：脚本默认值可能指向已不存在的 JDK 版本（例如 17.0.11-zulu
#   早已被卸掉，只剩 20.fx-zulu），此时 gradlew 会直接报 "invalid directory" 退出，
#   而 build 目录下还留着上一轮的旧 APK —— 极易被当成新产物拿走。这里显式校验。
if [[ ! -d "$JAVA_HOME" ]]; then
    for cand in /root/.sdkman/candidates/java/current /usr/lib/jvm/default-java \
                /opt/android-studio/jbr /opt/java/openjdk; do
        [[ -d "$cand" ]] && { export JAVA_HOME="$cand"; break; }
    done
fi
if [[ ! -d "$JAVA_HOME" ]]; then
    echo "找不到可用的 JDK，请设置 JAVA_HOME" >&2; exit 1
fi
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

# 从 build.gradle 取当前版本号，文件名跟着它走，不用手动维护
VERSION="$(grep -oE 'versionName "[^"]+"' app/build.gradle | head -1 \
    | sed 's/versionName "//;s/"//' || true)"
if [[ -z "$VERSION" ]]; then
    echo "无法从 app/build.gradle 解析 versionName" >&2
    exit 1
fi

# 基础名：与正式包同一版本号体系，不再用 001~062 递增序号
BASE="ProcessKeepAlive-v${VERSION}-debug"

# ★ 为什么去掉递增序号：
#   序号机制本是「debug 版本号长期固定 3.3.0，只能靠文件名分新旧」的补丁。
#   但它带来两个问题：① 与正式包命名体系不一致（正式用版本号、debug 用序号）；
#   ② 序号要跨根目录+归档目录扫描维护，一旦脚本改动就容易错位。
#   现改用【构建时间戳】：天然单调递增、无需维护计数器、同一版本号体系。
#   例：ProcessKeepAlive-v3.3.0-debug-20260918-0935.apk
STAMP="$(date +%Y%m%d-%H%M)"
OUT="${BASE}-${STAMP}.apk"

# 极端情况：同一分钟内构建两次会撞名 —— 加秒级后缀兜底
if [[ -e "$OUT" ]]; then
    OUT="${BASE}-$(date +%Y%m%d-%H%M%S).apk"
fi

echo "版本号: $VERSION"
echo "产物名: $OUT"
echo "JDK   : $JAVA_HOME"
echo

# ★ versionName 后缀带时间戳 → LSPosed 模块列表能直接看出是哪个构建
export BUILD_SEQ="$STAMP"
# ★ versionCode：每次构建单调递增，避免「同 versionCode + 同签名被判无需更新」
#   而静默跳过替换 —— 这是「装了新版却一直是老界面」的元凶。
#
#   版号分段约定（与 build-release.sh 一致，避免撞号）：
#     3000~3999  debug 测试包（本脚本，递增）
#     4000+      正式发布包（恒大于 debug，保证正式包永远是最新）
#
#   算法：扫描已有 debug 包取最大值 +1（基线 3000）。
#   不用时间戳做版号：「2000+HHMM」跨天会回退（今 2359→4359，明 0001→2001），
#   反而制造「后构建的更旧」。递增计数不受日期影响，是唯一稳妥做法。
MAXV=3000
for f in *.apk 历史版本_勿安装/*.apk; do
    [[ -f "$f" ]] || continue
    v="$("$ANDROID_HOME/build-tools/34.0.0/aapt" dump badging "$f" 2>/dev/null \
        | grep -oE "versionCode='[0-9]+'" | head -1 | grep -oE '[0-9]+' || true)"
    # 只统计 debug 区间（3001..3999）；正式包在 4000+，不会误入
    if [[ -n "$v" ]] && (( v > MAXV && v < 4000 )); then MAXV="$v"; fi
done
export BUILD_SEQ_CODE="$((MAXV + 1))"

# ★ module.prop 的 versionCode 同步注入。
#   Xposed 模块列表 / 部分安装器读的是 module.prop 里的 versionCode，而不是
#   APK 清单里的。这里若不同步，就会出现「LSPosed 显示 34、安装器也判成旧包」
#   的分裂状态 —— 必须两处一致，安装器才肯真正替换。
MODULE_PROP="app/src/main/resources/META-INF/xposed/module.prop"
if [[ -f "$MODULE_PROP" ]]; then
    # 先无条件还原成占位符，清除上一次构建可能残留的具体数字（trap 被 kill 等）
    sed -i -E "s/^versionCode=[0-9]+$/versionCode=@@VERSION_CODE@@/" "$MODULE_PROP"
    sed -i "s/@@VERSION_CODE@@/${BUILD_SEQ_CODE}/" "$MODULE_PROP"
    trap 'sed -i "s/versionCode=${BUILD_SEQ_CODE}/versionCode=@@VERSION_CODE@@/" "$MODULE_PROP"' EXIT
    echo "module.prop versionCode → ${BUILD_SEQ_CODE}"
fi

if [[ "${1:-}" != "--no-clean" ]]; then
    ./gradlew clean assembleDebug --no-daemon 2>&1 | grep -E "BUILD|FAILED|error:" | head -5
else
    ./gradlew assembleDebug --no-daemon 2>&1 | grep -E "BUILD|FAILED|error:" | head -5
fi

mv -f app/build/outputs/apk/debug/app-debug.apk "$OUT"

echo
echo "产物: $OUT"
echo "大小: $(du -h "$OUT" | cut -f1)"
"$ANDROID_HOME/build-tools/34.0.0/aapt" dump badging "$OUT" 2>/dev/null \
    | grep -oE "versionCode='[0-9]+' versionName='[^']+'" | sed 's/^/版本: /'
echo
echo "提示：debug 测试包，文件名时间戳越新越晚构建（$STAMP）"
echo "      正式包请用 build-release.sh（命名：ProcessKeepAlive-v$VERSION-release.apk）"
