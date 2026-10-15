#!/bin/bash
# HY_VQ Gitee 更新通道发布脚本
# 用法: gitee-publish.sh <版本名> <APK路径> [changelog文件]
# 例:   gitee-publish.sh 2.33.0 app/build/outputs/apk/release/app-release.apk CHANGELOG.md
#
# 产物：
#   1) Gitee 发行版 v<ver> + 附件 HY_VQ-v<ver>.apk
#      https://gitee.com/haoyou781/hy_vq/releases/download/v<ver>/HY_VQ-v<ver>.apk
#   2) 仓库 raw 的 versions.json（客户端国内源清单）
#      https://gitee.com/haoyou781/hy_vq/raw/main/versions.json
set -euo pipefail

VER="${1:?需要版本名, 如 2.33.0}"
APK="${2:?需要 APK 路径}"
CL="${3:-}"
OWNER=haoyou781
REPO=hy_vq
TOK="$(cat /root/.gitee-token)"
API="https://gitee.com/api/v5/repos/$OWNER/$REPO"
[ -f "$APK" ] || { echo "APK 不存在: $APK"; exit 1; }

SIZE=$(stat -c %s "$APK")
MD5=$(md5sum "$APK" | cut -d' ' -f1)
NAME="HY_VQ-v${VER}.apk"
TAG="v${VER}"
echo "版本 $VER | $SIZE 字节 | md5 $MD5"

if [ -n "$CL" ] && [ -f "$CL" ]; then CHANGELOG=$(cat "$CL"); else CHANGELOG="v${VER}"; fi
VC=$(grep -oP 'versionCode\s*=\s*\K[0-9]+' /root/HY_VQ/app/build.gradle.kts 2>/dev/null || echo 0)
PUB=$(date +%F)

echo "1/4 创建/复用发行版 $TAG…"
RID=$(curl -s --max-time 60 "$API/releases/tags/$TAG?access_token=$TOK" \
  | python3 -c "import json,sys
try: print(json.load(sys.stdin).get('id',''))
except Exception: print('')" 2>/dev/null || echo "")
if [ -z "$RID" ]; then
  RID=$(curl -s --max-time 60 -X POST "$API/releases" \
    --data-urlencode "access_token=$TOK" --data-urlencode "tag_name=$TAG" --data-urlencode "name=$TAG" \
    --data-urlencode "target_commitish=main" --data-urlencode "body=$CHANGELOG" \
    | python3 -c "import json,sys;print(json.load(sys.stdin)['id'])")
  echo "   已创建 release id=$RID"
else
  echo "   已存在 release id=$RID（附件将追加）"
fi

echo "2/4 上传 APK 附件…"
# 先删同名旧附件（避免重发时出现重复）
curl -s --max-time 60 "$API/releases/$RID?access_token=$TOK" | python3 -c "
import json,sys
d=json.load(sys.stdin)
for a in d.get('assets',[]):
    if a.get('name')=='$NAME': print(a.get('id'))
" 2>/dev/null | while read -r aid; do
  [ -n "$aid" ] && curl -s --max-time 60 -X DELETE "$API/releases/$RID/attach_files/$aid?access_token=$TOK" -o /dev/null
done
curl -s --max-time 1800 -X POST "$API/releases/$RID/attach_files" \
  -F "access_token=$TOK" -F "file=@$APK" \
  | python3 -c "import json,sys;d=json.load(sys.stdin);print('   附件:',d.get('browser_download_url') or d)"

echo "3/4 更新 versions.json…"
# 拉取现有清单（不存在则新建）
curl -s --max-time 60 "$API/contents/versions.json?access_token=$TOK" -o /tmp/gp-vj.json 2>/dev/null || true
python3 - "$VER" "$VC" "$NAME" "$SIZE" "$MD5" "$PUB" "$CHANGELOG" <<'PY'
import json,sys,base64,datetime
ver,vc,name,size,md5,pub,cl=sys.argv[1:8]
try:
    raw=json.load(open('/tmp/gp-vj.json',encoding='utf-8'))
    if 'content' in raw:
        d=json.loads(base64.b64decode(raw['content']).decode('utf-8'))
        sha=raw.get('sha','')
    else:
        d={'versions':[]}; sha=''
except Exception:
    d={'versions':[]}; sha=''
if 'versions' not in d: d['versions']=[]
body=cl.strip() if cl.strip() else f"## v{ver}"
d['versions']=[v for v in d['versions'] if v.get('ver')!=ver]
d['versions'].insert(0,{"ver":ver,"versionCode":int(vc),"publishedAt":pub,
  "size":int(size),"md5":md5,"apk":name,"body":body})
d['updatedAt']=datetime.datetime.now().strftime('%Y-%m-%d %H:%M')
out=json.dumps(d,ensure_ascii=False,indent=2)
open('/tmp/gp-versions.json','w',encoding='utf-8').write(out)
open('/tmp/gp-sha.txt','w').write(sha)
print(f"   已置顶 {ver}，历史共 {len(d['versions'])} 条")
PY
SHA=$(cat /tmp/gp-sha.txt)
B64=$(base64 -w0 /tmp/gp-versions.json)
if [ -n "$SHA" ]; then
  curl -s --max-time 120 -X PUT "$API/contents/versions.json" \
    --data-urlencode "access_token=$TOK" --data-urlencode "content=$B64" --data-urlencode "sha=$SHA" \
    --data-urlencode "message=release: v$VER (versionCode $VC)" -o /dev/null
else
  curl -s --max-time 120 -X POST "$API/contents/versions.json" \
    --data-urlencode "access_token=$TOK" --data-urlencode "content=$B64" \
    --data-urlencode "message=release: v$VER (versionCode $VC)" -o /dev/null
fi

echo "4/4 回读校验…"
curl -sL --max-time 90 "https://gitee.com/$OWNER/$REPO/raw/main/versions.json" -o /tmp/gp-check.json
python3 - "$VER" "$MD5" "$SIZE" <<'PY'
import json,sys
ver,md5,size=sys.argv[1:4]
d=json.load(open('/tmp/gp-check.json',encoding='utf-8'))
v=[x for x in d['versions'] if x.get('ver')==ver]
assert v, "versions.json 未找到该版本"
v=v[0]
assert v['md5']==md5 and int(v['size'])==int(size), "md5/size 不匹配"
print(f"   ✅ versions.json -> {v['ver']} apk={v['apk']} md5 一致")
PY
echo "   ✅ 发布完成"
echo "   直链: https://gitee.com/$OWNER/$REPO/releases/download/$TAG/$NAME"
