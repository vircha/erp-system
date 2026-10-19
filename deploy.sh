#!/usr/bin/env bash
# ============================================================
# ERP 系统一键部署脚本
# ============================================================
# 用法：
#   bash deploy.sh              首次部署 / 更新（默认）
#   bash deploy.sh start        启动
#   bash deploy.sh stop         停止（保留数据）
#   bash deploy.sh restart      重启
#   bash deploy.sh logs         看日志（Ctrl+C 退出查看，不影响服务）
#   bash deploy.sh status       看状态
#   bash deploy.sh backup       备份数据库到 backups/
#   bash deploy.sh restore FILE 恢复数据库
#
# 首次使用：
#   1) cp .env.example .env && vi .env     填 ERP_DOMAIN / 账号密码
#   2) 把域名的 A 记录解析到本服务器 IP
#   3) bash deploy.sh
# ============================================================

set -euo pipefail

cd "$(dirname "$0")"
C='docker compose'

c_ok()   { printf '\033[32m%s\033[0m\n' "$*"; }
c_warn() { printf '\033[33m%s\033[0m\n' "$*"; }
c_err()  { printf '\033[31m%s\033[0m\n' "$*"; }
c_step() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }

# ------------------------------------------------------------
# 环境检查
# ------------------------------------------------------------
precheck() {
  c_step '检查运行环境'

  if ! command -v docker >/dev/null 2>&1; then
    c_err "✗ 没装 Docker"
    echo "  Ubuntu/Debian: sudo apt install -y docker.io docker-compose-v2"
    echo "  CentOS/RHEL:   sudo yum install -y docker docker-compose-plugin"
    echo "  装完记得：sudo systemctl enable --now docker && sudo usermod -aG docker \$USER"
    exit 1
  fi
  c_ok "✓ Docker 已安装"

  # docker compose（v2 插件）与旧版 docker-compose 的探测
  if docker compose version >/dev/null 2>&1; then
    :
  elif command -v docker-compose >/dev/null 2>&1; then
    C='docker-compose'
  else
    c_err "✗ 没装 docker compose"
    echo "  Ubuntu/Debian: sudo apt install -y docker-compose-v2"
    echo "  CentOS/RHEL:   sudo yum install -y docker-compose-plugin"
    exit 1
  fi
  c_ok "✓ Compose 可用（$C）"

  if ! docker info >/dev/null 2>&1; then
    c_err "✗ Docker 服务没运行，或当前用户无权访问"
    echo "  试：sudo systemctl start docker"
    echo "     sudo usermod -aG docker \$USER && newgrp docker   # 之后需重新登录"
    exit 1
  fi
  c_ok "✓ Docker 服务正常"

  [ -f .env ] || { c_err "✗ 缺少 .env 文件"; echo "  先执行：cp .env.example .env，然后填写内容"; exit 1; }
  c_ok "✓ .env 存在"
}

# ------------------------------------------------------------
# 读 .env 并校验必填项
# ------------------------------------------------------------
# 🔴 绝不能用 `. ./.env`（shell source）：shell 会把值里的 # 当注释起点、
#    空格当参数分隔符，导致密码被静默截断：
#      ERP_BOSS_PASSWORD=p@ss w0rd#123
#      → source 之后只剩 "p@ss"，登录直接失败且极难排查。
#    docker compose 自己会正确解析 .env，但本脚本需要读值做校验，
#    所以必须用与 systemd EnvironmentFile 相同的规则（整行右半都是值）。
load_env() {
  local line key val
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue ;; esac
    case "$line" in *=*) ;; *) continue ;; esac
    key="${line%%=*}"; val="${line#*=}"
    key="$(printf '%s' "$key" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    case "$key" in ''|[0-9]*|*[!A-Za-z0-9_]*) continue ;; esac
    val="$(printf '%s' "$val" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    export "$key=$val"
  done < .env

  local bad=0
  [ -n "${ERP_DOMAIN:-}" ]     || { c_err "✗ .env 里没填 ERP_DOMAIN（你的域名）"; bad=1; }
  [ -n "${ERP_BOSS_USERNAME:-}" ] || { c_err "✗ .env 里没填 ERP_BOSS_USERNAME（初始管理员账号）"; bad=1; }
  [ -n "${ERP_BOSS_PASSWORD:-}" ] || { c_err "✗ .env 里没填 ERP_BOSS_PASSWORD（初始管理员密码）"; bad=1; }

  #🔴 拦住「直接用默认弱口令上线」——公开部署等于把系统敞开
  if [ "${ERP_BOSS_PASSWORD:-}" = "admin123" ]; then
    c_err "✗ ERP_BOSS_PASSWORD 还是默认的 admin123"
    echo"     这是公开在GitHub 上的默认密码，必须改。改完再运行本脚本。"
    bad=1
  fi
  if [ "${ERP_BOSS_USERNAME:-}" = "admin" ]; then
    c_warn "! ERP_BOSS_USERNAME 是 admin，建议改成一个别人猜不到的名字"
  fi

  [ "$bad" = 0 ] || { echo; echo "改完 .env 后重新运行：bash deploy.sh"; exit 1; }

  c_ok "✓ 配置校验通过"
  c_ok "  域名：$ERP_DOMAIN"
  c_ok "  管理员：$ERP_BOSS_USERNAME"
}

# ------------------------------------------------------------
# 域名 DNS 预检（提前发现问题，别等证书申请失败才排查）
# ------------------------------------------------------------
check_dns() {
  c_step '检查域名解析'

  local host="${ERP_DOMAIN%%:*}"          # 去掉可能的端口
  local ip
  ip="$(getent hosts "$host" 2>/dev/null | awk '{print $1; exit}')" || true

  if [ -z "$ip" ]; then
    c_warn "! 域名 $host 还解析不出IP，Caddy 申请证书会失败"
    echo "    请先在域名服务商处添加 A 记录指向本服务器 IP"
    echo "    （刚改完 DNS 可能要等几分钟到几小时生效）"
    read -r -p "  仍要继续吗？(y/N) " a
    case "$a" in [yY]*) ;; *) c_err "已取消"; exit 1 ;; esac
  else
    local pub
    pub="$(curl -s --max-time 5 https://api.ipify.org 2>/dev/null || echo '?')"
    if [ "$ip" != "$pub" ]; then
      c_warn "! 域名 $host 解析到 $ip，但本机公网 IP 是 $pub"
      echo "    证书仍能申请成功，但访问会走到错误的服务器。请检查 A 记录。"
    else
      c_ok "✓ 域名解析正确（$host → $ip）"
    fi
  fi

  # 80/443 被占用的话 Caddy 拿不到证书
  for port in 80 443; do
    if command -v ss >/dev/null 2>&1 && ss -tln "sport = :$port" 2>/dev/null | grep -q LISTEN; then
      c_warn "! 宿主机 $port 端口已被占用，Caddy 可能启动失败"
      echo "    查占用：ss -tlnp 'sport = :$port'"
    fi
  done
}

# ------------------------------------------------------------
# 部署
# ------------------------------------------------------------
deploy() {
  mkdir -p data uploads/feedback backups

  # 🔴 库已存在时，.env 里的 ERP_BOSS_* 是**不生效**的
  #   （db.js 只在首次建库时创建账号）。不提示的话，
  #   部署者会以为「我改了密码为什么还是老密码」。
  if [ -s data/erp.db ]; then
    c_warn "!检测到已有数据库 data/erp.db"
    c_warn "  .env 里的 ERP_BOSS_USERNAME / ERP_BOSS_PASSWORD **不会生效**"
    c_warn "  （它们只在首次建库时创建账号）。要改密码请登录后在「设置」页改。"
  fi

  c_step '拉取镜像并启动'
  $C up -d --build --remove-orphans

  c_step '等待服务就绪'
  local i
  for i in $(seq 1 40); do
    if docker exec erp-system node -e \
      "require('http').get('http://127.0.0.1:3000/api/me',r=>process.exit(r.statusCode<500?0:1)).on('error',()=>process.exit(1))" \
      >/dev/null 2>&1; then
      c_ok "✓ 服务已就绪（约 ${i} 秒）"
      return 0
    fi
    sleep 2
  done

  c_err "✗ 60 秒内没就绪，最后 30 行日志："
  docker logs --tail 30 erp-system 2>&1 || true
  exit 1
}

# ------------------------------------------------------------
# 备份
# ------------------------------------------------------------
# 🔴 方案选型（2026-10-19 在生产的 Node 22 上实测）：
#   DatabaseSync.backup()  —— **不存在**，别用
#   sqlite3 CLI .backup     —— 容器里没装，别用
#   VACUUM INTO             —— ✅ node:sqlite 直接支持 SQL 级热备，
#                             且实测能带全WAL 里未 checkpoint 的事务
#   （原理上要拿写锁，但读写都是毫秒级，实际不影响开单）
backup() {
  c_step '备份数据库'
  mkdir -p backups
  local ts out
  ts="$(date +%Y%m%d_%H%M%S)"
  out="backups/erp_${ts}.db"

  docker ps --format '{{.Names}}' | grep -qx erp-system || {
    c_err "✗ 容器没在运行，无法备份"; exit 1
  }

  # 路径是本脚本写死的固定值，不接受外部输入，所以直接字面量拼进 SQL 最安全，
  # 也免去多层引号转义的坑。
  # DB_PATH 允许被compose 覆盖，但这里用相对稳妥的默认值 + 环境变量兜底。
  docker exec erp-system node -e '
    const { DatabaseSync } = require("node:sqlite");
    const fs = require("fs");
    const src = process.env.DB_PATH || "/app/data/erp.db";
    const dst = "/app/data/.backup.tmp";
    try { fs.unlinkSync(dst); } catch (e) {}
    const db = new DatabaseSync(src);
    db.exec("VACUUM INTO \x27" + dst + "\x27");
    db.close();
    console.log("VACUUM INTO 完成 -> " + dst);
  ' || { c_err "✗ 备份失败"; exit 1; }

  docker cp "erp-system:/app/data/.backup.tmp" "$out" >/dev/null
  docker exec erp-system rm -f /app/data/.backup.tmp

  [ -s "$out" ] || { c_err "✗ 备份文件为空，判定失败"; rm -f "$out"; exit 1; }

  # 校验：能打开且表结构完整才算数（只看文件大小会被半截文件骗）
  if ! docker exec erp-system node -e "
      const { DatabaseSync } = require('node:sqlite');
      new DatabaseSync(process.argv[1], { readOnly: true })
        .prepare(\"SELECT count(*) n FROM sqlite_master WHERE type='table'\").get();
    " "$out" >/dev/null 2>&1; then
    c_warn "! 备份文件本地校验未通过（不影响已生成，但建议人工确认）"
  fi

  c_ok "✓ 已备份到 $out（$(du -h "$out" | cut -f1)）"
  echo "  建议加 cron 每日自动备份："
  echo "  0 3 * * * cd $PWD && bash deploy.sh backup >> backups/cron.log 2>&1"
}

restore() {
  local f="${1:-}"
  [ -n "$f" ] && [ -f "$f" ] || { c_err "用法：bash deploy.sh restore backups/erp_xxx.db"; exit 1; }

  c_step "从 $f 恢复"
  c_warn "! 恢复会覆盖当前数据库。建议先备份：bash deploy.sh backup"
  read -r -p "  确认继续？(yes/N) " a
  [ "$a" = "yes" ] || { c_err "已取消"; exit 1; }

  #🔴 必须停服再覆盖：运行中替换数据库文件会连WAL 一起错乱
  $C stop erp >/dev/null 2>&1 || true
  docker cp "$f" "erp-system:/app/data/erp.db" >/dev/null 2>&/null || {
    # 容器已停时 docker cp 仍可用；失败则用临时容器拷
    docker run --rm -v "$PWD/data:/d" -v "$(cd "$(dirname "$f")" && pwd):/s:ro" \
      alpine cp "/s/$(basename "$f")" /d/erp.db
  }
  # 清掉旧 WAL：它属于被覆盖掉的旧库，留着会导致新库启动即损坏
  $C run --rm --entrypoint sh erp-system -c 'rm -f /app/data/erp.db-wal /app/data/erp.db-shm' || true
  $C start erp >/dev/null

  c_ok "✓ 已恢复并启动"
}

# ------------------------------------------------------------
case "${1:-deploy}" in
  deploy|"")
    precheck; load_env; check_dns; deploy
    echo
    c_ok '════════════════════════════════════════'
    c_ok " 部署完成！访问：https://$ERP_DOMAIN"
    c_ok " 管理员：$ERP_BOSS_USERNAME"
    c_warn " ① 立刻登录 → 设置页改掉密码"
    c_warn " ② 确认无误后，编辑 .env 把 ERP_BOSS_PASSWORD 换掉（下次重建会用到）"
    c_ok '════════════════════════════════════════'
    ;;
  start)    precheck; $C start ;;
  stop)     $C stop ;;
  restart)  precheck; $C restart ;;
  logs)     docker logs -f --tail 100 erp-system ;;
  status)
    $C ps
    echo
    docker inspect --format '健康检查：{{if .State.Health}}{{.State.Health.Status}}{{else}}未配置{{end}}' erp-system 2>/dev/null || true
    ;;
  backup)   backup ;;
  restore)  shift; restore "${1:-}" ;;
  *)
    sed -n '2,20p' "$0"
    exit 1
    ;;
esac