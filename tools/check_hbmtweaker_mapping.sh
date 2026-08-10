#!/usr/bin/env bash
# ============================================================
# 检查部署的 hbmtweaker jar 是否已正确 reobf（无 MCP 漏映射）
# 用法: bash tools/check_hbmtweaker_mapping.sh [jar路径]
# 退出码: 0=干净, 1=发现问题, 2=jar未变化(与基线相同)
# ============================================================
set -u

JAR="${1:-/e/pcl/PCL 正式版 2.10.3/.minecraft/versions/1.7.10-hbm航天改/mods/HbmTweaker-0.1.0.jar}"
BASELINE_HASH="ede5e27e70f0bb74f91f0fa0d7f5b8cb"  # 8月7旧jar（有映射问题）
JAVAP="/e/JAVA/java8/bin/javap.exe"
TMPDIR="/tmp/hbmtw_check/verify_$$"

if [ ! -f "$JAR" ]; then
    echo "[CHECK] jar 不存在: $JAR"
    exit 3
fi

CUR_HASH=$(md5sum "$JAR" | awk '{print $1}')
echo "[CHECK] jar: $JAR"
echo "[CHECK] 当前哈希: $CUR_HASH"
echo "[CHECK] 基线哈希: $BASELINE_HASH"

if [ "$CUR_HASH" = "$BASELINE_HASH" ]; then
    echo "[CHECK] 哈希与基线相同 → jar 未更新，仍是旧的（有映射问题）"
    exit 2
fi
echo "[CHECK] jar 已更新（哈希不同于基线）→ 继续扫描映射"

mkdir -p "$TMPDIR"
unzip -o -q "$JAR" -d "$TMPDIR"

echo "[SCAN] 扫描所有类中未重映射的 vanilla MCP 引用..."
# 未重映射 = 引用了 net/minecraft/* 类，但成员名是 MCP 可读名（非 func_/field_）
MCP_REFS=0
for f in $(find "$TMPDIR" -name "*.class"); do
    OUT=$("$JAVAP" -c -p "$f" 2>/dev/null)
    # 提取 net/minecraft/ 下的字段/方法引用；SRG 名以 func_/field_ 开头（func_12345_a / field_12345_a）
    HITS=$(echo "$OUT" | grep -oE '(Field|Method) net/minecraft/[a-zA-Z/$]+\.([a-zA-Z0-9_]+)' \
        | sed 's/^Field //;s/^Method //' \
        | awk -F. '{print $1" . "$2}' \
        | grep -vE '\. (func_[0-9]+_[a-zA-Z0-9_]*|field_[0-9]+_[a-zA-Z0-9_]*|clinit|<init>|<clinit>|equals|hashCode|toString|getClass|notify|notifyAll|wait|registerCommand|executeCommand|canCommandSenderUseCommand|getCommands|getRequiredPermissionLevel|getCommandAliases|getCommandName|processCommand|getCommandUsage|func_)' \
        | grep -vE 'net/minecraft/util/EnumChatFormatting \. ' \
        | sort -u)
    if [ -n "$HITS" ]; then
        echo "── $f"
        echo "$HITS"
        MCP_REFS=$((MCP_REFS + $(echo "$HITS" | wc -l)))
    fi
done

rm -rf "$TMPDIR"

if [ "$MCP_REFS" -gt 0 ]; then
    echo ""
    echo "[VERDICT] 发现问题: $MCP_REFS 处疑似未重映射的 MCP 引用（↑ 上面的就是）"
    echo "[VERDICT] 这个 jar 仍有映射问题，部署后会报 NoSuchFieldError/NoSuchMethodError"
    exit 1
else
    echo ""
    echo "[VERDICT] 未发现未重映射的 MCP 引用 → jar 映射干净，可以部署实测"
    echo ""
    echo "【游戏内实测清单】"
    echo "  1. 启动游戏进世界（MineTweaker3 会在进世界时加载 scripts/hbm.zs）"
    echo "  2. 聊天栏应出现 '[HbmTweaker] AssemblyMachine addRecipe: added recipe demo.iron_redstone_to_diamond'"
    echo "  3. 输 /ht hand（手持熔融流体）或 /ht → 不应再报 unknown command"
    echo "  4. 检查 整合包logs/minetweaker.log 是否有 [HbmTweaker] 行"
    exit 0
fi
