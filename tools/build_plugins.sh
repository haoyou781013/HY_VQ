#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════
#  HY_VQ 插件打包脚本
#
#  把插件源码编译成 classes.dex + plugin.json，打成 zip，
#  放到 build/plugins/ 供发布脚本上传到 GitHub Release。
#
#  ⚠️ 关键设计：插件编译时**只依赖宿主导出的少量类**，因此这里
#     用 javac 直接编译（不建独立 Gradle 工程），把宿主 classpath
#     作为 -cp 传给 javac —— 插件能引用宿主类，宿主不引用插件类。
#
#  用法：tools/build_plugins.sh [插件名...]   （不带参数 = 全部）
# ══════════════════════════════════════════════════════════════
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SDK=/opt/android-sdk
BT="$SDK/build-tools/34.0.4"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
OUT="$ROOT/build/plugins"
PLUGIN_SRC="$ROOT/plugin_src"

mkdir -p "$OUT"

ALL_PLUGINS=(github anime wuwaemoji)
if [ $# -gt 0 ]; then
    PLUGINS=("$@")
else
    PLUGINS=("${ALL_PLUGINS[@]}")
fi

echo "════════ 插件打包 ════════"

# ── 1. 先确保宿主已编译（插件依赖宿主的 ModuleUiKit / GhApi 等类）──
echo "▶ 编译宿主（提供插件依赖的 classpath）"
if ! ./gradlew :app:compileReleaseJavaWithJavac -q 2>/dev/null; then
    echo "  ✗ 宿主编译失败，先跑 tools/build_hyvq.sh"
    exit 1
fi
HOST_CLASSES="$ROOT/app/build/intermediates/javac/release/classes"
if [ ! -d "$HOST_CLASSES" ]; then
    echo "  ✗ 找不到宿主 class: $HOST_CLASSES"
    exit 1
fi
echo "  ✓ 宿主 class 就绪"

# ── 2. 逐个编译插件 ──
for pid in "${PLUGINS[@]}"; do
    SRC="$PLUGIN_SRC/$pid"
    if [ ! -d "$SRC" ]; then
        echo "▶ $pid —— 源码不存在，跳过"
        continue
    fi

    # plugin.json（从 meta.json 读，或用默认值）
    NAME="$pid"
    VER="1.0.0"
    DESC=""
    if [ -f "$SRC/plugin.json" ]; then
        NAME=$(grep -o '"name"[[:space:]]*:[[:space:]]*"[^"]*"' "$SRC/plugin.json" | head -1 | sed 's/.*"\([^"]*\)"$/\1/')
        VER=$(grep -o '"version"[[:space:]]*:[[:space:]]*"[^"]*"' "$SRC/plugin.json" | head -1 | sed 's/.*"\([^"]*\)"$/\1/')
        DESC=$(grep -o '"description"[[:space:]]*:[[:space:]]*"[^"]*"' "$SRC/plugin.json" | head -1 | sed 's/.*"\([^"]*\)"$/\1/')
    fi

    echo "▶ 编译插件 $pid（$NAME v$VER）"
    WORK="$OUT/.work/$pid"
    rm -rf "$WORK"
    mkdir -p "$WORK/classes"

    # 收集源码
    SRCFILES=$(find "$SRC" -name '*.java' | sort)
    if [ -z "$SRCFILES" ]; then
        echo "  ✗ 没有 .java 源码"
        continue
    fi
    echo "  · 源文件 $(echo "$SRCFILES" | wc -l) 个"

    # classpath = 宿主 classes + android.jar + 第三方库
    CP="$HOST_CLASSES:$ANDROID_JAR"
    # material（R 类需要）
    for j in $(find "$HOME/.gradle/caches/modules-2/files-2.1" -name 'material-*.aar' 2>/dev/null | head -1); do
        CP="$CP:$j"
    done
    for j in $(find "$HOME/.gradle/caches" -name 'classes.jar' -path '*material*' 2>/dev/null | head -1); do
        CP="$CP:$j"
    done

    # javac → class
    if ! javac -nowarn -encoding UTF-8 \
            -source 11 -target 11 \
            -bootclasspath "$ANDROID_JAR" \
            -cp "$CP" \
            -d "$WORK/classes" \
            $SRCFILES 2>"$WORK/javac.log"; then
        echo "  ✗ javac 失败："
        grep -E 'error:' "$WORK/javac.log" | head -12 | sed 's/^/    /'
        continue
    fi

    # class → dex
    if ! "$BT/d8" --min-api 28 --output "$WORK" $(find "$WORK/classes" -name '*.class') \
            >"$WORK/d8.log" 2>&1; then
        echo "  ✗ d8 失败："
        tail -6 "$WORK/d8.log" | sed 's/^/    /'
        continue
    fi

    if [ ! -f "$WORK/classes.dex" ]; then
        echo "  ✗ 未生成 classes.dex"
        continue
    fi

    # 写 plugin.json
    ENTRY=$(grep -rhoE 'class [A-Za-z0-9_]+[^ ]*implements (com\.aliya\.hy_vq\.plugin\.)?HyVqPlugin' "$SRC" 2>/dev/null | head -1)
    if [ -z "$ENTRY" ]; then
        ENTRY=$(grep -rhoE 'implements (com\.aliya\.hy_vq\.plugin\.)?HyVqPlugin' "$SRC" | head -1)
        ENTRY=${ENTRY#implements }
    fi
    if [ -z "$ENTRY" ]; then
        echo "  ✗ 找不到实现 HyVqPlugin 的类"
        continue
    fi
    PKG=$(grep -rhoE '^package [a-z0-9_.]+' "$SRC" | head -1 | cut -d' ' -f2)
    ENTRY_CLASS="$PKG.$ENTRY"

    cat > "$WORK/plugin.json" <<JSON
{
  "id": "$pid",
  "name": "$NAME",
  "version": "$VER",
  "entry": "$ENTRY_CLASS",
  "description": "$DESC"
}
JSON

    # 打包 zip（classes.dex 必须在压缩层根目录，dex 不能压缩）
    ZIP="$OUT/hyvq-plugin-$pid-$VER.zip"
    rm -f "$ZIP"
    cd "$WORK"
    zip -0 -q "$ZIP" classes.dex
    zip -q "$ZIP" plugin.json
    cd "$ROOT"

    SIZE=$(stat -c%s "$ZIP")
    MD5=$(md5sum "$ZIP" | cut -d' ' -f1)
    echo "  ✓ $ZIP"
    echo "     入口 $ENTRY_CLASS"
    echo "     大小 $((SIZE / 1024)) KB · md5 $MD5"

    # 写清单，供发布脚本与 PluginStore 校准
    echo "$pid $NAME $VER $SIZE $MD5 $ENTRY_CLASS" >> "$OUT/manifest.txt"
done

echo "════════ 完成 ════════"
echo "清单：$OUT/manifest.txt"
ls -la "$OUT"/*.zip 2>/dev/null | awk '{printf "  %-46s %8.1f KB\n", $9, $5/1024}'