#!/usr/bin/env bash
#
# 构建【正式签名】release 包，用于发布（GitHub Release）。
#
#   与 build-debug.sh 的区别：
#     - assembleRelease（正式签名，v1+v2+v3 全开）
#     - 输出文件名用 -release 后缀，与线上发布命名一致
#     - versionName 不带 -debug 后缀
#     - 其余（序号机制、versionCode 3000+序号、module.prop 注入）完全一致
#
#   签名依赖根目录 key.properties（已被 .gitignore 忽略）+ keystore 文件。
#   缺任一个会拒绝构建 —— 正式包绝不能是未签名的。
#
# 用法：
#   ./build-release.sh              # 构建，产出 0NN-ProcessKeepAlive-v3.3.0-release.apk
#   ./build-release.sh --no-clean   # 跳过 clean，增量构建（更快）
#
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

export JAVA_HOME="${JAVA_HOME:-/root/.sdkman/candidates/java/current}"
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

# ★ 正式包前置校验：签名配置必须在位，否则拒绝构建。
#   绝不允许「以为出了正式包、其实是未签名包」这种情况发生。
if [[ ! -f key.properties ]]; then
    echo "错误：缺少 key.properties（正式签名配置）。" >&2
    echo "      正式包必须签名，请先补齐 key.properties 与 keystore。" >&2
    exit 1
fi
KS_FILE="$(grep -E '^storeFile=' key.properties | head -1 | cut -d= -f2- | tr -d ' \r')"
if [[ -z "$KS_FILE" || ! -f "$KS_FILE" ]]; then
    echo "错误：key.properties 指向的 keystore 不存在：${KS_FILE:-<空>}" >&2
    exit 1
fi
echo "签名配置: $KS_FILE"

VERSION="$(grep -oE 'versionName "[^"]+"' app/build.gradle | head -1 \
    | sed 's/versionName "//;s/"//' || true)"
if [[ -z "$VERSION" ]]; then
    echo "无法从 app/build.gradle 解析 versionName" >&2
    exit 1
fi

# ★ 正式包【不】使用递增序号命名 —— 序号是 debug 测试包的机制
#   （debug 版本号长期固定 3.3.0，只能靠文件名序号分新旧）。
#   正式包有正式版本号，文件名直接体现版本，与线上历史包命名一致：
#       ProcessKeepAlive-v3.2.1-release.apk
#       ProcessKeepAlive-v3.2.2-release.apk
#       ProcessKeepAlive-v3.3.0-release.apk   ← 本次
BASE="ProcessKeepAlive-v${VERSION}-release"
OUT="${BASE}.apk"

# 重新构建前先移走同名旧包，避免被 mv 静默覆盖（保留可追溯）
if [[ -f "$OUT" ]]; then
    PREV_DIR="历史版本_勿安装"
    mkdir -p "$PREV_DIR"
    mv -f "$OUT" "$PREV_DIR/$(date +%Y%m%d-%H%M%S)-${OUT}"
    echo "已归档同名旧包 → ${PREV_DIR}/"
fi

echo "版本号: $VERSION"
echo "产物名: $OUT"
echo "JDK   : $JAVA_HOME"
echo

# ★ 正式包的 versionCode。
#
#   【取值必须大于所有历史包（含 debug）】，否则老用户装了新版却收不到更新、
#   或先装 debug 再装正式包时系统认为正式包「更旧」而拒绝替换。
#   两者都不会报错，极难排查 —— 所以宁可取大，不取"更好看"。
#
#   版号分段约定（写死在此，避免将来又撞号）：
#     1000~2999  预留（未使用）
#     3000~3999  debug 测试包（递增，见 build-debug.sh）
#     4000+      正式发布包  ← 恒大于 debug，保证正式包永远是「最新」
#
#   本包取 4000（v3.3.0）。下次发新版改这里（如 v3.3.1 → 4001）。
REL_VERSION_CODE="${RELEASE_VERSION_CODE:-4000}"
export BUILD_SEQ="$VERSION"
export BUILD_SEQ_CODE="$REL_VERSION_CODE"

MODULE_PROP="app/src/main/resources/META-INF/xposed/module.prop"
if [[ -f "$MODULE_PROP" ]]; then
    sed -i -E "s/^versionCode=[0-9]+$/versionCode=@@VERSION_CODE@@/" "$MODULE_PROP"
    sed -i "s/@@VERSION_CODE@@/${BUILD_SEQ_CODE}/" "$MODULE_PROP"
    trap 'sed -i "s/versionCode=${BUILD_SEQ_CODE}/versionCode=@@VERSION_CODE@@/" "$MODULE_PROP"' EXIT
    echo "module.prop versionCode → ${BUILD_SEQ_CODE}"
fi

if [[ "${1:-}" != "--no-clean" ]]; then
    ./gradlew clean assembleRelease --no-daemon 2>&1 | grep -E "BUILD|FAILED|error:" | head -5
else
    ./gradlew assembleRelease --no-daemon 2>&1 | grep -E "BUILD|FAILED|error:" | head -5
fi

mv -f app/build/outputs/apk/release/app-release.apk "$OUT"

echo
echo "产物: $OUT"
echo "大小: $(du -h "$OUT" | cut -f1)"
"$ANDROID_HOME/build-tools/34.0.0/aapt" dump badging "$OUT" 2>/dev/null \
    | grep -oE "versionCode='[0-9]+' versionName='[^']+'" | sed 's/^/版本: /'
echo

# ★ 验证签名真的生效（正式包最容易出的乌龙：忘配签名，产出未签名包还当正式包发）
APKSIGNER="$ANDROID_HOME/build-tools/34.0.0/apksigner"
if [[ -x "$APKSIGNER" ]]; then
    echo "=== 签名校验 ==="
    "$APKSIGNER" verify --print-certs "$OUT" 2>&1 | grep -iE "SHA-256|Signer #1 certificate DN|verified" | head -5
fi
echo
echo "提示：这是【正式签名包】，可直接发布 / 覆盖安装。"
