# 简单 ERP — 改动记录（CHANGELOG）

> 本文件由 AI 每次改动后自动追加，倒序排列（最新在最上）。
> 格式：`## 日期 — 标题` + 改了什么 + 为什么 + 影响范围 + 相关文件。
> 手机版（Android 原生）改动请一并记在这里，方便下次接手时知道要动哪些文件。

---

## 2026-10-15 — v5.5.1：修「存为图片」自动弹预览 + 修 /downloads/apk 短链 404

### 1. 手机版订单页「存为图片」保存后不再自动弹预览

**经理反馈**：「点存为图片 → 自动弹出预览 → 预览一直加载不出来。」

**原因**（`ui/main/OrdersScreen.kt`）：保存成功分支里写了
`savedUri = uri; showPreview = true`——把预览浮层强行拉起来。
`ReceiptPreviewDialog` 依赖外层 `previewBmp`，而这条路径只填了 `savedUri` 没填 `previewBmp`，
于是浮层里`bmp = null`，表现就是「一直加载不出来」。

**改法**：删掉 `savedUri = uri; showPreview = true` 两行，只留 toast。
- `savedUri` 变量本身保留——预览层里的「保存后分享」还要用
- 要看渲染效果请点旁边的「订单预览」（那条路径会先 `buildPreview()` 填 `previewBmp` 再开浮层）

### 2. `/downloads/apk` 短链 404（二维码扫不出来）

**这是个一直存在的老问题**，v5.5 加二维码时才暴露：网页版下载条与手机版
「下载手机版」按钮、二维码里的短链全指 `${API_BASE}/downloads/apk`，
但这个路径落到 `serveStatic()` 去找 `public/downloads/apk` 这个**并不存在的文件** → 404。

**改法**（`server.js`，本版唯一服务端改动）：主分发处加一条专用路由
```js
else if (u.pathname === '/downloads/apk') redirectApk(res);
```
`redirectApk()` 读 `public/downloads/update.json`，302 到里面的 R2 预签名地址，
带 `Cache-Control: no-store`。**用 302 不用 307**：APK 是 GET，302 后浏览器/微信正常跟随下载。
读不到 update.json 时返 503 并给中文提示（而不是 404让人以为地址错了）。

> 为什么二维码里不能直接写 R2 预签名 URL：那些链接带 `X-Amz-Expires=604800`（7 天），
> 过期后二维码就扫不出来。短链永不变，由 `refresh_apk_url.sh` 持续把 update.json 指向最新版。

**部署注意**：本次动了 `server.js`，所以**必须重启 erp**。脚本 `deploy_apkfix_v55.py`
的做法是「先`node --check` 预检 →不过就不重启」，避免把跑着的服务搞挂。

### 3. 涉及文件

- Android：`ui/main/OrdersScreen.kt`（存图不再弹预览）、`app/build.gradle`（code 45 / 5.5.1）
- 服务端：`server.js`（`redirectApk()`）
- `refresh_apk_url.sh`：APK/VC/VN → `HePingERP-v5.5.1-release.apk` / `45` / `5.5.1`
- 部署：`deploy_apkfix_v55.py`（服务端补丁）、`publish_native_v551.py`（APK 发布）

---

## 2026-10-15 — v5.5：信任设备免密不再自动跳进系统（两端统一三按钮）+ 设置页账套切换

### 1. 信任设备：免密后停首页，不自动进系统

**问题**：勾了「信任此设备」后，网页/手机每次打开都直接跳进开单页。
人多的时候容易在别人前面误开单，且换账套必须先进系统去设置里点，两端路径还不一样。

**改法**（两端结构一一对应）：
- 网页 `app.js`：`tryTrustedLogin()` 原本拿到 token 就调 `afterAuth()`（内部 `enterApp()`），
  现在只换 token + 拉 `/api/me` 就返回；`afterAuth(stay)` 加 `stay` 参数，
  `stay=true` 时只显示面板不进系统。`boot()` / 登录成功后 / `doLogout()` 都改成停留面板。
- Android `AppNav.kt`：启动Destination 从动态判定改为固定 `login`（网关页）；
  恢复会话 + 设备令牌续期都在 `LaunchedEffect` 里做完置 `booted`，
  但**不导航到 main**；`LoginScreen` 靠 `SessionStore.token != null && loadMe()` 判定进面板。
- Android 续期失败时会 `forgetDevice()` 丢设备令牌，避免每次开 App 都白跑一次失败请求。

**面板三个按钮**（`进入开单` / `选择其他账套` / `更换账号`）：
- 网页新增 `showTrustedPanel/ trustedEnter / trustedPickBook / trustedSwitchAcct / trustedUnbind`
- Android 新增 `TrustedPanel()` + `BookRow()`，`AuthViewModel` 加 `me / loadMe / switchBook / unbindSelf`

**顺带**：两端都在面板底部给了「关闭本设备的免密信任」。
无痕/隐私窗口关掉后进不了设置页，只能从这里关，否则服务端 `trusted_devices` 那条记录
会一直占着 4 台上限的名额。网页版设置页也加了同一个入口（`td-self`）。

### 2. 设置页账套切换（两端）

- 网页：设置页加账套列表，调用已有的 `POST /api/books/switch`
- Android `SettingsViewModel`：加 `loadBooks / switchBook / unbindSelf`；
  `SettingsScreen` 加「账套切换」分区（**所有角色可用**，不是只有老板）
- 🔴 切换成功后必须重启 App：账套一变，商品/订单/客户全按 `book_id` 隔离，
  各 ViewModel 内存里缓存的旧账套数据必须丢掉。Android 用
  `restartApp()`（`NEW_TASK|CLEAR_TASK` 重建 `MainActivity`）。
- Android 新增 `SessionStore.forgetDevice()`：只删设备令牌，不动主题/打印机等本地偏好
  （`clear()` 会把 DataStore 全清，用户设好的主题会丢）

### 3. 两端互跳 + 首页下载移动版

- 网页首页新增下载条 `.lp-dlbar`（按钮 + 二维码），位置在信任面板/登录卡片**之外**，
  登录前后都可见。`apk_download.js` 改成`['apk-qr','apk-qr-login']` 统一 forEach 渲染，
  绑两个入口 `['apk-dl','apk-dl-login']`，不再写死单个 id。
- Android 登录页顶部「打开网页版」、面板底部「改用网页版」、登录表单底部「下载手机版（安卓 APK）」
- Android 设置页新增「网页版」分区（打开网页版 + 显示地址）与「信任设备」分区

### 4. 涉及文件

**网页**：`index.html`（`#trusted-panel` / `.lp-dlbar`）、`app.js`、`login.css`（+4274B）、
`apk_download.js`、`sw.js`（`erp-shell-v28` → `v29`）
**Android**：`ui/login/LoginScreen.kt`（重写）、`ui/Nav/AppNav.kt`、
`vm/AuthViewModel.kt`、`ui/main/SettingsScreen.kt`、`ui/main/SettingsViewModel.kt`、
`data/Api.kt`（加 `forgetDevice()`）、`app/build.gradle`（code 43→44 / 5.4→5.5）
**测试**：`_regress/trusted_login.js`（95 项）、`_regress/apk_login_entry.js`（20 项新增）
**部署**：`deploy_web_v55.py`、`fix_sh_v55.py`、`publish_native_v55.py`

### 5. 注意

- 本版**服务端零改动**（`/api/me` 已返回 `books`+`account`，`/api/books/switch` 早已存在），
  部署只传静态前端，不重启 `erp` 服务
- `refresh_apk_url.sh` 的 `APK/VC/VN` 三项已同步到 v5.5/44/5.5，
  上传后转 LF 并 `bash -n` 预检通过（见 `fix_sh_v55.py`）
- APK 31.8MB（原生版特征；< 10MB 就是 Capacitor 套壳，禁止发布）

---

## 2026-10-15 — v5.3：正式品牌图标上线 + 退货单负数口径全链路修复

### 1. 品牌图标（网页 + Android 共用同一套）

**设计**：绿色渐变圆角方块 + 白色小票（顶部锯齿撕口 + 两行镂空文字线 + 底部条码块）。
与网页原有主色 `--primary:#07c160` 保持一致。小票同时暗喻「开单」和「打印」。

**为什么不用 AI 生图**：试过 ImageGen，生成结果方向不符、图上带「AI生成 WORKBUDDY」
水印、小票内部元素糊成一团。改用 Pillow 程序化矢量绘制，可控、可复现、无水印。

**产物**（`erp_work/gen_logo.py` 一键生成）：
- 网页：`logo_1024/512/256/192/128.png`、`favicon.ico`(16~256)、`favicon-32.png`、
  `apple-touch-icon.png`（180）
- Android：`mipmap-{m,h,x,xx,xxx}dpi/ic_launcher.png` + `ic_launcher_round.png`；
  `drawable-*/ic_launcher_foreground.png`（透明底小票，落中心 66% 安全区）
  + `ic_launcher_background.png`（渐变底）
  + `ic_launcher_monochrome.png`（Android 13+ 主题图标，单色 alpha 掩膜）
- 自适应图标：`mipmap-anydpi-v26/ic_launcher.xml`、`ic_launcher_round.xml`

**关键改动**：
- `AndroidManifest.xml`：`android:icon` 从 `@android:drawable/ic_menu_compass`
  （系统指南针占位图）改为 `@mipmap/ic_launcher`，并新增 `android:roundIcon`
- 网页 `index.html`：加 favicon / apple-touch-icon；侧栏品牌改「图标 + 简单ERP」；
  **侧栏新增「手机版」下载卡片**（图标 + 文案 + 二维码 + 直接下载链接）；
  顶栏新增「手机版」入口按钮（≤1100px 自动收起文字只留图标）
- 网页 `style.css`：新增 `.side-brand/.side-logo/.side-apk/.apk-btn/.apk-qr/.topbar-apk`
- `sw.js` 缓存版本 → `erp-shell-v22`（🔴 不 bump 用户就拿旧缓存看不到新图标）

**二维码**：`js/qrcode-mini.js`（71KB，MIT，由 npm `qrcode` + `dijkstrajs` 打包成
单文件 UMD）。内容用稳定短链 `${API_BASE}/downloads/apk`；
**不能用 R2 预签名 URL**（7 天后过期，二维码就废了）。
下载时 `js/apk_download.js` 现读 `downloads/update.json` 拿最新地址，取不到则回落短链。
与官方 `qrcode` 库逐模块比对 4 组用例，全部 `diff=0`。

**踩坑**：手写 QR 编码器失败两次 —— ISO 18004 纠错块表记错、v≥4 多纠错块交织未实现、
格式信息与掩码选择和官方不一致（diff=312）。**直接打包官方核心模块，别手写**。

### 2. 退货单：标题错 + 金额符号错（全链路修复）

**根因（服务端）**：`POST /api/orders` 完全忽略 `type='return'`，
原生端传了 `type:'return'` 照样建成 `XS` 开头的正数销售单、`status='paid'`。
所以前端 `isReturn` 判据虽然写对了，但永远拿到销售单数据 → 标题印成「销售订单」。

**服务端 `server.js` 修复**：
- `const isReturn = b.type === 'return'`，数量 `Math.abs()` 后按 `sign = isReturn ? -1 : 1` 统一翻负
- 单号 `TH` 前缀（`genReturnNo`）、`status='returned'`（强制，客户端传什么都改不了）
- `orders.total/payable` 为负，`order_items.qty/subtotal` 为负
- 库存 `qty + (-sq)` → 退货**回补**库存
- `if (!isReturn) recordCustomerPrices(...)` → 退货**不写历史价**，不污染下次开单取价
- `PUT /api/orders/:id` 同样按 `o.status==='returned'` 判 `sign`，编辑退货单状态锁死 `returned`
- `DELETE /api/orders/:id` 按 `o.return_of` 判符号，删退货单要扣回库存

**客户端统一判据**（两端一致）：
```js
const isRet = (o.status === 'returned') || Number(o.total || 0) < 0;
```

**原生 App 修复（v5.3）**：
| 文件 | 改了什么 |
|---|---|
| `SaleViewModel.printLast()` | 不再用内存 `cart`/`isReturnMode`（都是易失状态，clearCart 后印空单），改回查 `orderDetail(orderId)`，用服务端 `status`+负金额判定 |
| `SaleViewModel.prepareEdit()` | 编辑退货单时**保持退货模式**（原来一律 `mode="sale"`，界面显示「应收」而非「应退」） |
| `SaleViewModel.toEditLine()` | 装车时 `qty` 取 `abs()`，否则退货单进编辑器显示「-2 件」，再提交会被服务端再翻一次负号 |
| `BtPrinter.EscPos.receipt()` | 删掉明细 `qty/price` 与合计 `beforeDiscount/payable` 的 `abs()`，原样透传负数 |
| `ReceiptImage.render()` | 同上，且明细金额优先用服务端 `subtotal`（原来 `qty*price` 自己算） |
| `OrdersScreen.printOrder()` / `buildPreview()` | 页脚按类型切换：退货印「退货单已入账，感谢配合」，不再印「谢谢惠顾」 |

🔴 **这类「金额取绝对值」的坑两处都有**：明细行和合计行。只改一处会出现
「每行都是正的、合计是负的」自相矛盾小票。

**验证**：服务端端到端建退货单 → `TH20261015-0001`、`total=-10`、`payable=-10`、
`status=returned`、明细 `qty=-2 / subtotal=-10`；库存 `-96 → -94`（回补 2 件），
删测试单后回到 `-92`。

### 2b. 顺带查出并修掉一个更隐蔽的库存 bug（v5.3 端到端测试才暴露）

端到端跑「建退货单 → 编辑 → 删除」后发现**库存删完不复原**，顺着查出三处：

| 位置 | 原写法 | 问题 | 修正 |
|---|---|---|---|
| `PUT /api/orders/:id` 撤销旧明细 | `UPDATE inventory SET qty=qty-? ` 传 `r.qty` | 建单时影响是 `qty += -sq`，这里又`+= -r.qty` = **把影响再施加一次**。编辑一次销售单 → 库存双倍扣；编辑一次退货单 → 库存双倍回补 | 改成 `qty=qty+?` 传 `r.qty`，直接撤销。明细 qty 自带符号，销售/退货方向自动对 |
| `DELETE /api/orders/:id` | `sign = o.return_of ? -1 : 1` 再乘 qty | 明细 qty 本身已带符号（销售正、退货负），再按 `return_of` 翻一次符号 = 反向 | 去掉 sign，直接 `qty += r.qty` |
| `POST /api/orders/delete-batch` | 同上 | 同上 | 同上 |

🔴 **铁律**：库存回补/扣减**永远只用明细 qty 的符号**，不要再叠加「这是不是退货单」
的第二层判断。`return_of` 那套是早期挂原单退款的遗留，与现在的管家婆式退货单（负数明细）
是两套口径混用了。

另外按老铁律补上了**删单连带清 `customer_prices`**（单个删 + 批量删两处）：
错误单价一旦落库就会污染该门店后续所有开单取价。

**验证（第二次跑，全绿）**：
```
建退货单   TH20261015-0001  total=-7.6  payable=-7.6  status=returned
明细       qty=-2  subtotal=-7.6  barcode=6934665010545
库存       3 → 5   delta=+2      ← 退货回补，正确
两端判据   isRet=true → 标题「销售退货」 页脚「退货单已入账，感谢配合」
历史价     customer_prices 未新增行  ← 退货不污染取价
编辑(2→3) total=-11.4  status=returned（客户端传 status:'paid' 也被锁死）
           明细 qty=-3 subtotal=-11.4
删除       inv_final=3inv_restored=true  ← 库存完全复原
```

### 3. 版本与产物

- 原生 **v5.3 / versionCode 42**（v5.2=41）
- 产物 30.33MB（`BUILD SUCCESSFUL in 2m`，JDK 21 + `assembleRelease`）
- 校验：`package=com.heping.erpnative`、无 `assets/public`（纯原生）、
  `application-icon-*` → `res/BW.xml`（自适应图标生效）、md5 `b950e4ea54bacd489f80bbc6e91ef04f`
- 公网已验证：`curl` 下载 R2 链接得 31,805,637 字节、md5 与本地一致
- 网页端缓存 `erp-shell-v23`（v22 上下发了图标与下载入口，v23 修退货负数）
- 交付副本：`outputs/HePingERP-v5.3-release.apk`

**踩坑（本轮新增）**：
- `trusted_devices` 列名是 `device`（不是 `device_name`），且必填 `account_id` +
  `expires_at`；插了记录还要先 `POST /api/trusted-login` 换 session token 才能调业务接口
- `bash -c "... node -e \"...\""` 内联长 JS 会被 shell 转义吃掉（括号/引号），
  测试脚本一律落 `.js` 文件再执行
- `Python -c` 里写含 `&` 的布尔掩码（`a & b & c`）会被 bash 当后台符吞掉，
  同样要落 `.py` 文件

---
## 2026-10-15 — 订单编辑改为跳开单界面自由修改 + 修复订单打印条码缺失

本次三项改动（服务器源码 + 网页端 + Android 原生 App 同步）。

### 1. 订单编辑：不再限制「只能改数量金额」

**改动前**：App 订单列表左滑「编辑」→ 弹出 `EditOrderDialog`，只能改每条的数量/单价、
删行、改整单折扣和备注。**不能增删商品、不能改单品折扣**，店员要加一个商品只能
「复制为新单」再重录一遍。

**改动后**：点「编辑」→ 直接跳到**开单界面**，原单明细已装进购物车，可以：
- 增删任意商品（跟平时开单完全一样，搜名字/条码都行）
- 改数量、改单价、改每一行的单品折扣
- 改整单折扣
- 保存后**单号不变**（走 `PUT /api/orders/{id}` 整单替换明细，不是新建）
- 顶部有蓝色提示条 + 「放弃修改」出口；编辑期间隐藏「销售单/退货单」切换
  （防止切模式把购物车清空，正在改的单子就没了）

**技术要点**：
- `SaleViewModel` 新增编辑态：`editingOrderId / editingOrderNo / editingOrderDate /
  editingStatus`，`prepareEdit(id)` 装车、`cancelEdit()` 放弃
- `submit()` 分流：`editingOrderId != null` → `PUT /api/orders/{id}`，否则 `POST /api/orders`
- **回填折前单价**：有单品折扣的行 `price` 是折后价，必须用 `orig_price` 回填，
  否则「再存一次就折两次」
- **商品被删也要保住那一行**：`toEditLine()` 找不到商品时用明细快照临时造一个
  `Product`，否则编辑一次会静默吞掉门店买过的商品
- `MainScreen` 把 tab 状态提上来做跨页跳转：订单页 `onEditOrder(id)` → 装车 → 切
  `Tab.Sale`；开单页 `onEditingDone()` → 跳回订单页并刷新列表
- 旧的 `EditOrderDialog` / `OrdersViewModel.edit` / `saveEdit` 全部删除（避免两套编辑逻辑）

### 2. 修复订单打印条码缺失（两个现象同一个根因）

**现象 A**：第一次打印（小票）只有部分商品有条码，而且有的打的是 `106g` 这种规格。
**现象 B**：在订单页面点击打印，全部没有条码。

**根因（数据实证）**：
| 字段 | 实际情况 |
|---|---|
| `products.barcode` | **107/109** 有值，是真条码（如 `6901209212895`） |
| `products.spec` | 只有 **8** 有值，装的是**规格文字**（`106g` / `180ml` / `200ml`） |
| `order_items.spec` | 开单时从 `products.spec` 快照 → 大量为空或规格文字 |
| `order_items.barcode` | **以前根本没有这一列** |

所以：
- 第一次打印取 `product.spec ?: product.barcode` → 少数 spec 有值的商品打出了规格文字，
  其余 fallback 到 barcode 才对 → 「有但不全、且不对」
- 第二次打印取 `it.spec`（来自 `order_items.spec`）→ **必然拿不到条码** → 全无

**修复**：
1. `db.js`：`order_items` 新增 `barcode TEXT` 列（`ensureColumn` 迁移，自动建列）
2. `server.js`：**4 处** `INSERT INTO order_items` 全部带上 `L.prod.barcode`
   （开单 / 编辑保存 / 整单退货 / 部分退货）
3. `server.js` `GET /api/orders/:id`：明细 `barcode` 为空时**用商品表回填**
   （只改返回值不改历史行）→ **老订单也能打出条码，不用重录**
4. 原生 App + 网页端打印：**条码只取 `barcode` 字段，不再拿 `spec` 冒充**

**实测**（订单 XS20261014-0021，9 个商品）：修复前 0/9 有条码 → 修复后 **8/9**。
剩下 1 个是「燕塘180ml*4盒乳酸奶」，因为**商品表里它自己的 barcode 就是空的**（数据缺失，
不是代码问题，需人工补录）。

### 3. 服务器留档（本次新增机制）

按经理要求：`/www/erp/` 下建立本文件 + `_source_latest/` 存最新版源码。

**用途边界（重要）**：
- ✅ **仅供下次改动时查阅参考** —— 想知道「线上到底跑的是哪版代码」「某功能当初为什么这么改」
- ❌ **不参与任何分发/更新** —— APK 更新走 `/www/erp/public/downloads/` +
  R2 + `update.json`，与本目录无关；网页端直接由 `/www/erp/public/` 提供

---

## 2026-10-14 — 单品折扣功能（有的单品打折，有的不打折）

- `order_items` 加 `discount`（单品折扣率）/ `orig_price`（折前单价）
- `customer_prices` 加 `discount` → 门店×商品的折扣记忆
- 后端 `POST/PUT /api/orders` 支持 `items[].discount`；**一旦传了 discount，
  `price` 就按「折前单价」处理**，成交价 = price × discount；不传 discount 则
  `price` 即成交价（兼容旧调用方/表格批量开单）
- `GET /api/customer-prices` 返回 `item_discounts`，选客户时自动套用上次折扣
- 原生 App 开单每行有独立折扣输入框

## 2026-10-14 — 商品条码归位 + 牛角包参数统一

- `products.spec` 里的条码归位到 `products.barcode`（104/107 条）
- 某品牌牛角包 7 个商品参数统一（pack/成本/售价见下表，已脱敏）

## 2026-10-09 — 开单模板加合计（件数）列与列宽优化

- 列宽 A=min(46,max(22,最长商品名+2.5))、门店列 min(18,max(11,..))、合计 9 / 件数 15
- 「合计（件数）」= `ROUNDUP(EA ÷ pack_size)`；件数总计必须 `SUM(各行件数)`，
  **不能** `ROUNDUP(Σ(EA/箱),0)`（ROUNDUP 不满足分配律会少算）
- 导入口径：订单数量 = 门店列填写的 EA 原样导入；「合计」「合计（件数）」两列只作参考

## 2026-10-03 — Android 原生版（Jetpack Compose）替代 Capacitor 套壳

- **唯一正确源码**：仓库根目录下的 `android-native/`
  （包名 `com.heping.erpnative`，产物约 32MB）
- ⚠️ 本工作区的 `android_build/` 是已废弃的 Capacitor WebView 套壳（产物仅 3.2MB），
  **禁止再发布**。判别法：产物 < 10MB = 套壳，立刻停手。
- ⚠️ 编译必须 **JDK 21**（JDK 17 会报「无效的目标发行版: 21」）


## 2026-10-15 — 网页版：订单预览 / 保存为图片 + 小票底部 2cm 签收留白

**v5.0（versionCode 39）原生版同步上线**

### 新增功能
1. **订单详情新增「📄 订单预览」「💾 保存为图片」两个按钮**
   - 预览 = 与打印完全一致的版式（含 EAN-13 图形条码 + 数字行）
   - 存图 = 3x 精度（≈230dpi）PNG，优先调起系统分享（微信/QQ/邮件），
     不支持 `navigator.canShare` 时降级为下载
   - 用途：把电子单直接发给客户
2. **打印小票底部预留 2cm 空白**（方便纸质单据上手写签收）
   - 三处都实现了，别只改一处：
     - 网页 Canvas 预览/存图：`TAIL_MM = 20`，画完后回扫墨迹裁到「墨迹底 + 20mm」
     - 网页 DOM 打印浮层：`css .receipt .sign-tail { height: 2cm }`
     - ESC/POS 蓝牙打印：`ESC d 160`（203dpi 下 2cm ≈ 160 点）+ 2 行 LF 兜底
3. **商品管理页条码框右边新增「扫码」按钮**（原生版）
   - CameraX 取景 + ML Kit 条码识别（bundled，离线可用）
   - 支持 EAN-13/EAN-8/UPC-A/UPC-E/CODE-128/CODE-39/ITF/QR/DATA_MATRIX
   - 识别到即自动填入条码框并自动关闭窗口；同码 1200ms 冷却防连扫
4. **ESC/POS 明细行补 EAN-13 图形条码**（`GS ( k` m=65 格式 1，宽 1 高 2）

### 🔴 本轮踩坑（下次改务必先看）

1. **列宽必须减掉左右边距再除列数**
   ```js
   CONTENT_W = PAPER_W - SIDE_MM * 2 * MM;   // SIDE_MM = 4
   COL_W = CONTENT_W / COLS;                // 48 列
   ```
   早期用 `PAPER_W / COLS` 算列宽，48 列画完正好等于纸宽，再加左边距就溢出，
   右侧金额被裁到只剩「84.」「64.」。

2. **画布高度估算宁高勿矮（估矮会把 2cm 留白吃掉）**
   初始高度 = 估算值 × 4，绘制后用 `getImageData()` 回扫墨迹底部，
   再裁到「墨迹底 + TAIL_MM」。若估矮了，`Math.min(cv.height, ...)` 会把裁剪卡死，
   底部留白变成「估算与实际的差值」（实测出现过 0.1mm / 8.4mm / **8.8cm** 假留白）。

3. **中文逐字定位绘制 + 超格缩放**
   中文占 2 个半角列，但中文字面宽 ≠ 2×Courier 半角宽。必须逐字按列定位绘制，
   超格时 `ctx.scale()`（网页）/ `textSize`（原生）压到格宽 94% 并居中，
   否则「往来单位名称」那行会糊成一团。

4. **统一走 SFTP 直传，别用 `echo '<base64>' | base64 -d`**
   app.js 有 161KB，base64 后超过 `ARG_MAX`，直接报 `Argument list too long`。
   `ssh103b.put()` 已改成 SFTP + sha256 校验 + 原子 mv。

5. **原生端 Kotlin 坑**
   - `Preview.surfaceProvider` 只有 setter 没有 getter，Kotlin 合成属性用不了
     （`Unresolved reference`），必须 `preview.setSurfaceProvider(pv.surfaceProvider)`。
   - `Order` 模型的时间字段名是 `created_at`（不是 `createdAt`）。
   - `s.sumOf { if (isWide(it)) 2 else 1 }` 会触发 `sumOf` 重载歧义，改显式 for 循环。
   - 局部 `fun` 的默认参数不能用命名参数跳过中间形参之外的类型不匹配，
     `textAt(nums, baseline, "right")` 要写成 `textAt(nums, baseline, align = "right")`。

6. **服务 worker 缓存必须 bump**
   `CACHE: erp-shell-v17 → erp-shell-v18`，`SHELL` 新增 `/js/receipt_preview.js`。
   不 bump 用户浏览器会继续吃旧缓存。

### 条码字段铁律（重申）
打印条码**只取 `barcode`**（真条码，106/107 个商品有值），
`spec` 装的是规格文字（`106g`/`180ml`），**永远不能拿来打印条码**。

### 新增文件
- `public/js/receipt_preview.js` — 预览/存图模块，暴露 `window.ERPReceipt`
  （`open` / `download` / `draw` / `newCanvas` / `ean13Bits`）
- 原生 `ui/main/ReceiptImage.kt` — Bitmap 版小票渲染 + 相册存储 + 分享
- 原生 `ui/main/ScannerDialog.kt` — 通用扫码框


---


## 2026-10-15 (晚) — v5.1：去掉条形码图形 + 三处 UI 修复（经理反馈）

### 1. 小票不再打印条形码图形，只显示条码「数字」
经理确认：**80mm 热敏小票上条形码图形又粗又占地方，实际没人扫，纯浪费纸**。
- 网页 Canvas 预览/存图、网页 ESC/POS、原生 Bitmap 预览、原生 ESC/POS 四处全部去掉图形绘制
- 排版改成「条码:6901209212895」紧贴左列，右边数量/单价/金额靠右
  （比原来「数字独占左 27 列」紧凑，也省一行高度）
- `ean13Bits()` / `putEan13()` / `EscBuilder.ean13()` **函数保留但不再调用**，
  将来若要图形条码，改回调用即可（重新上线前先跟经理确认）

### 2. 订单列表左划右划更自然（原来要划很大范围才出按钮）
根因：触发阈值写成 `offsetPx > startPx * 0.4f`，startPx=228dp 时阈值高达 **91dp**，
手要划掉半个屏幕才展开。
修复（`ui/components/SwipeRow.kt`）：
- 阈值改成**固定 12.dp**，不再跟区域宽度挂钩
- 新增 **fling 判定**：速度 > 800px/s 时，扫一下就直接展开/收起，不看幅度
- 顺手把 `Animatable` spring 换成直接 settle（更跟手，少一层动画延迟）

### 3. 滑动按钮间空白太多
根因：`SwipeAction` width=76.dp 但圆图标只有 40.dp，左右各空 18.dp，
三个按钮排一起中间像隔着一条河。
修复：width 收到 **56.dp**、padding 只留 1.dp、图标 20.dp、文字 10.sp，
外层加一层淡色圆角底板（视觉分组更清楚）。
区域宽度同步收窄：228→170 / 152→114 / 76→56。

### 4. 订单预览界面四行按钮遮挡预览
根因：`AlertDialog` + 图片 `fillMaxWidth` 不限高 + 保存/分享/提示×2 全挤在下面。
修复（原生 `ReceiptImage.kt` 的 `ReceiptPreviewDialog`）：
- 弃用 AlertDialog，改成**全屏浮层 + Card**（92% 高）
- 图片用 `aspectRatio` 按原始比例自适应，外面套 `weight(1f)` 吃掉剩余高度
- 按钮压成**一行**（保存图片 / 分享给客户，高 42dp）
- 两行提示合并成一行 10sp 小字，关闭改成右上角圆形 ✕ 钮

订单详情弹窗同步收紧：
- 四行按钮 → **两行**（第一行：打印小票 / 订单预览 / 存为图片，三等分；第二行：修改订单）
- 商品列表区 260.dp → 200.dp，给按钮腾空间
- 按钮文案去掉 emoji（「📄 订单预览」→「订单预览」），字号 12.sp，高 40 / 36dp

### 网页端同步
- `receipt_preview.js` 预览浮层同样重做：
  标题栏（带 ✕）+ `flex:1` 滚动区 + 一行按钮 + 一行提示
- 表头「序号 名称 / 商品条码」→「序号 名称 / 条码」

---


## 2026-10-15 (上午) — v5.2：修复订单预览浮层被遮挡

### 问题现象
经理反馈「订单预览会隐藏在页面后面」——点「订单预览」看不到浮层。

### 根因：v5.1 的改法把独立窗口特性丢了
v5.1 为了解决「四行按钮遮挡预览」，把 `ReceiptPreviewDialog` 从
`AlertDialog` 改成「全屏浮层 + Card」，也就是一个**普通的 `Box`**。

但订单详情用的是 **`AlertDialog`，它自己就是一个独立的 Android 窗口**，
层级盖在主窗口之上。而预览浮层属于主窗口 → **必然被订单详情弹窗压在下面**。

这是 Compose 的经典陷阱：
- `AlertDialog` / `Dialog` = 新窗口，层级靠窗口 Z-order 决定
- 普通 `Box` = 主窗口里的一个节点，**永远出不了一个正在显示的 Dialog 窗口之上**

### 修复：保留独立窗口，只换布局（`ReceiptImage.kt`）
```kotlin
Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)   // 🔴 必须 false
) {
    Box(Modifier.fillMaxSize().background(...).clickable { onDismiss() }) {
        Card(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f)
             .clickable(enabled = false) {}) { ... }                 // 🔴 吃掉点击，别冒泡
    }
}
```
- `Dialog` 是独立窗口，后创建 → 层级在 `AlertDialog` 之上，遮挡问题解决
- `usePlatformDefaultWidth = false`：不加的话系统会把内容包进默认 ~280dp 容器，撑不满
- 暗区`.clickable{onDismiss}` 让「点空白关闭」符合直觉
- 卡片上再挂一个 `.clickable(enabled = false) {}` **吃掉点击**，
  否则点图片会冒泡到暗区把弹窗直接关掉（Compose 里 `enabled=false` 仍会消费事件）

### 网页端同步加固
`receipt_preview.js` 的 `#rc-overlay` 显式提到 **z-index:70**
（通用 `.overlay` 类是 50，toast 是 99，扫码层是 99999）。
原来两个浮层都是 50、靠 DOM 顺序决定谁在上——太脆弱，显式提级更保险。

---


## 2026-10-15 — 网页版：订单预览 / 保存为图片 + 小票底部 2cm 签收留白

**v5.0（versionCode 39）原生版同步上线**

### 新增功能
1. **订单详情新增「📄 订单预览」「💾 保存为图片」两个按钮**
   - 预览 = 与打印完全一致的版式（含 EAN-13 图形条码 + 数字行）
   - 存图 = 3x 精度（≈230dpi）PNG，优先调起系统分享（微信/QQ/邮件），
     不支持 `navigator.canShare` 时降级为下载
   - 用途：把电子单直接发给客户
2. **打印小票底部预留 2cm 空白**（方便纸质单据上手写签收）
   - 三处都实现了，别只改一处：
     - 网页 Canvas 预览/存图：`TAIL_MM = 20`，画完后回扫墨迹裁到「墨迹底 + 20mm」
     - 网页 DOM 打印浮层：`css .receipt .sign-tail { height: 2cm }`
     - ESC/POS 蓝牙打印：`ESC d 160`（203dpi 下 2cm ≈ 160 点）+ 2 行 LF 兜底
3. **商品管理页条码框右边新增「扫码」按钮**（原生版）
   - CameraX 取景 + ML Kit 条码识别（bundled，离线可用）
   - 支持 EAN-13/EAN-8/UPC-A/UPC-E/CODE-128/CODE-39/ITF/QR/DATA_MATRIX
   - 识别到即自动填入条码框并自动关闭窗口；同码 1200ms 冷却防连扫
4. **ESC/POS 明细行补 EAN-13 图形条码**（`GS ( k` m=65 格式 1，宽 1 高 2）

### 🔴 本轮踩坑（下次改务必先看）

1. **列宽必须减掉左右边距再除列数**
   ```js
   CONTENT_W = PAPER_W - SIDE_MM * 2 * MM;   // SIDE_MM = 4
   COL_W = CONTENT_W / COLS;                // 48 列
   ```
   早期用 `PAPER_W / COLS` 算列宽，48 列画完正好等于纸宽，再加左边距就溢出，
   右侧金额被裁到只剩「84.」「64.」。

2. **画布高度估算宁高勿矮（估矮会把 2cm 留白吃掉）**
   初始高度 = 估算值 × 4，绘制后用 `getImageData()` 回扫墨迹底部，
   再裁到「墨迹底 + TAIL_MM」。若估矮了，`Math.min(cv.height, ...)` 会把裁剪卡死，
   底部留白变成「估算与实际的差值」（实测出现过 0.1mm / 8.4mm / **8.8cm** 假留白）。

3. **中文逐字定位绘制 + 超格缩放**
   中文占 2 个半角列，但中文字面宽 ≠ 2×Courier 半角宽。必须逐字按列定位绘制，
   超格时 `ctx.scale()`（网页）/ `textSize`（原生）压到格宽 94% 并居中，
   否则「往来单位名称」那行会糊成一团。

4. **统一走 SFTP 直传，别用 `echo '<base64>' | base64 -d`**
   app.js 有 161KB，base64 后超过 `ARG_MAX`，直接报 `Argument list too long`。
   `ssh103b.put()` 已改成 SFTP + sha256 校验 + 原子 mv。

5. **原生端 Kotlin 坑**
   - `Preview.surfaceProvider` 只有 setter 没有 getter，Kotlin 合成属性用不了
     （`Unresolved reference`），必须 `preview.setSurfaceProvider(pv.surfaceProvider)`。
   - `Order` 模型的时间字段名是 `created_at`（不是 `createdAt`）。
   - `s.sumOf { if (isWide(it)) 2 else 1 }` 会触发 `sumOf` 重载歧义，改显式 for 循环。
   - 局部 `fun` 的默认参数不能用命名参数跳过中间形参之外的类型不匹配，
     `textAt(nums, baseline, "right")` 要写成 `textAt(nums, baseline, align = "right")`。

6. **服务 worker 缓存必须 bump**
   `CACHE: erp-shell-v17 → erp-shell-v18`，`SHELL` 新增 `/js/receipt_preview.js`。
   不 bump 用户浏览器会继续吃旧缓存。

### 条码字段铁律（重申）
打印条码**只取 `barcode`**（真条码，106/107 个商品有值），
`spec` 装的是规格文字（`106g`/`180ml`），**永远不能拿来打印条码**。

### 新增文件
- `public/js/receipt_preview.js` — 预览/存图模块，暴露 `window.ERPReceipt`
  （`open` / `download` / `draw` / `newCanvas` / `ean13Bits`）
- 原生 `ui/main/ReceiptImage.kt` — Bitmap 版小票渲染 + 相册存储 + 分享
- 原生 `ui/main/ScannerDialog.kt` — 通用扫码框


## 2026-10-15 — 网页版：订单预览 / 保存为图片 + 小票底部 2cm 签收留白

**v5.0（versionCode 39）原生版同步上线**

### 新增功能
1. **订单详情新增「📄 订单预览」「💾 保存为图片」两个按钮**
   - 预览 = 与打印完全一致的版式（含 EAN-13 图形条码 + 数字行）
   - 存图 = 3x 精度（≈230dpi）PNG，优先调起系统分享（微信/QQ/邮件），
     不支持 `navigator.canShare` 时降级为下载
   - 用途：把电子单直接发给客户
2. **打印小票底部预留 2cm 空白**（方便纸质单据上手写签收）
   - 三处都实现了，别只改一处：
     - 网页 Canvas 预览/存图：`TAIL_MM = 20`，画完后回扫墨迹裁到「墨迹底 + 20mm」
     - 网页 DOM 打印浮层：`css .receipt .sign-tail { height: 2cm }`
     - ESC/POS 蓝牙打印：`ESC d 160`（203dpi 下 2cm ≈ 160 点）+ 2 行 LF 兜底
3. **商品管理页条码框右边新增「扫码」按钮**（原生版）
   - CameraX 取景 + ML Kit 条码识别（bundled，离线可用）
   - 支持 EAN-13/EAN-8/UPC-A/UPC-E/CODE-128/CODE-39/ITF/QR/DATA_MATRIX
   - 识别到即自动填入条码框并自动关闭窗口；同码 1200ms 冷却防连扫
4. **ESC/POS 明细行补 EAN-13 图形条码**（`GS ( k` m=65 格式 1，宽 1 高 2）

### 🔴 本轮踩坑（下次改务必先看）

1. **列宽必须减掉左右边距再除列数**
   ```js
   CONTENT_W = PAPER_W - SIDE_MM * 2 * MM;   // SIDE_MM = 4
   COL_W = CONTENT_W / COLS;                // 48 列
   ```
   早期用 `PAPER_W / COLS` 算列宽，48 列画完正好等于纸宽，再加左边距就溢出，
   右侧金额被裁到只剩「84.」「64.」。

2. **画布高度估算宁高勿矮（估矮会把 2cm 留白吃掉）**
   初始高度 = 估算值 × 4，绘制后用 `getImageData()` 回扫墨迹底部，
   再裁到「墨迹底 + TAIL_MM」。若估矮了，`Math.min(cv.height, ...)` 会把裁剪卡死，
   底部留白变成「估算与实际的差值」（实测出现过 0.1mm / 8.4mm / **8.8cm** 假留白）。

3. **中文逐字定位绘制 + 超格缩放**
   中文占 2 个半角列，但中文字面宽 ≠ 2×Courier 半角宽。必须逐字按列定位绘制，
   超格时 `ctx.scale()`（网页）/ `textSize`（原生）压到格宽 94% 并居中，
   否则「往来单位名称」那行会糊成一团。

4. **统一走 SFTP 直传，别用 `echo '<base64>' | base64 -d`**
   app.js 有 161KB，base64 后超过 `ARG_MAX`，直接报 `Argument list too long`。
   `ssh103b.put()` 已改成 SFTP + sha256 校验 + 原子 mv。

5. **原生端 Kotlin 坑**
   - `Preview.surfaceProvider` 只有 setter 没有 getter，Kotlin 合成属性用不了
     （`Unresolved reference`），必须 `preview.setSurfaceProvider(pv.surfaceProvider)`。
   - `Order` 模型的时间字段名是 `created_at`（不是 `createdAt`）。
   - `s.sumOf { if (isWide(it)) 2 else 1 }` 会触发 `sumOf` 重载歧义，改显式 for 循环。
   - 局部 `fun` 的默认参数不能用命名参数跳过中间形参之外的类型不匹配，
     `textAt(nums, baseline, "right")` 要写成 `textAt(nums, baseline, align = "right")`。

6. **服务 worker 缓存必须 bump**
   `CACHE: erp-shell-v17 → erp-shell-v18`，`SHELL` 新增 `/js/receipt_preview.js`。
   不 bump 用户浏览器会继续吃旧缓存。

### 条码字段铁律（重申）
打印条码**只取 `barcode`**（真条码，106/107 个商品有值），
`spec` 装的是规格文字（`106g`/`180ml`），**永远不能拿来打印条码**。

### 新增文件
- `public/js/receipt_preview.js` — 预览/存图模块，暴露 `window.ERPReceipt`
  （`open` / `download` / `draw` / `newCanvas` / `ean13Bits`）
- 原生 `ui/main/ReceiptImage.kt` — Bitmap 版小票渲染 + 相册存储 + 分享
- 原生 `ui/main/ScannerDialog.kt` — 通用扫码框


## 2026-10-15 — 网页版：订单预览 / 保存为图片 + 小票底部 2cm 签收留白

**v5.0（versionCode 39）原生版同步上线**

### 新增功能
1. **订单详情新增「📄 订单预览」「💾 保存为图片」两个按钮**
   - 预览 = 与打印完全一致的版式（含 EAN-13 图形条码 + 数字行）
   - 存图 = 3x 精度（≈230dpi）PNG，优先调起系统分享（微信/QQ/邮件），
     不支持 `navigator.canShare` 时降级为下载
   - 用途：把电子单直接发给客户
2. **打印小票底部预留 2cm 空白**（方便纸质单据上手写签收）
   - 三处都实现了，别只改一处：
     - 网页 Canvas 预览/存图：`TAIL_MM = 20`，画完后回扫墨迹裁到「墨迹底 + 20mm」
     - 网页 DOM 打印浮层：`css .receipt .sign-tail { height: 2cm }`
     - ESC/POS 蓝牙打印：`ESC d 160`（203dpi 下 2cm ≈ 160 点）+ 2 行 LF 兜底
3. **商品管理页条码框右边新增「扫码」按钮**（原生版）
   - CameraX 取景 + ML Kit 条码识别（bundled，离线可用）
   - 支持 EAN-13/EAN-8/UPC-A/UPC-E/CODE-128/CODE-39/ITF/QR/DATA_MATRIX
   - 识别到即自动填入条码框并自动关闭窗口；同码 1200ms 冷却防连扫
4. **ESC/POS 明细行补 EAN-13 图形条码**（`GS ( k` m=65 格式 1，宽 1 高 2）

### 🔴 本轮踩坑（下次改务必先看）

1. **列宽必须减掉左右边距再除列数**
   ```js
   CONTENT_W = PAPER_W - SIDE_MM * 2 * MM;   // SIDE_MM = 4
   COL_W = CONTENT_W / COLS;                // 48 列
   ```
   早期用 `PAPER_W / COLS` 算列宽，48 列画完正好等于纸宽，再加左边距就溢出，
   右侧金额被裁到只剩「84.」「64.」。

2. **画布高度估算宁高勿矮（估矮会把 2cm 留白吃掉）**
   初始高度 = 估算值 × 4，绘制后用 `getImageData()` 回扫墨迹底部，
   再裁到「墨迹底 + TAIL_MM」。若估矮了，`Math.min(cv.height, ...)` 会把裁剪卡死，
   底部留白变成「估算与实际的差值」（实测出现过 0.1mm / 8.4mm / **8.8cm** 假留白）。

3. **中文逐字定位绘制 + 超格缩放**
   中文占 2 个半角列，但中文字面宽 ≠ 2×Courier 半角宽。必须逐字按列定位绘制，
   超格时 `ctx.scale()`（网页）/ `textSize`（原生）压到格宽 94% 并居中，
   否则「往来单位名称」那行会糊成一团。

4. **统一走 SFTP 直传，别用 `echo '<base64>' | base64 -d`**
   app.js 有 161KB，base64 后超过 `ARG_MAX`，直接报 `Argument list too long`。
   `ssh103b.put()` 已改成 SFTP + sha256 校验 + 原子 mv。

5. **原生端 Kotlin 坑**
   - `Preview.surfaceProvider` 只有 setter 没有 getter，Kotlin 合成属性用不了
     （`Unresolved reference`），必须 `preview.setSurfaceProvider(pv.surfaceProvider)`。
   - `Order` 模型的时间字段名是 `created_at`（不是 `createdAt`）。
   - `s.sumOf { if (isWide(it)) 2 else 1 }` 会触发 `sumOf` 重载歧义，改显式 for 循环。
   - 局部 `fun` 的默认参数不能用命名参数跳过中间形参之外的类型不匹配，
     `textAt(nums, baseline, "right")` 要写成 `textAt(nums, baseline, align = "right")`。

6. **服务 worker 缓存必须 bump**
   `CACHE: erp-shell-v17 → erp-shell-v18`，`SHELL` 新增 `/js/receipt_preview.js`。
   不 bump 用户浏览器会继续吃旧缓存。

### 条码字段铁律（重申）
打印条码**只取 `barcode`**（真条码，106/107 个商品有值），
`spec` 装的是规格文字（`106g`/`180ml`），**永远不能拿来打印条码**。

### 新增文件
- `public/js/receipt_preview.js` — 预览/存图模块，暴露 `window.ERPReceipt`
  （`open` / `download` / `draw` / `newCanvas` / `ean13Bits`）
- 原生 `ui/main/ReceiptImage.kt` — Bitmap 版小票渲染 + 相册存储 + 分享
- 原生 `ui/main/ScannerDialog.kt` — 通用扫码框

---

## 版本号对照

| 版本 | versionCode | 说明 |
|---|---|---|
| v4.7 | 34 | 原生版基线 |
| v4.8 | 37 | 单品折扣（35/36 已被废弃的套壳版占用） |
| v4.9 | 38 | 编辑跳开单页 + 条码修复 |
| v5.0 | 39 | 订单预览/存图 + 小票底部 2cm 留白 + 商品扫码 |
| v5.1 | 40 | 去掉条形码图形只留数字 + 滑动阈值调低 + 按钮布局压缩 |
| v5.2 | 41 | 修复订单预览浮层被订单详情弹窗遮挡 |
| v5.3 | 42 | 正式品牌图标 + 退货单标题/负数口径全链路修复 |
| v5.4 | 43 | 反馈系统 + 打印模板引擎（A2-A5/自定义mm/可重复模块）+ 登录页重做 + 移动端自动重连打印机/打印不关弹窗 |
| v5.5 | 44 | 信任设备三按钮（进入开单/换账号/选账套）+ 设置页账套切换 + 两端互跳 + 首页移动版下载 |
| v5.5.1 | 45 | 修「存为图片」自动弹空预览 + 修 /downloads/apk 短链 404（二维码扫不出） |
| v5.5.2 | 45 | 网页热修（无新APK）：修「管理打印模板」弹窗卡死 |

---

## v5.5.2（2026-10-15）网页热修：打印模板弹窗卡死

**经理反馈**：> 点击管理打印模板 然后弹窗会卡死

**根因**：`print_template.js` 全篇用 `$('#tpl-title', o)` 这种**带 scope 第二参**的写法选元素，
但这个文件**从未定义过 `$`**。

- app.js 里的 `$` 是它IIFE 内部的私有常量 `const $ = s => document.querySelector(s)`，
  既没有挂到 `window`，也只接受 1 个参数 —— 所以这里是 `ReferenceError: $ is not defined`，
  不是「选不到节点」。
- 失败时序正好解释截图现象：
  1. `overlay()` 第 434 行 `document.body.appendChild(o)` **已执行** → 弹窗外壳出现
  2. 第 435 行 `$('#tpl-title', o)` 抛异常 → 标题没填、× 按钮 onclick 没绑
  3. 异常冒泡出 `openManager()` → `paint()` **从未执行** → 内容区一片空白
  4. × 没 onclick、点暗区的listener 也没绑 → **怎么点都关不掉，只能刷新**
- 所以「卡死」其实是「静默崩溃」：JS 抛错但 UI 已画了一半，用户看到的就是一个关不掉的空壳。

**修复**：
| 文件 | 改动 |
|---|---|
| `js/print_template.js` | 文件头补`const $ = (sel, root) => (root || document).querySelector(sel)` 与 `const $$ = ...`，自给自足，不依赖任何外部全局 |
| `js/feedback.js` | 同类隐患：`$` 原本是单参版，却被 31 处按双参调用（第二参被静默丢弃）。一并改为支持 scope |
| `sw.js` | `CACHE` bump到 `erp-shell-v30` |

**为什么不用「挂到 window.$」**：那是把两个 IIFE 绑在一起，谁先加载谁定义、加载顺序一变就又坏。
每个文件自给自足才是唯一可靠解法。

**回归测试**：新增 `_regress/tpl_overlay.js`（39 项），用极简 DOM桩跑**真实的**
`print_template.js`，断言 `openManager()` 不抛错、`paint()` 生成非空 `.tpl-wrap`、
× 能关闭、5 个内置模板 normalize/cols 正常、5 类脏数据不崩。
全量回归：`tpl_overlay 39 + trusted_login 97 + apk_login_entry 20 + tpl_render 58` 全绿。

**部署**：`erp_work/deploy_hotfix_v552.py`（只替3 个静态文件，不重启 erp）
备份 `/www/erp/_source_archive/v552hotfix_20261015_232705`，公网 sha256 与本地一致。

## v5.5.3（2026-10-16）网页热修：打印模板弹窗文字排版 + 打单前选模板崩溃

经理截图：管理打印模板弹窗里「＋ 新建」按钮文字被折成两行、三个按钮高度参差。
用 Chromium 真实布局引擎量到了根因，不靠肉眼猜。

### 排版根因（实测数据）

| 问题 | 实测 | 根因 |
|---|---|---|
| 「＋ 新建」折成两行 | 内容区可用 39.0px，文字实测需 45.89px，溢出 6.89px | 左列只有 210px，3 个 `flex:1` 按钮 + 2 个 5px gap → 每按钮约 64px，扣掉 `.btn-sm` 左右 padding 14px×2 只剩 36px |
| 三按钮高度参差 | 折行的那个撑到 3 行高，另两个 1 行 | `.tpl-left-ops` 写了 `flex-wrap:wrap`，放不下就整按钮跳到下一行 |
| 「开始打单」比旁边高 | 35.89 / 35.89 / **49.47** px | `.fb-actions`（打单前选模板底部按钮行）**CSS 里根本没定义** |
| 「＋ 快速重复上一个」被挤下去 | 断成两行 | `.tpl-extraidk`（自定义模块添加行）**CSS 里根本没定义** |

`grep` 出来 `.fb-actions`、`.tpl-extraidk`、`.tpl-ed` 三个类在 JS 里用了、在 CSS 里查不到，
说明这三个元素一直在吃浏览器默认块级样式。

### 修复

| 文件 | 改动 |
|---|---|
| `css/print_template.css` | 左列 210px → **232px**；`.tpl-left-ops .btn-sm` 改 `flex:1 1 0; min-width:0; white-space:nowrap; height:33px; line-height:33px; padding:0 4px`；**去掉 `flex-wrap:wrap`** |
| `css/print_template.css` | 新增 `.fb-actions`（三个按钮统一 38px，`flex:1 1 0`，`align-items:stretch`）与 `.fb-actions > .btn-primary{flex:0 0 auto;padding:0 20px}`（同高但用底色区分层级，不靠尺寸堆） |
| `css/print_template.css` | 新增 `.tpl-extraidk`（一行 flex，控件统一 32px 高）+ 窄屏媒体查询 |
| `css/print_template.css` | `.tpl-savebar .btn-primary` 压成 `height:36px; line-height:36px; padding:0 10px; font-size:14px`（全局 `.btn-primary` 是 padding14px/17px，跟上方 33px 小按钮不在一个量级） |
| `css/print_template.css` | 新增 `.tpl-ed{display:block}`、`.tpl-mc span{flex:1 1 auto;min-width:0}`、`.tpl-item b/span` 省略号截断 |
| `js/print_template.js` | `overlay(title, html, wide)` 补 `if (html) body.innerHTML = html;` —— 原来**接了 html 参数却从不写入**，导致 `pick()` 在空内容上执行 `$('#tpl-cancel', body).onclick` 抛 `TypeError: Cannot set properties of null`，打单前选模板弹窗空白且按钮点不动 |
| `js/print_template.js` | 模板名补 `title="${esc(x.name)}"`（管理页 + 选择页各一处），配合 CSS 截断，鼠标悬停能看全名 |
| `sw.js` | `CACHE` bump 到 `erp-shell-v31` |

### 排版体检流程（可复用）

新增 `_regress/tpl_typo.html` + `tpl_typo_run.js`（Playwright + Chromium 真实布局引擎）：
- 逐元素测 `getBoundingClientRect()` 高度、`scrollWidth/clientWidth` 溢出、
  行数（`clientHeight / line-height`）、字号
- 视口跑两遍：1280（桌面）+ 375/390（窄屏，经理多半是窄窗口截图）
- 反查 JS 里用到但 CSS 未定义的 class
- 生成前后对比特写：`shot_left_before_1280.png` → `shot_left_after_1280.png`
- ⚠️ favicon/ico 的 404 必须过滤，否则每次跑都假失败

修复后：新建/复制/删除统一 **33px** 且全部单行，底部三按钮统一 **36.86px**，
1280 与 390 两档视口均通过，13 项断言 0 失败。

### 回归与部署

全量回归全绿：`tpl_typo 13 + tpl_overlay 39 + trusted_login 98 + apk_login_entry 20
+ feedback_e2e 22 + paper_extra 53 + receipt_canvas 10 + tpl_render 58`。
部署 `erp_work/deploy_hotfix_v553.py`，备份
`/www/erp/_source_archive/v553hotfix_20261016_000438`，公网 `sw.js CACHE= erp-shell-v31`，
未重启 erp（静态文件替换即时生效）。

## 源码归档说明

- `_source_latest/` = 部署到 `/www/erp/` 的**最新版源码副本**（server.js / db.js /
  order_table.js / xlsx_util.js / moderation*.js / package.json / public 静态资源）
- 更新方式：每次部署后从 `/www/erp/` 同步一份过去（带日期后缀留档）
- **此目录不参与分发更新**，仅供查阅
