# ============================================================
# 裸机部署（无 Docker 环境）
# ============================================================
# 用法：
#   sudo bash deploy-systemd.sh
#
# 与 Docker 方案的区别：自己装 Node、跑 systemd、用自带证书或
# 已有 nginx/Caddy 反代。适合服务器不允许装 Docker 的场合。
#
# 前提：
#   - Debian/Ubuntu 或 CentOS/RHEL
#   - 能 sudo
#   - 已有域名解析到本机（要HTTPS 就必须有）

set -euo pipefail

APP_USER="${APP_USER:-erp}"
APP_DIR="${APP_DIR:-/opt/erp}"
APP_PORT="${APP_PORT:-3000}"

c_ok()   { printf '\033[32m%s\033[0m\n' "$*"; }
c_err()  { printf '\033[31m%s\033[0m\n' "$*"; }
c_warn() { printf '\033[33m%s\033[0m\n' "$*"; }
c_step() { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }

[ "$(id -u)" = 0 ] || { c_err "需要 root，请用 sudo 运行"; exit 1; }

# ------------------------------------------------------------
c_step '检查 / 安装 Node.js 22'
# ------------------------------------------------------------
need_node=22
if command -v node >/dev/null 2>&1; then
  cur="$(node -v | sed 's/v\([0-9]*\).*/\1/')"
  if [ "$cur" -ge "$need_node" ]; then
    c_ok "✓ Node $(node -v) 已满足要求"
  else
    c_warn "! Node $(node -v) 版本过低（本项目需要 22.5+，因为用了内置node:sqlite）"
    read -r -p "  现在升级到 Node 22？其他项目若依赖旧版Node 会受影响 [y/N] " a
    case "$a" in [yY]*) ;; *) c_err "已取消"; exit 1 ;; esac
  fi
else
  c_warn "! 没装 Node，开始安装"
fi

if ! command -v node >/dev/null 2>&1 || [ "$(node -v | sed 's/v\([0-9]*\).*/\1/')" -lt "$need_node" ]; then
  if command -v apt-get >/dev/null 2>&1; then
    apt-get update -qq
    apt-get install -y -qq curl ca-certificates
    curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
    apt-get install -y -qq nodejs
  elif command -v yum >/dev/null 2>&1; then
    curl -fsSL https://rpm.nodesource.com/setup_22.x | bash -
    yum install -y -q nodejs
  else
    c_err "✗ 不认识的包管理器，请手动安装 Node 22 后重跑"
    exit 1
  fi
fi
c_ok "✓ Node $(node -v)"

# ------------------------------------------------------------
c_step '创建运行用户'
# ------------------------------------------------------------
if id "$APP_USER" >/dev/null 2>&1; then
  c_ok "✓ 用户 $APP_USER 已存在"
else
  useradd --system --shell /usr/sbin/nologin --home-dir "$APP_DIR" "$APP_USER"
  c_ok "✓ 已创建系统用户 $APP_USER"
fi

# ------------------------------------------------------------
c_step '同步代码'
# ------------------------------------------------------------
if [ -d "$APP_DIR/.git" ]; then
  cd "$APP_DIR"
  git pull --ff-only
  c_ok "✓ git pull 完成"
else
  c_err "✗ $APP_DIR 不是 git 仓库"
  echo "  请先 clone 到 $APP_DIR 再运行本脚本，例如："
  echo "    git clone <仓库地址> $APP_DIR"
  echo "    cd $APP_DIR && sudo -E bash deploy-systemd.sh"
  exit 1
fi

cd "$APP_DIR"
mkdir -p data uploads/feedback tls backups

# ------------------------------------------------------------
c_step '配置环境变量'
# ------------------------------------------------------------
if [ ! -f .env ]; then
  cp .env.example .env
  c_ok "✓ 已从模板生成 .env，**必须现在编辑它**："
  echo "    vi $APP_DIR/.env"
  echo "  至少要填：ERP_BOSS_USERNAME / ERP_BOSS_PASSWORD"
  echo "  （ERP_DOMAIN 只在你用 Caddy 反代时才需要）"
  exit 0
fi

#🔴 不要用 `. ./.env`（shell source）来读密码。
#   shell 会把值里的 # 当注释起点、把空格当分隔符：
#     ERP_BOSS_PASSWORD=p@ss w0rd#123
#     → source 之后只剩 "p@ss"，登录直接失败且极难排查。
#   systemd 的 EnvironmentFile 反而是安全的（整行右半部分都是值）。
#   所以这里用与 systemd 相同规则的解析，把结果导出为 shell 变量。
load_env_systemd_style() {
  local line key val
  while IFS= read -r line || [ -n "$line" ]; do
    # 跳过空行与 # 开头的注释
    case "$line" in
      ''|'#'*) continue ;;
    esac
    case "$line" in
      *=*) ;;
      *) continue ;;
    esac
    key="${line%%=*}"
    val="${line#*=}"
    # 去掉 key 两侧空格
    key="$(printf '%s' "$key" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    case "$key" in
      ''|[0-9]*|*[!A-Za-z0-9_]*) continue ;;   # 不是合法变量名，跳过
    esac
    # 🔴 值两侧空格要去掉，但**中间的 # 和空格必须原样保留**
    val="$(printf '%s' "$val" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    export "$key=$val"
  done < .env
}

load_env_systemd_style
c_ok "✓ .env 解析完成"

[ -n "${ERP_BOSS_PASSWORD:-}" ] || { c_err "✗ .env 里没填 ERP_BOSS_PASSWORD"; exit 1; }
[ "${ERP_BOSS_PASSWORD}" = "admin123" ] && { c_err "✗ 密码还是默认 admin123，必须改"; exit 1; }
c_ok "✓ .env 校验通过"

# ------------------------------------------------------------
c_step '注册 systemd 服务'
# ------------------------------------------------------------
cat > /etc/systemd/system/erp.service <<EOF
[Unit]
Description=ERP 经销商进销存系统
# 需要网络就绪（systemd 比 network.target 更准）
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$APP_USER
Group=$APP_USER
WorkingDirectory=$APP_DIR
EnvironmentFile=$APP_DIR/.env
Environment=PORT=$APP_PORT
Environment=ERP_BIND=127.0.0.1
ExecStart=/usr/bin/node $APP_DIR/server.js
Restart=always
RestartSec=5

# 🔴 安全加固（不影响功能）
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full
#🔴 关键：这三行必须可写，否则 SQLite 起不来。
#    但注意ProtectSystem=full 把 /usr /boot /etc 设成只读，/opt 默认仍可写。
ReadWritePaths=$APP_DIR/data $APP_DIR/uploads

StandardOutput=journal
StandardError=journal
SyslogIdentifier=erp

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable erp >/dev/null 2>&1
c_ok "✓ systemd 服务已注册（开机自启）"

c_step '启动服务'
systemctl restart erp
sleep 4

if systemctl is-active --quiet erp; then
  c_ok "✓ 服务已启动"
else
  c_err "✗ 启动失败，最近日志："
  journalctl -u erp -n 40 --no-pager
  exit 1
fi

if curl -fsS -m 5 "http://127.0.0.1:$APP_PORT/api/me" >/dev/null 2>&1; then
  c_ok "✓ 接口自检通过"
else
  c_warn "! 接口自检未通过（若返回 401 属正常 —— /api/me 需要登录态）"
fi

c_step '防火墙'
if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q active; then
  ufw allow 80/tcp  >/dev/null 2>&1 || true
  ufw allow 443/tcp >/dev/null 2>&1 || true
  c_ok "✓ ufw 已放行 80/443"
fi

# ------------------------------------------------------------
echo
c_ok '════════════════════════════════════════'
c_ok " 部署完成！服务监听 127.0.0.1:$APP_PORT"
c_ok
c_ok " 下一步 —— 配置 HTTPS（必须，App 不支持明文 HTTP）："
c_ok "   方案 A：装 Caddy 自动签证书（最省事）"
c_ok "     apt install -y debian-keyring debian-archive-keyring apt-transport-https curl"
c_ok "     curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg"
c_ok "     curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | tee /etc/apt/sources.list.d/caddy-stable.list"
c_ok "     apt update && apt install -y caddy"
c_ok "     然后把 Caddyfile 放到 /etc/caddy/Caddyfile 并 systemctl reload caddy"
c_ok
c_ok "   方案 B：已有 nginx —— 加一段反向代理（记得配证书）："
c_ok "     location / { proxy_pass http://127.0.0.1:$APP_PORT; proxy_set_header Host \$host; }"
c_ok '════════════════════════════════════════'
echo
echo "常用命令："
echo "  systemctl status erp      查看状态"
echo "  journalctl -u erp -f      看日志"
echo "  systemctl restart erp     重启"
echo "  cp \$APP_DIR/data/erp.db backups/   备份（记得连 -wal/-shm 一起）"