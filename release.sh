#!/usr/bin/env bash
# 一键发版：版本号自增 → 构建 → 哈希 → 写清单 → 提交推送（APK 走 git 分发，无需 GitHub API）
# 用法: ./release.sh <版本名> [更新说明]     例: ./release.sh 1.0.2 "修复恢复链路；新增应用内更新"
set -euo pipefail
cd "$(dirname "$0")"

VER="${1:?用法: ./release.sh <版本名> [更新说明]}"
NOTES="${2:-体验优化与问题修复}"
REPO="R4gM9Xk/music"
GRADLE="C:/Users/a/.workbuddy-ai/binaries/gradle/gradle-8.13/bin/gradle"
JAVA_HOME="C:/Users/a/.workbuddy-ai/binaries/jdk/jdk-17.0.20.1+1"
export JAVA_HOME

# 版本号必须 x.y.z 纯数字：不合法即退出（顺带杜绝 / 破坏 sed、引号注入 JSON）
[[ "$VER" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "版本号不合法：$VER（需要 x.y.z 形式）" >&2; exit 1; }

# JSON 字符串转义：优先 python json.dumps；没有 python 时用 bash 替代（\ " 回车 换行）
if command -v python >/dev/null 2>&1; then
    json_str() { python -c 'import json,sys; print(json.dumps(sys.argv[1], ensure_ascii=False))' "$1"; }
else
    json_str() {
        local s="$1"
        s="${s//\\/\\\\}"
        s="${s//\"/\\\"}"
        s="${s//$'\r'/\\r}"
        s="${s//$'\n'/\\n}"
        printf '"%s"' "$s"
    }
fi
VER_JSON="$(json_str "$VER")"
NOTES_JSON="$(json_str "$NOTES")"

echo "── [1/6] 版本号 → $VER (versionCode 自增)"
GRADLE_FILE="app/build.gradle.kts"
NEW_CODE=$(( $(grep -oE 'versionCode = [0-9]+' "$GRADLE_FILE" | grep -oE '[0-9]+') + 1 ))
# 版本号已改动后任一步失败（含构建/哈希/写清单/推送）→ 还原 build.gradle.kts，避免脏工作区+跳号；
# 提交推送成功后触发时工作区与索引一致，为无害 no-op
trap 'git checkout -- "$GRADLE_FILE"' EXIT
sed -i "s/versionCode = [0-9]*/versionCode = $NEW_CODE/" "$GRADLE_FILE"
sed -i "s/versionName = \"[^\"]*\"/versionName = \"$VER\"/" "$GRADLE_FILE"
echo "    versionCode=$NEW_CODE versionName=$VER"

echo "── [2/6] 构建 APK"
"$GRADLE" :app:assembleRelease --console=plain -q
APK="app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || { echo "构建失败：未找到 $APK"; exit 1; }

echo "── [3/6] 计算 sha256"
SHA=$(sha256sum "$APK" | cut -d' ' -f1)

echo "── [4/6] 写 update.json + 归档 APK（git 分发，raw 直链/gh-proxy/jsDelivr 三链可取）"
RAW_BASE="https://raw.githubusercontent.com/$REPO/main/apk/app-release.apk"
cat > update.json <<EOF
{
  "versionCode": $NEW_CODE,
  "versionName": $VER_JSON,
  "changelog": $NOTES_JSON,
  "apk": "https://gh-proxy.com/$RAW_BASE",
  "apkMirror": "https://ghfast.top/$RAW_BASE",
  "apkAlt": "$RAW_BASE",
  "sha256": "$SHA"
}
EOF
mkdir -p apk
cp "$APK" apk/app-release.apk

echo "── [5/6] 提交推送"
git add update.json apk/app-release.apk "$GRADLE_FILE"
git commit -m "release v$VER" --quiet
git push origin main --quiet

echo "── [6/6] 清单自检（非 2xx 视为发版失败）"
CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "https://gh-proxy.com/https://raw.githubusercontent.com/$REPO/main/update.json" || true)
if [[ ! "$CODE" =~ ^2[0-9][0-9]$ ]]; then
    echo "    自检失败：gh-proxy 返回 HTTP $CODE（检查推送是否生效/代理是否可用）" >&2
    exit 1
fi
echo "    完成 ✅ 清单 HTTP $CODE"
echo "    手机端：打开应用 → 设置 → 检查更新 → 立即更新"
