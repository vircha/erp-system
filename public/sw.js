// ERP 离线缓存：网络优先 + 缓存兜底
// 只缓存静态资源（页面/CSS/JS），/api/ 等业务数据始终走网络，避免拿到旧数据。
// 🔴 改动任何前端文件都必须 bump CACHE，否则用户浏览器继续用旧缓存。
//v60：加载提速（index.html 的 6 个 script 加 defer 并行下载 / print_template.css 改 media=print）
//     —— 对应服务端 nginx 静态直出 + HTTP/2 + 全部静态资源带内容哈希 ?v=
//v59：采购建议底部四按钮 → **右侧悬浮常驻**（经理「选好产品要滑到下面才找得到按钮」）+ 可折叠
//v58：采购页 UI 改造（采购建议双列卡片+勾选批量加入去「加入」按钮 / 选商品多列网格可多选填数量 / 侧栏顺序）
//v57：采购与库存（以销定购采购建议 / 采购单建单即入库 / 库存三口径 / 库存流水）
//v56：订单原价弹出修复（预览/打印/详情三处同步）
//v55：修移动端布局（侧栏 220px 固定 → 手机端改顶部横向滚动条）+ 静态资源缓存策略（去 immutable）
//v36：应收应付（管家婆式挂账+收款核销、自动分配最早优先、账期30天标红）+ 报表中心三组
//v35：开单页「销售单 / 退货单」模式切换（管家婆式手工录品，不绑定原单，type=return）
//v34：网页版单品/部分退货（订单详情→逐行填退货数量，0=不退；可多次部分退）
//v33：开单页顶部「门店 / 日期 / 表格入口」并排一行（原三张卡各占一整行太占地方）
//v32：历史价格+折扣按「门店×商品」记忆（批量折扣改勾选行、可与原价同单、整单折扣不再自动带出防叠乘）
//v31：打印模板弹窗文字排版（+新建折行、三按钮不等高、保存全部两行、fb-actions/tpl-extraidk 无样式）
//v30：修「管理打印模板」弹窗卡死（print_template.js 缺 $ 定义 → ReferenceError）
//v29：信任设备首页三按钮（不再自动跳入系统）+ 账套切换
//v28：打印模板纸张规格库（A2/A3/A4/A5/B5/单据尺寸）+ 自定义 mm + 可重复添加模块
//v27：登录页重做（左右分屏 + 动态极光 + 功能展示，login.css）
//v26：打印模板引擎（print_template.js/css）：纸宽/列数/留白 + 25 个内容模块全可配，打单前先选模板
//v25：意见反馈系统（feedback.js + feedback.css）+ 隐私政策页
//v24：去掉顶栏重复的「手机版」按钮，只留侧栏单入口
//v22：导航栏加品牌图标 + 手机版下载按钮（含二维码）
//v21：预览浮层 z-index 提到 70，不再被订单详情弹窗压住
//v62：开单页购物车行显示「件数」（= 数量 ÷ 每箱装量，保留 2 位小数不进位）
const CACHE = 'erp-shell-v62';   // v50: 客户下单员多门店（门店切换器 + 门店多选授权 + 按当前门店取历史价）
const SHELL = ['/', '/index.html', '/css/style.css', '/css/login.css', '/css/feedback.css', '/css/print_template.css',
  '/js/app.js', '/js/receipt_preview.js', '/js/print_template.js',
  '/js/qrcode-mini.js', '/js/apk_download.js', '/js/feedback.js', '/privacy.html', '/img/logo_192.png'];

self.addEventListener('install', (e) => {
  self.skipWaiting();
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).catch(() => {}));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((ks) => Promise.all(ks.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  let pathname = '';
  try { pathname = new URL(req.url).pathname; } catch (err) { return; }
  if (pathname.startsWith('/api/')) return; // 业务数据不缓存
  if (!pathname.startsWith('/') || pathname === '/sw.js') return;

  e.respondWith(
    fetch(req, { cache: 'no-cache' })
      .then((res) => {
        try {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(req, copy));
        } catch (err) {}
        return res;
      })
      .catch(() => caches.match(req).then((r) => r || caches.match('/index.html')))
  );
});
