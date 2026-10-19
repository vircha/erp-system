# 经销商进销存 / 开单打印系统

一套给经销商用的开单、库存、结算系统。**手机优先**：网页版和原生App 共用同一套后端接口，门店用手机扫码开单，80mm 热敏小票直接出单。

**零第三方运行时依赖** —— 数据库用 Node.js 内置 `node:sqlite`，密码哈希用内置 `crypto`，没有npm 依赖树，也就没有供应链攻击面。

---

## 功能

| 模块 | 说明 |
|---|---|
| 开单 | 多账套、按客户/商品开单、折扣、整件/散件装箱数、条码扫码录入 |
| 打印 | 80mm 热敏小票版式自定义（列宽、字号、留白均可调），商品条码取 `barcode` 字段 |
| 库存 | 按账套隔离的进销存、盘点、调价 |
| 对账 | 送货单、订单明细导出 Excel |
| 权限 | 账套隔离 + 平台管理员 + 内容审核 + 登录失败 IP 限流 |
| 多端 | 响应式网页版（桌面/手机自适应）+ 原生 Android App（Kotlin / Jetpack Compose） |

---

## 环境要求

- **Node.js >= 22.5**（`node:sqlite` 从22.5 起内置；建议 22 LTS 或 24）
- Android 构建另需 JDK 17+ 与 Android SDK（见下文）

## 快速开始

### 方式一：Docker 一键部署（推荐）

```bash
git clone <你的仓库地址>
cd <仓库名>
cp .env.example .env
vi .env                # 填 ERP_DOMAIN / ERP_BOSS_USERNAME / ERP_BOSS_PASSWORD
bash deploy.sh         # 就这一条命令
```

脚本会自己完成：环境检查 → 配置校验 → 域名解析预检 → 构建镜像 →
启动服务 → 健康检查 → 打印访问地址。

> `deploy.sh` 会**拒绝**用默认弱口令 `admin123` 部署，
> 也不会把 3000 端口暴露到公网（只经 Caddy 走 HTTPS）。

常用命令：

```bash
bash deploy.sh status      # 看状态与健康检查
bash deploy.sh logs        # 看日志
bash deploy.sh backup      # 备份数据库到 backups/
bash deploy.sh restore backups/erp_20261019_030000.db   # 恢复
bash deploy.sh restart     # 重启
bash deploy.sh stop        # 停止（保留数据）
```

**必须先做的**：把域名的 A 记录指向服务器 IP，再运行脚本。
Caddy 会自动申请并续期 Let's Encrypt 证书。

### 方式二：裸机部署（无 Docker）

```bash
git clone <你的仓库地址> /opt/erp
cd /opt/erp
sudo -E bash deploy-systemd.sh
```

脚本会安装 Node 22、创建系统用户、注册 systemd 服务并设置开机自启，
最后提示你如何用 Caddy 或 nginx 配 HTTPS。

| | Docker 方案 | 裸机方案 |
|---|---|---|
| 一条命令搞定 | ✅ | 需要先 `git clone` 到 `/opt/erp` |
| 开机自启 | ✅ | ✅ |
| 数据持久化 | `./data`、`./uploads` 目录挂载 | 同左（`ReadWritePaths` 已放开） |
| 升级 | `git pull && bash deploy.sh` | `sudo bash deploy-systemd.sh` |
| 占用资源 | 略高（多一层容器） | 更省 |
| 迁移到别的机器 | 拷 `data/` 即可 | 同左 |

> ⚠️ **数据只在 `./data/` 和 `./uploads/` 两个目录里。**
> 迁移或备份时只需带走它们（数据库是 SQLite 单文件，含 `-wal` / `-shm`）。

### 方式三：手动运行（开发调试用）

```bash
cp .env.example .env      # 至少填 ERP_BOSS_USERNAME / ERP_BOSS_PASSWORD
node server.js
```

数据库文件默认落在 `data/erp.db`（已被 `.gitignore` 排除）。

> 🔴 **只在本地开发时这样跑。** 生产必须走 HTTPS：原生 App 从 v4.7 起
> 强制 https，明文 HTTP 直接连不上；网页端的 Service Worker 和蓝牙打印
> 也只在安全上下文下可用。

### 🔴 默认管理员账号

```
用户名：admin
密码：  admin123
```

这是为了让克隆下来就能直接登录体验。**但公开仓库 + 固定默认密码 = 任何人都能登录你的实例**，
所以服务在两种情况下会打印醒目告警：

- 启动时若未设置 `ERP_BOSS_USERNAME` / `ERP_BOSS_PASSWORD`（正在用默认账号）
- 运行中若 `admin` 账号仍在使用 `admin123`

### 部署到公网前必须做三件事

1. **设置环境变量**（推荐，最省事）

   ```bash
   ERP_BOSS_USERNAME=你的登录名
   ERP_BOSS_PASSWORD=一个强密码
   ```

   或直接 `.env`：

   ```
   ERP_BOSS_USERNAME=你的登录手机号
   ERP_BOSS_PASSWORD=一个强密码
   ERP_ADMIN_ACCOUNTS=你的登录手机号   # 平台管理员，可选
   ```

2. **登录后立刻在「设置」页改掉admin 的密码**

   只设环境变量不够——环境变量只在**首次建库**时创建账号；库已经存在时改环境变量不会覆盖旧密码。

3. **别把服务端口直接暴露到公网**。80mm 小票场景手机端必须 HTTPS，请用 nginx / Caddy 反代。

数据库文件默认落在项目目录下的 `erp.db`（已被 `.gitignore` 排除）。

## 环境变量

完整清单见 [`.env.example`](.env.example)。常用的几个：

| 变量 | 作用 | 默认 |
|---|---|---|
| `ERP_DOMAIN` | 部署域名（**部署必填**，Caddy 据此签证书） | 无 |
| `ERP_BOSS_USERNAME` / `ERP_BOSS_PASSWORD` | 初始套账老板账号 | `admin` / `admin123`（**务必改**） |
| `PORT` | 监听端口（Docker 方案固定 3000，由 Caddy 反代） | `3000` |
| `ERP_BIND` | 监听地址 | `0.0.0.0` |
| `ERP_CORS_ORIGINS` | 允许的跨域来源（逗号分隔） | 空（仅同源） |
| `ERP_ADMIN_ACCOUNTS` | 平台管理员（逗号分隔） | 空（不授予） |
| `ERP_WHITELIST_ACCOUNTS` | 永久豁免内容检测的账号 | 空 |
| `ERP_IP_FAIL_LIMIT` / `ERP_IP_FAIL_WINDOW` | 登录失败限流 | `30` / `600000`ms |

**没有内置账号体系之外的任何密钥。** 对象存储、企业微信通知等集成全部走环境变量，不配就不启用。

> 📌 `.env` 里的值**按整行右半段读取**（与 systemd `EnvironmentFile` 相同的规则），
> 所以密码里可以包含 `#` 和空格。若你手动用 `source .env` 加载，
> 反而会被 shell 截断 —— 部署脚本已规避这个坑。

## 部署建议

1. **服务端口只监听 `127.0.0.1`（或容器内网），前面挂 nginx / Caddy 做 HTTPS 反代**
   —— Docker 方案的 `docker-compose.yml` 已按此配置，`erp` 容器没有 `ports` 映射
2. **必须 HTTPS**：原生 App 强制 https；网页端的 Service Worker、蓝牙打印也只在安全上下文下可用
3. `network_security_config.xml` **默认不放行任何明文域名**，需要时自行填
4. **定期备份 `data/` 目录**，注意 SQLite 的 `-wal` / `-shm` 也要一起处理
   （用 `bash deploy.sh backup` 会自动用 `VACUUM INTO` 生成一致快照，不会有这个问题）
5. **证书必须持久化**：Caddy 的证书与账号信息存在 Docker volume 里，
   删掉 volume 会触发 Let's Encrypt 速率限制，之后很难再申请到证书

---

## 手机版（原生 Android）

源码在 [`android-native/`](android-native/)，Kotlin + Jetpack Compose，min SDK 24，arm64-v8a。

```bash
cd android-native

# 1. 填服务器地址
#    推荐写local.properties（已被 .gitignore 排除，不会误提交）：
#        erpApiBase=https://你的域名
#    也可写gradle.properties，但那个文件是要入库的，别把真实域名提交上去。
echo "erpApiBase=https://你的域名" > local.properties

# 2. 配置签名（不上架可跳过，会自动回退debug 签名）
cp keystore.properties.example keystore.properties
# 然后填写 keystore 绝对路径与密码

# 3. 构建
./gradlew assembleRelease
```

> `gradle.properties` 里预置的 `erpApiBase=https://erp.example.com` 是**故意留的占位符**，
> 构建时会直接报错中断——因为占位域名打进 APK 会让 App 完全无法登录，
> 且这种错要等到装到真机、连不上服务器才会发现。宁可构建失败。

> 如果你在 GitHub Actions 里看到 `example.com 仍是模板占位符` 的报错，那是**故意的**：
> 说明仓库还没配置 `ERP_API_BASE` 这个 Secret。配置方法见下文「自动发布 APK」。

### 🔴 签名密钥须知

`keystore.properties` **已被 `.gitignore` 排除，永远不要提交它**。它的内容等于你App 的"身份证明" ——
泄露后任何人都能伪造出与你一模一样的 App 覆盖安装，用户无法分辨。

另外：应用商店上架后**永远不要更换这套密钥**，换了会导致无法覆盖升级，只能让用户卸载重装（丢本地数据）。

## 自动发布 APK（GitHub Actions）

仓库内置 [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml)：

- **推送 `v*` 标签** → 自动构建 APK → 自动创建 GitHub Release 并挂载 APK
- 仓库页面也可以手动触发（Actions → 该工作流 → Run workflow）

### 需要配置的仓库 Secrets

`Settings → Secrets and variables → Actions → New repository secret`

| Secret | 必填 | 说明 |
|---|---|---|
| `ERP_API_BASE` | **是** | 你的服务器地址，如 `https://erp.example.com`。不配则构建会以「占位符」为由失败 |
| `ANDROID_KEYSTORE_BASE64` | 否 | 签名密钥的base64（`base64 -i release-key.jks -o ks.b64`）。配了就出 release 包 |
| `ANDROID_KEYSTORE_PASSWORD` | 配了 KS 才需 | 密钥库密码 |
| `ANDROID_KEY_ALIAS` | 配了 KS 才需 | 密钥别名 |
| `ANDROID_KEY_PASSWORD` | 配了 KS 才需 | 别名密码 |

**签名密钥可选**：不配则产出 **debug 包**（能装、能用，但性能与体积吃亏，且不能上架）。

```bash
# 本地推送第一个版本
git tag v1.0.0
git push origin v1.0.0
```

> **release 包必须用你自己的签名密钥**，否则每次构建签名都不同，用户无法覆盖升级。
> 一旦对外发布过某个版本，密钥就不能再换了（见上文「签名密钥须知」）。

### 🔴 公开仓库放签名密钥的风险（请务必读完再决定）

签名密钥是这份系统里**最敏感的一项**。GitHub Secrets 是加密存储的，任何人（包括访问你仓库的
陌生人）都看不到明文；但**能触发构建的人，可以让构建过程把密钥写进日志或产物里**。

也就是说：签名密钥一旦配进 Secrets，**仓库的写权限 = APK 的签名权限**。拿到密钥的人
可以伪造一个「官方更新包」发给你的用户，手机装上去不会有任何提示。

如果你打算把仓库设为公开，又想保留自动发布，请至少做到：

| 措施 | 怎么做 |
|---|---|
| **不要邀请协作者** | 公开仓库不需要协作人也能自己维护。协作者 = 你把签名权给了对方 |
| **关闭 fork 与 PR 触发** | `Settings → Actions → General`，取消勾选「允许 fork 仓库创建 pull request」 |
| **把 Actions 默认权限降到只读** | 同页面，「Workflow permissions」选 **Read repository contents permission** |
| **别在 workflow 里 echo 密钥** | 本仓库的 workflow 不会打印任何密钥内容，请不要往里加调试用 `echo $KS_PASS` |
| **定期轮换** | 若怀疑泄露，重新生成密钥库并在 `Settings → Apps` 里重装到手机 |

**完全不想承担这个风险的做法**：把仓库设为私有，Actions 照常自动发布，只有你自己能看到源码。

### 发版铁律

**版本号只有改`versionCode` 是不够的 —— 改完必须重新构建**，否则 APK 里还是旧版本号，
老用户收不到更新提示。`versionCode` 必须**严格大于**线上正在发的那一个。

## 目录结构

```
.
├── server.js                # HTTP 服务与全部 API 路由
├── db.js                    # SQLite 结构定义、密码哈希、种子数据
├── xlsx_util.js             # Excel 生成/解析（零依赖自研实现）
├── order_table.js           # 订单表格与打印数据组装
├── moderation_server.js     # 内容审核服务端接线
├── moderation.js            # 内容检测规则
│
├── public/                  # ← 全部前端静态资源由这里提供
│   ├── index.html           # 单页入口
│   ├── privacy.html         # 隐私政策
│   ├── sw.js                # Service Worker（改前端后须 bump CACHE）
│   ├── css/
│   │   ├── style.css        # 主样式
│   │   ├── login.css        # 登录页
│   │   ├── print_template.css
│   │   └── feedback.css
│   ├── js/
│   │   ├── app.js           # 前端主逻辑
│   │   ├── print_template.js  # 打印模板编辑器
│   │   ├── receipt_preview.js # 小票预览
│   │   ├── feedback.js      # 意见反馈
│   │   ├── apk_download.js  # APK 下载二维码
│   │   ├── qrcode-mini.js   # 精简二维码生成
│   │   └── moderation.js
│   └── img/                 # PWA / favicon 图标
│
├── android-native/          # 原生 Android 工程（Kotlin + Compose）
├── .github/workflows/       # 打 v* tag 自动构建 APK 并建 Release
│
├── Dockerfile               # 精简镜像（node:22-alpine，零 npm 依赖）
├── docker-compose.yml       # ERP + Caddy(HTTPS) 双容器编排
├── Caddyfile                # 自动签发/续期 HTTPS 证书
├── deploy.sh                # 🔵 Docker 一键部署（主推）
├── deploy-systemd.sh        # ⚪ 裸机一键部署（无 Docker 时）
├── .env.example
├── README.md
└── CHANGELOG.md
```

> 🔴 **运行时产物（不入库）**：`data/`（SQLite 数据库，含 `-wal` / `-shm`）、
> `uploads/`（反馈附件）、`backups/`、`tls/`、`.env`、`node_modules/`。

## 安全说明

本仓库是**脱敏版**开源代码，已移除所有真实凭据：

- 无服务器地址、端口、root 密码
- 无签名密钥与签名口令（改由 `keystore.properties` 本地提供）
- 无真实客户名称、门店、商品条码、进货价、订单数据
- 部署与运维脚本（含服务器凭据）不随源码发布

若你是二次使用者，请自行准备环境变量、签名密钥与演示数据。

> ⚠️ 源码为了开箱即用内置了 `admin / admin123`。**公开部署前请务必改掉**，
> 详见上文「默认管理员账号」。

## 许可

MIT