#!/bin/bash
# ═══════════════════════════════════════════════════════════════════
# HY_VQ 增量更新包制作（容器/proot 版）
#   与 tools/make_update.sh 同款逻辑，路径改为本容器实际路径：
#     JDK      → /usr/lib/jvm/java-17-openjdk-arm64
#     SDK      → /opt/android-sdk
#     壳产物   → app/build/intermediates/javac/... （或用现成 R.jar）
#   用法：
#     tools/make_update_container.sh <源码目录> <versionCode> <versionName> <入口类> [输出目录]
#   环境变量：
#     SIGN_JKS / SIGN_PASS / SIGN_ALIAS   签名密钥（正式发布必填，壳强制验签）
# ═══════════════════════════════════════════════════════════════════
set -e
SRC_DIR=${1:?用法: make_update_container.sh <源码目录> <versionCode> <versionName> <入口类> [输出目录]}
VCODE=${2:?缺少 versionCode}
VNAME=${3:?缺少 versionName}
ENTRY=${4:?缺少入口类全名}
OUT_DIR=${5:-"$(cd "$(dirname "$0")/.." && pwd)/update_packages"}
PROJ="$(cd "$(dirname "$0")/.." && pwd)"

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=/opt/android-sdk
JAVAC="$JAVA_HOME/bin/javac"
D8=$(ls /opt/android-sdk/build-tools/*/d8 2>/dev/null | tail -1)
ANDROID_JAR=/opt/android-sdk/platforms/android-34/android.jar
TOOLS_DIR="$PROJ/tools"
SIGN_ALIAS=${SIGN_ALIAS:-hyvq}

mkdir -p "$OUT_DIR"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/classes"

echo "▶ 1/5 编译源码"
# 壳的契约接口（HyVqAppEntry）不在 android.jar 里 —— 更新包必须能编译它，
# 所以把壳自己的编译产物一并放进 classpath
SHELL_CLASSES=${SHELL_CLASSES:-"$PROJ/app/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes"}
if [ ! -d "$SHELL_CLASSES" ]; then
  SHELL_CLASSES=$(find "$PROJ/app/build/intermediates" -type d -name classes -path '*javac*' 2>/dev/null | head -1)
fi
CP="$ANDROID_JAR"
if [ -n "$SHELL_CLASSES" ] && [ -d "$SHELL_CLASSES" ]; then
  CP="$CP:$SHELL_CLASSES"
  echo "   壳 classpath: $SHELL_CLASSES"
else
  echo "   ⚠️ 未找到壳编译产物，接口类可能编译不过（先 build 一次 app）"
fi
"$JAVAC" -nowarn -source 17 -target 17 -encoding UTF-8 \
  -classpath "$CP" \
  -d "$WORK/classes" \
  $(find "$SRC_DIR" -name '*.java') 2>&1 | grep -v '^Note:' || true

echo "▶ 2/5 d8 转 dex"
mkdir -p "$WORK/dex"
# 只打更新包自己的 class（$WORK/classes 里只有本次编译产物，壳接口不在其中）
"$D8" --min-api 28 --output "$WORK/dex" \
  $(find "$WORK/classes" -name '*.class') 2>&1 | grep -vE 'warning|Ignoring' || true

DEX="$WORK/dex/classes.dex"
[ -f "$DEX" ] || { echo "❌ dex 生成失败"; exit 1; }
DEX_MD5=$(md5sum "$DEX" | cut -d' ' -f1)
DEX_SIZE=$(stat -c%s "$DEX")
echo "   classes.dex  ${DEX_SIZE} B  md5=${DEX_MD5}"

echo "▶ 3/5 生成 manifest.json"
BUILD_TIME=$(date -u +%Y-%m-%dT%H:%M:%SZ)
# minBaseVersion：更新包能应用到的最低壳版本（壳里 baseVersionCode() 判定）
MIN_BASE=${MIN_BASE:-1}
cat > "$WORK/manifest.json" <<JSON
{
  "format": "hyv-update",
  "versionCode": $VCODE,
  "versionName": "$VNAME",
  "minBaseVersion": $MIN_BASE,
  "entry": "$ENTRY",
  "dexMd5": "$DEX_MD5",
  "dexSize": $DEX_SIZE,
  "buildTime": "$BUILD_TIME"
}
JSON

echo "▶ 4/5 签名（SignTool 用法：<jks> <storepass> <alias> <manifest.json> <classes.dex>）"
# SignTool 会自行校验 manifest.dexMd5 与 dex 实际内容一致，并按 canonical 串签名
if [ -n "${SIGN_JKS:-}" ] && [ -f "${SIGN_JKS}" ]; then
  # SignTool 是 .java 源码（无 class 产物）→ 先编译到临时目录再用
  "$JAVAC" -nowarn -d "$WORK/toolc" "$TOOLS_DIR/SignTool.java"
  "$JAVA_HOME/bin/java" -cp "$WORK/toolc" SignTool \
      "$SIGN_JKS" "${SIGN_PASS:-}" "$SIGN_ALIAS" \
      "$WORK/manifest.json" "$DEX"
  echo "   ✅ 签名完成"
else
  echo "   ⚠️ 未提供 SIGN_JKS，产出未签名包（壳强制验签，会被拒绝，仅供本地链路测试）"
fi

echo "▶ 5/5 打包"
python3 - "$WORK" "$OUT_DIR" "$VCODE" "$VNAME" <<'PY'
import json,os,sys,zipfile
work,out,vcode,vname = sys.argv[1:5]
m=json.load(open(os.path.join(work,'manifest.json'),encoding='utf-8'))
zp=os.path.join(out, f'hyv_update_v{vcode}_{vname}.zip')
if os.path.exists(zp): os.remove(zp)
with zipfile.ZipFile(zp,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
    z.writestr('manifest.json', json.dumps(m,ensure_ascii=False,indent=2))
    z.write(os.path.join(work,'dex','classes.dex'),'classes.dex')
print(f'   {zp}  {os.path.getsize(zp)} B')
PY
echo "✅ 完成"
