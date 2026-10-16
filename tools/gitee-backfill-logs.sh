#!/bin/bash
# ═══════════════════════════════════════════════════════════════════
# 把 GitHub 的历史 Release「更新日志」补到 Gitee（**不含 APK 附件**）
#
# 用户 2026-10-16 定：Gitee 是客户端国内主源，但**只对含 Gitee 源的
# 新版本完整发布**（日志 + APK + 增量包）。旧版本（v2.35.1 及更早，
# 客户端里没有 Gitee 源）在 Gitee 上**只留日志**，不传 APK。
#
# 理由：旧客户端读不到 Gitee，传 APK 纯占带宽和配额；
#       留下日志是为了网页上能翻到历史，保持两仓库「看起来一致」。
#
# 幂等：已存在的 tag 跳过，可重复执行。
#
# 用法：
#   tools/gitee-backfill-logs.sh              # 补全部
#   tools/gitee-backfill-logs.sh --max 10     # 只补最近 10 个
#   tools/gitee-backfill-logs.sh --dry-run    # 只看会做什么，不写
# ═══════════════════════════════════════════════════════════════════
set -uo pipefail

PROJ=/root/HY_VQ
[ -f /root/.gitee-env ] && . /root/.gitee-env
GITEE_USER=${GITEE_USER:-}
GITEE_TOKEN=${GITEE_TOKEN:-}
GITEE_REPO=${GITEE_REPO:-hy_vq}
GH_TOKEN=$(cat /root/.gh-token 2>/dev/null)

MAX=0
DRY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --max) MAX=${2:-0}; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    *) echo "未知参数: $1"; exit 1 ;;
  esac
done

if [ -z "$GITEE_USER" ] || [ -z "$GITEE_TOKEN" ]; then
  echo "❌ 缺少 Gitee 凭据（/root/.gitee-env）"; exit 1
fi
[ -z "$GH_TOKEN" ] && { echo "❌ 缺少 /root/.gh-token"; exit 1; }

cd "$PROJ" || exit 1

echo "════════════════════════════════════════════════"
echo " Gitee 历史日志补录（仅日志，不含 APK）"
[ "$DRY" = "1" ] && echo " 模式：dry-run（不写入）"
echo "════════════════════════════════════════════════"

# ── 拉 GitHub 全部 release ──────────────────────────
echo ""
echo "▶ 拉取 GitHub release 列表"
: > /tmp/bk_gh.json
for pg in 1 2 3; do
  curl -s -m 30 "https://api.github.com/repos/haoyou781013/HY_VQ/releases?per_page=100&page=$pg" \
    -H "Authorization: Bearer $GH_TOKEN" \
    -H "Accept: application/vnd.github+json" > /tmp/bk_pg.json 2>/dev/null
  # 空数组就停止
  if [ "$(python3 -c 'import json;print(len(json.load(open("/tmp/bk_pg.json"))))' 2>/dev/null || echo 0)" = "0" ]; then
    break
  fi
  cat /tmp/bk_pg.json >> /tmp/bk_gh.json
  echo "   第 $pg 页 OK"
done

# 合并成一个按版本降序、每行一条 JSON 的文件
python3 - <<'PY' > /tmp/bk_list.jsonl
import json
d = json.load(open('/tmp/bk_gh.json'))
out = []
for r in d:
    out.append({
        'tag': r.get('tag_name', ''),
        'name': r.get('name') or r.get('tag_name', ''),
        'body': r.get('body') or '',
        'created': r.get('created_at', ''),
    })
print('\n'.join(json.dumps(x, ensure_ascii=False) for x in out))
PY
TOTAL=$(wc -l < /tmp/bk_list.jsonl)
echo "   GitHub 共 $TOTAL 个 release"

# ── 拉 Gitee 已有的 tag ────────────────────────────
echo ""
echo "▶ 检查 Gitee 已有 release"
curl -s -m 30 "https://gitee.com/api/v5/repos/$GITEE_USER/$GITEE_REPO/releases?per_page=100" \
  > /tmp/bk_gt.json 2>/dev/null
python3 -c "
import json
try:
    d=json.load(open('/tmp/bk_gt.json'))
    tags=sorted(r.get('tag_name','') for r in d if isinstance(d,list))
except Exception:
    tags=[]
open('/tmp/bk_have.txt','w').write('\n'.join(tags))
print(f'   Gitee 已有 {len(tags)} 个 release')
"
HAVE=$(wc -l < /tmp/bk_have.txt 2>/dev/null || echo 0)

# ── 逐个补 ─────────────────────────────────────────
echo ""
echo "▶ 开始补录"
CREATE=0
SKIP=0
FAIL=0
N=0

while IFS= read -r line; do
  [ -z "$line" ] && continue
  N=$((N+1))
  if [ "$MAX" -gt 0 ] && [ "$N" -gt "$MAX" ]; then break; fi

  TAG=$(printf '%s' "$line" | python3 -c 'import json,sys;print(json.load(sys.stdin)["tag"])')
  NAME=$(printf '%s' "$line" | python3 -c 'import json,sys;print(json.load(sys.stdin)["name"])')
  [ -z "$TAG" ] && continue

  # 已存在则跳过
  if grep -qxF "$TAG" /tmp/bk_have.txt 2>/dev/null; then
    SKIP=$((SKIP+1))
    continue
  fi

  printf '%s' "$line" | python3 -c '
import json,sys
d=json.load(sys.stdin)
open("/tmp/bk_body.md","w").write(d["body"] or "(此版本无更新日志)")
'
  BODYLEN=$(wc -c < /tmp/bk_body.md)

  if [ "$DRY" = "1" ]; then
    echo "   [dry] $TAG  ($BODYLEN 字)"
    CREATE=$((CREATE+1))
    continue
  fi

  # 建 release（不带附件 = 只有日志）
  RESP=$(curl -s -m 30 -X POST "https://gitee.com/api/v5/repos/$GITEE_USER/$GITEE_REPO/releases" \
    -d "access_token=$GITEE_TOKEN" \
    -d "tag_name=$TAG" \
    --data-urlencode "name=$NAME" \
    --data-urlencode "body@/tmp/bk_body.md" \
    -d "prerelease=false" \
    -d "target_commitish=main" 2>/dev/null)

  if printf '%s' "$RESP" | python3 -c 'import json,sys;d=json.load(sys.stdin);sys.exit(0 if "tag_name" in d else 1)' 2>/dev/null; then
    echo "   ✅ $TAG  ($BODYLEN 字)"
    CREATE=$((CREATE+1))
    echo "$TAG" >> /tmp/bk_have.txt
  else
    MSG=$(printf '%s' "$RESP" | head -c 120)
    echo "   ❌ $TAG  → $MSG"
    FAIL=$((FAIL+1))
  fi

  # 轻微限速，避免触发 Gitee 频控
  sleep 0.4
done < /tmp/bk_list.jsonl

echo ""
echo "════════════════════════════════════════════════"
echo " 补录完成"
echo "   新建: $CREATE"
echo "   跳过: $SKIP （已存在）"
echo "   失败: $FAIL"
echo "════════════════════════════════════════════════"

# 收尾核对
echo ""
echo "▶ Gitee 现状"
curl -s -m 30 "https://gitee.com/api/v5/repos/$GITEE_USER/$GITEE_REPO/releases?per_page=100" 2>/dev/null | \
python3 -c "
import json,sys
try:
    d=json.load(sys.stdin)
    print(f'   release 总数: {len(d)}')
    withasset=[r for r in d if r.get('assets')]
    real=[r for r in d if any('archive/refs' not in a.get('browser_download_url','')
                              for a in r.get('assets',[]))]
    print(f'   其中含真实资产（非源码包）: {len(real)}')
    for r in d[:3]:
        print(f\"     {r.get('tag_name')}\")
except Exception as e:
    print('   读取失败:', e)
"
