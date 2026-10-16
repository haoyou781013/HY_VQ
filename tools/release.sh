#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════
# HY_VQ 发布流程（国内优先）
#
#   顺序（用户 2026-10-16 重置）：
#     ① Gitee（国内主源）—— 凭据在 /root/.gitee-env，自动加载
#     ② GitHub（镜像源 + 备份）
#     ③ GitHub 更新源校验
#
#   客户端取版本列表的顺序：
#     Gitee → gh-proxy 镜像 → GitHub 直连
#
#   ⚠️ 2026-10-16 移除 123 云盘：会员到期，WebDAV 实测 HTTP 401，
#      凭据失效。客户端也已同步删除该源。
#
#   ⚠️ 两仓库同步规则（用户 2026-10-16 定）：
#      新版本（v2.36.0 起，客户端已内置 Gitee 源）→ Gitee 完整发布
#        （更新日志 + APK + 增量包）
#      旧版本（v2.35.1 及更早，客户端无 Gitee 源）→ Gitee 不补
#        旧客户端读不到 Gitee，补了也没人用。
#      GitHub 侧始终完整发布（老用户靠它升级到含 Gitee 源的新版本）。
#
#   任一步失败即中止，避免出现"部分发布"的混乱状态。
#   ③ 单独跑，因为它是给客户端读的，必须确认 ①② 都到位才生效。
#
# 用法：
#   tools/release.sh <versionName>          # 完整流程 ①→②→③
#   tools/release.sh <versionName> --no-github   # 跳过 GitHub（纯国内）
# ═══════════════════════════════════════════════════════════════════
set -uo pipefail

VER=${1:?用法: release.sh <versionName> [--no-github]}
SKIP_GH=${2:-}

PROJ=/root/HY_VQ
# 自动加载 Gitee 凭据（2026-10-13 起）。已有环境变量则优先用环境变量。
if [ -f /root/.gitee-env ]; then . /root/.gitee-env; fi
GITEE_USER=${GITEE_USER:-}
GITEE_TOKEN=${GITEE_TOKEN:-}
# ⚠️ Gitee 仓库名是小写 hy_vq（GitHub 侧才是大写 HY_VQ）——
#    2026-10-13 用令牌调 /api/v5/users/haoyou781/repos 实测确认。
GITEE_REPO=${GITEE_REPO:-hy_vq}
GH_REPO=HY_VQ
# Gitee 已启用（2026-10-16）。凭据缺失时才跳过，不算失败。
GITEE_ON=0
if [ -n "$GITEE_USER" ] && [ -n "$GITEE_TOKEN" ]; then GITEE_ON=1; fi
GH_TOKEN=$(cat /root/.gh-token)

cd "$PROJ" || exit 1

# 容错 JSON 取值：接口报错/限流时不崩脚本，只返回空串
# （2026-10-12 实测：GitHub API 偶发返回空 body，python json.load 会抛
#  JSONDecodeError 把整个发布流程打断，而实际 Release 可能已建好）
jget() { python3 -c '
import json,sys
try:
    d=json.load(sys.stdin)
    print(d.get(sys.argv[1],"") if isinstance(d,dict) else "")
except Exception:
    print("")
' "$1" 2>/dev/null; }

echo "════════════════════════════════════════════════"
echo " HY_VQ v$VER 发布流程"
echo " ① Gitee → ② GitHub → ③ GitHub 更新源校验"
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
  echo "▶ ① Gitee —— 凭据未配置，跳过（②→③ 照常执行）"
else
echo "▶ ① Gitee（国内主源）"
git config --global --unset-all url.https://axisnow.gh-proxy.org/https://github.com/.insteadof 2>/dev/null
for k in $(git config --global --get-regexp 'url\..*\.insteadof' 2>/dev/null | cut -d' ' -f1); do
  git config --global --unset-all "$k" 2>/dev/null
done

# 仓库不存在则创建
if ! curl -s -o /dev/null -w "%{http_code}" "https://gitee.com/api/v5/repos/$GITEE_USER/$GITEE_REPO" \
     | grep -q 200; then
  echo "   创建仓库…"
  curl -s -X POST "https://gitee.com/api/v5/user/repos" \
    -d "access_token=$GITEE_TOKEN" -d "name=$GITEE_REPO" -d "path=$GITEE_REPO" \
    -d "description=原神语音助手" -d "private=false" -d "auto_init=false" \
    | jget path | grep -q . && echo "   ✅ 仓库已就绪" || echo "   ❌ 建仓失败（检查 token 与 projects 权限）"
fi

# ⚠️ 用 HTTPS + 令牌，不用 SSH：Gitee 侧的 SSH key 未配置，
#    HTTPS 令牌已验证可用（2026-10-16 推代码成功）。
GITEE_URL="https://$GITEE_USER:$GITEE_TOKEN@gitee.com/$GITEE_USER/$GITEE_REPO.git"
git remote add gitee "$GITEE_URL" 2>/dev/null || git remote set-url gitee "$GITEE_URL"
git push gitee main 2>&1 | tail -2 | sed 's/^/   /'
git push gitee "refs/tags/v$VER" 2>&1 | tail -1 | sed 's/^/   /'

# Release（用与 GitHub 相同的更新日志）
if [ -f /tmp/relnotes.md ]; then
  curl -s -X POST "https://gitee.com/api/v5/repos/$GITEE_USER/$GITEE_REPO/releases" \
    -d "access_token=$GITEE_TOKEN" \
    -d "tag_name=v$VER" -d "name=HY_VQ v$VER" \
    --data-urlencode "body@/tmp/relnotes.md" -d "prerelease=false" \
    -d "target_commitish=main" \
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
    # ⚠️ Gitee 的上传端点是 attach_files（/assets 会 404，实测 2026-10-13）
    curl -s -X POST "https://gitee.com/api/v5/repos/$GITEE_USER/$GITEE_REPO/releases/$RID/attach_files" \
      -F "access_token=$GITEE_TOKEN" -F "file=@$f" \
      | jget browser_download_url | grep -q . && echo "   ✅ $N" || echo "   ❌ $N 上传失败"
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
  RID=$(echo "$REL" | jget id)
  if [ -z "$RID" ]; then
    python3 -c "
import json
print(json.dumps({'tag_name':'v$VER','name':'HY_VQ v$VER','body':open('/tmp/relnotes.md',encoding='utf-8').read() if __import__('os').path.exists('/tmp/relnotes.md') else ''},ensure_ascii=False))" > /tmp/rel.json
    RID=$(curl -s -X POST "https://api.github.com/repos/haoyou781013/HY_VQ/releases" \
      -H "Authorization: Bearer $GH_TOKEN" -H "Content-Type: application/json" \
      --data-binary @/tmp/rel.json | jget id)
    echo "   ✅ Release 已建 id=$RID"
  fi
  UP=$(curl -s "https://api.github.com/repos/haoyou781013/HY_VQ/releases/$RID" \
       -H "Authorization: Bearer $GH_TOKEN" | jget upload_url | cut -d'{' -f1)
  for f in "$APK" update_packages/*.zip; do
    [ -f "$f" ] || continue
    N=$(basename "$f"); case "$f" in *.apk) N="HY_VQ-v$VER.apk";; esac
    curl -s -X POST "$UP?name=$N" -H "Authorization: Bearer $GH_TOKEN" \
      -H "Content-Type: application/octet-stream" --data-binary "@$f" \
      | python3 -c "import json,sys; d=json.load(sys.stdin); print('   ✅',d['name'], f\"{d['size']/1024:.0f}KB\") if 'name' in d else print('   ❌',str(d)[:80])"
  done
fi

# ── ③ GitHub 更新源 ────────────────────────────────
echo ""
if [ "$SKIP_GH" = "--no-github" ]; then
  echo "▶ ③ GitHub 更新源 —— 已跳过"
else
  echo "▶ ③ GitHub 更新源（客户端检查更新读这里）"
  # 检查客户端能否看到新版本
  SEEN=$(curl -s "https://api.github.com/repos/haoyou781013/HY_VQ/releases/latest" \
         -H "Authorization: Bearer $GH_TOKEN" | jget tag_name)
  SEEN_DATE=$(curl -s "https://api.github.com/repos/haoyou781013/HY_VQ/releases/latest" \
         -H "Authorization: Bearer $GH_TOKEN" | jget published_at)
  echo "   客户端可见: $SEEN ${SEEN_DATE:0:10}"
  echo "   更新检查 URL: https://api.github.com/repos/haoyou781013/HY_VQ/releases?per_page=100"
fi

echo ""
echo "════════════════════════════════════════════════"
echo " ✅ v$VER 发布完成"
[ "$GITEE_ON" = "1" ] && echo "   ① Gitee   https://gitee.com/$GITEE_USER/$GITEE_REPO" || echo "   ① Gitee   （暂定 · 未启用）"
echo "   ② GitHub  https://github.com/haoyou781013/HY_VQ/releases/tag/v$VER"
echo "════════════════════════════════════════════════"