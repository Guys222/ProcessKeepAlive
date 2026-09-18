#!/usr/bin/env bash
#
# 安全打包源码：生成一份可以放心外发的干净源码包。
#
# 与直接用 zip -r 的区别：本脚本在打包完成后会【拆包自检产物】，
# 一旦发现密钥 / 签名配置 / 构建产物混入，立即删除 zip 并报错退出，
# 保证「有问题就绝对不产出包」，而不是靠排除规则碰运气。
#
# 用法：
#   ./pack-source.sh              # 打包到当前目录
#   ./pack-source.sh --dry-run    # 只列出将打包的文件，不生成 zip
#   ./pack-source.sh --list       # 打包完成后打印完整文件清单
#
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_NAME="$(basename "$PROJECT_DIR")"
STAMP="$(date +%Y%m%d)"
OUT_ZIP="${PROJECT_NAME}-src-${STAMP}.zip"

DRY_RUN=0
SHOW_LIST=0
for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=1 ;;
        --list)    SHOW_LIST=1 ;;
        -h|--help) sed -n '2,16p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "未知参数: $arg（用 --help 查看用法）" >&2; exit 2 ;;
    esac
done

cd "$PROJECT_DIR"

# ---------- 1. 排除规则 ----------
# 目录类（结尾带 /）
EXCLUDE_DIRS=(
    ".git/" ".gradle/" ".idea/" ".vscode/" ".svn/"
    "build/" "app/build/" "app/release/" "app/debug/"
    ".cxx/" ".externalNativeBuild/" "captures/" "out/"
)
# 文件通配类
EXCLUDE_GLOBS=(
    # 密钥与签名配置（重点）
    "*.jks" "*.keystore" "*.p12" "*.pfx" "*.pk8" "*.pem" "*.key"
    "key.properties" "keystore.properties" "signing.properties"
    # 构建产物
    # 注意：这里【不要】排除 *.jar / *.class —— gradle/wrapper/gradle-wrapper.jar
    # 是运行 ./gradlew 的必需文件，排掉它会让拿到包的人无法构建。
    # 其余 jar/class 都在 build/ 目录内，已由上面的目录规则排除。
    "*.apk" "*.aab" "*.ap_" "*.dex"
    # 本地环境
    "local.properties"
    # IDE / 系统 / 日志
    "*.iml" "*.ipr" "*.iws" ".DS_Store" "Thumbs.db" "*.log"
    # 已有的打包产物，避免自我嵌套
    "*-src-*.zip"
)

# ---------- 2. 敏感项检查表（用于打包后自检）----------
# 这些 pattern 若出现在最终 zip 里，视为泄漏，立即中止。
SENSITIVE_PATTERNS=(
    '\.jks$' '\.keystore$' '\.p12$' '\.pfx$' '\.pk8$' '\.pem$'
    'key\.properties$' 'keystore\.properties$' 'signing\.properties$'
    'local\.properties$'
    '\.apk$' '\.aab$' '\.dex$'
    '\.git/' '\.gradle/' 'build/'
)

# 注意：zip 的 -x 是「贪婪」的，会把其后所有非选项参数都当成排除 pattern，
# 因此 -x 必须放在【归档名 + 输入路径之后】（见下方 zip 调用），否则报
# "nothing to select from"。
EXCLUDE_ARGS=()
# 打包时路径带项目名前缀（ProcessKeepAlive/xxx），因此两种前缀形式都要排除：
#   "xxx"     -> 匹配项目根下的同名项
#   "*/xxx"   -> 匹配任意层级（含项目名前缀）
for d in "${EXCLUDE_DIRS[@]}";    do EXCLUDE_ARGS+=(-x "${d}*" -x "*/${d}*"); done
for g in "${EXCLUDE_GLOBS[@]}";   do EXCLUDE_ARGS+=(-x "${g}" -x "*/${g}"); done

# ---------- 3. 预检：扫描工作区里的密钥文件，提前告知 ----------
echo "[1/4] 预检工作区…"
FOUND_KEYS="$(find . \( -name '*.jks' -o -name '*.keystore' -o -name 'key.properties' \
    -o -name '*.p12' -o -name '*.pfx' \) -not -path './.git/*' 2>/dev/null || true)"
if [[ -n "$FOUND_KEYS" ]]; then
    echo "      发现以下密钥/签名文件，它们【不会】被打进包："
    echo "$FOUND_KEYS" | sed 's/^/        - /'
else
    echo "      未发现密钥文件（key.properties / *.jks）"
fi

# ---------- 4. 统计待打包文件 ----------
if [[ $DRY_RUN -eq 1 ]]; then
    echo
    echo "[dry-run] 将打包的文件（不含排除项）："
    find . -type f \
        -not -path './.git/*' -not -path '*/build/*' -not -path './.gradle/*' \
        -not -name '*.jks' -not -name '*.keystore' -not -name 'key.properties' \
        -not -name '*.apk' -not -name '*.iml' \
        | sort | sed 's/^/  /'
    echo
    echo "[dry-run] 未生成 zip。"
    exit 0
fi

# ---------- 5. 打包 ----------
echo "[2/4] 打包中…"
rm -f "$OUT_ZIP"
# 先在 /tmp 生成，避免 zip 扫描到正在写入的产物本身；
# 从上一级目录打包，使 zip 内含一层项目目录名（便于解压）
TMP_ZIP="$(mktemp -t "${PROJECT_NAME}-src-XXXXXX.zip")"
rm -f "$TMP_ZIP"
( cd .. && zip -r -q -y "$TMP_ZIP" "$PROJECT_NAME" "${EXCLUDE_ARGS[@]}" )
mv "$TMP_ZIP" "$OUT_ZIP"

echo "[3/4] 校验产物内容…"
LEAKS="$(unzip -l "$OUT_ZIP" | awk '{print $4}' | grep -E "$(IFS='|'; echo "${SENSITIVE_PATTERNS[*]}")" || true)"
if [[ -n "$LEAKS" ]]; then
    echo
    LEAK_COUNT="$(echo "$LEAKS" | wc -l | tr -d ' ')"
    echo >&2
    echo "!!!!!!!!!! 自检失败：包内发现 $LEAK_COUNT 个敏感文件，已删除 zip !!!!!!!!!!" >&2
    echo "$LEAKS" | head -15 | sed 's/^/    /' >&2
    if [[ "$LEAK_COUNT" -gt 15 ]]; then
        echo "    … 其余 $((LEAK_COUNT - 15)) 个已省略" >&2
    fi
    echo >&2
    echo "请修正排除规则后重试。这些文件绝不能外发。" >&2
    rm -f "$OUT_ZIP"
    exit 1
fi
echo "      自检通过：无密钥、无签名配置、无构建产物 ✓"

# ---------- 6. 输出 ----------
TOTAL="$(unzip -l "$OUT_ZIP" | tail -1 | awk '{print $2}')"
echo "[4/4] 完成"
echo
echo "  产物: $OUT_ZIP"
echo "  文件数: $TOTAL"
echo "  大小: $(du -h "$OUT_ZIP" | cut -f1)"
echo "  路径: $PROJECT_DIR/$OUT_ZIP"
echo
echo "外发前可再自行确认一次："
echo "  unzip -l $OUT_ZIP | grep -iE 'jks|keystore|key.properties|apk'"
echo
if [[ $SHOW_LIST -eq 1 ]]; then
    unzip -l "$OUT_ZIP" | awk '{print $4}' | grep -v '^$' | grep -v 'Name$' | sed 's/^/  /'
fi
