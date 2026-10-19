#!/usr/bin/env bash
# ============================================================================
#  Android 一键打包（把真实服务器地址注入 APK）
# ============================================================================
#
#  🔴🔴 为什么要有这个脚本
#
#  仓库里 `gradle.properties` 的 erpApiBase 是**占位符** https://erp.example.com，
#  这是故意的：仓库要能公开，就不能把真实域名写进入库文件 ——
#  否则任何人 clone 一下就知道你的服务器在哪。
#
#  所以真实地址走这条链路：
#      build.local.env  （本机，已 gitignore）
#            ↓  本脚本读取
#      android-native/local.properties （本机，已 gitignore）
#            ↓  settings.gradle 解析 + 校验
#      BuildConfig.API_BASE  →  打进 APK
#
#  也就是说：**源码永远只含占位符，真实地址只在你的机器上注入。**
#
#  用法：
#      cp build.local.env.example build.local.env   # 第一次：填地址和签名
#      ./build-apk.sh                               # 之后：一键出包
#
#  Windows 用户：在 Git Bash 里运行，或直接双击 build-apk.bat
# ============================================================================
set -euo pipefail

cd "$(dirname "$0")"

RED=$'\033[31m'; GRN=$'\033[32m'; YEL=$'\033[33m'; DIM=$'\033[2m'; RST=$'\033[0m'
[ -t 1 ] || { RED=; GRN=; YEL=; DIM=; RST=; }

c_ok()   { printf '%s✓%s %s\n' "$GRN" "$RST" "$*"; }
c_err()  { printf '%s✗%s %s\n' "$RED" "$RST" "$*" >&2; }
c_warn() { printf '%s!%s %s\n' "$YEL" "$RST" "$*"; }
c_dim()  { printf '%s  %s%s\n' "$DIM" "$*" "$RST"; }

die() { c_err "$*"; exit 1; }


# ============================================================================
#  🔴🔴 JDK 探测（不加这段，Windows 上大概率构建失败）
# ============================================================================
#
#  为什么需要：
#   Gradle 官方兼容性矩阵里 **JDK 25 那一行是空的** —— 8.14 不支持，
#   要Gradle 9.1+ 才行。而 Android Studio 自带的 jbr 会自动升级到 25，
#   于是「IDE 里明明能编，命令行就是编不过」，非常费解。
#   报错长这样：Unsupported class file major version 69（69 = Java 25）
#
#  为什么不在这里直接钉死 JDK 21：
#   JDK 路径是**每台机器不一样**的，写死别人的仓库会在别人机器上失效。
#   所以这里做「探测 + 允许覆盖」，顺序是：
#     1) ERP_JAVA_HOME 环境变量（想指定就指定，最优先）
#     2) 本机常见的 JDK 21/17 安装位置
#     3) 当前 JAVA_HOME
#     4) Android Studio 自带的 jbr（可能是 25，会失败）
#
#  Android Studio 自己**只能**用它自带的 jbr，所以 IDE 里点 Build APK 仍然会失败 ——
#  这是Gradle 版本决定的，没有「两边都搞定」的解，除非升Gradle 9.1 + AGP 9 + SDK 36。
#  命令行出包则完全没问题。
# ============================================================================
java_major() { # 打印 java 主版本号，失败输出 0
  local jh="${1:-}"
  [ -x "$jh/bin/java" ] || return 0
  "$jh/bin/java" -version 2>&1 | awk -F'"' '/version/{split($2,a,"."); print (a[1]=="1" ? a[2] : a[1]); exit}'
}

pick_java_home() {
  local cands=() c m
  [ -n "${ERP_JAVA_HOME:-}" ] && cands+=("$ERP_JAVA_HOME")
  # Windows 上JDK 的常见安装位置（Git Bash 能认 /c/... 这种路径）
  for d in "/c/Program Files/Java" "/c/Program Files/Eclipse Adoptium" \
           "/c/Program Files/Microsoft" "/c/Program Files/Zulu" \
           "$HOME/jdk21" "$HOME/jdk" \
           "/c/Users/$USERNAME/.gradle/jdks"; do
    [ -d "$d" ] || continue
    for sub in "$d"/*/bin/java "$d"/*/bin/java.exe; do
      # 往上退两层拿到 JDK 根目录
      sub=$(dirname "$(dirname "$sub")")
      [ -d "$sub" ] && cands+=("$sub")
    done
  done
  [ -n "${JAVA_HOME:-}" ] && cands+=("$JAVA_HOME")
  for d in "/c/Program Files/Android/Android Studio/jbr"; do
    [ -d "$d" ] && cands+=("$d")
  done

  for c in "${cands[@]}"; do
    m=$(java_major "$c")
    # Gradle 8.14 支持 17~24。优先选 21，其次 17。
    if [ "$m" = "21" ] || [ "$m" = "17" ] || [ "$m" = "23" ] || [ "$m" = "24" ]; then
      printf '%s' "$c"; return 0
    fi
  done
  # 没有合适的就返回当前 JAVA_HOME，让Gradle 自己报错（信息更准确）
  printf '%s' "${JAVA_HOME:-}"
}

_mh=$(pick_java_home)
if [ -z "$_mh" ] || [ ! -x "$_mh/bin/java" ]; then
  c_warn "没找到任何 JDK。请装一个 JDK 17 或 21，或设 ERP_JAVA_HOME 指向你的 JDK。"
  die  "无法继续构建"
fi
_mj=$(java_major "$_mh")
if [ "$_mj" -ge 25 ] 2>/dev/null; then
  c_warn "找到的 JDK 是 $_mj（$_mh）—— Gradle 8.14 不支持 25，会报 major version 69。"
  c_warn "装一个 JDK 21（推荐 Temurin 21）后重试，或设 ERP_JAVA_HOME 指向它。"
  die  "JDK 版本过低，请改用 JDK 17/21"
fi
export JAVA_HOME="$_mh"
c_dim "JAVA_HOME = $_mh（Java $_mj）"

# ---------------------------------------------------------------------------
# 1. 读取配置
# ---------------------------------------------------------------------------
CONF=build.local.env
[ -f "$CONF" ] || {
  c_err "缺少 $CONF"
  echo
  echo "  第一次用先照着模板建一份："
  echo "      cp build.local.env.example build.local.env"
  echo "      然后用编辑器填上你的服务器地址与签名信息"
  echo
  echo "  这个文件已被 .gitignore 排除，**不会被提交**，可以放心写明文。"
  exit 1
}

# 🔴 不能用 `source` / `.` 读配置！
#   密码里只要有 # 或空格，shell 会把 # 之后当注释、把空格当命令分隔：
#      ERP_BOSS_PASSWORD=p@ss w0rd#123
#      → 报错 "w0rd#123: command not found"，实际只读到 p@ss
#    这里按 systemd EnvironmentFile 的规则解析：整行等号右边**全部**都是值。
load_conf() {
  local line key val
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue ;; esac
    case "$line" in *=*) ;; *) continue ;; esac
    key="${line%%=*}"
    val="${line#*=}"
    # 去掉 key 两侧空白
    key="$(printf '%s' "$key" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    case "$key" in ''|[0-9]*|*[!A-Za-z0-9_]*) continue ;; esac
    # 去掉包裹的成对引号（可选），但保留值内部的 # 与空格
    val="$(printf '%s' "$val" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    case "$val" in
      \"*\") val="${val#\"}"; val="${val%\"}" ;;
      \'*\') val="${val#\'}"; val="${val%\'}" ;;
    esac
    export "$key=$val"
  done < "$CONF"
}
load_conf

# ---------------------------------------------------------------------------
# 2. 校验服务器地址
# ---------------------------------------------------------------------------
API="${ERP_API_BASE:-}"
[ -n "$API" ] || die "$CONF 里没有设置 ERP_API_BASE"

case "$API" in
  https://*) ;;
  http://*)  die "ERP_API_BASE 必须是 https://（http 会把登录密码明文传上公网）
        服务器还没配 HTTPS 证书的话，先配证书（deploy.sh 会用 Caddy 自动申请）" ;;
  *)         die "ERP_API_BASE 要以 https:// 开头，当前是：$API" ;;
esac

case "$API" in
  *example.com*)
    die "ERP_API_BASE 还是占位符 $API
        请改成你自己的域名，例如 https://erp.yourdomain.com" ;;
esac

# 去掉结尾的 /，避免拼出 //api/xxx
API="${API%/}"
HOST="${API#https://}"

# ---------------------------------------------------------------------------
# 3. 写入 local.properties（Android Gradle 从这里读地址与 SDK 路径）
# ---------------------------------------------------------------------------
LP=android-native/local.properties
{
  # sdk.dir：优先用配置里的，其次用环境变量，最后试常见路径
  if [ -n "${ANDROID_SDK:-}" ]; then
    printf 'sdk.dir=%s\n' "${ANDROID_SDK//\\//}"
  elif [ -n "${ANDROID_HOME:-}" ]; then
    printf 'sdk.dir=%s\n' "${ANDROID_HOME//\\//}"
  elif [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    printf 'sdk.dir=%s\n' "${ANDROID_SDK_ROOT//\\//}"
  fi
  printf 'erpApiBase=%s\n' "$API"
} > "$LP"
c_ok "已写入 $LP"
c_dim "erpApiBase = $API"

# ---------------------------------------------------------------------------
# 4. 准备签名
# ---------------------------------------------------------------------------
HAS_KS=0
KS_FILE="${ANDROID_KEYSTORE:-}"
if [ -n "$KS_FILE" ]; then
  # Windows 路径可能写成 C:\path\to\x.jks，转成正斜杠给 gradle
  KS_FILE_UNIX="${KS_FILE//\\//}"
  [ -f "$KS_FILE_UNIX" ] || die "签名密钥文件不存在：$KS_FILE"
  [ -n "${ANDROID_KEYSTORE_PASSWORD:-}" ] || die "缺 ANDROID_KEYSTORE_PASSWORD"
  [ -n "${ANDROID_KEY_ALIAS:-}" ]         || die "缺 ANDROID_KEY_ALIAS"
  [ -n "${ANDROID_KEY_PASSWORD:-}" ]      || die "缺 ANDROID_KEY_PASSWORD"

  cat > android-native/keystore.properties <<EOF
storeFile=$KS_FILE_UNIX
storePassword=$ANDROID_KEYSTORE_PASSWORD
keyAlias=$ANDROID_KEY_ALIAS
keyPassword=$ANDROID_KEY_PASSWORD
EOF
  HAS_KS=1
  c_ok "已配置签名密钥（$(basename "$KS_FILE_UNIX")）"
else
  c_warn "没有配置 ANDROID_KEYSTORE —— 本次出 **debug 包**"
  c_dim "debug 包能装能跑，但签名是公开的，且不能覆盖安装已发布的正式版"
fi

# ---------------------------------------------------------------------------
# 5. 构建
# ---------------------------------------------------------------------------
if [ "$HAS_KS" = "1" ]; then TASK=assembleRelease; else TASK=assembleDebug; fi

# 演示包需要显式放行占位域名；我们自己注入了真地址，用不到这个开关
EXTRA=()
echo
echo "开始构建（$TASK）……"
echo

pushd android-native > /dev/null
if [ -x ./gradlew ]; then
  ./gradlew "$TASK" --no-daemon "${EXTRA[@]+"${EXTRA[@]}"}"
else
  die "找不到 gradlew（android-native/gradlew）"
fi
popd > /dev/null

# ---------------------------------------------------------------------------
# 6. 收集产物
# ---------------------------------------------------------------------------
if [ "$HAS_KS" = "1" ]; then SRC=android-native/app/build/outputs/apk/release
else                          SRC=android-native/app/build/outputs/apk/debug; fi

APK=$(find "$SRC" -name '*.apk' -type f 2>/dev/null | head -1)
[ -n "$APK" ] || die "构建结束但没找到 APK（查找路径：$SRC）"

VC=$(grep -m1 'versionCode' android-native/app/build.gradle | grep -oE '[0-9]+')
VN=$(grep -m1 'versionName' android-native/app/build.gradle | sed "s/.*versionName *//;s/['\"]//g")

mkdir -p dist
OUT="dist/erp-app-${VN}-${VC}.apk"
cp "$APK" "$OUT"

# ---------------------------------------------------------------------------
# 7. 终检：APK 里必须含着我们刚注入的地址，且绝不能有占位符
# ---------------------------------------------------------------------------
echo
echo "校验 APK 内的服务器地址……"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
unzip -o -q "$OUT" 'classes*.dex' -d "$TMP" 2>/dev/null || c_warn "解包 dex 失败，跳过地址校验"

if ls "$TMP"/*.dex >/dev/null 2>&1; then
  # 🔴 必须用 grep -a 直接扫二进制。
  #    别用 `strings`：很多环境（Git Bash / 精简容器）根本没这个命令，
  #    而 `strings missing | grep -q xxx` 会因为「命令不存在、输出为空」
  #    静默通过判断 —— 曾因此误以为「APK 里没有真实域名」。
  if grep -aq "$HOST" "$TMP"/*.dex; then
    c_ok "APK 内含目标地址：$HOST"
  else
    c_err "APK 里没找到 $HOST —— 地址没注入成功，别发这个包"
    die  "请检查 settings.gradle 是否被改动"
  fi

  if grep -aq 'example\.com' "$TMP"/*.dex; then
    die "APK 里检出了占位域名 example.com，说明注入失败，已中止"
  fi
  c_ok "APK 内不含占位域名"
fi

# ---------------------------------------------------------------------------
# 8. 汇总
# ---------------------------------------------------------------------------
SIZE=$(du -h "$OUT" | cut -f1)
echo
echo "────────────────────────────────────────────────"
c_ok "打包完成"
echo "  文件      $OUT"
echo "  大小      $SIZE"
echo "  版本      $VN  (versionCode $VC)"
if [ "$HAS_KS" = "1" ]; then
  echo "  签名      ✅ 正式签名（可覆盖安装已发布版本）"
else
  echo "  签名      ⚠️  debug 签名"
fi
echo "  服务器    $API"
echo "────────────────────────────────────────────────"
echo
echo "装到手机："
echo "  1) 数据线连电脑，把 $OUT 拷进手机，点开安装"
echo "  2) 或传到你的服务器 /downloads/ 目录，用手机浏览器下载"
echo
echo "🔴 发版前记得改 versionCode（android-native/app/build.gradle），"
echo "   且必须**严格大于**线上正在发的那个，否则老用户收不到更新提示。"