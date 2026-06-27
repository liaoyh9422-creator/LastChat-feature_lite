#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

FLAVOR="${1:-plus}"
BUILD_KIND="${2:-apk}"
CLEAN_FIRST="${CLEAN_FIRST:-0}"
SKIP_NPM_INSTALL="${SKIP_NPM_INSTALL:-0}"
SKIP_WEB_BUILD="${SKIP_WEB_BUILD:-0}"

case "$FLAVOR" in
  plus|exp|zh) ;;
  *)
    echo "[ERROR] 无效 flavor: $FLAVOR"
    echo "用法: $0 [plus|exp|zh] [apk|aab|all]"
    exit 1
    ;;
esac

case "$BUILD_KIND" in
  apk|aab|all) ;;
  *)
    echo "[ERROR] 无效构建类型: $BUILD_KIND"
    echo "用法: $0 [plus|exp|zh] [apk|aab|all]"
    exit 1
    ;;
esac

command_exists() {
  command -v "$1" >/dev/null 2>&1
}

require_cmd() {
  if ! command_exists "$1"; then
    echo "[ERROR] 缺少命令: $1"
    exit 1
  fi
}

echo "== LastChat 一键构建 =="
echo "根目录: $ROOT_DIR"
echo "Flavor: $FLAVOR"
echo "类型: $BUILD_KIND"
echo

require_cmd java

if [[ ! -f "./gradlew" ]]; then
  echo "[ERROR] 未找到 ./gradlew，请在项目根目录运行"
  exit 1
fi

JAVA_VERSION_OUTPUT="$(java -version 2>&1 | head -n 1 || true)"
echo "Java: $JAVA_VERSION_OUTPUT"

if [[ "$JAVA_VERSION_OUTPUT" != *'17.'* && "$JAVA_VERSION_OUTPUT" != *'version "17'* ]]; then
  echo "[WARN] 项目要求 JDK 17，当前可能不是 17，请自行确认。"
fi

if [[ ! -f "local.properties" ]]; then
  echo "[WARN] 未找到 local.properties"
  echo "       如果没有 release 签名，Gradle 可能会回退到 debug 签名。"
else
  if grep -q '^storeFile=' local.properties \
    && grep -q '^storePassword=' local.properties \
    && grep -q '^keyAlias=' local.properties \
    && grep -q '^keyPassword=' local.properties; then
    echo "[INFO] 检测到 release 签名配置"
  else
    echo "[WARN] local.properties 存在，但 release 签名字段可能不完整"
  fi
fi

if [[ "$CLEAN_FIRST" == "1" ]]; then
  echo
  echo ">> 清理旧构建"
  ./gradlew clean
fi

GRADLE_TASKS=()
CAP_FLAVOR="$(tr '[:lower:]' '[:upper:]' <<< "${FLAVOR:0:1}")${FLAVOR:1}"

case "$BUILD_KIND" in
  apk)
    GRADLE_TASKS+=("assemble${CAP_FLAVOR}Release")
    ;;
  aab)
    GRADLE_TASKS+=("bundle${CAP_FLAVOR}Release")
    ;;
  all)
    GRADLE_TASKS+=("assemble${CAP_FLAVOR}Release" "bundle${CAP_FLAVOR}Release")
    ;;
esac

echo
printf '>> 执行 Gradle 任务: %s\n' "${GRADLE_TASKS[*]}"
./gradlew "${GRADLE_TASKS[@]}"

echo
APK_DIR="app/build/outputs/apk/$FLAVOR/release"
AAB_DIR="app/build/outputs/bundle/${FLAVOR}Release"

echo "== 构建完成 =="
[[ -d "$APK_DIR" ]] && echo "APK 输出目录: $APK_DIR"
[[ -d "$AAB_DIR" ]] && echo "AAB 输出目录: $AAB_DIR"

echo
find "$APK_DIR" "$AAB_DIR" -maxdepth 1 -type f \( -name '*.apk' -o -name '*.aab' \) 2>/dev/null || true

echo
cat <<'EOF'
用法:
  bash scripts/build-release.sh                # 默认 plus + apk
  bash scripts/build-release.sh plus apk
  bash scripts/build-release.sh plus aab
  bash scripts/build-release.sh plus all
  bash scripts/build-release.sh exp apk
  bash scripts/build-release.sh zh aab

可选环境变量:
  CLEAN_FIRST=1       先执行 ./gradlew clean
  SKIP_NPM_INSTALL=1  跳过 npm install
  SKIP_WEB_BUILD=1    跳过单独 npm run build（Gradle preBuild 仍会触发）
EOF