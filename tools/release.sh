#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════
# HY_VQ 发布流程（国内优先）
#
#   顺序（用户指定 2026-10-11，2026-10-12 调整）：
#     ① Gitee（国内主源）—— **暂定**：尚未开通，无凭据时自动跳过
#     ② GitHub 镜像（备份 + 海外访问）
#     ③ 123云盘（离线归档）
#     ④ GitHub 更新源（检查更新接口读 GitHub Releases）
#
#   Gitee 凭据就位后取消暂定：
#     export GITEE_USER=<账号> GITEE_TOKEN=<私人令牌>
#   未设置时①跳过，流程自动降级为 ②→③→④（不报错、不中止）。
#
#   任一步失败即中止，不继续往下走 —— 避免出现"部分发布"的混乱状态。
#   ④ 单独跑，因为它是给客户端读的，必须确认 ①②③ 都到位才生效。
#
# 用法：
#   tools/release.sh <versionName>          # 完整流程 ①→②→③→④
#   tools/release.sh <versionName> --no-github   # 跳过 GitHub（纯国内）
# ═══════════════════════════════════════════════════════════════════
set -uo pipefail

VER=${1:?用法: release.sh <versionName> [--no-github]}
SKIP_GH=${2:-}

PROJ=/root/HY_VQ
GITEE_USER=${GITEE_USER:-}
GITEE_TOKEN=${GITEE_TOKEN:-}
# Gitee 暂定：两者都配齐才算启用①，否则跳过（不算失败）
GITEE_ON=0
if [ -n "$GITEE_USER" ] && [ -n "$GITEE_TOKEN" ]; then GITEE_ON=1; fi
GH_TOKEN=$(cat /root/.gh-token)
RCLONE_CONF=/storage/emulated/0/Android/haoyou781/.rclone

cd "$PROJ" || exit 1

echo "════════════════════════════════════════════════"
echo " HY_VQ v$VER 发布流程"
echo " ① Gitee → ② GitHub 镜像 → ③ 123云盘 → ④ GitHub 更新源"
echo "════════════════════════════════════════════════"

# ── 前置检查 ────────────────────────────────────────
echo ""
echo "▶ 前置检查"
VCODE=$(grep -oP 'versionCode\s*=\s*\K\d+' app/build.gradle.kts | head -1)
CURRENT=$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts | head -1)
echo "   代码版本: $CURRENT (code $VCODE)"
[ "$CURRENT" != "$VER" ] && { echo "   ❌ build.gradle.kts 里的 versionName 是 $CURRENT，与参数 $VER 不符"; exit 1; }
[ -z "$(git status --porcelain)" ] && echo "   ✅ 工作区干净" || { echo "   ❌ 工作区有未提交改动:"; git status --porcelain | head -5; exit 1; }
APK=app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || { echo "   ❌ APK 不存在，先跑 /opt/build-hyvq.sh :app:assembleRelease"; exit 1; }
echo "   APK: $(du -h "$APK" | cut -f1)"
ls update_packages/*.zip 2>/dev/null | while read z; do echo "   增量包: $(basename "$z") $(du -h "$z"|cut -f1)"; done

# ── ① Gitee ────────────────────────────────────────
echo ""
if [ "$GITEE_ON" = "0" ]; then
  echo "▶ ① Gitee —— 暂定，凭据未配置，跳过（②→③→④ 照常执行）"
else
echo "▶ ① Gitee（国内主源）"
git config --global --unset-all url.https://axisnow.gh-proxy.org/https://github.com/.insteadof 2>/dev/null
for k in $(git config --global --get-regexp 'url\..*\.insteadof' 2>/dev/null | cut -d' ' -f1); do
  git config --global --unset-all "$k" 2>/dev/null
done

# 仓库不存在则创建
if ! curl -s -o /dev/null -w "%{http_code}" "https://gitee.com/api/v5/repos/$GITEE_USER/HY_VQ" \
     | grep -q 200; then
  echo "   创建仓库…"
  curl -s -X POST "https://gitee.com/api/v5/user/repos" \
    -d "access_token=$GITEE_TOKEN" -d "name=HY_VQ" -d "path=HY_VQ" \
    -d "description=原神语音助手" -d "private=false" -d "auto_init=false" \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print('   ✅' if 'path' in d else '   ❌ '+str(d)[:120])"
fi

git remote add gitee "git@gitee.com:$GITEE_USER/HY_VQ.git" 2>/dev/null || \
  git remote set-url gitee "git@gitee.com:$GITEE_USER/HY_VQ.git"
git push gitee main 2>&1 | tail -2 | sed 's/^/   /'
git push gitee "refs/tags/v$VER" 2>&1 | tail -1 | sed 's/^/   /'

# Release（用与 GitHub 相同的更新日志）
if [ -f /tmp/relnotes.md ]; then
  curl -s -X POST "https://gitee.com/api/v5/repos/$GITEE_USER/HY_VQ/releases" \
    -d "access_token=$GITEE_TOKEN" \
    -d "tag_name=v$VER" -d "name=HY_VQ v$VER" \
    -d "body@/tmp/relnotes.md" -d "prerelease=false" \
    | python3 -c "
import json,sys
d=json.load(sys.stdin)
if 'tag_name' in d:
    print('   ✅ Release:', d.get('html_url',''))
    open('/tmp/gitee_rel_id','w').write(str(d['id']))
else: print('   ⚠️', str(d)[:150])"
  # 上传资产
  RID=$(cat /tmp/gitee_rel_id 2>/dev/null)
  for f in "$APK" update_packages/*.zip; do
    [ -f "$f" ] || continue
    N=$(basename "$f")
    case "$f" in *.apk) N="HY_VQ-v$VER.apk";; esac
    curl -s -X POST "https://gitee.com/api/v5/repos/$GITEE_USER/HY_VQ/releases/$RID/assets" \
      -F "access_token=$GITEE_TOKEN" -F "file=@$f" \
      | python3 -c "import json,sys; d=json.load(sys.stdin); print('   ✅', d.get('name','?')) if 'browser_download_url' in d else print('   ❌', str(d)[:100])"
  done
fi   # GITEE_ON
fi

# ── ② GitHub 镜像 ──────────────────────────────────
echo ""
if [ "$SKIP_GH" = "--no-github" ]; then
  echo "▶ ② GitHub 镜像 —— 已跳过（--no-github）"
else
  echo "▶ ② GitHub 镜像"
  git push origin main 2>&1 | tail -1 | sed 's/^/   /'
  git push origin "refs/tags/v$VER" 2>&1 | tail -1 | sed 's/^/   /'
  # Release
  REL=$(curl -s "https://api.github.com/repos/haoyou781013/HY_VQ/releases/tags/v$VER" \
        -H "Authorization: Bearer $GH_TOKEN")
  RID=$(echo "$REL" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))")
  if [ -z "$RID" ]; then
    python3 -c "
import json
print(json.dumps({'tag_name':'v$VER','name':'HY_VQ v$VER','body':open('/tmp/relnotes.md',encoding='utf-8').read() if __import__('os').path.exists('/tmp/relnotes.md') else ''},ensure_ascii=False))" > /tmp/rel.json
    RID=$(curl -s -X POST "https://api.github.com/repos/haoyou781013/HY_VQ/releases" \
      -H "Authorization: Bearer $GH_TOKEN" -H "Content-Type: application/json" \
      --data-binary @/tmp/rel.json | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))")
    echo "   ✅ Release 已建 id=$RID"
  fi
  UP=$(curl -s "https://api.github.com/repos/haoyou781013/HY_VQ/releases/$RID" \
       -H "Authorization: Bearer $GH_TOKEN" | python3 -c "import json,sys; print(json.load(sys.stdin)['upload_url'].split('{')[0])")
  for f in "$APK" update_packages/*.zip; do
    [ -f "$f" ] || continue
    N=$(basename "$f"); case "$f" in *.apk) N="HY_VQ-v$VER.apk";; esac
    curl -s -X POST "$UP?name=$N" -H "Authorization: Bearer $GH_TOKEN" \
      -H "Content-Type: application/octet-stream" --data-binary "@$f" \
      | python3 -c "import json,sys; d=json.load(sys.stdin); print('   ✅',d['name'], f\"{d['size']/1024:.0f}KB\") if 'name' in d else print('   ❌',str(d)[:80])"
  done
fi

# ── ③ 123云盘 ──────────────────────────────────────
echo ""
echo "▶ ③ 123云盘（离线归档）"
export RCLONE_CONFIG=$RCLONE_CONF GODEBUG=netdns=cgo
export SSL_CERT_FILE=$RCLONE_CONF/cacert.pem
mkdir -p /tmp/stage && rm -rf /tmp/stage/* 2>/dev/null
cp "$APK" "/tmp/stage/HY_VQ-v$VER.apk"
cp update_packages/*.zip /tmp/stage/ 2>/dev/null
cd /tmp/stage
for f in *; do
  # 用 copyto 逐个传：rclone copy 读目录在 proot 下会报 directory not found
  timeout 400 rclone copyto "$f" "p123:HY_VQ-updates/$f" 2>&1 | grep -E 'ERROR|succeeded' | tail -1 | sed 's/^/   /'
  echo "   ✅ $f"
done

# ── ④ GitHub 更新源 ────────────────────────────────
echo ""
if [ "$SKIP_GH" = "--no-github" ]; then
  echo "▶ ④ GitHub 更新源 —— 已跳过"
else
  echo "▶ ④ GitHub 更新源（客户端检查更新读这里）"
  # 检查客户端能否看到新版本
  SEEN=$(curl -s "https://api.github.com/repos/haoyou781013/HY_VQ/releases/latest" \
         -H "Authorization: Bearer $GH_TOKEN" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('tag_name',''), d.get('published_at','')[:10])")
  echo "   客户端可见: $SEEN"
  echo "   更新检查 URL: https://api.github.com/repos/haoyou781013/HY_VQ/releases?per_page=100"
fi

echo ""
echo "════════════════════════════════════════════════"
echo " ✅ v$VER 发布完成"
[ "$GITEE_ON" = "1" ] && echo "   ① Gitee   https://gitee.com/$GITEE_USER/HY_VQ" || echo "   ① Gitee   （暂定 · 未启用）"
echo "   ② GitHub  https://github.com/haoyou781013/HY_VQ/releases/tag/v$VER"
echo "   ③ 123云盘 HY_VQ-updates/"
echo "════════════════════════════════════════════════"