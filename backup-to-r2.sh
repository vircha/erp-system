#!/bin/bash
# ============================================================================
# ERP 全量备份 → 对象存储（Cloudflare R2 / S3 兼容）
#
#   内容（前后端全套）：
#     后端  server.js / db.js / xlsx_util.js / order_table.js / moderation*.js
#           + package.json + capacitor.config.json + refresh_apk_url*.sh
#     前端  public/ 下的 css js img html sw.js
#     数据  data/erp.db  ← 用 SQLite **在线备份 API** 出**一致快照**
#           （直接 cp 会丢 WAL 里已提交的数据；生产库 WAL 常比主库还大）
#     附件  uploads/
#
#   刻意排除：
#     public/downloads/*.apk  历史安装包（已单独传到对象存储的 apk 目录）
#     tls/key.pem            🔴 私钥绝不上云
#     所有 *.bak*            编辑器/部署留下的垃圾文件
#     node_modules
#
#   产出：  $REMOTE/backup-YYYYmmdd_HHMMSS.tar.gz
#   保留：  远端按文件名（内含时间戳）倒序，只留最新 KEEP 份；本地留 LOCAL_KEEP 份
#
#   调度：  systemd timer → 每天 00:00 与 12:00
#          （装 erp-backup.service + erp-backup.timer，见同目录）
#
#   日志：  $LOG
#
#   依赖：  rclone（配置好名为 $REMOTE_NAME 的 remote）、python3（标准库 sqlite3）
# ============================================================================
set -uo pipefail

# ---------- 可调参数（也可用环境变量覆盖） ----------
SRC_ROOT="${SRC_ROOT:-/opt/erp}"
REMOTE_NAME="${REMOTE_NAME:-erpbackup}"          # rclone remote 名
REMOTE_PATH="${REMOTE_PATH:-erp}"
PREFIX=backup
KEEP="${KEEP:-10}"                               # 🔴 远端保留份数
LOCAL_DIR="${LOCAL_DIR:-/root/erp_backups}"
LOCAL_KEEP="${LOCAL_KEEP:-3}"                    # 本地应急副本留几份
LOG="${LOG:-/var/log/erp_backup.log}"
TMP_ROOT="${TMP_ROOT:-/root}"                   # 🔴 不放 /tmp，见下方说明
RCLONE="${RCLONE:-$(command -v rclone || echo /usr/local/bin/rclone)}"
PY="${PY:-python3}"

REMOTE="${REMOTE_NAME}:${REMOTE_PATH}"

# ---------- 日志 ----------
log() {
  local msg="[$(date '+%F %T')] $*"
  echo "$msg"
  echo "$msg" >> "$LOG" 2>/dev/null || true
}

# ---------- 环境自检 ----------
precheck() {
  local ok=0
  [ -d "$SRC_ROOT" ]               || { log "❌ 源目录不存在: $SRC_ROOT"; ok=1; }
  [ -f "$SRC_ROOT/server.js" ]     || { log "❌ 缺 server.js"; ok=1; }
  [ -f "$SRC_ROOT/data/erp.db" ]   || { log "❌ 缺 data/erp.db"; ok=1; }
  [ -x "$RCLONE" ]                 || { log "❌ rclone 不可执行: $RCLONE"; ok=1; }
  command -v "$PY" >/dev/null 2>&1 || { log "❌ 找不到 $PY"; ok=1; }
  [ $ok -eq 0 ] || exit 1
}

# ---------- 数据库一致快照 ----------
# 🔴 关键：不能用 cp erp.db —— SQLite 的 WAL 可能比主库还大，
#    直接 cp 拿到的是「不完整的旧主库 + 未合并的 WAL」，
#    恢复后会丢最近的数据。必须用 SQLite 在线备份 API（backup() 走 checkpoint）。
snapshot_db() {
  local src="$SRC_ROOT/data/erp.db" dst="$1"
  "$PY" - "$src" "$dst" <<'PYEOF'
import sqlite3, sys
src, dst = sys.argv[1], sys.argv[2]
s = sqlite3.connect(src)
try:
    d = sqlite3.connect(dst)
    with d:
        s.backup(d)
    # 校验：能打开且表结构完整（少于 5 张表说明备份是坏的）
    rows = d.execute("SELECT count(*) FROM sqlite_master WHERE type='table'").fetchone()[0]
    d.close()
    if rows < 5:
        raise RuntimeError('快照表数量异常: %d' % rows)
finally:
    s.close()
PYEOF
}

# ---------- 主流程 ----------
main() {
  precheck

  local TS
  TS=$(date +%Y%m%d_%H%M%S)
  local NAME="${PREFIX}-${TS}.tar.gz"
  # 🔴 临时目录放 $TMP_ROOT，不放 /tmp —— systemd 下 /tmp 可能是 PrivateTmp 私有挂载，
  #    且系统清理脚本随时会扫 /tmp，打断正在打包的备份。
  local TMPD="${TMP_ROOT}/erp_bak_${TS}"
  local STAGE="$TMPD/stage"
  local TARBALL="$TMPD/$NAME"

  rm -rf "$TMPD"
  mkdir -p "$STAGE" "$LOCAL_DIR"
  # 🔴 umask 077：备份里含业务数据，别让其他用户能读
  chmod 700 "$TMPD"

  # ---- 1. 数据库一致快照 ----
  log "▶ 1/4 数据库快照 …"
  if ! snapshot_db "$STAGE/erp.db"; then
    log "❌ 数据库快照失败，中止"
    rm -rf "$TMPD"; exit 1
  fi
  local DBSZ
  DBSZ=$(stat -c '%s' "$STAGE/erp.db")
  log "   erp.db 快照 ${DBSZ} 字节"

  # ---- 2. 代码 + 前端 + 附件 ----
  log "▶ 2/4 收集前后端文件 …"
  for f in server.js db.js xlsx_util.js order_table.js moderation.js \
           moderation_server.js package.json capacitor.config.json; do
    [ -f "$SRC_ROOT/$f" ] && cp -p "$SRC_ROOT/$f" "$STAGE/$f"
  done
  cp -p "$SRC_ROOT"/refresh_apk_url*.sh "$STAGE/" 2>/dev/null || true

  # 前端：public/ 全部，但排掉 downloads/（历史 APK，动辄几百 M）
  mkdir -p "$STAGE/public"
  if [ -d "$SRC_ROOT/public" ]; then
    ( cd "$SRC_ROOT/public" && tar cf - \
        --exclude='./downloads' \
        --exclude='*.apk' \
        --exclude='node_modules' \
        . ) | ( cd "$STAGE/public" && tar xf - )
  fi

  # 附件
  if [ -d "$SRC_ROOT/uploads" ]; then
    mkdir -p "$STAGE/uploads"
    ( cd "$SRC_ROOT/uploads" && tar cf - . ) | ( cd "$STAGE/uploads" && tar xf - )
  fi

  # 🔴 排除私钥与各种 .bak 垃圾
  #    模式要覆盖 `.bak` `.bak.xxx` `.bak_xxx` 三种写法 —— 线上真实存在
  #    `app.js.bak_tplsync_20261017_101114` 这种「.bak_」写法，
  #    `*.bak` 和 `*.bak.*` 都会漏掉它，只有 `*.bak*` 能全覆盖。
  find "$STAGE" \( -name '*.bak*' -o -name 'key.pem' \
                   -o -name '*.key' -o -name 'node_modules' \) -prune -exec rm -rf {} + 2>/dev/null || true

  local NF
  NF=$(find "$STAGE" -type f | wc -l)
  log "   收集 $NF 个文件"

  # ---- 3. 打包 + 上传 ----
  log "▶ 3/4 打包并上传 …"
  tar czf "$TARBALL" -C "$STAGE" . || { log "❌ 打包失败"; rm -rf "$TMPD"; exit 1; }
  local TSZ
  TSZ=$(stat -c '%s' "$TARBALL")
  log "   ${NAME} ${TSZ} 字节"

  if ! "$RCLONE" copyto "$TARBALL" "$REMOTE/$NAME" \
        --transfers 2 --retries 3 --low-level-retries 10 \
        --stats-one-line --stats 30s; then
    log "❌ 上传失败（本地包保留在 $TARBALL）"
    rm -rf "$STAGE"
    log "▶ 4/4 跳过清理（上传失败，绝不能删旧备份）"
    exit 1
  fi
  log "   ✅ 已上传 $REMOTE/$NAME"

  rm -rf "$STAGE"

  # ---- 4. 保留策略 ----
  log "▶ 4/4 清理旧备份（保留最新 $KEEP 个）…"

  # 远端：按文件名（内含时间戳）倒序，第 KEEP+1 个及以后全删
  # 🔴 只删本脚本自己的 backup-* 文件，绝不碰同 bucket 的其它目录/文件
  local OLD
  OLD=$("$RCLONE" lsf "$REMOTE/" 2>/dev/null \
        | grep -E "^${PREFIX}-[0-9]{8}_[0-9]{6}\.tar\.gz$" \
        | sort -r | tail -n +$((KEEP + 1)))
  if [ -n "$OLD" ]; then
    local n=0
    while IFS= read -r f; do
      [ -z "$f" ] && continue
      if "$RCLONE" deletefile "$REMOTE/$f" 2>/dev/null; then
        n=$((n + 1))
        log "   已删旧备份 $f"
      else
        log "   ⚠️ 删除失败 $f"
      fi
    done <<< "$OLD"
    log "   远端清理 $n 个，剩 $KEEP 个"
  else
    log "   远端无需清理（当前 ≤ $KEEP 个）"
  fi

  # 本地应急副本
  cp -p "$TARBALL" "$LOCAL_DIR/$NAME"
  find "$LOCAL_DIR" -maxdepth 1 -name "${PREFIX}-*.tar.gz" -printf '%T@ %p\n' 2>/dev/null \
    | sort -rn | tail -n +$((LOCAL_KEEP + 1)) | cut -d' ' -f2- \
    | while IFS= read -r f; do [ -n "$f" ] && rm -f "$f"; done

  rm -rf "$TMPD"

  # ---- 收尾核对 ----
  local CNT
  CNT=$("$RCLONE" lsf "$REMOTE/" 2>/dev/null | grep -cE "^${PREFIX}-[0-9]{8}_[0-9]{6}\.tar\.gz$")
  log "✅ 完成 | 本次 $NAME ($TSZ B) | 远端现存 $CNT 份 | 目标 $KEEP 份"
  if [ "$CNT" -gt "$KEEP" ]; then
    log "⚠️ 远端份数 $CNT > 目标 $KEEP，需人工检查"
  fi
}

main "$@"