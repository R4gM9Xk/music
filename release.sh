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

echo "── [1/6] 版本号 → $VER (versionCode 自增)"
GRADLE_FILE="app/build.gradle.kts"
NEW_CODE=$(( $(grep -oE 'versionCode = [0-9]+' "$GRADLE_FILE" | grep -oE '[0-9]+') + 1 ))
sed -i "s/versionCode = [0-9]*/versionCode = $NEW_CODE/" "$GRADLE_FILE"
sed -i "s/versionName = \"[^\"]*\"/versionName = \"$VER\"/" "$GRADLE_FILE"
echo "    versionCode=$NEW_CODE versionName=$VER"

echo "── [2/6] 构建 APK"
"$GRADLE" :app:assembleDebug --console=plain -q
APK="app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "构建失败：未找到 $APK"; exit 1; }

echo "── [3/6] 计算 sha256"
SHA=$(sha256sum "$APK" | cut -d' ' -f1)

echo "── [4/6] 写 update.json + 归档 APK（git 分发，raw 直链/gh-proxy/jsDelivr 三链可取）"
RAW_BASE="https://raw.githubusercontent.com/$REPO/main/apk/app-debug.apk"
cat > update.json <<EOF
{
  "versionCode": $NEW_CODE,
  "versionName": "$VER",
  "changelog": "$NOTES",
  "apk": "https://gh-proxy.com/$RAW_BASE",
  "apkMirror": "https://ghfast.top/$RAW_BASE",
  "apkAlt": "$RAW_BASE",
  "sha256": "$SHA"
}
EOF
mkdir -p apk
cp "$APK" apk/app-debug.apk

echo "── [5/6] 提交推送"
git add update.json apk/app-debug.apk "$GRADLE_FILE"
git commit -m "release v$VER" --quiet
git push origin main --quiet

echo "── [6/6] 完成 ✅"
echo "    手机端：打开应用 → 设置 → 检查更新 → 立即更新"
echo "    清单自检：$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 "https://gh-proxy.com/https://raw.githubusercontent.com/$REPO/main/update.json") (gh-proxy)"
