#!/usr/bin/env bash
# 手机版 APK 构建（v5.6.0 应收应付 + 报表中心）
# 用法：bash build_apk.sh [assembleDebug|assembleRelease]
#
# 🔴 签名凭据一律不写进脚本：release 构建需要工程根目录的 keystore.properties
#    （已被 .gitignore 排除）。缺失时直接报错，不要在这里写明文密码。
set -euo pipefail

cd "$(dirname "$0")"

# JAVA_HOME / ANDROID_HOME 不写死在脚本里（换机器就跑不动），改为从环境读取。
# 用法：export JAVA_HOME=/path/to/jdk21 ; export ANDROID_HOME=/path/to/Android/Sdk
: "${JAVA_HOME:?请先设置 JAVA_HOME（JDK 21）}"
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:?请先设置 ANDROID_HOME 或 ANDROID_SDK_ROOT}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

TASK="${1:-assembleRelease}"

if [ "$TASK" = "assembleRelease" ] && [ ! -f keystore.properties ]; then
  echo "[build] ❌ 缺少 keystore.properties（release 签名配置）"
  echo "       参考 keystore.properties.example 创建，填入自己的密钥信息："
  echo "         storeFile=C:/path/to/your-release-key.jks"
  echo "         storePassword=你的密钥库密码"
  echo "         keyAlias=你的别名"
  echo "         keyPassword=你的别名密码"
  exit 1
fi

echo "[build] JAVA_HOME=$JAVA_HOME"
echo "[build] task=$TASK"
cmd.exe /c "gradlew.bat :app:$TASK --no-daemon --console=plain"