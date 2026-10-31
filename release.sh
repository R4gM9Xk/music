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
# 失败回滚：旧实现用 `git checkout -- app/build.gradle.kts`，那是**文件级全量回滚**，
# 会连开发者在 build.gradle.kts 里手工改过的内容（临时 versionName / 加依赖 / 调 signingConfig）
# 一起静默丢弃。改为「先整份备份到临时文件，失败时原样拷回」：
# 既不碰手工改动，也不依赖 sed 反向替换的正确性。
GRADLE_BACKUP="$(mktemp "${TMPDIR:-/tmp}/gradle.kts.XXXXXX")"
cp "$GRADLE_FILE" "$GRADLE_BACKUP"
GRADLE_DIRTY=1   # 版本号已改写、尚未提交 → 需要回滚
restore_gradle() {
    [[ "${GRADLE_DIRTY:-0}" == "1" ]] || { rm -f "$GRADLE_BACKUP"; return 0; }
    if [[ -f "$GRADLE_BACKUP" ]]; then
        cp "$GRADLE_BACKUP" "$GRADLE_FILE"   # 整份还原，手工改动一并保留
    fi
    rm -f "$GRADLE_BACKUP"
}
trap restore_gradle EXIT
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
PRE_PUSH_HEAD=$(git rev-parse HEAD)
git commit -m "release v$VER" --quiet
# push 失败时必须回滚本地 commit：否则重跑脚本会基于「已自增的 versionCode」再 +1 → 跳号，
# 且上一轮「没发出去」的 APK/update.json 会以半个版本的状态堆在历史里。
# 用 --soft 回滚（保留工作区内容），随后 EXIT trap 把 build.gradle.kts 整份还原。
if ! git push origin main --quiet; then
    echo "    推送失败：回滚本地 commit（避免重跑跳号）" >&2
    git reset --soft "$PRE_PUSH_HEAD" >/dev/null 2>&1 || true
    git restore --staged update.json apk/app-release.apk "$GRADLE_FILE" >/dev/null 2>&1 || \
        git reset -q update.json apk/app-release.apk "$GRADLE_FILE" >/dev/null 2>&1 || true
    exit 1
fi
# 推送成功：版本号改动已进历史，EXIT trap 不必再还原
GRADLE_DIRTY=0

echo "── [6/6] 清单自检（仅告警，不判失败）"
# 注意：此处 commit+push **已经成功**，发版事实上已完成。自检只是「顺手看一眼代理是否可取到
# 新清单」，检查对象是第三方代理（gh-proxy）的可用性与缓存刷新——代理抖动、限流、CDN 未刷新
# 都会失败。过去这里 exit 1，导致发版成功却报「发版失败」，运维重跑直接踩跳号。
# 因此自检失败只告警，退出码保持 0。
CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "https://gh-proxy.com/https://raw.githubusercontent.com/$REPO/main/update.json" 2>/dev/null || true)
if [[ ! "$CODE" =~ ^2[0-9][0-9]$ ]]; then
    echo "    ⚠️  自检未通过：gh-proxy 返回「${CODE:-无响应}」" >&2
    echo "       推送已成功，发版已完成；代理可能抖动/限流或 CDN 尚未刷新，稍后再验证即可。" >&2
fi
echo "    完成 ✅ 清单 HTTP ${CODE:-?}"
echo "    手机端：打开应用 → 设置 → 检查更新 → 立即更新"
