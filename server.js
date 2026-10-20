// ERP 后端：Node 原生 http + node:sqlite，零依赖
// 多套账 + 账户角色权限；所有业务接口按当前登录账户的 book_id 隔离。
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const zlib = require('zlib');
const { db, hashPassword, verifyPassword } = require('./db');
const xlsxUtil = require('./xlsx_util');
const orderTable = require('./order_table');
const modServer = require('./moderation_server');

const PORT = process.env.PORT || 3000;
const PUBLIC_DIR = path.join(__dirname, 'public');
// CORS 跨域来源白名单（逗号分隔，如 https://a.com,https://b.com）；
// 留空=仅允许同源 + 原生 App(capacitor/ionic://localhost)，防任意站点跨域读取 ERP 数据
const ALLOWED_ORIGINS = (process.env.ERP_CORS_ORIGINS || '').split(',').map(s => s.trim()).filter(Boolean);
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon'
};

// 构建版本戳：以 public/js/app.js 的修改时间为准，部署后随 erp.service 重启刷新；
// 前端轮询 /api/version，ts 变大即代表有新版可刷新。
const APP_VERSION = (() => { try { return require('./package.json').version; } catch { return '1.0.0'; } })();
const APP_BUILD_TS = (() => { try { return fs.statSync(path.join(PUBLIC_DIR, 'js', 'app.js')).mtimeMs; } catch { return Date.now(); } })();

// ---------- 角色与权限模型 ----------
// boss 老板：全部（含设置、账户管理、套账管理、退货、删除）
// manager 经理：除「设置与账户管理」外全部（可改商品/库存/客户、退货、看分析）
// staff 员工：开单 + 订单查看/打印 + 商品库存只读
const ROLES = {
  boss: { label: '老板', perms: ['*'] },
  manager: {
    label: '经理',
    perms: [
      'sale.create', 'order.view', 'order.edit', 'order.return', 'order.print', 'order.delete',
      'product.view', 'product.edit', 'product.import', 'inventory.edit',
      'customer.view', 'customer.edit',
      'price.view', 'price.edit',
      'ar.view', 'ar.edit',
      // 🔴 2026-10-18 补齐：注释里写明经理「可看分析」，但 perms 漏了 analysis.view，
      //    且 /api/report/stock-alert 要 inventory.view —— 经理有 edit 却没有 view，
      //    结果经理既看不到「报表」菜单，库存预警接口还直接 403。
      'inventory.view', 'analysis.view'
    ]
  },
  staff: {
    label: '员工',
    perms: ['sale.create', 'order.view', 'order.return', 'order.print', 'product.view', 'inventory.view', 'customer.view', 'price.view', 'ar.view']
  },
  // customer 客户（下单角色，2026-10-18 新增）：给自己门店下单 + 看自己的订单/欠款。
  // 🔴 关键：它的数据隔离**不能只靠 perms** —— perms 只决定「能不能进这个接口」，
  //    进了接口以后还得用 isCustomer(ctx) 强制锁 customer_id 并剥掉成本价/库存等字段，
  //    否则客户改个 URL 参数就能看到全店经营数据。见各接口里的 isCustomer 分支。
  customer: {
    label: '客户',
    // customer.view 是开单页取本店名必须的（GET /api/customers 对客户只返回自己负责的那些）
    // 🔴 price.view 只为 GET /api/customer-prices（取**自己门店**的历史送货价）。
    //    别被它吓到：价目体系(price-systems) 和 各价体系底价(prices) 两个接口
    //    在 need() 之后还有一层显式的 isCustomer 403，客户照样看不到全店价格表。
    perms: ['sale.create', 'order.view', 'order.print', 'product.view', 'customer.view', 'price.view', 'ar.view', 'profile.edit']
  }
};
function roleLabel(r) { return (ROLES[r] || {}).label || r; }
function can(role, perm) {
  const r = ROLES[role];
  if (!r) return false;
  if (r.perms.includes('*')) return true;
  return r.perms.includes(perm);
}

// ---------- 客户角色的数据隔离（2026-10-18；2026-10-19 支持多门店）----------
function isCustomer(ctx) { return !!(ctx && ctx.role === 'customer'); }

// 账号被授权的门店集合（account_stores 表）。非客户角色返回空数组。
//   表里没记录（迁移失败/手工插的账号）时回落到 accounts.customer_id，至少还能用。
function custStoreIdsOf(acc, bookId) {
  if (!acc || acc.role !== 'customer') return [];
  let ids = [];
  try {
    ids = db.prepare('SELECT customer_id FROM account_stores WHERE account_id=? AND book_id=? ORDER BY customer_id')
      .all(acc.id, bookId).map(r => Number(r.customer_id)).filter(n => isFinite(n) && n > 0);
  } catch (e) { }
  // 🔴 默认门店永远并进授权集合。
  //    只在 ids 为空时才回落是不够的：那会导致「默认门店 A 被移出多门店绑定后，
  //    账号连自己原本那家店都下不了单」，而 accounts.customer_id 还指着它。
  const def = Number(acc.customer_id);
  if (isFinite(def) && def > 0 && ids.indexOf(def) < 0) { ids.push(def); ids.sort((a, b) => a - b); }
  return ids;
}
// 🔴🔴 一个客户下单员可以负责多家门店 → 判断「这个门店归不归他管」必须查集合，不能比等号。
//    门店 id 不在集合内一律当越权，返 404（403 等于确认「这个单号真实存在」）。
function custAllowed(ctx, cid) {
  if (!isCustomer(ctx)) return true;      // 内部角色不受门店限制
  const n = Number(cid);
  return isFinite(n) && n > 0 && (ctx.customerIds || []).indexOf(n) >= 0;
}
// 客户请求指定的门店：优先 body/query 里的customer_id（必须属于他的授权范围），
// 否则回落到默认门店 accounts.customer_id。都不行返回 0。
function custTarget(ctx, want) {
  if (!isCustomer(ctx)) return Number(want) || 0;
  const ids = ctx.customerIds || [];
  const n = Number(want);
  if (isFinite(n) && n > 0 && ids.indexOf(n) >= 0) return n;
  return Number(ctx.customerId) || ids[0] || 0;
}
// 客户可见的商品字段：只给 name/spec/unit/barcode/装量 + 本店价。
// 🔴 绝不给 cost（成本价）、qty（库存）、low_threshold（预警阈值）——
//    这些是经营数据，客户拿到了等于把家底亮给别人看。
function customerPriceMap(bookId, cid) {
  const m = {};
  if (!cid) return m;
  try {
    db.prepare('SELECT product_id, price FROM customer_prices WHERE book_id=? AND customer_id=?')
      .all(bookId, cid).forEach(r => { const v = Number(r.price); if (isFinite(v) && v > 0) m[r.product_id] = v; });
  } catch (e) { }
  return m;
}
function pubProductFor(r, pmap) {
  const own = pmap[r.id];
  return {
    id: r.id, name: r.name, spec: r.spec, unit: r.unit,
    barcode: r.barcode || '', pack_size: Number(r.pack_size) || 0,
    // 本店历史价优先，没有历史价则回落到商品库统一价（经理 2026-10-18 定的口径）
    price: (own != null && own > 0) ? own : (Number(r.price) || 0),
    qty: 0, low_threshold: 0, cost: 0,
  };
}
// 客户可见的客户字段：只给本店基本信息，剥掉备注/联系人等内部信息
function pubCustomerFor(c) {
  return { id: c.id, name: c.name, type: c.type, phone: c.phone || '', address: c.address || '' };
}

function sendJSON(res, code, obj) {
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(obj));
}
function getBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = []; let size = 0;
    req.on('data', c => { chunks.push(c); size += c.length; if (size > 1e7) req.destroy(); });
    req.on('end', () => { try { const raw = Buffer.concat(chunks).toString('utf8'); resolve(raw ? JSON.parse(raw) : {}); } catch (e) { reject(e); } });
    req.on('error', reject);
  });
}
function getRawBody(req, max) {
  return new Promise((resolve, reject) => {
    const chunks = []; let size = 0; max = max || 10 * 1024 * 1024;
    req.on('data', c => { chunks.push(c); size += c.length; if (size > max) { req.destroy(); reject(new Error('文件过大（>10MB）')); } });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

// ---------- 会话（内存 token -> account/book） ----------
const SESSIONS = new Map();

// ---------- 内容合规与平台治理 ----------
// MOD：违规检测（写入时登记）→ 30 分钟整改窗口 → 超期自动封禁 → 管理员接口。
// 巡检定时器在文件末尾启动（服务起来后才跑，避免初始化期误扫）。
let MOD = null;

// ---------- 验证码（自绘 SVG，无需第三方库） ----------
const CAPTCHAS = new Map();      // cid -> { code, exp }
const LOGIN_FAILS = new Map();   // username -> { count, ts }
const CAPTCHA_TTL = 5 * 60 * 1000;
const FAIL_WINDOW = 10 * 60 * 1000;

function newCaptcha() {
  // 字符集：剔除点阵下易混淆的 0/O、1/I/L、G/6（G 去掉了），保留其余
  const chars = '23456789ABCDEFHJKLMNPQRSTUVWXYZ';
  let code = '';
  for (let i = 0; i < 4; i++) code += chars[Math.floor(Math.random() * chars.length)];
  const cid = 'cap-' + crypto.randomBytes(12).toString('hex');
  CAPTCHAS.set(cid, { code, exp: Date.now() + CAPTCHA_TTL });
  return { cid, code };
}
// 5x7 点阵字模：字符只以 rect 点阵呈现，SVG 中不含任何明文字符，防止直接读取。
const FONT5x7 = {
  '2': ['.###.', '#...#', '....#', '...#.', '..#..', '.#...', '#####'],
  '3': ['####.', '....#', '....#', '.###.', '....#', '....#', '####.'],
  '4': ['...#.', '..##.', '.#.#.', '#..#.', '#####', '...#.', '...#.'],
  '5': ['#####', '#....', '####.', '....#', '....#', '#...#', '.###.'],
  '6': ['.###.', '#....', '#....', '####.', '#...#', '#...#', '.###.'],
  '7': ['#####', '....#', '...#.', '..#..', '.#...', '.#...', '.#...'],
  '8': ['.###.', '#...#', '#...#', '.###.', '#...#', '#...#', '.###.'],
  '9': ['.###.', '#...#', '#...#', '.####', '....#', '....#', '.###.'],
  'A': ['.###.', '#...#', '#...#', '#####', '#...#', '#...#', '#...#'],
  'B': ['####.', '#...#', '#...#', '####.', '#...#', '#...#', '####.'],
  'C': ['.###.', '#...#', '#....', '#....', '#....', '#...#', '.###.'],
  'D': ['####.', '#...#', '#...#', '#...#', '#...#', '#...#', '####.'],
  'E': ['#####', '#....', '#....', '####.', '#....', '#....', '#####'],
  'F': ['#####', '#....', '#....', '####.', '#....', '#....', '#....'],
  'G': ['.###.', '#...#', '#....', '#.###', '#..##', '#...#', '.###.'],
  'H': ['#...#', '#...#', '#...#', '#####', '#...#', '#...#', '#...#'],
  'J': ['..###', '...#.', '...#.', '...#.', '...#.', '#..#.', '.##..'],
  'K': ['#...#', '#..#.', '#.#..', '##...', '#.#..', '#..#.', '#...#'],
  'L': ['#....', '#....', '#....', '#....', '#....', '#....', '#####'],
  'M': ['#...#', '##.##', '#.#.#', '#.#.#', '#...#', '#...#', '#...#'],
  'N': ['#...#', '##..#', '##..#', '#.#.#', '#..##', '#..##', '#...#'],
  'P': ['####.', '#...#', '#...#', '####.', '#....', '#....', '#....'],
  'Q': ['.###.', '#...#', '#...#', '#...#', '#.#.#', '#..#.', '.##.#'],
  'R': ['####.', '#...#', '#...#', '####.', '#.#..', '#..#.', '#...#'],
  'S': ['.####', '#....', '#....', '.###.', '....#', '....#', '####.'],
  'T': ['#####', '..#..', '..#..', '..#..', '..#..', '..#..', '..#..'],
  'U': ['#...#', '#...#', '#...#', '#...#', '#...#', '#...#', '.###.'],
  'V': ['#...#', '#...#', '#...#', '#...#', '#...#', '.#.#.', '..#..'],
  'W': ['#...#', '#...#', '#...#', '#.#.#', '#.#.#', '##.##', '#...#'],
  'X': ['#...#', '#...#', '.#.#.', '..#..', '.#.#.', '#...#', '#...#'],
  'Y': ['#...#', '#...#', '.#.#.', '..#..', '..#..', '..#..', '..#..'],
  'Z': ['#####', '....#', '...#.', '..#..', '.#...', '#....', '#####']
};

function captchaSvg(code) {
  const W = 132, H = 44;
  const colors = ['#2b7de9', '#e5484d', '#1aad19', '#8e4ec6', '#d9822b'];
  let out = `<rect width="${W}" height="${H}" fill="#f4f6f9"/>`;
  const dot = 4.2, gap = 1.1;
  // 干扰线：限制在上下留白区，避免盖住字符主干
  for (let i = 0; i < 4; i++) {
    const y1 = i % 2 ? 2 : H - 2;
    const y2 = i % 2 ? H - 2 : 2;
    out += `<line x1="${Math.random() * W | 0}" y1="${y1}" x2="${Math.random() * W | 0}" y2="${y2}" stroke="${colors[i % colors.length]}" stroke-width="1" opacity="0.25"/>`;
  }
  // 点阵字符：仅用 rect，不出现任何明文
  const cell = dot + gap;
  for (let i = 0; i < code.length; i++) {
    const glyph = FONT5x7[code[i]];
    if (!glyph) continue;
    const ox = 8 + i * 30 + (Math.random() * 3 - 1.5);
    const oy = 4 + (Math.random() * 4 - 2);
    const color = colors[(Math.random() * colors.length) | 0];
    for (let r = 0; r < 7; r++) {
      for (let c = 0; c < 5; c++) {
        if (glyph[r][c] !== '#') continue;
        const x = (ox + c * cell).toFixed(1);
        const y = (oy + r * cell).toFixed(1);
        out += `<rect x="${x}" y="${y}" width="${dot}" height="${dot}" fill="${color}"/>`;
      }
    }
  }
  // 干扰点：只撒在四周边缘，不落在字符主体
  for (let i = 0; i < 18; i++) {
    const x = (Math.random() * W).toFixed(1);
    const y = (Math.random() < 0.5 ? Math.random() * 6 : H - 6 + Math.random() * 6).toFixed(1);
    out += `<rect x="${x}" y="${y}" width="1.8" height="1.8" fill="${colors[(Math.random() * colors.length) | 0]}" opacity="0.45"/>`;
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">${out}</svg>`;
}
function verifyCaptcha(cid, input) {
  if (!cid || !input) return false;
  const rec = CAPTCHAS.get(cid);
  if (!rec) return false;
  CAPTCHAS.delete(cid); // 一次性
  if (rec.exp < Date.now()) return false;
  return String(input).trim().toUpperCase() === rec.code;
}
function loginFails(username) {
  const r = LOGIN_FAILS.get(username);
  if (!r || Date.now() - r.ts > FAIL_WINDOW) return 0;
  return r.count;
}
function markLoginFail(username) {
  const n = loginFails(username) + 1;
  LOGIN_FAILS.set(username, { count: n, ts: Date.now() });
  return n;
}
function clearLoginFail(username) { LOGIN_FAILS.delete(username); }

// ---------- 信任设备（30 天免密登录） ----------
const TRUST_DAYS = 30;
// 🔴 1.5.1：每账户最多信任 4 台设备（经理要求）。
//   超限时**不签发新令牌**，而不是自动淘汰最久未用的一台。
//   理由：淘汰会静默踢掉经理还在用的设备（那台下次打开就要求输密码，
//   属于"没交代的故障"）。改成不签发 → 登录照常成功，只是这台不被记住，
//   并明确告诉经理"已达上限，请先在设置里解除一台"。
const TRUST_MAX = 4;
//只存哈希：即使数据库泄露，也无法反推出可用的设备令牌
function hashDeviceToken(tok) { return crypto.createHash('sha256').update(String(tok)).digest('hex'); }
// 🔴 1.5.1：还有多少名额可签发。
//   过期的不算名额（本来 findTrusted 见到就会删，这里提前批量清，省得占位置）。
function trustSlots(accountId) {
  try {
    const now = db.prepare("SELECT datetime('now','localtime') AS n").get().n;
    db.prepare('DELETE FROM trusted_devices WHERE account_id=? AND expires_at<=?').run(accountId, now);
    const used = db.prepare('SELECT COUNT(*) AS c FROM trusted_devices WHERE account_id=?')
      .get(accountId).c;
    return Math.max(0, TRUST_MAX - used);
  } catch (e) { return TRUST_MAX; }   // 查询失败时放行，绝不因计数失败挡住登录
}
// 设备标识归一化：只做大小写/空白/分隔符差异的收敛（Android·Chrome 与 Android·微信 是两台入口，保留区分）。
// 注意不要把「·」后面的平台名也砍掉当同设备 —— 同一台电脑上的 Chrome 与微信
// 是两个各自独立的免密入口，合并会让后登录的顶掉先登录的。
function deviceKeyOf(device) {
  return String(device || '').trim().toLowerCase().replace(/\s+/g, ' ');
}
// 🔴 1.5.1：返回 { token, full }
//   token = 设备令牌明文（只在这里出现一次，回给客户端）；null = 没能签发
//   full  = true 表示已达 4 台上限，本台不会被记住（**登录本身不受影响**）
// ⚠️ 满额时不要抛错、不要拦登录 —— 只是不给免密令牌。
//   抛错会让经理以为登录失败，反复重试反而更困惑。
function newDeviceToken(account, device) {
  const raw = 'td-' + crypto.randomBytes(32).toString('hex');   // 明文只回给客户端一次
  const name = String(device || '').slice(0, 120);
  const key = deviceKeyOf(name);
  try {
    // 同一账号 + 同一设备（归一化后）只保留一条：复用最早那条并换成新令牌，
    // 顺带删掉历史遗留的重复行。旧做法是无条件 INSERT，
    // 导致同设备每次勾选信任就多出一行（线上曾累积到 4 条同设备记录）。
    const dupes = db.prepare('SELECT id FROM trusted_devices WHERE account_id=? AND device=?')
      .all(account.id, name);
    let keepId = 0;
    if (dupes.length) {
      keepId = dupes[0].id;
      db.prepare("UPDATE trusted_devices SET token_hash=?, last_used_at=datetime('now','localtime'),"
        + " expires_at=datetime('now','localtime','+" + TRUST_DAYS + " days') WHERE id=?")
        .run(hashDeviceToken(raw), keepId);
      for (const d of dupes.slice(1)) db.prepare('DELETE FROM trusted_devices WHERE id=?').run(d.id);
    } else {
      // 设备名可能被改过（如原「SGT-AL10」现「Android · Chrome」），
      // 再按归一化键兜底匹配一次，避免换入口就多一条
      const all = db.prepare('SELECT id, device FROM trusted_devices WHERE account_id=?').all(account.id);
      const sameKey = all.filter(r => deviceKeyOf(r.device) === key);
      if (sameKey.length) {
        keepId = sameKey[0].id;
        db.prepare('UPDATE trusted_devices SET device=?, token_hash=?, last_used_at=datetime(\'now\',\'localtime\'),'
          + " expires_at=datetime('now','localtime','+" + TRUST_DAYS + " days') WHERE id=?")
          .run(name, hashDeviceToken(raw), keepId);
        for (const d of sameKey.slice(1)) db.prepare('DELETE FROM trusted_devices WHERE id=?').run(d.id);
      } else {
        // 🔴 1.5.1：**全新设备**且名额已满 → 不签发，直接返回。
        //   已信任的设备重新登录会走上面的 UPDATE 分支，永远不受上限影响，
        //   所以「经理换新手机」时该被记住的是新设备这条路径，
        //   而不是让已信任那台被踢掉。
        if (trustSlots(account.id) <= 0) return { token: null, full: true };
        db.prepare("INSERT INTO trusted_devices(book_id,token_hash,account_id,device,expires_at) VALUES(?,?,?,?,datetime('now','localtime','+" + TRUST_DAYS + " days'))")
          .run(account.book_id || 1, hashDeviceToken(raw), account.id, name);
      }
    }
  } catch (e) {
    // 兜底：唯一约束/字段异常时退回 INSERT，绝不因去重失败阻断登录
    console.error('newDeviceToken dedupe:', e.message);
    try {
      if (trustSlots(account.id) <= 0) return { token: null, full: true };
      db.prepare("INSERT INTO trusted_devices(book_id,token_hash,account_id,device,expires_at) VALUES(?,?,?,?,datetime('now','localtime','+" + TRUST_DAYS + " days'))")
        .run(account.book_id || 1, hashDeviceToken(raw), account.id, name);
    } catch (e2) { console.error('newDeviceToken insert:', e2.message); }
  }
  return { token: raw, full: false };
}
function findTrusted(tok) {
  if (!tok) return null;
  const row = db.prepare('SELECT * FROM trusted_devices WHERE token_hash=?').get(hashDeviceToken(tok));
  if (!row) return null;
  // 过期判断（含「已过期但未清理」的历史记录）
  const expired = db.prepare("SELECT datetime('now','localtime') > ? AS e").get(row.expires_at);
  if (expired && expired.e) { db.prepare('DELETE FROM trusted_devices WHERE id=?').run(row.id); return null; }
  return row;
}
// 滑动续期：每次凭设备令牌登录成功，把有效期顺延到 30 天后
function touchTrusted(id) {
  try { db.prepare("UPDATE trusted_devices SET last_used_at=datetime('now','localtime'), expires_at=datetime('now','localtime','+" + TRUST_DAYS + " days') WHERE id=?").run(id); } catch (e) {}
}

function newSession(account) {
  const tok = 'erp-' + crypto.randomBytes(16).toString('hex');
  SESSIONS.set(tok, { account_id: account.id, book_id: account.book_id, ts: Date.now() });
  return tok;
}
/**
 * 取一组 product_id 在给定区间内**最新一条明细**的快照（name/spec/unit/barcode/cost）。
 *
 * 🔴🔴 2026-10-20 经理需求「商品信息变动不影响历史已开出的订单」。
 *   报表按 product_id 聚合时，显示名/规格/单位**必须统一取一个值**：
 *   原来 SQL 里 `GROUP BY oi.product_id, oi.product_name`，商品改名后同一个
 *   product_id 被**拆成两组**，销量/金额被割裂（同一商品在报表里出现两行，各算一半）。
 *   ✅ 正解：SQL 只按 product_id 分组（数字永远合并），
 *      显示用的名称/规格/单位单独取区间内**最新**明细的快照。
 */
function latestItemSnapshots(bookId, pids, whereSql, args) {
  const map = new Map();
  if (!Array.isArray(pids) || !pids.length) return map;
  const uniq = [...new Set(pids.filter(v => v != null))];
  if (!uniq.length) return map;
  const ph = uniq.map(() => '?').join(',');
  let sql = `SELECT oi.product_id, oi.product_name, oi.spec, oi.unit, oi.barcode, oi.cost
             FROM order_items oi JOIN orders o ON o.id=oi.order_id
             WHERE oi.book_id=? AND oi.product_id IN (${ph}) ${whereSql || ''}
             ORDER BY oi.id ASC`;
  const rows = db.prepare(sql).all(bookId, ...uniq, ...(args || []));
  // 升序遍历 → 后写入的覆盖前面的 → 最终留下**最新**那条
  for (const r of rows) map.set(r.product_id, r);
  return map;
}

function ctxOf(req) {
  const h = req.headers['authorization'] || '';
  const tok = h.startsWith('Bearer ') ? h.slice(7) : '';
  const s = SESSIONS.get(tok);
  if (!s) return null;
  const acc = db.prepare('SELECT * FROM accounts WHERE id=? AND active=1').get(s.account_id);
  if (!acc) return null;
  const bookId = s.book_id || acc.book_id;
  const book = db.prepare('SELECT * FROM books WHERE id=?').get(bookId);
  if (!book) return null;
  // customer_id：客户账号的**默认门店**（accounts.customer_id，首次开通的那家）。
  // customerIds：他负责的**全部门店**（account_stores，2026-10-19 起支持多门店）。
  //   数据隔离一律以 customerIds 为准，customerId 只当「没指定门店时的回落值」，
  //   且必须落在 customerIds 里 —— 否则等于给账号开了一把后门。
  const customerIds = custStoreIdsOf(acc, bookId);
  const defaultCid = acc.customer_id ? Number(acc.customer_id) : null;
  const customerId = (customerIds.indexOf(defaultCid) >= 0 ? defaultCid : (customerIds[0] || null));
  return { token: tok, account: acc, book, role: acc.role, bookId: book.id, customerId, customerIds };
}

// 记录门店历史价：开单/改单成功后，把本单每个商品的成交价 + 单品折扣 + 整单折扣记到该门店名下
// 兼容三种入参：前台 {product_id} / lines {prod:{id}} / 明细行 {id}
function recordCustomerPrices(bookId, customerId, items, discount, orderId) {
  if (!customerId || !Array.isArray(items)) return;
  try {
    const upP = db.prepare("INSERT INTO customer_prices(book_id,customer_id,product_id,price,discount) VALUES(?,?,?,?,?) ON CONFLICT(book_id,customer_id,product_id) DO UPDATE SET price=excluded.price, discount=excluded.discount, updated_at=datetime('now','localtime')");
    const upD = db.prepare("INSERT INTO customer_discounts(book_id,customer_id,discount,last_order_id) VALUES(?,?,?,?) ON CONFLICT(book_id,customer_id) DO UPDATE SET discount=excluded.discount, last_order_id=excluded.last_order_id, updated_at=datetime('now','localtime')");
    for (const it of items) {
      const pid = it.product_id != null ? it.product_id : (it.prod && it.prod.id != null ? it.prod.id : it.id);
      // 🔴 必须存「折前单价」= orig_price，绝不能存 price。
      // 调用方传进来的是 lines[]，其中 price 已被 lineCalc 乘过单品折扣（成交价）。
      // 存成交价的话，下次开单取这个价再乘一次折扣 -> 双重打折（0.97×0.97=0.9409），
      // 每复开一单金额就少 3%，而且越拖越离谱。orig_price 缺失时才退回 price。
      const orig = (it.orig_price != null && it.orig_price > 0) ? it.orig_price : it.price;
      const pr = Number(orig);
      if (!pid || !isFinite(pr)) continue;
      // 单品折扣：没带就按 1 记，避免把上一单的折扣串到下一单
      const dc = Math.min(1, Math.max(0.1, Number(it.discount) || 1));
      upP.run(bookId, customerId, pid, pr, dc);
    }
    upD.run(bookId, customerId, Number(discount) || 1, orderId || null);
  } catch (e) { console.error('recordCustomerPrices:', e.message); }
}

// ---------- 单品折扣工具 ----------
// 明细的折前单价 × 单品折扣率 = 成交单价（四舍五入到分）
function itemDisc(it) {
  const v = Number(it && it.discount);
  if (!isFinite(v) || v <= 0) return 1;
  return Math.min(1, Math.max(0.1, v));
}
function lineCalc(origPrice, qty, disc) {
  const d = itemDisc({ discount: disc });
  const price = +((Number(origPrice) || 0) * d).toFixed(2);
  return { price, orig_price: +(Number(origPrice) || 0).toFixed(2), discount: d, subtotal: +(price * (Number(qty) || 0)).toFixed(2) };
}

// ---------- 工具 ----------
function ymdToday() {
  const d = new Date();
  return `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`;
}
/**
 * 单号流水 = 「当天同前缀已用的最大号 + 1」。
 * 绝不能用 COUNT(*)：订单一被删除条数就变小，重算出的号码会撞上仍然存在的旧号，
 * 而 order_no 上有 UNIQUE 约束 → 整单 INSERT 直接 500（2026-09-30 线上踩过）。
 * 这里故意不按 book_id 过滤：order_no 的唯一索引是全局的，跨套账也不能重号。
 */
function nextSeqNo(prefix, table, col) {
  const head = `${prefix}${ymdToday()}-`;
  let n = 0;
  try {
    const row = db.prepare(
      `SELECT MAX(CAST(substr(${col}, ?) AS INTEGER)) AS mx FROM ${table} WHERE ${col} LIKE ?`
    ).get(head.length + 1, head + '%');
    if (row && row.mx != null) n = Number(row.mx) || 0;
  } catch (e) { n = 0; }
  // 从「最大号+1」起找第一个没被占用的号（防御历史脏数据里非数字后缀等情况）
  let candidate = head + String(n + 1).padStart(4, '0');
  try {
    let guard = 0;
    while (guard++ < 10000 && db.prepare(`SELECT 1 FROM ${table} WHERE ${col}=?`).get(candidate)) {
      n++; candidate = head + String(n + 1).padStart(4, '0');
    }
  } catch (e) { /* 查询失败就用候选号，交给 UNIQUE 约束兜底 */ }
  return candidate;
}
function genOrderNo(bookId) { return nextSeqNo('XS', 'orders', 'order_no'); }
function genDeliveryNo(bookId) { return nextSeqNo('DH', 'deliveries', 'delivery_no'); }
function genReturnNo(bookId) { return nextSeqNo('TH', 'orders', 'order_no'); }
function getSetting(bookId, key, fallback) {
  try {
    const r = db.prepare('SELECT value FROM settings WHERE book_id=? AND key=?').get(bookId, key);
    return (r && r.value != null && r.value !== '') ? r.value : fallback;
  } catch (e) { return fallback; }
}
function setSetting(bookId, key, value) {
  db.prepare('INSERT INTO settings(book_id,key,value) VALUES(?,?,?) ON CONFLICT(book_id,key) DO UPDATE SET value=excluded.value').run(bookId, key, String(value));
}
// 🔴 防御：删套账等路径上 book 可能是 undefined（套账刚被删、查出null），
//    直接 book.id 会抛 "Cannot read properties of undefined" 让整个请求 500。
//    这里统一兜成空串，宁可店名空也不能让接口炸。
function effShopName(book) { return book && book.id ? getSetting(book.id, 'shop_name', book.name || '') : ''; }
// 邀请码（分享用；不是口令，可随时重置，明文存 settings.invite_code）
function genInviteCode() {
  const cs = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; let s = '';
  for (let i = 0; i < 8; i++) s += cs[Math.floor(Math.random() * cs.length)];
  return s;
}

// ---------- 模糊搜索（SQL 层粗筛 + 前端精排） ----------
// 把查询串按空白拆成多个关键词，每个关键词都必须命中 fields 之一（AND）。
// 关键词会去掉常见分隔符、全角转半角，便于「万家 4店」「XS-2000」这类输入命中。
// 返回 { sql, args }（sql 形如 "(a LIKE ? OR b LIKE ?) AND (...)"）；无有效关键词返回 null。
function fuzzyWhere(q, fields) {
  const toks = String(q == null ? '' : q)
    .replace(/[\uFF01-\uFF5E]/g, c => String.fromCharCode(c.charCodeAt(0) - 0xFEE0))
    .replace(/\u3000/g, ' ')
    .split(/\s+/)
    .map(t => t.replace(/[\-_/\\.,，、。·:：;；#()（）\[\]【】"'’“”`]+/g, ''))
    .filter(Boolean)
    // 转义 LIKE 通配符，避免「50%」「100_」被当成通配符匹配到无关数据
    .map(t => t.replace(/[\\%_]/g, c => '\\' + c));
  if (!toks.length) return null;
  const parts = []; const args = [];
  for (const t of toks) {
    parts.push('(' + fields.map(f => f + " LIKE ? ESCAPE '\\'").join(' OR ') + ')');
    fields.forEach(() => args.push('%' + t + '%'));
  }
  return { sql: parts.join(' AND '), args };
}

// 批量导入商品（按套账）
function importProducts(rows, bookId) {
  if (!rows || !rows.length) return { imported: 0, skipped: 0, error: '文件为空' };
  const headers = rows[0].map(h => String(h == null ? '' : h).trim());
  const fieldMap = {
    name: ['名称', '商品名称', '品名', '名字', 'name'],
    barcode: ['条码', '条形码', 'bar', 'barcode'],
    spec: ['规格', '规格型号', 'spec'],
    unit: ['单位', 'unit'],
    price: ['单价', '价格', '售价', 'price'],
    cost: ['进价', '成本', 'cost'],
    qty: ['库存数量', '库存', '数量', 'qty', 'stock'],
    low_threshold: ['低库存阈值', '低库存', '预警阈值', 'low'],
    pack_size: ['每箱装量', '装箱量', '箱规', '每箱个数', 'pack_size', 'pack']
  };
  const colIndex = {};
  for (const key in fieldMap) {
    const idx = headers.findIndex(h => fieldMap[key].some(n => h.toLowerCase() === n.toLowerCase()));
    if (idx >= 0) colIndex[key] = idx;
  }
  if (colIndex.name == null) return { imported: 0, skipped: 0, error: '未找到「名称」列，请使用模板填写' };
  const existing = new Set(db.prepare("SELECT barcode FROM products WHERE book_id=? AND barcode IS NOT NULL AND barcode<>''").all(bookId).map(r => r.barcode));
  // 同名去重：避免无条码商品被重复导入生成脏数据（R4 风险）
  const existingNames = new Set(db.prepare("SELECT lower(name) AS n FROM products WHERE book_id=? AND name IS NOT NULL AND name<>''").all(bookId).map(r => r.n));
  const seen = new Set(); const seenNames = new Set(); let imported = 0, skipped = 0; const skippedList = [];
  const get = (row, k) => { const i = colIndex[k]; return i == null ? undefined : row[i]; };
  db.exec('BEGIN');
  try {
    for (let i = 1; i < rows.length; i++) {
      const row = rows[i];
      const name = String(get(row, 'name') == null ? '' : get(row, 'name')).trim();
      if (!name) { skipped++; continue; }
      const lowerName = name.toLowerCase();
      if (existingNames.has(lowerName) || seenNames.has(lowerName)) { skipped++; skippedList.push(name + '（名称重复，已跳过）'); continue; }
      const barcode = String(get(row, 'barcode') == null ? '' : get(row, 'barcode')).trim();
      if (barcode && (existing.has(barcode) || seen.has(barcode))) { skipped++; skippedList.push(name + '（条码重复，已跳过）'); continue; }
      const spec = String(get(row, 'spec') == null ? '' : get(row, 'spec')).trim();
      const unit = String(get(row, 'unit') == null ? '' : get(row, 'unit')).trim() || '个';
      const price = Number(get(row, 'price')) || 0;
      const cost = Number(get(row, 'cost')) || 0;
      const qty = Number(get(row, 'qty')) || 0;
      const low = Number(get(row, 'low_threshold')) || 0;
      // pack_size = 每箱装量（件数 = EA ÷ 装箱量），取不到按 0 存
      const packSize = Math.max(0, Math.round(Number(get(row, 'pack_size'))) || 0);
      const info = db.prepare('INSERT INTO products(book_id,name,barcode,spec,unit,price,cost,pack_size) VALUES(?,?,?,?,?,?,?,?)').run(bookId, name, barcode, spec, unit, price, cost, packSize);
      const pid = info.lastInsertRowid;
      db.prepare('INSERT OR IGNORE INTO inventory(book_id,product_id,qty,low_threshold) VALUES(?,?,?,?)').run(bookId, pid, qty, low);
      db.prepare('UPDATE inventory SET qty=?, low_threshold=? WHERE product_id=?').run(qty, low, pid);
      imported++;
      if (barcode) { existing.add(barcode); seen.add(barcode); }
      seenNames.add(lowerName);
    }
    db.exec('COMMIT');
  } catch (e) { db.exec('ROLLBACK'); return { imported: 0, skipped: 0, error: String(e.message || e) }; }
  return { imported, skipped, skippedList };
}

// ---- 登录 IP 级速率限制（防分布式绕过单账号验证码门槛） ----
function clientIp(req) {
  const xff = req.headers['x-forwarded-for'];
  if (xff) return String(xff).split(',')[0].trim();
  return req.socket.remoteAddress || 'unknown';
}
const _ipFails = new Map();
const IP_FAIL_LIMIT = Number(process.env.ERP_IP_FAIL_LIMIT || 30);
const IP_FAIL_WINDOW = Number(process.env.ERP_IP_FAIL_WINDOW || 10 * 60 * 1000);
function markIpFail(ip) {
  const e = _ipFails.get(ip) || { n: 0, t: Date.now() };
  if (Date.now() - e.t > IP_FAIL_WINDOW) { e.n = 0; e.t = Date.now(); }
  e.n++; _ipFails.set(ip, e);
}
function clearIpFail(ip) { _ipFails.delete(ip); }
function ipBlocked(ip) {
  const e = _ipFails.get(ip);
  if (!e) return false;
  if (Date.now() - e.t > IP_FAIL_WINDOW) { _ipFails.delete(ip); return false; }
  return e.n >= IP_FAIL_LIMIT;
}

// ---------- 意见反馈：附件落盘 + 管理员提醒（2026-10-15）----------
// 附件目录放在项目内 uploads/feedback/（与 data/ 分开，便于备份与清理）。
// 🔴 只放行白名单扩展名，且磁盘文件名重新随机生成：
//   ① 原始文件名可能带 ../ 或路径分隔符，直接用会造成任意文件写；
//   ② 「a.png.exe」这类双扩展名在某些服务端会被当可执行文件解析。
const UPLOAD_DIR = path.join(__dirname, 'uploads', 'feedback');
const FB_EXT = {
  '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif',
  '.webp': 'image/webp', '.bmp': 'image/bmp', '.txt': 'text/plain', '.log': 'text/plain',
  '.pdf': 'application/pdf', '.zip': 'application/zip', '.json': 'application/json',
  '.csv': 'text/csv', '.mp4': 'video/mp4', '.mov': 'video/quicktime'
};
const FB_MAX = 8 * 1024 * 1024;   // 单个附件 8MB

function saveFeedbackFile(a, accountId) {
  const origRaw = String((a && a.name) || 'file');
  // 只取最后一段，剥掉 ../ 与 Windows 路径，限长 80
  const orig = origRaw.replace(/\\/g, '/').split('/').pop().replace(/[\x00-\x1f]/g, '').slice(0, 80) || 'file';
  const ext = path.extname(orig).toLowerCase();
  if (!FB_EXT[ext]) return { error: `不支持的附件类型：${ext || '（无扩展名）'}。仅支持图片、txt/log、pdf、zip、json、csv、mp4` };
  const b64 = String((a && a.data) || '');
  if (!b64) return { error: '附件内容为空' };
  let buf;
  try { buf = Buffer.from(b64.replace(/^data:[^;]+;base64,/, ''), 'base64'); }
  catch (e) { return { error: '附件解析失败' }; }
  if (!buf.length) return { error: '附件内容为空' };
  if (buf.length > FB_MAX) return { error: `附件过大：${orig}（单文件限 8MB）` };
  const safe = Date.now().toString(36) + '_' + crypto.randomBytes(8).toString('hex') + ext;
  try {
    fs.mkdirSync(UPLOAD_DIR, { recursive: true });
    fs.writeFileSync(path.join(UPLOAD_DIR, safe), buf);
  } catch (e) { return { error: '附件保存失败：' + e.message }; }
  db.prepare('INSERT INTO feedback_files(feedback_id,account_id,orig_name,safe_name,size,mime) VALUES(NULL,?,?,?,?,?)')
    .run(accountId, orig, safe, buf.length, FB_EXT[ext]);
  const fid = Number(db.prepare('SELECT last_insert_rowid() AS id').get().id);
  // 🔴 attachments JSON 里存 feedback_files.id：前端下载走 /api/feedback-files/:id，
  // 不必再按「反馈 id + 序号」反查附件顺序（顺序一旦错位就会下载到别人的图）。
  // safe_name 也要一并带出去：调用方要用它把 feedback_files.feedback_id 从 NULL 回填成真 id。
  return { keep: { id: fid, safe_name: safe, name: orig, size: buf.length, mime: FB_EXT[ext] } };
}

// 提醒管理员：站内未读角标（前端 /api/me 读 feedbackUnread）为主；
// 若套账设置了 Webhook（settings.feedback_webhook，如企业微信/钉钉机器人），额外推一条。
function notifyAdminsOfFeedback(fid, type, content) {
  const label = { bug: 'Bug 反馈', feature: '功能需求', account: '账户问题', other: '其他' }[type] || type;
  const brief = String(content || '').replace(/\s+/g, ' ').slice(0, 80);
  console.log(`[feedback] #${fid} 新反馈（${label}）：${brief}`);
  // Webhook：单套账级配置，失败只记日志不打扰用户
  try {
    const rows = db.prepare("SELECT DISTINCT book_id FROM accounts WHERE is_admin=1 AND active=1").all();
    for (const r of rows) {
      const url = getSetting(r.book_id, 'feedback_webhook', '');
      if (!url || !/^https?:\/\//.test(url)) continue;
      const payload = JSON.stringify({ msgtype: 'text', text: { content: `【ERP 新反馈 #${fid}】${label}\n${brief}\nhttps://erp.example.com/` } });
      const u2 = new URL(url);
      // 懒加载 https 模块（文件末尾的 TLS 监听里也用 require('https')，不冲突）
      const https2 = require('https');
      const rq = https2.request({
        hostname: u2.hostname, port: u2.port || (u2.protocol === 'https:' ? 443 : 80),
        path: u2.pathname + u2.search, method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) }
      }, rs => rs.resume());
      rq.on('error', e => console.error('[feedback] webhook 失败:', e.message));
      rq.setTimeout(8000, () => rq.destroy());
      rq.write(payload); rq.end();
    }
  } catch (e) { console.error('[feedback] notify:', e.message); }
}

async function handleApi(req, res, u) {
  const p = u.pathname, method = req.method;

  // ---------- 无需鉴权 ----------
  // 图形验证码（注册必填；登录失败 3 次后必填）
  if (p === '/api/captcha' && method === 'GET') {
    const { cid, code } = newCaptcha();
    return sendJSON(res, 200, { cid, svg: captchaSvg(code) });
  }
  // 登录前置：查询该账号是否已需验证码
  if (p === '/api/login/precheck' && method === 'POST') {
    const b = await getBody(req).catch(() => ({}));
    const u = String(b.username || '').trim();
    const fails = loginFails(u);
    return sendJSON(res, 200, { need_captcha: fails >= 3, fails });
  }
  if (p === '/api/register' && method === 'POST') {
    const b = await getBody(req).catch(() => ({}));
    const username = String(b.username || '').trim();
    const password = String(b.password || '');
    if (!verifyCaptcha(b.captcha_id, b.captcha)) return sendJSON(res, 400, { error: '验证码错误或已过期' });
    if (!username || username.length < 4) return sendJSON(res, 400, { error: '账号至少 4 位' });
    if (password.length < 4) return sendJSON(res, 400, { error: '密码至少 4 位' });
    if (db.prepare('SELECT id FROM accounts WHERE username=?').get(username)) return sendJSON(res, 400, { error: '该账号已存在' });
    // 注册默认为老板；未指定套账则自动创建新套账，指定则加入该套账（角色可被该套账老板调整）
    let bookId = b.book_id ? Number(b.book_id) : 0;
    let role = 'boss';
    let viaInvite = false;
    // 支持邀请码注册：未选套账时凭邀请码反查套账（口令框填邀请码即可）
    const invInput = String(b.invite_code || b.join_password || '').trim().toUpperCase();
    if (!bookId && invInput) {
      const inv = db.prepare("SELECT book_id FROM settings WHERE key='invite_code' AND value=?").get(invInput);
      if (inv) { bookId = inv.book_id; viaInvite = true; }
    }
    db.exec('BEGIN');
    try {
      if (!bookId) {
        const binfo = db.prepare('INSERT INTO books(name,owner_account_id) VALUES(?,NULL)').run(String(b.book_name || (username + '的套账')));
        bookId = binfo.lastInsertRowid;
        db.prepare('INSERT INTO price_systems(book_id,name) VALUES(?,?)').run(bookId, '默认价');
      } else {
        const bk = db.prepare('SELECT * FROM books WHERE id=?').get(bookId);
        if (!bk) { db.exec('ROLLBACK'); return sendJSON(res, 400, { error: '套账不存在' }); }
        // 加入口令：库中只存 scrypt 哈希（salt:hash）；兼容历史明文，命中后顺手升级为哈希
        // 口令框同时接受老板分享的邀请码（settings.invite_code）
        const storedJoin = getSetting(bookId, 'join_password', '');
        const bookInv = getSetting(bookId, 'invite_code', '');
        const input = String(b.join_password || '');
        let ok = viaInvite;
        if (!ok && storedJoin) {
          if (storedJoin.indexOf(':') >= 0) {
            ok = verifyPassword(input, storedJoin);
          } else {
            ok = (input === storedJoin);
            if (ok) setSetting(bookId, 'join_password', hashPassword(input)); // 老明文 → 哈希自动升级
          }
        }
        if (!ok && bookInv && input.trim().toUpperCase() === bookInv) ok = true;
        if (!ok && (storedJoin || bookInv)) { db.exec('ROLLBACK'); return sendJSON(res, 400, { error: '加入口令或邀请码错误' }); }
        role = 'staff'; // 加入已有套账，默认员工，由老板调整
      }
      const info = db.prepare('INSERT INTO accounts(username,password,display_name,role,book_id,active) VALUES(?,?,?,?,?,1)')
        .run(username, hashPassword(password), String(b.display_name || '').trim() || username, role, bookId);
      const aid = info.lastInsertRowid;
      db.prepare('INSERT OR IGNORE INTO account_books(account_id,book_id) VALUES(?,?)').run(aid, bookId);
      if (role === 'boss') db.prepare('UPDATE books SET owner_account_id=? WHERE id=? AND owner_account_id IS NULL').run(aid, bookId);
      db.exec('COMMIT');
      const acc = db.prepare('SELECT * FROM accounts WHERE id=?').get(aid);
      const tok = newSession(acc);
      const book = db.prepare('SELECT * FROM books WHERE id=?').get(bookId);
      const _rIds = custStoreIdsOf(acc, bookId);
      const outR = { token: tok, account: pubAcc(acc), book: pubBook(book), role: acc.role, roleLabel: roleLabel(acc.role), perms: permsOf(acc.role), shopName: effShopName(book),
                    customerId: _rIds.indexOf(Number(acc.customer_id)) >= 0 ? Number(acc.customer_id) : (_rIds[0] || null), customerIds: _rIds };
      if (b.trust_device) {
        // 🔴 1.5.1：满 4 台时 token 为 null → 不下发免密令牌，但**登录照常成功**
        const t = newDeviceToken(acc, b.device);
        if (t.token) outR.device_token = t.token;
        else if (t.full) outR.trust_full = true;
      }
      return sendJSON(res, 200, outR);
    } catch (e) { db.exec('ROLLBACK'); return sendJSON(res, 500, { error: String(e.message || e) }); }
  }
  if (p === '/api/login' && method === 'POST') {
    const rip = clientIp(req);
    if (ipBlocked(rip)) return sendJSON(res, 429, { error: '尝试过于频繁，请稍后再试' });
    const b = await getBody(req).catch(() => ({}));
    const username = String(b.username || '').trim();
    const fails = loginFails(username);
    if (fails >= 3 && !verifyCaptcha(b.captcha_id, b.captcha)) {
      return sendJSON(res, 400, { error: '验证码错误或已过期', need_captcha: true, fails });
    }
    const acc = db.prepare('SELECT * FROM accounts WHERE username=?').get(username);
    if (!acc || !verifyPassword(String(b.password || ''), acc.password)) {
      markIpFail(rip);
      const n = markLoginFail(username);
      return sendJSON(res, 401, { error: '账号或密码错误', need_captcha: n >= 3, fails: n });
    }
    // 封禁单独提示：让用户知道是违规被封，而不是密码错（否则会一直试密码、进不去申诉）
    if (!acc.active) {
      return sendJSON(res, 403, {
        error: '账号已被封禁：' + (acc.banned_reason || '违反使用规范'),
        banned_at: acc.banned_at || null,
        appeal: '如有异议请联系平台管理员'
      });
    }
    clearIpFail(rip);
    clearLoginFail(username);
    const tok = newSession(acc);
    const book = db.prepare('SELECT * FROM books WHERE id=?').get(acc.book_id);
    // 🔴 这个分支没有 bookId 变量（那是注册分支的局部变量），必须用 acc.book_id
    const _cIds = custStoreIdsOf(acc, acc.book_id || 1);
    const out = { token: tok, account: pubAcc(acc), book: pubBook(book), role: acc.role, roleLabel: roleLabel(acc.role), perms: permsOf(acc.role), shopName: effShopName(book), isAdmin: !!acc.is_admin, violations: MOD ? MOD.myPending(acc.id) : [],
                  customerId: _cIds.indexOf(Number(acc.customer_id)) >= 0 ? Number(acc.customer_id) : (_cIds[0] || null), customerIds: _cIds };
    // 勾选「信任此设备」→ 签发 30 天设备令牌
    if (b.trust_device) {
      // 🔴 1.5.1：最多 4 台。满额时不下发令牌，但登录照常成功 ——
      //   前端据此提示"已达上限，请先解除一台"，别让经理以为登录失败。
      const t = newDeviceToken(acc, b.device);
      if (t.token) out.device_token = t.token;
      else if (t.full) out.trust_full = true;
    }
    return sendJSON(res, 200, out);
  }
  // 凭设备令牌免密登录
  if (p === '/api/trusted-login' && method === 'POST') {
    const b = await getBody(req).catch(() => ({}));
    const row = findTrusted(b.device_token);
    if (!row) return sendJSON(res, 401, { error: '设备信任已过期，请重新登录' });
    const acc = db.prepare('SELECT * FROM accounts WHERE id=? AND active=1').get(row.account_id);
    if (!acc) { db.prepare('DELETE FROM trusted_devices WHERE id=?').run(row.id); return sendJSON(res, 401, { error: '账户已停用' }); }
    touchTrusted(row.id);
    const tok = newSession(acc);
    const book = db.prepare('SELECT * FROM books WHERE id=?').get(acc.book_id);
    return sendJSON(res, 200, { token: tok, account: pubAcc(acc), book: pubBook(book), role: acc.role, roleLabel: roleLabel(acc.role), perms: permsOf(acc.role), shopName: effShopName(book), device_token: b.device_token });
  }
  if (p === '/api/books/public' && method === 'GET') {
    // 注册时可选套账列表（仅名称/ID）
    return sendJSON(res, 200, db.prepare('SELECT id,name FROM books ORDER BY id').all());
  }

  // ---------- 公开：版本检查（无需登录） ----------
  if (p === '/api/version' && method === 'GET') {
    return sendJSON(res, 200, { ts: APP_BUILD_TS, version: APP_VERSION, serverTime: Date.now() });
  }

  // ---------- 鉴权 ----------
  const ctx = ctxOf(req);
  if (p.startsWith('/api/') && !ctx) return sendJSON(res, 401, { error: '未登录或会话已失效' });
  const bookId = ctx.bookId;
  const role = ctx.role;
  const need = (perm) => { if (!can(role, perm)) { sendJSON(res, 403, { error: '当前角色（' + roleLabel(role) + '）无此权限' }); return false; } return true; };
  let m;   // 路由正则匹配变量（全段共用，勿再重复 let）

  // ---------- 身份 / 套账 ----------
  if (p === '/api/me' && method === 'GET') {
    return sendJSON(res, 200, {
      account: pubAcc(ctx.account), book: pubBook(ctx.book), role, roleLabel: roleLabel(role),
      perms: permsOf(role), shopName: effShopName(ctx.book),
      books: myBooks(ctx.account),
      customerId: ctx.customerId || null,
      // 2026-10-19 多门店：前端门店切换器的数据源（配合 customer_id= 传参取那家店的价格）
      customerIds: (ctx.customerIds || []).slice(),
      isAdmin: !!ctx.account.is_admin,
      // 未处理反馈数：管理员看全部，普通用户看自己被回复后仍标记 open 的条数
      feedbackUnread: ctx.account.is_admin
        ? (db.prepare("SELECT COUNT(*) c FROM feedbacks WHERE status='open'").get().c)
        : (db.prepare("SELECT COUNT(*) c FROM feedbacks WHERE account_id=? AND status='open'").get(ctx.account.id).c),
      // 违规提醒：登录即拉一次，前端弹横幅 + 倒计时
      violations: MOD ? MOD.myPending(ctx.account.id) : []
    });
  }
  if (p === '/api/books/my' && method === 'GET') return sendJSON(res, 200, myBooks(ctx.account));

  // ---------- 信任设备管理 ----------
  if (p === '/api/trusted-devices' && method === 'GET') {
    const rows = db.prepare("SELECT id, device, created_at, last_used_at, expires_at, book_id FROM trusted_devices WHERE account_id=? ORDER BY id DESC").all(ctx.account.id);
    const now = db.prepare("SELECT datetime('now','localtime') AS n").get().n;
    // 🔴 1.5.1：顺带返回 max 与剩余名额，前端才能显示「3/4 台」并置灰勾选框
    const live = rows.filter(r => r.expires_at > now).length;
    return sendJSON(res, 200, {
      devices: rows.map(r => Object.assign({}, r, { expired: r.expires_at <= now })),
      max: TRUST_MAX,
      used: live,
      left: Math.max(0, TRUST_MAX - live)
    });
  }
  m = p.match(/^\/api\/trusted-devices\/(\d+)$/);
  if (m && method === 'DELETE') {
    const d = db.prepare('DELETE FROM trusted_devices WHERE id=? AND account_id=?').run(m[1], ctx.account.id);
    if (!d.changes) return sendJSON(res, 404, { error: '记录不存在' });
    return sendJSON(res, 200, { ok: true });
  }
  if (p === '/api/trusted-devices' && method === 'DELETE') {
    // 解除全部信任设备；带 keep_token 时保留当前这台（当前设备不自踢）
    const keep = String(u.searchParams.get('keep_token') || '');
    let n;
    if (keep) n = db.prepare('DELETE FROM trusted_devices WHERE account_id=? AND token_hash<>?').run(ctx.account.id, hashDeviceToken(keep)).changes;
    else n = db.prepare('DELETE FROM trusted_devices WHERE account_id=?').run(ctx.account.id).changes;
    return sendJSON(res, 200, { ok: true, removed: n });
  }
  if (p === '/api/books/switch' && method === 'POST') {
    const b = await getBody(req);
    const target = db.prepare('SELECT * FROM books WHERE id=?').get(Number(b.book_id));
    if (!target) return sendJSON(res, 404, { error: '套账不存在' });
    const allowed = myBooks(ctx.account).some(x => x.id === target.id);
    if (!allowed) return sendJSON(res, 403, { error: '无权访问该套账' });
    SESSIONS.get(ctx.token).book_id = target.id;
    return sendJSON(res, 200, { ok: true, book: pubBook(target), shopName: effShopName(target) });
  }
  if (p === '/api/books' && method === 'POST') {
    if (role !== 'boss') return sendJSON(res, 403, { error: '只有老板可以新建套账' });
    const b = await getBody(req);
    const name = String(b.name || '').trim();
    if (!name) return sendJSON(res, 400, { error: '套账名称必填' });
    db.exec('BEGIN');
    try {
      const info = db.prepare('INSERT INTO books(name,owner_account_id) VALUES(?,?)').run(name, ctx.account.id);
      const bid = info.lastInsertRowid;
      db.prepare('INSERT INTO price_systems(book_id,name) VALUES(?,?)').run(bid, '默认价');
      db.prepare('INSERT OR IGNORE INTO account_books(account_id,book_id) VALUES(?,?)').run(ctx.account.id, bid);
      if (b.join_password) setSetting(bid, 'join_password', hashPassword(String(b.join_password).trim()));
      db.exec('COMMIT');
      return sendJSON(res, 200, { id: bid, name });
    } catch (e) { db.exec('ROLLBACK'); return sendJSON(res, 500, { error: String(e.message || e) }); }
  }
  if (p === '/api/books/join-password' && method === 'PUT') {
    if (role !== 'boss') return sendJSON(res, 403, { error: '只有老板可以设置加入口令' });
    const b = await getBody(req);
    const jp = String(b.join_password || '').trim();
    setSetting(bookId, 'join_password', jp ? hashPassword(jp) : ''); // 留空=清除口令；否则存 scrypt 哈希
    return sendJSON(res, 200, { ok: true, join_password_set: !!jp });
  }

  // 当前套账：邀请码（分享给员工注册用）
  if (p === '/api/books/invite' && method === 'GET') {
    if (role !== 'boss') return sendJSON(res, 403, { error: '只有老板可以查看邀请码' });
    let code = getSetting(bookId, 'invite_code', '');
    if (!code) { code = genInviteCode(); setSetting(bookId, 'invite_code', code); }
    return sendJSON(res, 200, { book_id: bookId, book_name: ctx.book.name, invite_code: code, has_password: !!getSetting(bookId, 'join_password', '') });
  }
  if (p === '/api/books/invite/reset' && method === 'POST') {
    if (role !== 'boss') return sendJSON(res, 403, { error: '只有老板可以重置邀请码' });
    const code = genInviteCode();
    setSetting(bookId, 'invite_code', code);
    return sendJSON(res, 200, { ok: true, invite_code: code });
  }

  // 当前套账：删除（仅套账创建者；需输入套账名确认；级联清空该套账全部数据）
  if (p === '/api/books/delete' && method === 'POST') {
    if (role !== 'boss') return sendJSON(res, 403, { error: '只有老板可以删除套账' });
    const b = await getBody(req).catch(() => ({}));
    const bk = db.prepare('SELECT * FROM books WHERE id=?').get(bookId);
    if (!bk) return sendJSON(res, 404, { error: '套账不存在' });
    if (bk.owner_account_id !== ctx.account.id) return sendJSON(res, 403, { error: '只有套账创建者（老板）可以删除' });
    if (String(b.confirm_name || '').trim() !== bk.name) return sendJSON(res, 400, { error: '确认名称与套账名不一致' });
    db.exec('BEGIN');
    try {
      // 1) 清空所有含 book_id 列的表中该套账数据（含 orders/customers/products/... 及 settings）
      const tabs = db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").all();
      for (const t of tabs) {
        const cols = db.prepare('PRAGMA table_info(' + t.name + ')').all();
        if (cols.some(c => c.name === 'book_id')) db.prepare('DELETE FROM ' + t.name + ' WHERE book_id=?').run(bookId);
      }
      // 2) 账号处理：只属于该套账的账号删除（并踢下线）；跨套账账号仅摘除本套账关联
      const accs = db.prepare('SELECT id FROM accounts WHERE book_id=?').all(bookId);
      for (const a of accs) {
        const others = db.prepare('SELECT COUNT(*) AS n FROM account_books WHERE account_id=? AND book_id<>?').get(a.id, bookId);
        if (others.n > 0) {
          db.prepare('DELETE FROM account_books WHERE account_id=? AND book_id=?').run(a.id, bookId);
        } else {
          db.prepare('DELETE FROM account_books WHERE account_id=?').run(a.id);
          db.prepare('DELETE FROM trusted_devices WHERE account_id=?').run(a.id);
          db.prepare('DELETE FROM accounts WHERE id=?').run(a.id);
          for (const [tk, s] of SESSIONS) { if (s.account_id === a.id) SESSIONS.delete(tk); }
        }
      }
      // 3) 删除套账本身
      db.prepare('DELETE FROM books WHERE id=?').run(bookId);
      db.exec('COMMIT');
    } catch (e) { db.exec('ROLLBACK'); return sendJSON(res, 500, { error: String(e.message || e) }); }
    // 4) 老板若还有其他套账 → 会话自动切换过去；否则登出
    // 🔴🔴 必须**逐个校验套账还在不在**，不能只看myBooks 返回的条数。
    //    myBooks() 的回落分支会返回 { id: acc.book_id } —— 而acc.book_id 指向的套账
    //    可能正是刚被删掉的那一个。于是 rest.length===1 但 nb===undefined，
    //    effShopName(undefined) 直接抛 'Cannot read properties of undefined' → 整个删除返 500，
    //    **数据其实已经 COMMIT 删掉了**，前端却收到失败 → 白屏 + 用户以为没删成功。
    //    （只删最后一个套账的老板 100% 触发。）
    const rest = myBooks(ctx.account).filter(r => db.prepare('SELECT id FROM books WHERE id=?').get(r.id));
    if (rest.length) {
      SESSIONS.get(ctx.token).book_id = rest[0].id;
      const nb = db.prepare('SELECT * FROM books WHERE id=?').get(rest[0].id);
      return sendJSON(res, 200, { ok: true, switched: true, book: pubBook(nb), shopName: nb ? effShopName(nb) : '' });
    }
    SESSIONS.delete(ctx.token);
    return sendJSON(res, 200, { ok: true, logged_out: true });
  }

  // ---------- 账户与角色管理（仅老板） ----------
  if (p === '/api/accounts' && method === 'GET') {
    if (role !== 'boss') return sendJSON(res, 403, { error: '只有老板可以管理账户' });
    const rows = db.prepare('SELECT * FROM accounts WHERE book_id=? ORDER BY id').all(bookId);
    return sendJSON(res, 200, rows.map(pubAcc));
  }
  if (p === '/api/accounts' && method === 'POST') {
    if (role !== 'boss') return sendJSON(res, 403, { error: '只有老板可以新增账户' });
    const b = await getBody(req);
    const username = String(b.username || '').trim();
    const password = String(b.password || '');
    const r = ['boss', 'manager', 'staff'].includes(b.role) ? b.role : 'staff';
    if (!username || username.length < 4) return sendJSON(res, 400, { error: '账号至少 4 位' });
    if (password.length < 4) return sendJSON(res, 400, { error: '密码至少 4 位' });
    if (db.prepare('SELECT id FROM accounts WHERE username=?').get(username)) return sendJSON(res, 400, { error: '该账号已存在' });
    const info = db.prepare('INSERT INTO accounts(username,password,display_name,role,book_id,active) VALUES(?,?,?,?,?,1)')
      .run(username, hashPassword(password), String(b.display_name || '').trim() || username, r, bookId);
    db.prepare('INSERT OR IGNORE INTO account_books(account_id,book_id) VALUES(?,?)').run(info.lastInsertRowid, bookId);
    return sendJSON(res, 200, { id: info.lastInsertRowid });
  }
  m = p.match(/^\/api\/accounts\/(\d+)$/);
  if (m && method === 'PUT') {
    if (role !== 'boss') return sendJSON(res, 403, { error: '只有老板可以修改账户' });
    const b = await getBody(req);
    const acc = db.prepare('SELECT * FROM accounts WHERE id=?').get(m[1]);
    if (!acc) return sendJSON(res, 404, { error: '账户不存在' });
    const r = ['boss', 'manager', 'staff'].includes(b.role) ? b.role : acc.role;
    if (b.password) db.prepare('UPDATE accounts SET password=? WHERE id=?').run(hashPassword(String(b.password)), m[1]);
    db.prepare('UPDATE accounts SET display_name=?, role=?, active=? WHERE id=?')
      .run(b.display_name != null ? String(b.display_name) : acc.display_name, r, b.active != null ? (b.active ? 1 : 0) : acc.active, m[1]);
    db.prepare('UPDATE books SET owner_account_id=? WHERE id=? AND owner_account_id IS NULL').run(m[1], acc.book_id);
    return sendJSON(res, 200, { ok: true });
  }
  if (m && method === 'DELETE') {
    if (role !== 'boss') return sendJSON(res, 403, { error: '只有老板可以删除账户' });
    const acc = db.prepare('SELECT * FROM accounts WHERE id=?').get(m[1]);
    if (!acc) return sendJSON(res, 404, { error: '账户不存在' });
    if (acc.id === ctx.account.id) return sendJSON(res, 400, { error: '不能删除自己' });
    db.prepare('DELETE FROM account_books WHERE account_id=?').run(m[1]);
    db.prepare('DELETE FROM accounts WHERE id=?').run(m[1]);
    return sendJSON(res, 200, { ok: true });
  }

  // ---------- 客户下单账号 ----------
  // 老板/经理给门店开通登录账号：手机号作登录名 + 初始密码，绑定 customers.id。
  // 2026-10-19 起支持「一个下单员负责多家门店」，绑定关系存 account_stores；
  //   accounts.customer_id 只保留**默认门店**（首个），供旧字段/旧客户端回落。
  //   「一个门店只允许一个账号」的限制已放开：同店可以有多个下单员（店长 + 下单员）。
  function accStoreIds(accId) {
    try {
      return db.prepare('SELECT customer_id FROM account_stores WHERE account_id=? AND book_id=? ORDER BY customer_id')
        .all(accId, bookId).map(r => Number(r.customer_id)).filter(n => isFinite(n) && n > 0);
    } catch (e) { return []; }
  }
  function custAccRow(r) {
    // 🔴🔴 以 account_stores 为准，但**默认门店必须并进授权集合**，与 custStoreIdsOf() 同口径。
    //    不并的后果：账号默认门店 A、account_stores 里只有 B → 后台管理页只显示 [B]，
    //    但这人实际能下 A 和 B 的单（ctx.customerIds 是并过的）。
    //    「后台看到的」和「实际能干的」不一致，客服据此改权限一定会改错。
    let ids = accStoreIds(r.id);
    const def = Number(r.customer_id);
    if (isFinite(def) && def > 0 && ids.indexOf(def) < 0) { ids.push(def); ids.sort((a, b) => a - b); }
    const names = ids.length
      ? db.prepare('SELECT id,name FROM customers WHERE book_id=? AND id IN (' + ids.map(() => '?').join(',') + ')').all(bookId, ...ids)
      : [];
    const nm = {}; names.forEach(n => { nm[n.id] = n.name; });
    const defId = ids.indexOf(Number(r.customer_id)) >= 0 ? Number(r.customer_id) : (ids[0] || null);
    return { id: r.id, username: r.username, display_name: r.display_name, role: r.role,
             roleLabel: roleLabel(r.role), active: !!r.active,
             customer_id: defId,                            // 默认门店（兼容旧字段）
             customer_ids: ids,                             // 🔴 授权的全部门店
             customer_names: ids.map(i => nm[i] || ('门店#' + i)),
             customer_name: defId ? (nm[defId] || '') : '',  // 兼容旧字段：默认门店名
             created_at: r.created_at };
  }
  // 校验一批门店 id 全部属于本套账，返回去重后的合法列表（任一非法即整体拒绝）
  function parseStoreIds(want, minOne) {
    const arr = Array.isArray(want) ? want : (want === undefined || want === null || want === '' ? [] : [want]);
    const ids = [];
    for (const x of arr) {
      const n = Number(x);
      if (!isFinite(n) || n <= 0) continue;
      if (ids.indexOf(n) < 0) ids.push(n);
    }
    if (ids.length) {
      const ph = ids.map(() => '?').join(',');
      const ok = db.prepare('SELECT id FROM customers WHERE book_id=? AND id IN (' + ph + ')').all(bookId, ...ids);
      if (ok.length !== ids.length) return null;// 有不存在的门店 → 整体拒绝，别静默丢一家
    } else if (minOne) {
      return null;
    }
    return ids;
  }
  function writeAccStores(accId, ids, defId) {
    db.prepare('DELETE FROM account_stores WHERE account_id=?').run(accId);
    const ins = db.prepare('INSERT OR IGNORE INTO account_stores(account_id, book_id, customer_id) VALUES(?,?,?)');
    for (const i of ids) ins.run(accId, bookId, i);
    db.prepare('UPDATE accounts SET customer_id=? WHERE id=?').run(defId || null, accId);
  }
  if (p === '/api/customer-accounts' && method === 'GET') {
    if (role !== 'boss' && role !== 'manager') return sendJSON(res, 403, { error: '只有老板/经理可以管理客户账号' });
    const rows = db.prepare("SELECT * FROM accounts WHERE book_id=? AND role='customer' ORDER BY id DESC").all(bookId);
    return sendJSON(res, 200, rows.map(custAccRow));
  }
  if (p === '/api/customer-accounts' && method === 'POST') {
    if (role !== 'boss' && role !== 'manager') return sendJSON(res, 403, { error: '只有老板/经理可以开通客户账号' });
    const b = await getBody(req);
    const phone = String(b.phone || '').trim();
    const pwd = String(b.password || '');
    if (!/^1[3-9]\d{9}$/.test(phone)) return sendJSON(res, 400, { error: '请填写正确的 11 位手机号' });
    if (pwd.length < 4) return sendJSON(res, 400, { error: '密码至少 4 位' });
    // 多门店：优先读customer_ids[]，兼容只传 customer_id 的旧客户端
    const ids = parseStoreIds(b.customer_ids !== undefined ? b.customer_ids : b.customer_id, true);
    if (!ids || !ids.length) return sendJSON(res, 400, { error: '请选择要开通的门店' });
    if (db.prepare('SELECT id FROM accounts WHERE username=?').get(phone)) return sendJSON(res, 400, { error: '该手机号已被注册' });
    const firstCust = db.prepare('SELECT * FROM customers WHERE id=? AND book_id=?').get(ids[0], bookId);
    const info = db.prepare("INSERT INTO accounts(username,password,display_name,role,book_id,customer_id,active) VALUES(?,?,?,?,?,?,1)")
      .run(phone, hashPassword(pwd), String(b.display_name || '').trim() || firstCust.name, 'customer', bookId, ids[0]);
    const aid = info.lastInsertRowid;
    db.prepare('INSERT OR IGNORE INTO account_books(account_id,book_id) VALUES(?,?)').run(aid, bookId);
    writeAccStores(aid, ids, ids[0]);
    return sendJSON(res, 200, { id: aid, phone, customer_ids: ids });
  }
  // 🔴 2026-10-19 新增：改门店绑定（增/减门店）。前端「编辑客户账号」用这个。
  m = p.match(/^\/api\/customer-accounts\/(\d+)\/stores$/);
  if (m && method === 'PUT') {
    if (role !== 'boss' && role !== 'manager') return sendJSON(res, 403, { error: '只有老板/经理可以修改客户账号门店' });
    const acc = db.prepare("SELECT * FROM accounts WHERE id=? AND book_id=? AND role='customer'").get(m[1], bookId);
    if (!acc) return sendJSON(res, 404, { error: '客户账号不存在' });
    const b = await getBody(req);
    const ids = parseStoreIds(b.customer_ids, true);
    if (!ids || !ids.length) return sendJSON(res, 400, { error: '至少要保留一家门店' });
    const defId = Number(b.customer_id) && ids.indexOf(Number(b.customer_id)) >= 0 ? Number(b.customer_id) : ids[0];
    writeAccStores(Number(m[1]), ids, defId);
    return sendJSON(res, 200, { ok: true, customer_ids: ids, customer_id: defId });
  }
  m = p.match(/^\/api\/customer-accounts\/(\d+)\/password$/);
  if (m && method === 'PUT') {
    if (role !== 'boss' && role !== 'manager') return sendJSON(res, 403, { error: '只有老板/经理可以重置客户密码' });
    const acc = db.prepare("SELECT * FROM accounts WHERE id=? AND book_id=? AND role='customer'").get(m[1], bookId);
    if (!acc) return sendJSON(res, 404, { error: '客户账号不存在' });
    const b = await getBody(req);
    const pwd = String(b.password || '');
    if (pwd.length < 4) return sendJSON(res, 400, { error: '密码至少 4 位' });
    db.prepare('UPDATE accounts SET password=? WHERE id=?').run(hashPassword(pwd), m[1]);
    return sendJSON(res, 200, { ok: true });
  }
  m = p.match(/^\/api\/customer-accounts\/(\d+)\/active$/);
  if (m && method === 'PUT') {
    if (role !== 'boss' && role !== 'manager') return sendJSON(res, 403, { error: '只有老板/经理可以停用客户账号' });
    const acc = db.prepare("SELECT * FROM accounts WHERE id=? AND book_id=? AND role='customer'").get(m[1], bookId);
    if (!acc) return sendJSON(res, 404, { error: '客户账号不存在' });
    const b = await getBody(req);
    db.prepare('UPDATE accounts SET active=? WHERE id=?').run(b.active ? 1 : 0, m[1]);
    return sendJSON(res, 200, { ok: true });
  }
  m = p.match(/^\/api\/customer-accounts\/(\d+)$/);
  if (m && method === 'DELETE') {
    if (role !== 'boss' && role !== 'manager') return sendJSON(res, 403, { error: '只有老板/经理可以删除客户账号' });
    const acc = db.prepare("SELECT * FROM accounts WHERE id=? AND book_id=? AND role='customer'").get(m[1], bookId);
    if (!acc) return sendJSON(res, 404, { error: '客户账号不存在' });
    // 🔴 连带清门店绑定，否则 account_stores 会留孤儿行，
    //    同一手机号重新开通时会被旧绑定悄悄授权到不该看的门店。
    db.prepare('DELETE FROM account_stores WHERE account_id=?').run(m[1]);
    db.prepare('DELETE FROM account_books WHERE account_id=?').run(m[1]);
    db.prepare('DELETE FROM accounts WHERE id=?').run(m[1]);
    return sendJSON(res, 200, { ok: true });
  }

  // ---------- 平台管理（仅 is_admin，与套账角色完全解耦）----------
  // 管理员可跨套账查看全平台账户、违规记录，并封禁/解封。
  // 与「套账老板」的区别：老板只能管自己套账，管理员是平台级。
  const needAdmin = () => {
    if (!ctx.account.is_admin) { sendJSON(res, 403, { error: '仅平台管理员可操作' }); return false; }
    return true;
  };
  if (p === '/api/admin/overview' && method === 'GET') {
    if (!needAdmin()) return;
    return sendJSON(res, 200, MOD.adminOverview());
  }
  if (p === '/api/admin/violations' && method === 'GET') {
    if (!needAdmin()) return;
    const st = u.searchParams.get('status') || '';
    const rows = st
      ? db.prepare('SELECT v.*, a.username, a.display_name FROM violations v LEFT JOIN accounts a ON a.id=v.account_id WHERE v.status=? ORDER BY v.id DESC LIMIT 300').all(st)
      : db.prepare('SELECT v.*, a.username, a.display_name FROM violations v LEFT JOIN accounts a ON a.id=v.account_id ORDER BY (v.status=\'pending\') DESC, v.id DESC LIMIT 300').all();
    return sendJSON(res, 200, rows);
  }
  // 人工封禁
  m = p.match(/^\/api\/admin\/accounts\/(\d+)\/ban$/);
  if (m && method === 'POST') {
    if (!needAdmin()) return;
    const b = await getBody(req).catch(() => ({}));
    const acc = db.prepare('SELECT * FROM accounts WHERE id=?').get(m[1]);
    if (!acc) return sendJSON(res, 404, { error: '账户不存在' });
    if (acc.id === ctx.account.id) return sendJSON(res, 400, { error: '不能封禁自己' });
    if (acc.is_admin) return sendJSON(res, 403, { error: '管理员账号不可被封禁' });
    const ok = MOD.banAccount(Number(m[1]), String(b.reason || '管理员手动封禁'), b.detail || '', ctx.account.id);
    return sendJSON(res, ok ? 200 : 400, ok ? { ok: true } : { error: '封禁失败' });
  }
  // 解封
  m = p.match(/^\/api\/admin\/accounts\/(\d+)\/unban$/);
  if (m && method === 'POST') {
    if (!needAdmin()) return;
    const acc = db.prepare('SELECT * FROM accounts WHERE id=?').get(m[1]);
    if (!acc) return sendJSON(res, 404, { error: '账户不存在' });
    const ok = MOD.unbanAccount(Number(m[1]), ctx.account.id);
    return sendJSON(res, 200, { ok });
  }
  // 白名单切换：豁免内容检测 + 豁免自动封禁
  m = p.match(/^\/api\/admin\/accounts\/(\d+)\/whitelist$/);
  if (m && method === 'POST') {
    if (!needAdmin()) return;
    const b = await getBody(req).catch(() => ({}));
    const acc = db.prepare('SELECT * FROM accounts WHERE id=?').get(m[1]);
    if (!acc) return sendJSON(res, 404, { error: '账户不存在' });
    if (acc.is_admin) return sendJSON(res, 400, { error: '管理员账号天然豁免，无需加白' });
    const ok = MOD.setWhitelist(Number(m[1]), !!b.on, ctx.account.id);
    return sendJSON(res, ok ? 200 : 400, ok ? { ok: true } : { error: '操作失败' });
  }
  // 违规记录标记为已整改（管理员代为处理）
  m = p.match(/^\/api\/admin\/violations\/(\d+)\/resolve$/);
  if (m && method === 'POST') {
    if (!needAdmin()) return;
    const b = await getBody(req).catch(() => ({}));
    const r = db.prepare("UPDATE violations SET status='resolved', resolved_at=datetime('now','localtime'), admin_note=? WHERE id=? AND status='pending'")
      .run(String(b.note || '管理员标记已整改'), m[1]);
    if (!r.changes) return sendJSON(res, 400, { error: '记录不存在或已处理' });
    return sendJSON(res, 200, { ok: true });
  }

  // ---------- 客户 ----------
  if (p === '/api/customers' && method === 'GET') {
    // 🔴 原来这个接口**完全没有鉴权**，任何登录账号都能拉到全部门店的
    //    名称/联系人/电话/地址/备注 —— 加上客户角色后等于直接泄底。补上。
    if (!need('customer.view')) return;
    if (isCustomer(ctx)) {
      // 2026-10-19：返回他负责的**全部**门店（原来只返回默认那一家，多门店时开单页选不了店）
      const ids = ctx.customerIds || [];
      if (!ids.length) return sendJSON(res, 200, []);
      const ph = ids.map(() => '?').join(',');
      const mine = db.prepare('SELECT * FROM customers WHERE book_id=? AND id IN (' + ph + ') ORDER BY id').all(bookId, ...ids);
      return sendJSON(res, 200, mine.map(pubCustomerFor));
    }
    const q = u.searchParams.get('q') || '';
    const fw = fuzzyWhere(q, ['name', 'phone', 'contact', 'address', 'remark']);
    let rows;
    if (fw) rows = db.prepare('SELECT * FROM customers WHERE book_id=? AND ' + fw.sql + ' ORDER BY id DESC').all(bookId, ...fw.args);
    else rows = db.prepare('SELECT * FROM customers WHERE book_id=? ORDER BY id DESC').all(bookId);
    return sendJSON(res, 200, rows);
  }
  if (p === '/api/customers' && method === 'POST') {
    if (!need('customer.edit')) return;
    const b = await getBody(req);
    if (!b.name) return sendJSON(res, 400, { error: '名称必填' });
    const info = db.prepare('INSERT INTO customers(book_id,name,type,contact,phone,address,remark) VALUES(?,?,?,?,?,?,?)').run(bookId, b.name, b.type || 'store', b.contact || '', b.phone || '', b.address || '', b.remark || '');
    const cid = info.lastInsertRowid;
    const v = MOD ? MOD.scanAndRecord(bookId, ctx.account.id, 'customer', cid, String(b.name), 'name', b.name) : null;
    return sendJSON(res, 200, v ? { id: cid, violation: v } : { id: cid });
  }
  m = p.match(/^\/api\/customers\/(\d+)$/);
  if (m && method === 'PUT') {
    if (!need('customer.edit')) return;
    const b = await getBody(req);
    const cur = db.prepare('SELECT * FROM customers WHERE id=? AND book_id=?').get(m[1], bookId);
    if (!cur) return sendJSON(res, 404, { error: '客户不存在' });
    db.prepare('UPDATE customers SET name=?,type=?,contact=?,phone=?,address=?,remark=? WHERE id=?').run(
      String(b.name == null ? cur.name : b.name), String(b.type == null ? cur.type : b.type),
      String(b.contact == null ? '' : b.contact), String(b.phone == null ? '' : b.phone),
      String(b.address == null ? '' : b.address), String(b.remark == null ? '' : b.remark), m[1]);
    const v = MOD ? MOD.scanAndRecord(bookId, ctx.account.id, 'customer', Number(m[1]), String(b.name || ''), 'name', b.name) : null;
    return sendJSON(res, 200, v ? { ok: true, violation: v } : { ok: true });
  }
  if (m && method === 'DELETE') {
    if (!need('customer.edit')) return;
    if (!db.prepare('SELECT id FROM customers WHERE id=? AND book_id=?').get(m[1], bookId)) return sendJSON(res, 404, { error: '客户不存在' });
    db.prepare('DELETE FROM customers WHERE id=?').run(m[1]);
    return sendJSON(res, 200, { ok: true });
  }

  // ---------- 商品 ----------
  if (p === '/api/products' && method === 'GET') {
    // 🔴 原来没有鉴权，且 SELECT p.* 会把 cost（成本价）一起返回。
    if (!need('product.view')) return;
    const q = u.searchParams.get('q') || '';
    const sql = "SELECT p.*, COALESCE(i.qty,0) as qty, COALESCE(i.low_threshold,0) as low_threshold FROM products p LEFT JOIN inventory i ON i.product_id=p.id WHERE p.book_id=? ";
    const fw = fuzzyWhere(q, ['p.name', 'p.barcode', 'p.spec', 'p.unit']);
    let rows;
    if (fw) rows = db.prepare(sql + "AND " + fw.sql + " ORDER BY p.id DESC").all(bookId, ...fw.args);
    else rows = db.prepare(sql + "ORDER BY p.id DESC").all(bookId);
    // 客户：换成「当前选中门店的历史送货价」并剥掉成本价/库存/预警阈值
    if (isCustomer(ctx)) {
      const pmap = customerPriceMap(bookId, custTarget(ctx, u.searchParams.get('customer_id')));
      rows = rows.map(r => pubProductFor(r, pmap));
    }
    return sendJSON(res, 200, rows);
  }
  if (p === '/api/products' && method === 'POST') {
    if (!need('product.edit')) return;
    const b = await getBody(req);
    if (!b.name) return sendJSON(res, 400, { error: '商品名必填' });
    const info = db.prepare('INSERT INTO products(book_id,name,barcode,spec,unit,price,cost,pack_size) VALUES(?,?,?,?,?,?,?,?)').run(bookId, b.name, b.barcode || '', b.spec || '', b.unit || '个', Number(b.price) || 0, Number(b.cost) || 0, Math.max(0, Math.round(Number(b.pack_size))) || 0);
    const pid = info.lastInsertRowid;
    db.prepare('INSERT OR IGNORE INTO inventory(book_id,product_id,qty,low_threshold) VALUES(?,?,?,?)').run(bookId, pid, Number(b.qty) || 0, Number(b.low_threshold) || 0);
    // MOD：登记违规（不拦截写入，30 分钟内改名即自动解除）
    const v = MOD ? MOD.scanAndRecord(bookId, ctx.account.id, 'product', pid, String(b.name), 'name', b.name) : null;
    return sendJSON(res, 200, v ? { id: pid, violation: v } : { id: pid });
  }
  m = p.match(/^\/api\/products\/(\d+)$/);
  if (m && method === 'PUT') {
    if (!need('product.edit')) return;
    const b = await getBody(req);
    if (!db.prepare('SELECT id FROM products WHERE id=? AND book_id=?').get(m[1], bookId)) return sendJSON(res, 404, { error: '商品不存在' });
    // 兜底 undefined：node:sqlite 对 undefined 参数直接报
    // "Provided value cannot be bound to SQLite parameter N"（PATCH 式局部提交会命中）
    // pack_size = 每箱装量；件数 = EA ÷ 装箱量。0 = 未维护（件数列留空，不算成 0）
    db.prepare('UPDATE products SET name=?,barcode=?,spec=?,unit=?,price=?,cost=?,pack_size=? WHERE id=?').run(
      String(b.name == null ? '' : b.name), String(b.barcode == null ? '' : b.barcode),
      String(b.spec == null ? '' : b.spec), String(b.unit == null ? '个' : b.unit),
      Number(b.price) || 0, Number(b.cost) || 0,
      Math.max(0, Math.round(Number(b.pack_size))) || 0, m[1]);
    db.prepare('INSERT OR IGNORE INTO inventory(book_id,product_id,qty,low_threshold) VALUES(?,?,?,?)').run(bookId, m[1], Number(b.qty) || 0, Number(b.low_threshold) || 0);
    db.prepare('UPDATE inventory SET qty=?, low_threshold=? WHERE product_id=?').run(Number(b.qty) || 0, Number(b.low_threshold) || 0, m[1]);
    // MOD：改名后重新检测；若已不含违规词则把 pending 记录置为 resolved
    const v = MOD ? MOD.scanAndRecord(bookId, ctx.account.id, 'product', Number(m[1]), String(b.name || ''), 'name', b.name) : null;
    return sendJSON(res, 200, v ? { ok: true, violation: v } : { ok: true });
  }
  if (m && method === 'DELETE') {
    if (!need('product.edit')) return;
    if (!db.prepare('SELECT id FROM products WHERE id=? AND book_id=?').get(m[1], bookId)) return sendJSON(res, 404, { error: '商品不存在' });
    db.prepare('DELETE FROM inventory WHERE product_id=?').run(m[1]);
    // 🔴 2026-10-17 修：删商品必须连带清关联表，否则留孤儿行。
    //   只清 inventory 留下了 prices / customer_prices 中指向已删商品的行，
    //   以致 PRAGMA foreign_key_check 持续报错、数据库慢慢变脏。
    //   注意 order_items 不清：历史订单明细要保留商品名快照，报表靠它算钱。
    db.prepare('DELETE FROM prices WHERE product_id=?').run(m[1]);
    db.prepare('DELETE FROM customer_prices WHERE product_id=?').run(m[1]);
    db.prepare('DELETE FROM products WHERE id=?').run(m[1]);
    return sendJSON(res, 200, { ok: true });
  }
  if (p === '/api/products/template' && method === 'GET') {
    if (!need('product.edit')) return;
    const buf = xlsxUtil.buildTemplate();
    res.writeHead(200, { 'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 'Content-Disposition': 'attachment; filename="products_template.xlsx"' });
    return res.end(buf);
  }
  if (p === '/api/products/import' && method === 'POST') {
    if (!need('product.import')) return;
    try {
      const buf = await getRawBody(req);
      // R5：入口先校验 ZIP 魔数，旧 .xls / CSV 改名上传给明确报错
      if (buf.length < 2 || buf[0] !== 0x50 || buf[1] !== 0x4B) {
        return sendJSON(res, 400, { error: '文件不是有效的 .xlsx（ZIP 格式），请用 Excel 另存为 .xlsx 后重试' });
      }
      const rows = await xlsxUtil.parseXlsx(buf);
      const result = importProducts(rows, bookId);
      if (result.error) return sendJSON(res, 400, { error: result.error });
      return sendJSON(res, 200, result);
    } catch (e) { return sendJSON(res, 400, { error: '导入失败：' + (e.message || e) }); }
  }

  // ---------- 库存 ----------
  if (p === '/api/inventory' && method === 'GET') {
    // 🔴 库存是经营数据，客户一概不给（原来完全没鉴权）
    if (!need('inventory.view')) return;
    if (isCustomer(ctx)) return sendJSON(res, 403, { error: '客户账号不能查看库存' });
    const rows = db.prepare("SELECT p.id,p.name,p.spec,p.unit,p.price,COALESCE(i.qty,0) as qty,COALESCE(i.low_threshold,0) as low_threshold FROM products p LEFT JOIN inventory i ON i.product_id=p.id WHERE p.book_id=? ORDER BY p.id DESC").all(bookId);
    return sendJSON(res, 200, rows);
  }
  m = p.match(/^\/api\/inventory\/(\d+)$/);
  if (m && method === 'DELETE') {
    if (!need('inventory.edit')) return;
    db.prepare('DELETE FROM inventory WHERE product_id=? AND book_id=?').run(m[1], bookId);
    return sendJSON(res, 200, { ok: true });
  }

  // ---------- 开单 ----------
  if (p === '/api/orders' && method === 'POST') {
    if (!need('sale.create')) return;
    const b = await getBody(req);
    // 🔴 客户角色：body.customer_id 只在他**被授权的门店集合**里才认，
    //    否则强制回落默认门店 —— 改报文想冒充别家门店下单是不行的。
    //    同时禁止客户提交退货单，并强制整单折扣为 1（折扣只能由商家给）。
    if (isCustomer(ctx)) {
      if (!ctx.customerIds || !ctx.customerIds.length) return sendJSON(res, 403, { error: '账号未绑定门店，请联系商家开通' });
      if (b.type === 'return') return sendJSON(res, 403, { error: '客户账号不能提交退货单' });
      // 🔴🔴 **显式传了门店就必须是他授权的**，不合法要**报错**而不是悄悄回落默认门店。
      //    回落会造成「明明给 C 店下单、单却落进 A 店」—— 金额和历史价全错，且用户毫无察觉。
      const want = Number(b.customer_id);
      if (want) {
        if (!custAllowed(ctx, want)) return sendJSON(res, 403, { error: '只能给自己负责的门店下单' });
        b.customer_id = want;
      } else {
        b.customer_id = Number(ctx.customerId) || (ctx.customerIds[0] || 0);
      }
      b.discount = 1;
    }
    if (!b.customer_id) return sendJSON(res, 400, { error: '请选择客户' });
    if (!Array.isArray(b.items) || b.items.length === 0) return sendJSON(res, 400, { error: '请添加商品' });
    const cust = db.prepare('SELECT * FROM customers WHERE id=? AND book_id=?').get(b.customer_id, bookId);
    if (!cust) return sendJSON(res, 400, { error: '客户不存在' });
    // 🔴 退货单：type='return'。前端（原生开单页/网页端）一律按正数量提交，
    // 由服务端统一取负并回补库存，同时强制 status='returned' + TH 单号。
    // 不这么做的后果：建出一张 XS 正数销售单 → 两端小票标题都判成「销售订单」。
    const isReturn = b.type === 'return';
    const lines = []; let total = 0;
    for (const it of b.items) {
      const prod = db.prepare('SELECT * FROM products WHERE id=? AND book_id=?').get(it.product_id, bookId);
      if (!prod) return sendJSON(res, 400, { error: '商品不存在 id=' + it.product_id });
      const qty = Math.abs(Number(it.qty));
      if (!(qty > 0)) return sendJSON(res, 400, { error: '数量无效' });
      // 单品折扣：it.price 视为「折前单价」，乘 it.discount 得成交单价。
      // 不传 discount 的行按 1 处理（不打折），所以「有的单品有折扣，有的没有」天然支持。
      let c;
      if (it.discount != null) {
        c = lineCalc(it.price !== undefined ? it.price : prod.price, qty, it.discount);
      } else {
        // 没带 discount 时保持旧行为：price 就是成交价（兼容老调用方/表格批量开单）
        const p0 = Number(it.price !== undefined ? it.price : prod.price) || 0;
        c = { price: p0, orig_price: p0, discount: 1, subtotal: +(p0 * qty).toFixed(2) };
      }
      const subtotal = c.subtotal; total += subtotal;
      lines.push({ prod, qty, price: c.price, cost: Number(prod.cost) || 0, subtotal,
                   discount: c.discount, orig_price: c.orig_price });
    }
    total = +total.toFixed(2);
    const discount = Math.min(1, Math.max(0.1, Number(b.discount) || 1));
    const payable = +(total * discount).toFixed(2);
    // 🔴 退货（type='return'）：金额取负 + TH 单号 + status 强制 returned。
    // 不这么做的话建出来是张 XS 正数销售单 → 两端小票标题都判成「销售订单」。
    const sign = isReturn ? -1 : 1;
    const totalSigned = +(total * sign).toFixed(2);
    const payableSigned = +(payable * sign).toFixed(2);
    const order_no = isReturn ? genReturnNo(bookId) : genOrderNo(bookId);
    let orderId = 0;
    db.exec('BEGIN');
    try {
      const oi = db.prepare("INSERT INTO orders(book_id,order_no,customer_id,customer_name,total,payable,discount,order_date,status,remark) VALUES(?,?,?,?,?,?,?,?,?,?)").run(bookId, order_no, cust.id, cust.name, totalSigned, payableSigned, discount, b.order_date || null, isReturn ? 'returned' : (b.status || 'paid'), b.remark || (isReturn ? '销售退货' : ''));
      orderId = oi.lastInsertRowid;
      for (const L of lines) {
        const sq = L.qty * sign;                                  // 落库数量：退货为负
        const ssub = +(L.subtotal * sign).toFixed(2);             // 落库小计：退货为负
        db.prepare("INSERT INTO order_items(book_id,order_id,product_id,product_name,spec,barcode,unit,qty,price,cost,subtotal,discount,orig_price) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)").run(bookId, orderId, L.prod.id, L.prod.name, L.prod.spec, L.prod.barcode || '', L.prod.unit, sq, L.price, L.cost, ssub, L.discount, L.orig_price);
        // 退货是入库 → 库存回补(+qty)；销售是出库 → 扣减(-qty)
        db.prepare("UPDATE inventory SET qty=qty+?, updated_at=datetime('now','localtime') WHERE product_id=?").run(-sq, L.prod.id);
      }
      db.exec('COMMIT');
    } catch (e) {
      // 只有真在事务里才回滚（否则 ROLLBACK 自己抛错会盖掉真实错误）
      try { db.exec('ROLLBACK'); } catch (_) { }
      console.error('createOrder:', (e && e.message) || e);
      return sendJSON(res, 500, { error: String((e && e.message) || e) });
    }
    // 记录门店历史价放事务外：它内部自带 try/catch，失败也不该影响已成交的订单
    // 🔴 退货不写历史价，否则会把退货价当成"上次成交价"污染下次开单取价
    if (!isReturn) recordCustomerPrices(bookId, cust.id, lines, discount, orderId);
    return sendJSON(res, 200, {
      order_id: orderId, order_no, total: totalSigned, discount,
      payable: payableSigned, is_return: isReturn, payable_signed: payableSigned
    });
  }
  if (p === '/api/orders' && method === 'GET') {
    // 🔴 原来没有任何鉴权：登录即可拉全店订单（含别家门店的金额）。补上。
    if (!need('order.view')) return;
    const date = u.searchParams.get('date');
    const from = u.searchParams.get('from'), to = u.searchParams.get('to');
    const min = u.searchParams.get('min'), max = u.searchParams.get('max');
    const status = u.searchParams.get('status');
    const q = u.searchParams.get('q') || '';
    const dExpr = "date(COALESCE(order_date,created_at))";
    const conds = ['book_id=?']; const args = [bookId];
    // 客户：只返回自己负责门店的订单（锁死，不接受前端传参绕过）
    if (isCustomer(ctx)) {
      const ids = ctx.customerIds || [];
      if (!ids.length) return sendJSON(res, 200, []);
      conds.push('customer_id IN (' + ids.map(() => '?').join(',') + ')');
      args.push(...ids);
    }
    if (status) { conds.push('status=?'); args.push(status); }
    const fw = fuzzyWhere(q, ['order_no', 'customer_name', 'remark']);
    if (fw) { conds.push(fw.sql); args.push(...fw.args); }
    let rows;
    if (from || to || (min != null && min !== '') || (max != null && max !== '')) {
      if (from) { conds.push(dExpr + ">=?"); args.push(from); }
      if (to) { conds.push(dExpr + "<=?"); args.push(to); }
      const amt = "COALESCE(payable,total)";
      if (min != null && min !== '') { conds.push(amt + ">=?"); args.push(Number(min)); }
      if (max != null && max !== '') { conds.push(amt + "<=?"); args.push(Number(max)); }
      rows = db.prepare("SELECT * FROM orders WHERE " + conds.join(' AND ') + " ORDER BY id DESC LIMIT 500").all(...args);
    } else if (date) {
      conds.push(dExpr + "=?"); args.push(date);
      rows = db.prepare("SELECT * FROM orders WHERE " + conds.join(' AND ') + " ORDER BY id DESC").all(...args);
    } else {
      rows = db.prepare("SELECT * FROM orders WHERE " + conds.join(' AND ') + " ORDER BY id DESC LIMIT " + (fw ? 500 : 200)).all(...args);
    }
    return sendJSON(res, 200, rows);
  }
  // 开单模板：行=门店名称、列=产品名称、单位 EA、自动带汇总公式（不含价格，只填数量）
  // 「合计（件数）」列 = EA ÷ 每箱装量（products.pack_size）。pack_size 为 0（未维护）时留空。
  if (p === '/api/orders/template' && method === 'GET') {
    // 🔴 这个模板会把**全套账所有门店名 + 所有商品名**导成 xlsx，客户拿到等于泄底
    if (!need('order.view')) return;
    if (isCustomer(ctx)) return sendJSON(res, 403, { error: '客户账号不能下载开单模板' });
    try {
      const stores = db.prepare("SELECT name FROM customers WHERE book_id=? AND name IS NOT NULL AND name<>'' ORDER BY name").all(bookId).map(r => r.name);
      // 带出 pack_size（每箱装量）：0 表示未维护，件数列会留空
      const prods = db.prepare("SELECT name, COALESCE(pack_size,0) AS pack_size FROM products WHERE book_id=? AND name IS NOT NULL AND name<>'' ORDER BY name").all(bookId)
        .map(r => ({ name: r.name, packSize: Number(r.pack_size) || 0 }));
      const buf = xlsxUtil.buildOrderTemplate(stores, prods);
      res.writeHead(200, {
        'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        'Content-Disposition': 'attachment; filename="order_template.xlsx"',
        'Content-Length': buf.length
      });
      return res.end(buf);
    } catch (e) { return sendJSON(res, 500, { error: '生成模板失败：' + (e.message || e) }); }
  }
  // 模板自动同步（2026-10-17）：管理员手上那份已填好数量的旧表 → 上传后按数据库现状重建，
  //   旧数量照字段搬迁，新商品行/新门店列自动补齐。不动任何业务数据。
  if (p === '/api/orders/template-sync' && method === 'POST') {
    if (!need('sale.create')) return;
    try {
      const buf = await getRawBody(req);
      if (!buf || !buf.length) return sendJSON(res, 400, { error: '文件是空的' });
      if (buf.length < 2 || buf[0] !== 0x50 || buf[1] !== 0x4B) {
        return sendJSON(res, 400, { error: '文件不是有效的 .xlsx（ZIP 格式），请用 Excel 另存为 .xlsx 后重试' });
      }
      const stores = db.prepare("SELECT name FROM customers WHERE book_id=? AND name IS NOT NULL AND name<>'' ORDER BY name").all(bookId).map(r => r.name);
      const prods = db.prepare("SELECT name, COALESCE(pack_size,0) AS pack_size FROM products WHERE book_id=? AND name IS NOT NULL AND name<>'' ORDER BY name").all(bookId)
        .map(r => ({ name: r.name, packSize: Number(r.pack_size) || 0 }));

      const grid = await xlsxUtil.parseXlsx(buf);
      const head = (grid[0] || []).map(x => String(x == null ? '' : x).trim());
      // 🔴 表头 B 列往后不全是门店：末两列是「合计」「合计（件数）」汇总列，
      //    不排除会被当成「已删门店」报出来，还会污染门店列对齐。
      const TOTAL_COLS = new Set(['合计', '合计（件数）', '合计(件数)', '小计', '总计']);
      const oldStoreCols = [];
      for (let c = 1; c < head.length; c++) {
        const hn = head[c];
        if (hn === '') continue;
        if (TOTAL_COLS.has(hn)) continue;
        if (/合计|小计|总计/.test(hn)) continue;
        oldStoreCols.push({ col: c, name: hn });
      }
      // 商品行：第 2 行起 A 列非空，排除末行「合计」
      const curStoreNames = stores.filter(x => x != null && String(x).trim() !== '');
      const curProdNames = prods.map(x => x.name).filter(x => x && String(x).trim() !== '');
      // 门店列对齐：精确名先行，再用互包作弱匹配
      const colMap = []; const usedStores = new Set(); const deadStores = [];
      for (const oc of oldStoreCols) {
        let hit = curStoreNames.find(n => !usedStores.has(n) && n === oc.name);
        if (!hit) hit = curStoreNames.find(n => !usedStores.has(n) && (n.includes(oc.name) || oc.name.includes(n)));
        if (hit) { colMap.push({ col: oc.col, name: hit }); usedStores.add(hit); }
        else deadStores.push(oc.name);
      }
      const newStores = curStoreNames.filter(n => !usedStores.has(n));
      // 商品行对齐：旧表行名对到数据库商品名
      const prefill = {};
      const keptProducts = new Set(); const seenProducts = new Set(); const deadProducts = [];
      for (let r = 1; r < grid.length; r++) {
        const nm = String((grid[r] || [])[0] == null ? '' : grid[r][0]).trim();
        if (!nm || nm === '合计' || nm === '总计') continue;
        let hit = curProdNames.find(n => n === nm);
        if (!hit) hit = curProdNames.find(n => n.includes(nm) || nm.includes(n));
        if (!hit) { deadProducts.push(nm); continue; }
        seenProducts.add(hit);
        const src = grid[r] || [];
        const arr = []; let any = false;
        for (let i = 0; i < curStoreNames.length; i++) {
          const cm = colMap.find(x => x.name === curStoreNames[i]);
          let q = 0;
          if (cm) { const v = Number(src[cm.col]); q = (Number.isFinite(v) && v > 0) ? v : 0; }
          arr.push(q); if (q > 0) any = true;
        }
        if (any) { prefill[hit] = arr; keptProducts.add(hit); }
      }
      // 🔴 「新增商品」= 旧模板里根本没有的行。空行也算已存在，
      //    否则第二次同步又会把 100+ 个空行报成「新增」，经理看着以为没生效。
      const newProducts = curProdNames.filter(n => !seenProducts.has(n));
      const outBuf = xlsxUtil.buildOrderTemplate(curStoreNames, prods, prefill);
      // 同步结果写到响应头，前端下载后展示弹窗
      res.setHeader('X-TplSync', encodeURIComponent(JSON.stringify({
        newProducts, newStores, deadProducts, deadStores, kept: keptProducts.size, seen: seenProducts.size
      })));
      res.writeHead(200, {
        'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        'Content-Disposition': 'attachment; filename="order_template_synced.xlsx"',
        'Content-Length': outBuf.length
      });
      return res.end(outBuf);
    } catch (e) { return sendJSON(res, 400, { error: '同步失败：' + (e.message || e) }); }
  }
  // 开单模板导入：解析上传的 xlsx 并与本套账的客户/商品做模糊匹配（只解析，不落库）
  if (p === '/api/orders/parse' && method === 'POST') {
    if (!need('sale.create')) return;
    try {
      const buf = await getRawBody(req);
      if (!buf || !buf.length) return sendJSON(res, 400, { error: '文件是空的' });
      // R5：入口先校验 ZIP 魔数，旧 .xls / CSV 改名上传给明确报错
      if (buf.length < 2 || buf[0] !== 0x50 || buf[1] !== 0x4B) {
        return sendJSON(res, 400, { error: '文件不是有效的 .xlsx（ZIP 格式），请用 Excel 另存为 .xlsx 后重试' });
      }
      const customers = db.prepare("SELECT id,name FROM customers WHERE book_id=? AND name IS NOT NULL AND name<>''").all(bookId);
      const products = db.prepare("SELECT id,name,price,unit FROM products WHERE book_id=?").all(bookId);
      const r = await orderTable.parseOrderTable(buf, customers, products);
      if (!r.stores.length || !r.lines.length) {
        return sendJSON(res, 400, { error: '文件里没解析出有效的开单数据。请确保第一行是门店名（或产品名）、第一列是产品名（或门店名），数量填在对应单元格里；建议直接用「开单模板」下载标准模板填写。' });
      }
      return sendJSON(res, 200, r);
    } catch (e) { return sendJSON(res, 400, { error: '解析失败：' + (e.message || e) }); }
  }
  m = p.match(/^\/api\/orders\/(\d+)$/);
  if (m && method === 'GET') {
    const o = db.prepare('SELECT * FROM orders WHERE id=? AND book_id=?').get(m[1], bookId);
    if (!o) return sendJSON(res, 404, { error: '订单不存在' });
    // 🔴 客户改 URL 里的订单号就能看别家的单 → 必须校验归属（多门店：查集合）
    if (isCustomer(ctx) && !custAllowed(ctx, o.customer_id)) {
      return sendJSON(res, 404, { error: '订单不存在' });
    }
    const items = db.prepare('SELECT * FROM order_items WHERE order_id=?').all(m[1]);
    // 2026-10-15：老单是 barcode 加列之前开的，明细里没条码快照。
    // 用当前商品表回填，保证老订单也能打出条码。
    // 🔴🔴 2026-10-20：原来**只改返回值不写库** → 商品表后来改了条码，这张老单的条码也跟着变，
    //   违反「商品信息变动不影响历史已开出的订单」。
    //   ✅ 回填时**写回 order_items 冻结**：取到什么就是什么，之后商品表再改也不动这张老单。
    const missing = items.filter(it => it.product_id && !String(it.barcode || '').trim());
    if (missing.length) {
      try {
        const stmt = db.prepare('SELECT barcode FROM products WHERE id=? AND book_id=?');
        const upd = db.prepare('UPDATE order_items SET barcode=? WHERE id=?');
        for (const it of missing) {
          const p = stmt.get(it.product_id, bookId);
          if (p && String(p.barcode || '').trim()) { it.barcode = p.barcode; upd.run(p.barcode, it.id); }
        }
      } catch (e) { console.error('barcode freeze:', e.message); }
    }
    return sendJSON(res, 200, { order: o, items });
  }
  if (m && method === 'PUT') {
    if (!need('order.edit')) return;
    const b = await getBody(req);
    const o = db.prepare('SELECT * FROM orders WHERE id=? AND book_id=?').get(m[1], bookId);
    if (!o) return sendJSON(res, 404, { error: '订单不存在' });
    if (b.discount != null && !Array.isArray(b.items)) {
      const discount = Math.min(1, Math.max(0.1, Number(b.discount) || 1));
      const payable = +(o.total * discount).toFixed(2);
      db.prepare('UPDATE orders SET discount=?, payable=? WHERE id=?').run(discount, payable, m[1]);
      return sendJSON(res, 200, { ok: true, discount, payable, total: o.total });
    }
    if (!Array.isArray(b.items) || !b.items.length) return sendJSON(res, 400, { error: '请至少包含一个商品' });
    if (!b.customer_id) return sendJSON(res, 400, { error: '请选择客户' });
    const cust = db.prepare('SELECT * FROM customers WHERE id=? AND book_id=?').get(b.customer_id, bookId);
    if (!cust) return sendJSON(res, 400, { error: '客户不存在' });
    const lines = []; let total = 0;
    // 🔴 编辑退货单时同样要保持负数口径：单据本身 status='returned' 就代表退货，
    // 客户端一律传正数量，服务端按 sign 翻负并沿用正确的库存方向。
    const isReturnEdit = o.status === 'returned';
    const sign = isReturnEdit ? -1 : 1;
    // 🔴🔴 2026-10-20 经理需求「商品信息变动不影响历史已开出的订单，该显示原来的信息就原来的信息」。
    //   场景：3 个月前开的单，商品名当时是「光明180ml轻巧包原味」，之后商品表改名成
    //   「光明180轻巧包原味」；今天编辑这张单（只想改数量）→ 旧实现 DELETE 明细后用
    //   **当前 products 表**重建 INSERT → 历史单的名字/规格/条码/成本全被改掉了。
    //   ✅ 正解：product_id 在**原单**里出现过 → 沿用原单快照；
    //      只有**新增**的行才取当前商品表。商品已从商品表删除也要保住这一行
    //      （否则编辑一次就静默吞掉门店买过的商品）。
    const prevRows = db.prepare('SELECT product_id, product_name, spec, barcode, unit, cost, price, orig_price, discount FROM order_items WHERE order_id=?').all(m[1]);
    const prevByPid = new Map();
    for (const pi of prevRows) if (pi.product_id != null && !prevByPid.has(pi.product_id)) prevByPid.set(pi.product_id, pi);
    for (const it of b.items) {
      const pid = Number(it.product_id);
      const prev = prevByPid.get(pid) || null;
      const prod = db.prepare('SELECT * FROM products WHERE id=? AND book_id=?').get(pid, bookId);
      // 商品表里已经没有它了，但原单有 → 允许改（按原单快照走）
      if (!prod && !prev) return sendJSON(res, 400, { error: '商品不存在 id=' + it.product_id });
      const qty = Math.abs(Number(it.qty)); if (!(qty > 0)) return sendJSON(res, 400, { error: '数量无效' });
      // 单价兜底：优先报文 → 其次原单快照（折前价优先）→ 最后当前商品表
      const prevBase = prev ? (Number(prev.orig_price) > 0 ? Number(prev.orig_price) : Number(prev.price)) : 0;
      const fallbackPrice = prevBase > 0 ? prevBase : (prod ? Number(prod.price) || 0 : 0);
      let c;
      if (it.discount != null) {
        c = lineCalc(it.price !== undefined ? it.price : fallbackPrice, qty, it.discount);
      } else {
        const p0 = Number(it.price !== undefined ? it.price : fallbackPrice) || 0;
        c = { price: p0, orig_price: p0, discount: 1, subtotal: +(p0 * qty).toFixed(2) };
      }
      const subtotal = c.subtotal; total += subtotal;
      lines.push({ prod, prev, pid, product_id: pid, qty, price: c.price,
                   cost: prev ? (Number(prev.cost) || 0) : (Number(prod && prod.cost) || 0),
                   subtotal, discount: c.discount, orig_price: c.orig_price });
    }
    total = +total.toFixed(2);
    const discount = Math.min(1, Math.max(0.1, Number(b.discount) || 1));
    const payable = +(total * discount).toFixed(2);
    const totalSigned = +(total * sign).toFixed(2);
    const payableSigned = +(payable * sign).toFixed(2);
    const orig = db.prepare('SELECT product_id, qty FROM order_items WHERE order_id=?').all(m[1]);
    db.exec('BEGIN');
    try {
      // 🔴 2026-10-15 修复库存方向 bug：这里原来写的是 `qty = qty - ?` 传旧 qty，
      // 等于把建单时的库存影响**再施加一次**（销售单双倍扣库存，退货单双倍回补）。
      // 建单时影响是 `inventory.qty += -sq`，所以撤销旧影响必须是 `+= +sq`。
      // 销售单：旧 qty=+2 → +2 回补；退货单：旧 qty=-2 → -2 扣回。方向自动正确。
      for (const r of orig) db.prepare('UPDATE inventory SET qty=qty+? WHERE product_id=?').run(r.qty, r.product_id);
      db.prepare('DELETE FROM order_items WHERE order_id=?').run(m[1]);
      for (const L of lines) {
        const sq = L.qty * sign;
        const ssub = +(L.subtotal * sign).toFixed(2);
        // 🔴 快照取值：原单有 → 一律沿用原单（name/spec/unit/cost）；
        //   只有**新增**的行才取当前商品表。barcode 例外：原单为空时取当前商品条码
        //   （老单开单时还没条码列，回填一次才能打条码；取到就落库冻结，见 GET 那段）。
        const pv = L.prev;
        const sName  = pv ? (pv.product_name || '') : (L.prod.name || '');
        const sSpec  = pv ? (pv.spec || '') : (L.prod.spec || '');
        const sUnit  = pv ? (pv.unit || '') : (L.prod.unit || '');
        const sBc    = pv ? (String(pv.barcode || '').trim() ? (pv.barcode || '') : (L.prod.barcode || ''))
                          : (L.prod.barcode || '');
        const pidUse = L.pid != null ? L.pid : L.prod.id;
        db.prepare('INSERT INTO order_items(book_id,order_id,product_id,product_name,spec,barcode,unit,qty,price,cost,subtotal,discount,orig_price) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)').run(bookId, m[1], pidUse, sName, sSpec, sBc, sUnit, sq, L.price, L.cost, ssub, L.discount, L.orig_price);
        db.prepare("UPDATE inventory SET qty=qty+?, updated_at=datetime('now','localtime') WHERE product_id=?").run(-sq, pidUse);
      }
      // status 锁死为 returned，编辑退货单不能把它改成 paid
      db.prepare('UPDATE orders SET customer_id=?, customer_name=?, total=?, payable=?, discount=?, order_date=?, status=? WHERE id=?').run(cust.id, cust.name, totalSigned, payableSigned, discount, b.order_date || o.order_date || null, isReturnEdit ? 'returned' : (b.status || o.status || 'paid'), m[1]);
      db.exec('COMMIT');
      // 退货单不写历史价，避免污染下次开单取价
      if (!isReturnEdit) recordCustomerPrices(bookId, cust.id, lines, discount, +m[1]);
      return sendJSON(res, 200, { order_id: +m[1], total: totalSigned, discount, payable: payableSigned, is_return: isReturnEdit });
    } catch (e) { db.exec('ROLLBACK'); return sendJSON(res, 500, { error: String(e.message || e) }); }
  }
  if (m && method === 'DELETE') {
    if (!need('order.delete')) return;
    const o = db.prepare('SELECT * FROM orders WHERE id=? AND book_id=?').get(m[1], bookId);
    if (!o) return sendJSON(res, 404, { error: '订单不存在' });
    // 已生成退货单的销售单不能直接删，避免退货单悬空
    if (db.prepare('SELECT id FROM orders WHERE return_of=?').get(o.id)) {
      return sendJSON(res, 400, { error: '该订单已生成退货单，请先删除对应的退货单' });
    }
    const items = db.prepare('SELECT product_id, qty FROM order_items WHERE order_id=?').all(o.id);
    // 撤销建单时的库存影响：建单是 `qty += -明细qty`，所以删除必须是 `qty += +明细qty`。
    // 明细 qty 本身带符号（销售单正、���货单负），所以两种单据方向自动正确，
    // 不需要再按 return_of 额外翻符号（那反而会错）。
    db.exec('BEGIN');
    try {
      for (const r of items) {
        db.prepare('UPDATE inventory SET qty=qty+?, updated_at=datetime(\'now\',\'localtime\') WHERE product_id=?')
          .run(r.qty, r.product_id);
      }
      db.prepare('DELETE FROM order_items WHERE order_id=?').run(o.id);
      db.prepare('DELETE FROM orders WHERE id=?').run(o.id);
      // 🔴🔴 删单必须清 customer_prices，但只清**本单涉及的商品**：
      //   写成 `WHERE customer_id=?` 是按门店整片删 → 删一张单就把该门店**全部商品**历史价清空。
      //   实测事故：回归测试删了 4 家门店的测试单 → 全库 6 门店历史价全清。
      const delPids = Array.from(new Set(items.map(r => r.product_id).filter(Boolean)));
      if (delPids.length) {
        const ph = delPids.map(() => '?').join(',');
        db.prepare('DELETE FROM customer_prices WHERE book_id=? AND customer_id=? AND product_id IN (' + ph + ')').run(bookId, o.customer_id, ...delPids);
      }
      db.exec('COMMIT');
      return sendJSON(res, 200, { ok: true });
    } catch (e) { db.exec('ROLLBACK'); return sendJSON(res, 500, { error: String(e.message || e) }); }
  }
  // 批量删除订单
  if (p === '/api/orders/delete-batch' && method === 'POST') {
    if (!need('order.delete')) return;
    const b = await getBody(req).catch(() => ({}));
    const ids = Array.isArray(b.ids) ? b.ids.map(n => parseInt(n, 10)).filter(n => isFinite(n) && n > 0) : [];
    if (!ids.length) return sendJSON(res, 400, { error: 'ids 无效' });
    let deleted = 0; const skipped = [];
    for (const id of ids) {
      const o = db.prepare('SELECT * FROM orders WHERE id=? AND book_id=?').get(id, bookId);
      if (!o) { skipped.push({ id, reason: '订单不存在' }); continue; }
      if (db.prepare('SELECT id FROM orders WHERE return_of=?').get(o.id)) { skipped.push({ id, reason: '已生成退货单' }); continue; }
      const items = db.prepare('SELECT product_id, qty FROM order_items WHERE order_id=?').all(o.id);
      // 同上：直接用带符号的明细 qty 撤销库存，不再按 return_of 翻符号
      db.exec('BEGIN');
      try {
        for (const r of items) db.prepare("UPDATE inventory SET qty=qty+?, updated_at=datetime('now','localtime') WHERE product_id=?").run(r.qty, r.product_id);
        db.prepare('DELETE FROM order_items WHERE order_id=?').run(o.id);
        db.prepare('DELETE FROM orders WHERE id=?').run(o.id);
        // 🔴 同上：只清本单涉及的商品，不能按门店整片删
        const delPids = Array.from(new Set(items.map(r => r.product_id).filter(Boolean)));
        if (delPids.length) {
          const ph = delPids.map(() => '?').join(',');
          db.prepare('DELETE FROM customer_prices WHERE book_id=? AND customer_id=? AND product_id IN (' + ph + ')').run(bookId, o.customer_id, ...delPids);
        }
        db.exec('COMMIT');
        deleted++;
      } catch (e) { db.exec('ROLLBACK'); skipped.push({ id, reason: String(e.message || e) }); }
    }
    return sendJSON(res, 200, { ok: true, deleted, skipped });
  }
  m = p.match(/^\/api\/orders\/(\d+)\/return$/);
  if (m && method === 'POST') {
    if (!need('order.return')) return;
    const o = db.prepare('SELECT * FROM orders WHERE id=? AND book_id=?').get(m[1], bookId);
    if (!o) return sendJSON(res, 404, { error: '订单不存在' });
    if (o.status === 'returned') return sendJSON(res, 400, { error: '退货单不能再退' });
    if (db.prepare('SELECT id FROM orders WHERE return_of=?').get(o.id)) return sendJSON(res, 400, { error: '该订单已退货' });
    const items = db.prepare('SELECT * FROM order_items WHERE order_id=?').all(o.id);
    const pay = o.payable != null ? o.payable : o.total;
    const t = new Date();
    const ymd = t.getFullYear() + '-' + String(t.getMonth() + 1).padStart(2, '0') + '-' + String(t.getDate()).padStart(2, '0');
    const rno = genReturnNo(bookId);
    db.exec('BEGIN');
    try {
      const ri = db.prepare("INSERT INTO orders(book_id,order_no,customer_id,customer_name,total,payable,discount,order_date,status,remark,return_of) VALUES(?,?,?,?,?,?,?,?,?,?,?)").run(bookId, rno, o.customer_id, o.customer_name, -o.total, -pay, o.discount != null ? o.discount : 1, ymd, 'returned', '销售退货 ' + o.order_no, o.id);
      const rid = ri.lastInsertRowid;
      for (const it of items) {
        db.prepare('INSERT INTO order_items(book_id,order_id,product_id,product_name,spec,barcode,unit,qty,price,cost,subtotal) VALUES(?,?,?,?,?,?,?,?,?,?,?)').run(bookId, rid, it.product_id, it.product_name, it.spec, it.barcode || '', it.unit, -it.qty, it.price, it.cost || 0, -it.subtotal);
        if (it.product_id) db.prepare("UPDATE inventory SET qty=qty+?, updated_at=datetime('now','localtime') WHERE product_id=?").run(it.qty, it.product_id);
      }
      db.exec('COMMIT');
      return sendJSON(res, 200, { order_id: rid, order_no: rno });
    } catch (e) { db.exec('ROLLBACK'); return sendJSON(res, 500, { error: String(e.message || e) }); }
  }
  // 可退数量查询：每个商品的 原单数量 / 已退数量 / 还剩可退
  // 🔴 前端退货弹窗必须显示真实可退量，否则业务员会把数量填到超退才被服务端 400 拦下。
  //   已退 = 该商品在 return_of=原单id 的所有退货单里的 qty 绝对值之和。
  m = p.match(/^\/api\/orders\/(\d+)\/returnable$/);
  if (m && method === 'GET') {
    if (!need('order.return')) return;
    const o = db.prepare('SELECT id FROM orders WHERE id=? AND book_id=?').get(m[1], bookId);
    if (!o) return sendJSON(res, 404, { error: '订单不存在' });
    const rows = db.prepare(`
      SELECT oi.product_id, oi.product_name, oi.spec, oi.unit, oi.qty AS orig_qty, oi.price,
             COALESCE((SELECT SUM(ABS(r.qty)) FROM order_items r JOIN orders ro ON ro.id=r.order_id
                        WHERE ro.return_of=? AND r.product_id=oi.product_id), 0) AS already
      FROM order_items oi WHERE oi.order_id=? ORDER BY oi.id`).all(o.id, o.id);
    return sendJSON(res, 200, {
      items: rows.map(r => ({
        product_id: r.product_id, product_name: r.product_name, spec: r.spec, unit: r.unit,
        orig_qty: Number(r.orig_qty) || 0, price: Number(r.price) || 0,
        already_returned: Number(r.already) || 0,
        remaining: +((Number(r.orig_qty) || 0) - (Number(r.already) || 0)).toFixed(3),
      })),
    });
  }
  // 部分退货：像开单一样逐项选商品/数量/单价，生成退货单（可多次，累计不超原单数量）
  m = p.match(/^\/api\/orders\/(\d+)\/return-items$/);
  if (m && method === 'POST') {
    if (!need('order.return')) return;
    const o = db.prepare('SELECT * FROM orders WHERE id=? AND book_id=?').get(m[1], bookId);
    if (!o) return sendJSON(res, 404, { error: '订单不存在' });
    if (o.status === 'returned') return sendJSON(res, 400, { error: '退货单不能再退' });
    const b = await getBody(req).catch(() => ({}));
    const items = Array.isArray(b.items) ? b.items : [];
    if (!items.length) return sendJSON(res, 400, { error: '请至少选择一个退货商品' });
    const origItems = db.prepare('SELECT * FROM order_items WHERE order_id=?').all(o.id);
    const byPid = new Map(origItems.map(it => [it.product_id, it]));
    const lines = []; let total = 0;
    for (const it of items) {
      const pid = Number(it.product_id);
      const orig = byPid.get(pid);
      if (!orig) return sendJSON(res, 400, { error: '商品不在原订单中 id=' + pid });
      const qty = Number(it.qty);
      if (!(qty > 0)) return sendJSON(res, 400, { error: '退货数量无效' });
      // 累计该商品已退数量（退货单 order_items.qty 存负值，取绝对值求和）
      const row = db.prepare("SELECT COALESCE(SUM(ABS(oi.qty)),0) AS s FROM order_items oi JOIN orders o2 ON o2.id=oi.order_id WHERE o2.return_of=? AND oi.product_id=?").get(o.id, pid);
      const already = row ? (Number(row.s) || 0) : 0;
      const remaining = +(orig.qty - already).toFixed(3);
      if (qty > remaining + 1e-9) return sendJSON(res, 400, { error: '退货数量超过可退数量: ' + orig.product_name + ' (已退 ' + already + ', 剩 ' + remaining + ')' });
      const price = Number(it.price !== undefined ? it.price : orig.price);
      const subtotal = +(price * qty).toFixed(2); total += subtotal;
      lines.push({ orig, qty, price, subtotal });
    }
    total = +total.toFixed(2);
    const discount = o.discount != null ? o.discount : 1;
    const payable = +(total * discount).toFixed(2);
    const t = new Date();
    const ymd = t.getFullYear() + '-' + String(t.getMonth() + 1).padStart(2, '0') + '-' + String(t.getDate()).padStart(2, '0');
    const rno = genReturnNo(bookId);
    db.exec('BEGIN');
    try {
      const ri = db.prepare("INSERT INTO orders(book_id,order_no,customer_id,customer_name,total,payable,discount,order_date,status,remark,return_of) VALUES(?,?,?,?,?,?,?,?,?,?,?)").run(bookId, rno, o.customer_id, o.customer_name, -total, -payable, discount, ymd, 'returned', '销售退货 ' + o.order_no, o.id);
      const rid = ri.lastInsertRowid;
      for (const L of lines) {
        db.prepare("INSERT INTO order_items(book_id,order_id,product_id,product_name,spec,barcode,unit,qty,price,cost,subtotal,discount,orig_price) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)").run(bookId, rid, L.orig.product_id, L.orig.product_name, L.orig.spec, L.orig.barcode || '', L.orig.unit, -L.qty, L.price, L.orig.cost || 0, -L.subtotal, Number(L.orig.discount) || 1, Number(L.orig.orig_price) || L.price);
        if (L.orig.product_id) db.prepare("UPDATE inventory SET qty=qty+?, updated_at=datetime('now','localtime') WHERE product_id=?").run(L.qty, L.orig.product_id);
      }
      db.exec('COMMIT');
      return sendJSON(res, 200, { order_id: rid, order_no: rno });
    } catch (e) { db.exec('ROLLBACK'); return sendJSON(res, 500, { error: String(e.message || e) }); }
  }
  // ================= 应收应付 / 货款往来（2026-10-17，参照管家婆「挂账+收款核销」） =================
  // 🔴 三条铁律，改这块前务必先读：
  //  1) 订单金额（orders.payable/total）**永远不改**。抹零走 payments.discount 单独记账。
  //  2) 已收金额只存在 orders.settled（这张单被核销掉多少）和 payments（每次收款单）。
  //  3) 欠款 = Σ正向 payable − Σ已收 settled − |退货额|。
  //     🔴 退货单（status='returned'）**必须计入欠款**（经理 2026-10-18 报修：
  //     「退货金额没有计算进客户的货款计算中」）。它 payable 为负，代表冲减债权。
  //     正确拆法：待收清单只列 due>0 的正向单，退货额由 returnCredit() 单独汇总后抵减。

  // 该门店未结清单里的「退货抵减额」：退货单 payable 是负数，代表这笔钱本来就不用收了，
  // 它必须从该门店的欠款里减掉。
  // 🔴 2026-10-18 经理报「退货金额没有计算进客户的货款计算中」。真根因：下面
  //    unsettledOrders 和 /api/ar/summary 都写了 status<>'returned' 把退货单整单排除，
  //    于是退货的负金额从头到尾没参与任何计算 —— 代码注释 L1523 写的
  //    「退货单 payable 是负数，会自动抵减欠款，不要额外特殊处理」**与实际 SQL 矛盾**。
  //    实测：万家便利店-4店 退货 -120.24，欠款却显示 770.70（应为 650.46）。
  // ✅ 正确拆法（别把退货单塞进待收清单）：
  //    · 待收清单只列**正向**欠款单（due>0），退货单 due 为负，本来也不该让人去收款；
  //    · 退货的负金额单独汇总成 return_credit，在欠款总额里减掉。
  function returnCredit(book, cid) {
    const r = db.prepare("SELECT COALESCE(SUM(COALESCE(payable, total)), 0) AS c"
      + " FROM orders WHERE book_id=? AND customer_id=? AND status='returned'").get(book, cid);
    return +(Number(r && r.c) || 0).toFixed(2);   // 负数
  }

  // 该门店的未结清单（开单日期升序 —— 管家婆「自动分配」就是最早优先）
  // 🔴 下面显式要求 due > 0.005：退货单 payable 为负，绝不能出现在「待收清单」里
  //    让业务员去收款。原代码靠「settled(0) < due(负)-0.005 天然不成立」而侥幸排除，
  //    那是隐式行为 —— 一旦将来给退货单补 settled 就会漏进来，这里写死更稳。
  function unsettledOrders(book, cid) {
    return db.prepare(`
      SELECT id, order_no, order_date, created_at, total, payable, discount, settled, customer_name,
             COALESCE(payable, total) AS due
      FROM orders
      WHERE book_id=? AND customer_id=? AND status<>'returned'
        AND COALESCE(payable,total) > 0.005
        AND COALESCE(settled,0) < COALESCE(payable,total) - 0.005
      ORDER BY COALESCE(date(order_date), date(created_at)) ASC, id ASC
    `).all(book, cid);
  }
  function settleState(o) {
    const due = +(Number(o.due != null ? o.due : (o.payable != null ? o.payable : o.total)) || 0).toFixed(2);
    const paid = +(Number(o.settled) || 0).toFixed(2);
    const left = +(due - paid).toFixed(2);
    return { due, paid, left, st: left <= 0.005 ? 'settled' : (paid > 0.005 ? 'partial' : 'unpaid') };
  }
  function termOf(book, cid) {
    const r = db.prepare('SELECT term_days FROM customer_terms WHERE book_id=? AND customer_id=?').get(book, cid);
    return r && Number(r.term_days) > 0 ? Number(r.term_days) : 30;   // 默认账期 30 天
  }
  // 距开单天数：order_date 优先，退回 created_at
  function daysSince(o) {
    const s = (o.order_date || (o.created_at || '')).slice(0, 10);
    if (!s) return 0;
    const t0 = new Date(s + 'T00:00:00');
    if (isNaN(t0)) return 0;
    const t1 = new Date(); t1.setHours(0, 0, 0, 0);
    return Math.max(0, Math.round((t1 - t0) / 86400000));
  }

  // GET /api/ar/unsettled?customer_id=N  → 该门店未结清单（收款弹窗用）
  if (p === '/api/ar/unsettled' && method === 'GET') {
    if (!need('ar.view')) return;
    // 🔴 客户：传入的 customer_id 只在他授权范围内才认，否则回落默认门店（传别家 id 也看不到）
    const cid = isCustomer(ctx) ? custTarget(ctx, u.searchParams.get('customer_id')) : Number(u.searchParams.get('customer_id'));
    if (!cid) return sendJSON(res, 400, { error: '缺少 customer_id' });
    const rows = unsettledOrders(bookId, cid).map(o => {
      const st = settleState(o); const days = daysSince(o); const term = termOf(bookId, cid);
      return {
        order_id: o.id, order_no: o.order_no,
        order_date: o.order_date || (o.created_at || '').slice(0, 10),
        due: st.due, paid: st.paid, left: st.left, state: st.st,
        days, term, overdue: st.left > 0.005 && days > term,
      };
    });
    // 🔴 2026-10-18：total_left 是「待收单据未结合计」。欠款总额必须再减掉退货抵减额，
    //    否则收款弹窗顶部显示的数字会和客户页/报表页对不上（同一个门店三个地方三个数）。
    const rc = returnCredit(bookId, cid);
    const leftSum = +rows.reduce((a, r) => a + r.left, 0).toFixed(2);
    return sendJSON(res, 200, {
      customer_id: cid, term_days: termOf(bookId, cid),
      total_left: leftSum,
      return_credit: rc,
      debt: +(leftSum + rc).toFixed(2),
      orders: rows,
    });
  }

  // POST /api/ar/pay  → 收款单（选门店 + 实收 + 优惠 + 核销明细）
  // body: { customer_id, amount, discount?, method?, pay_date?, remark?, items:[{order_id, amount, discount?}] }
  if (p === '/api/ar/pay' && method === 'POST') {
    if (!need('ar.edit')) return;
    const b = await getBody(req).catch(() => ({}));
    const cid = Number(b.customer_id);
    const cust = db.prepare('SELECT * FROM customers WHERE id=? AND book_id=?').get(cid, bookId);
    if (!cust) return sendJSON(res, 400, { error: '客户不存在' });

    const amount = Math.abs(Number(b.amount) || 0);
    const discount = Math.abs(Number(b.discount) || 0);
    if (!(amount > 0) && !(discount > 0)) {
      return sendJSON(res, 400, { error: '请填写收款金额或优惠金额' });
    }
    const items = Array.isArray(b.items) ? b.items : [];
    if (!items.length) return sendJSON(res, 400, { error: '请至少核销一张单' });

    const t = new Date();
    const ymd = t.getFullYear() + '-' + String(t.getMonth() + 1).padStart(2, '0') + '-' + String(t.getDate()).padStart(2, '0');

    // 🔴🔴 事务外先把所有明细校验完，再开事务。
    //    原因：node:sqlite 是单连接，事务里任何 `return sendJSON(...)` 都会跳过 ROLLBACK，
    //    留下一个悬挂事务 → 整个数据库被锁 → 后所有请求 "database is locked"。
    //    这个坑踩过一次（校验放在 BEGIN 里面），别再放回去。
    const lines = [];
    let vSumAmt = 0, vSumDis = 0;
    for (const it of items) {
      const oid = Number(it.order_id);
      const o = db.prepare('SELECT * FROM orders WHERE id=? AND book_id=? AND customer_id=?').get(oid, bookId, cid);
      if (!o || o.status === 'returned') {
        return sendJSON(res, 400, { error: '订单不存在或已退货：' + oid });
      }
      const st = settleState({ due: o.payable != null ? o.payable : o.total, settled: o.settled });
      const a = Math.abs(Number(it.amount) || 0);
      const d = Math.abs(Number(it.discount) || 0);
      if (a + d > st.left + 0.005) {
        return sendJSON(res, 400, {
          error: '核销金额超过该单未结清金额：' + o.order_no +
                 '（未结 ' + st.left.toFixed(2) + '，本次填 ' + (a + d).toFixed(2) + '）'
        });
      }
      lines.push({ o, oid, a, d });
      vSumAmt += a; vSumDis += d;
    }

    const pay_no = nextSeqNo('SK', 'payments', 'pay_no');
    db.exec('BEGIN');
    try {
      const pi = db.prepare(`INSERT INTO payments(book_id,pay_no,customer_id,customer_name,amount,discount,method,pay_date,remark,created_by)
        VALUES(?,?,?,?,?,?,?,?,?,?)`)
        .run(bookId, pay_no, cid, cust.name, amount, discount,
             String(b.method || '现金'), b.pay_date || ymd, String(b.remark || ''), ctx.account.id);
      const pid = Number(pi.lastInsertRowid);

      for (const L of lines) {
        const o = L.o;
        db.prepare(`INSERT INTO payment_items(book_id,payment_id,order_id,order_no,amount,discount) VALUES(?,?,?,?,?,?)`)
          .run(bookId, pid, L.oid, o.order_no, L.a, L.d);
        // 🔴 settled 累加「实收+优惠」：优惠也算这笔单结清了（业务员让步换结清），
        //    但金额永远不写回订单，只体现在 payments.discount。
        const ns = +(Number(o.settled) || 0) + L.a + L.d;
        // 🔴🔴 只回写 settled，绝不碰 orders.status：
        //    status 是「这张单开出来时收没收款」的原始语义（paid / returned），
        //    结清状态一律由 settled 推导（settleState），否则：
        //    ① 撤销收款会把原 paid 错改成 unpaid/partial，污染开单语义；
        //    ② 订单列表「已付款」标签会随收款动作乱跳。
        db.prepare("UPDATE orders SET settled=? WHERE id=?").run(+ns.toFixed(2), L.oid);
      }

      // 明细合计与表头对不上时按明细为准（前端四舍五入常差 1 分）
      const finalAmt = Math.abs(vSumAmt) > 0 ? +vSumAmt.toFixed(2) : amount;
      const finalDis = Math.abs(vSumDis) > 0 ? +vSumDis.toFixed(2) : discount;
      db.prepare('UPDATE payments SET amount=?, discount=? WHERE id=?').run(finalAmt, finalDis, pid);
      db.exec('COMMIT');

      const after = db.prepare("SELECT COUNT(*) n FROM orders WHERE book_id=? AND customer_id=? AND status<>?"
        + " AND COALESCE(payable,total) > 0.005 AND COALESCE(settled,0) < COALESCE(payable,total)-0.005")
        .get(bookId, cid, 'returned');
      return sendJSON(res, 200, { payment_id: pid, pay_no, amount: finalAmt, discount: finalDis, unsettled_left: after.n });
    } catch (e) {
      try { db.exec('ROLLBACK'); } catch (_) { }
      return sendJSON(res, 500, { error: String(e.message || e) });
    }
  }

  // DELETE /api/ar/pay/:id  → 撤销一张收款单（把核销金额退回 orders.settled）
  m = p.match(/^\/api\/ar\/pay\/(\d+)$/);
  if (m && method === 'DELETE') {
    if (!need('ar.edit')) return;
    const pid = Number(m[1]);
    const pay = db.prepare('SELECT * FROM payments WHERE id=? AND book_id=?').get(pid, bookId);
    if (!pay) return sendJSON(res, 404, { error: '收款单不存在' });
    db.exec('BEGIN');
    try {
      const its = db.prepare('SELECT * FROM payment_items WHERE payment_id=?').all(pid);
      for (const it of its) {
        const o = db.prepare('SELECT * FROM orders WHERE id=?').get(it.order_id);
        if (!o) continue;
        const ns = Math.max(0, +((Number(o.settled) || 0) - (Number(it.amount) || 0) - (Number(it.discount) || 0)).toFixed(2));
        // 🔴 同上：撤销只退 settled，不动 orders.status
        db.prepare("UPDATE orders SET settled=? WHERE id=?").run(ns, it.order_id);
      }
      db.prepare('DELETE FROM payment_items WHERE payment_id=?').run(pid);
      db.prepare('DELETE FROM payments WHERE id=?').run(pid);
      db.exec('COMMIT');
      return sendJSON(res, 200, { ok: true, pay_no: pay.pay_no });
    } catch (e) {
      try { db.exec('ROLLBACK'); } catch (_) { }
      return sendJSON(res, 500, { error: String(e.message || e) });
    }
  }

  // GET /api/ar/summary → 全部门店欠款汇总（报表中心用）
  if (p === '/api/ar/summary' && method === 'GET') {
    if (!need('ar.view')) return;
    // 🔴 2026-10-18：这里原来也是 `status<>'returned'`，退货的负金额被整单排除 →
    //    客户欠款虚高（万家便利店-4店 退货 -120.24 却不扣）。
    // ✅ 正解：SUM 里不再按 status 过滤，退货单的负 payable 自然抵减总应收。
    //    COUNT 只数正向单（退货不该计入「N 张单」），单列一个 return_orders。
    const rows = db.prepare(""
      + "SELECT o.customer_id, o.customer_name,"
      + " SUM(CASE WHEN o.status<>'returned' THEN 1 ELSE 0 END) AS n,"
      + " SUM(CASE WHEN o.status='returned' THEN 1 ELSE 0 END) AS return_orders,"
      + " COALESCE(SUM(CASE WHEN o.status<>'returned' THEN COALESCE(o.payable,o.total) ELSE 0 END),0) AS total_due,"
      + " COALESCE(SUM(CASE WHEN o.status='returned' THEN COALESCE(o.payable,o.total) ELSE 0 END),0) AS return_total,"
      + " COALESCE(SUM(COALESCE(o.settled,0)),0) AS total_paid"
      + " FROM orders o WHERE o.book_id=?"
      + " GROUP BY o.customer_id, o.customer_name"
    ).all(bookId);
    const list = rows.map(r => {
      const term = termOf(bookId, r.customer_id);
      const us = unsettledOrders(bookId, r.customer_id);
      let overdueN = 0, maxDays = 0;
      us.forEach(o => { const d = daysSince(o); if (d > term) overdueN++; if (d > maxDays) maxDays = d; });
      const due = +r.total_due.toFixed(2);
      const ret = +r.return_total.toFixed(2);      // 负数
      const debt = +(due + ret - r.total_paid).toFixed(2);
      return {
        customer_id: r.customer_id, customer_name: r.customer_name,
        orders: r.n, unsettled: us.length,
        // return_orders / return_total 单列出来，前端可显示「含退货冲减 ¥X」
        return_orders: r.return_orders, return_total: ret,
        total_due: due, total_paid: +r.total_paid.toFixed(2),
        debt,
        term_days: term, overdue_orders: overdueN, max_days: maxDays,
      };
      // 🔴 debt 可以是**负数**（退货冲减后客户反而欠我们钱 / 我们欠他），
      //    所以过滤条件不能只写 `debt > 0.005`，否则预收门店会被整行丢掉。
    }).filter(r => r.debt > 0.005 || r.unsettled > 0 || r.debt < -0.005)
      // 🔴 客户：只留他负责门店的那几行，绝不暴露其它门店的欠款
      .filter(r => !isCustomer(ctx) || ((ctx.customerIds || []).indexOf(Number(r.customer_id)) >= 0))
      .sort((a, b) => b.debt - a.debt);
    return sendJSON(res, 200, {
      rows: list,
      total_debt: +list.reduce((a, r) => a + r.debt, 0).toFixed(2),
      total_overdue: list.reduce((a, r) => a + r.overdue_orders, 0),
    });
  }

  // GET /api/ar/aging → 账龄分析（30天以内 / 31-60 / 61-90 / 90天以上）
  if (p === '/api/ar/aging' && method === 'GET') {
    if (!need('ar.view')) return;
    const cid = isCustomer(ctx) ? 0 : Number(u.searchParams.get('customer_id'));
    const buckets = { d30: 0, d60: 0, d90: 0, d90p: 0 };
    const c30 = { n: 0, amt: 0 }, c60 = { n: 0, amt: 0 }, c90 = { n: 0, amt: 0 }, c90p = { n: 0, amt: 0 };
    // 🔴 客户（多门店）：传 customer_id 也只在他授权范围内认；不传则汇总他负责的**全部**门店
    const cIds = isCustomer(ctx)
      ? (custAllowed(ctx, u.searchParams.get('customer_id'))
          ? [Number(u.searchParams.get('customer_id'))]
          : (ctx.customerIds || []))
      : [];
    const targets = isCustomer(ctx)
      ? cIds.map(i => ({ customer_id: i }))
      : (cid ? [{ customer_id: cid }] : [
        // 🔴 不按 status 过滤目标客户：只有退货单、没有正向单据的门店，
        //    其退货抵减额也必须体现（否则账龄 total 与 summary 对不上）。
        ...db.prepare('SELECT DISTINCT customer_id FROM orders WHERE book_id=?').all(bookId)
      ]);
    const detail = [];
    for (const t of targets) {
      unsettledOrders(bookId, t.customer_id).forEach(o => {
        const st = settleState(o); const d = daysSince(o);
        const b = d <= 30 ? c30 : d <= 60 ? c60 : d <= 90 ? c90 : c90p;
        b.n++; b.amt += st.left;
        detail.push({
          order_id: o.id, order_no: o.order_no, customer_id: t.customer_id,
          customer_name: o.customer_name, left: st.left, days: d,
          order_date: o.order_date || (o.created_at || '').slice(0, 10),
          overdue: d > termOf(bookId, t.customer_id),
        });
      });
    }
    detail.sort((a, b) => b.days - a.days);
    // 🔴 账龄桶只装正向未结单（退货 due 为负不该占一个桶），但**总额必须减掉退货抵减**，
    //    否则这里显示的「欠款总额」会比 /api/ar/summary 大，两页对不上。
    // 🔴 客户（多门店）：退货抵减只能汇总他负责的门店，绝不能给全店合计（那等于泄露别家经营数据）。
    const rcNum = isCustomer(ctx)
      ? (cIds.length ? +cIds.reduce((s, i) => s + returnCredit(bookId, i), 0).toFixed(2) : 0)
      : (cid ? returnCredit(bookId, cid)
             : Number((db.prepare("SELECT COALESCE(SUM(COALESCE(payable,total)),0) AS c FROM orders WHERE book_id=? AND status='returned'").get(bookId) || {}).c) || 0);
    const returnCreditTotal = +rcNum.toFixed(2);
    return sendJSON(res, 200, {
      return_credit: returnCreditTotal,
      buckets: [
        { label: '30 天以内', n: c30.n, amt: +c30.amt.toFixed(2) },
        { label: '31-60 天', n: c60.n, amt: +c60.amt.toFixed(2) },
        { label: '61-90 天', n: c90.n, amt: +c90.amt.toFixed(2) },
        { label: '90 天以上', n: c90p.n, amt: +c90p.amt.toFixed(2) },
      ],
      total: +(detail.reduce((a, r) => a + r.left, 0) + returnCreditTotal).toFixed(2),
      detail: cid ? detail : detail.slice(0, 200),
    });
  }

  // GET /api/ar/payments?customer_id=N → 收款流水
  if (p === '/api/ar/payments' && method === 'GET') {
    if (!need('ar.view')) return;
    // 🔴 客户（多门店）：cid 为空时**绝不能落到「全部流水」分支** —— 那等于把全店收款流水抖出来。
    //    一律用授权门店集合做 IN 查询。
    if (isCustomer(ctx)) {
      const ids = ctx.customerIds || [];
      if (!ids.length) return sendJSON(res, 200, { rows: [], total_in: 0, total_discount: 0 });
      const ph = ids.map(() => '?').join(',');
      const rws = db.prepare('SELECT * FROM payments WHERE book_id=? AND customer_id IN (' + ph + ') ORDER BY id DESC LIMIT 200').all(bookId, ...ids);
      return sendJSON(res, 200, {
        rows: rws.map(r => ({ ...r, total: +(Number(r.amount) + Number(r.discount)).toFixed(2) })),
        total_in: +rws.reduce((a, r) => a + (Number(r.amount) || 0), 0).toFixed(2),
        total_discount: +rws.reduce((a, r) => a + (Number(r.discount) || 0), 0).toFixed(2),
      });
    }
    const cid = Number(u.searchParams.get('customer_id')) || 0;
    const rows = cid
      ? db.prepare('SELECT * FROM payments WHERE book_id=? AND customer_id=? ORDER BY id DESC LIMIT 200').all(bookId, cid)
      : db.prepare('SELECT * FROM payments WHERE book_id=? ORDER BY id DESC LIMIT 200').all(bookId);
    return sendJSON(res, 200, {
      rows: rows.map(r => ({ ...r, total: +(Number(r.amount) + Number(r.discount)).toFixed(2) })),
      total_in: +rows.reduce((a, r) => a + (Number(r.amount) || 0), 0).toFixed(2),
      total_discount: +rows.reduce((a, r) => a + (Number(r.discount) || 0), 0).toFixed(2),
    });
  }

  // POST /api/ar/term  → 设置门店账期天数（默认 30）
  if (p === '/api/ar/term' && method === 'POST') {
    if (!need('ar.edit')) return;
    const b = await getBody(req).catch(() => ({}));
    const cid = Number(b.customer_id);
    if (!db.prepare('SELECT id FROM customers WHERE id=? AND book_id=?').get(cid, bookId)) {
      return sendJSON(res, 400, { error: '客户不存在' });
    }
    const d = Number(b.term_days);
    if (!isFinite(d) || d < 0 || d > 365) return sendJSON(res, 400, { error: '账期需在 0-365 天之间' });
    db.prepare(`INSERT INTO customer_terms(book_id,customer_id,term_days,remark) VALUES(?,?,?,?)
      ON CONFLICT(customer_id) DO UPDATE SET term_days=excluded.term_days,
      remark=excluded.remark, updated_at=datetime('now','localtime')`)
      .run(bookId, cid, Math.round(d), String(b.remark || ''));
    return sendJSON(res, 200, { customer_id: cid, term_days: Math.round(d) });
  }

  // ---------- 报表中心：商品排行 / 门店排行 / 库存预警 ----------
  // GET /api/report/products?from&to
  if (p === '/api/report/products' && method === 'GET') {
    if (!need('analysis.view')) return;
    const from = u.searchParams.get('from') || '', to = u.searchParams.get('to') || '';
    const w = []; const a = [bookId];
    if (from) { w.push('date(COALESCE(o.order_date,o.created_at))>=?'); a.push(from); }
    if (to) { w.push('date(COALESCE(o.order_date,o.created_at))<=?'); a.push(to); }
    const wh = w.length ? ' AND ' + w.join(' AND ') : '';
    // 🔴🔴 GROUP BY 只按 product_id：商品名/规格/单位会变（改名、改规格），
    //   裸列 product_name/unit/spec 取的是**任意一行**，改名后显示名会随机跳。
    const rows = db.prepare(`
      SELECT oi.product_id,
             SUM(oi.qty) qty, SUM(oi.subtotal) sales,
             SUM((oi.price - COALESCE(oi.cost,0)) * oi.qty) profit
      FROM order_items oi JOIN orders o ON o.id=oi.order_id
      WHERE o.book_id=? ${wh}
      GROUP BY oi.product_id ORDER BY sales DESC LIMIT 200`).all(...a);
    const snap = latestItemSnapshots(bookId, rows.map(r => r.product_id), wh, a.slice(1));
    return sendJSON(res, 200, rows.map(r => {
      const sn = snap.get(r.product_id) || {};
      return { ...r, product_name: sn.product_name || '', spec: sn.spec || '', unit: sn.unit || '',
               qty: r.qty || 0, sales: +(r.sales || 0).toFixed(2), profit: +(r.profit || 0).toFixed(2) };
    }));
  }

  // GET /api/report/stores?from&to
  if (p === '/api/report/stores' && method === 'GET') {
    if (!need('analysis.view')) return;
    const from = u.searchParams.get('from') || '', to = u.searchParams.get('to') || '';
    const w = []; const a = [bookId];
    if (from) { w.push('date(COALESCE(o.order_date,o.created_at))>=?'); a.push(from); }
    if (to) { w.push('date(COALESCE(o.order_date,o.created_at))<=?'); a.push(to); }
    const wh = w.length ? ' AND ' + w.join(' AND ') : '';
    const rows = db.prepare(`
      SELECT o.customer_id, o.customer_name,
             COUNT(DISTINCT o.id) orders, SUM(COALESCE(o.payable,o.total)) sales,
             SUM((oi.price - COALESCE(oi.cost,0)) * oi.qty) profit
      FROM orders o JOIN order_items oi ON oi.order_id=o.id
      WHERE o.book_id=? ${wh}
      GROUP BY o.customer_id ORDER BY sales DESC LIMIT 200`).all(...a);
    return sendJSON(res, 200, rows.map(r => ({ ...r, sales: +(r.sales || 0).toFixed(2), profit: +(r.profit || 0).toFixed(2) })));
  }

  // GET /api/report/stock-alert
  if (p === '/api/report/stock-alert' && method === 'GET') {
    if (!need('inventory.view')) return;
    const rows = db.prepare(`
      SELECT p.id, p.name, p.spec, p.unit,
             COALESCE(i.low_threshold,0) min_stock,
             COALESCE(i.qty,0) qty,
             COALESCE(i.qty,0) - COALESCE(i.low_threshold,0) gap
      FROM products p LEFT JOIN inventory i ON i.product_id=p.id AND i.book_id=p.book_id
      WHERE p.book_id=? AND COALESCE(i.low_threshold,0) > 0
        AND COALESCE(i.qty,0) <= COALESCE(i.low_threshold,0)
      ORDER BY gap ASC LIMIT 100`).all(bookId);
    return sendJSON(res, 200, rows.map(r => ({
      ...r,
      qty: +(r.qty || 0).toFixed(2),
      min_stock: +(r.min_stock || 0).toFixed(2),
      gap: +(r.gap || 0).toFixed(2)
    })));
  }

  if (p === '/api/analysis/monthly' && method === 'GET') {
    if (!need('analysis.view')) return;
    const rows = db.prepare(`
      SELECT strftime('%Y-%m', COALESCE(o.order_date, o.created_at)) as month,
             SUM(COALESCE(o.payable, o.total)) as sales,
             SUM(oi.qty) as qty,
             SUM((oi.price - COALESCE(oi.cost,0)) * oi.qty) as profit,
             COUNT(DISTINCT o.id) as orders
      FROM orders o JOIN order_items oi ON oi.order_id = o.id
      WHERE o.book_id = ?
      GROUP BY month ORDER BY month DESC LIMIT 24`).all(bookId);
    return sendJSON(res, 200, rows);
  }

  // ---------- 数据分析（日期区间：today/month/year/custom） ----------
  if (p === '/api/analytics' && method === 'GET') {
    if (!need('analysis.view')) return;
    const range = (u.searchParams.get('range') || 'today');
    const t = new Date();
    const y = t.getFullYear(), mo = t.getMonth(), d = t.getDate();
    const fmt = (dt) => dt.getFullYear() + '-' + String(dt.getMonth() + 1).padStart(2, '0') + '-' + String(dt.getDate()).padStart(2, '0');
    let from, to;
    if (range === 'month') { from = fmt(new Date(y, mo, 1)); to = fmt(new Date(y, mo + 1, 0)); }
    else if (range === 'year') { from = fmt(new Date(y, 0, 1)); to = fmt(new Date(y, 11, 31)); }
    else if (range === 'custom') { from = u.searchParams.get('from'); to = u.searchParams.get('to'); }
    else { from = to = fmt(t); }
    if (!from || !to) return sendJSON(res, 400, { error: '自定义区间需提供 from/to' });
    const dExpr = "date(COALESCE(o.order_date, o.created_at))";
    const tot = db.prepare(`
      SELECT COALESCE(SUM(COALESCE(o.payable,o.total)),0) as sales,
             COALESCE(SUM((oi.price - COALESCE(oi.cost,0)) * oi.qty),0) as profit,
             COALESCE(SUM(oi.qty),0) as qty,
             COUNT(DISTINCT o.id) as orders
      FROM orders o JOIN order_items oi ON oi.order_id=o.id
      WHERE o.book_id=? AND ${dExpr} BETWEEN ? AND ?`).get(bookId, from, to);
    // 🔴🔴 GROUP BY 只按 product_id（原来还带 oi.product_name → 商品改名后被拆组、销量割裂）
    const products = db.prepare(`
      SELECT oi.product_id as pid,
             COALESCE(SUM(oi.qty),0) as qty,
             COALESCE(SUM(oi.qty*oi.price),0) as sales,
             COALESCE(SUM((oi.price - COALESCE(oi.cost,0)) * oi.qty),0) as profit
      FROM orders o JOIN order_items oi ON oi.order_id=o.id
      WHERE o.book_id=? AND ${dExpr} BETWEEN ? AND ?
      GROUP BY oi.product_id
      HAVING SUM(oi.qty) <> 0 OR SUM(oi.qty*oi.price) <> 0
      ORDER BY profit DESC`).all(bookId, from, to);
    {
      const wh2 = ' AND ' + dExpr + ' BETWEEN ? AND ?';
      const sn = latestItemSnapshots(bookId, products.map(p => p.pid), wh2, [from, to]);
      for (const pr of products) {
        const s = sn.get(pr.pid) || {};
        pr.name = s.product_name || '';
        pr.unit = s.unit || '';
        pr.spec = s.spec || '';
        pr.cost = Number(s.cost) || 0;
      }
    }
    const sales = tot.sales || 0, profit = tot.profit || 0;
    products.forEach(pr => { pr.qty = pr.qty || 0; pr.sales = pr.sales || 0; pr.profit = pr.profit || 0; pr.margin = (pr.sales !== 0 ? pr.profit / pr.sales : 0); });
    return sendJSON(res, 200, { range, from, to, sales, profit, margin: (sales !== 0 ? profit / sales : 0), orders: tot.orders || 0, qty: tot.qty || 0, products });
  }

  // ---------- 当天出货汇总（门店×产品 透视；仅含出货/退货的门店与产品，含成本价） ----------
  if (p === '/api/shipments/daily' && method === 'GET') {
    if (!need('analysis.view')) return;
    const dExpr = "date(COALESCE(o.order_date, o.created_at))";
    const date = u.searchParams.get('date') || (new Date().getFullYear() + '-' + String(new Date().getMonth() + 1).padStart(2, '0') + '-' + String(new Date().getDate()).padStart(2, '0'));
    // 🔴🔴 GROUP BY 只按 customer_id + product_id：客户名/商品名/单位/成本都会变，
    //   放进 GROUP BY 会把同一个 product_id 拆成多组，出货汇总里同一商品出现两行。
    const lines = db.prepare(`
      SELECT o.customer_id as cid, o.customer_name as cname, oi.product_id as pid,
             SUM(oi.qty) as qty, SUM(oi.qty*oi.price) as amount
      FROM orders o JOIN order_items oi ON oi.order_id=o.id
      WHERE o.book_id=? AND ${dExpr}=?
      GROUP BY o.customer_id, oi.product_id`).all(bookId, date);
    {
      const wh2 = ' AND ' + dExpr + '=?';
      const sn = latestItemSnapshots(bookId, lines.map(l => l.pid), wh2, [date]);
      for (const l of lines) {
        const s = sn.get(l.pid) || {};
        l.pname = s.product_name || '';
        l.unit = s.unit || '';
        l.cost = Number(s.cost) || 0;
      }
    }
    const custMap = new Map(), prodMap = new Map();
    for (const l of lines) {
      if (l.cid != null && !custMap.has(l.cid)) custMap.set(l.cid, { id: l.cid, name: l.cname });
      if (l.pid != null && !prodMap.has(l.pid)) prodMap.set(l.pid, { id: l.pid, name: l.pname, unit: l.unit, cost: l.cost || 0 });
    }
    const stores = [...custMap.values()];
    const products = [...prodMap.values()];
    const rows = stores.map(s => ({
      customer_id: s.id, customer_name: s.name,
      cells: products.map(p => {
        const ln = lines.find(l => l.cid === s.id && l.pid === p.id);
        return { product_id: p.id, qty: ln ? (ln.qty || 0) : 0, amount: ln ? (ln.amount || 0) : 0 };
      })
    }));
    return sendJSON(res, 200, { date, stores, products, rows });
  }

  // ---------- 当天出货汇总 导出 Excel（含成本价；仅含有出货/退货的门店与产品） ----------
  if (p === '/api/shipments/daily/export' && method === 'GET') {
    if (!need('analysis.view')) return;
    const dExpr = "date(COALESCE(o.order_date, o.created_at))";
    const date = u.searchParams.get('date') || (new Date().getFullYear() + '-' + String(new Date().getMonth() + 1).padStart(2, '0') + '-' + String(new Date().getDate()).padStart(2, '0'));
    // 🔴🔴 GROUP BY 只按 customer_id + product_id：客户名/商品名/单位/成本都会变，
    //   放进 GROUP BY 会把同一个 product_id 拆成多组，出货汇总里同一商品出现两行。
    const lines = db.prepare(`
      SELECT o.customer_id as cid, o.customer_name as cname, oi.product_id as pid,
             SUM(oi.qty) as qty
      FROM orders o JOIN order_items oi ON oi.order_id=o.id
      WHERE o.book_id=? AND ${dExpr}=?
      GROUP BY o.customer_id, oi.product_id`).all(bookId, date);
    {
      const wh2 = ' AND ' + dExpr + '=?';
      const sn = latestItemSnapshots(bookId, lines.map(l => l.pid), wh2, [date]);
      for (const l of lines) {
        const s = sn.get(l.pid) || {};
        l.pname = s.product_name || '';
        l.unit = s.unit || '';
        l.cost = Number(s.cost) || 0;
      }
    }
    const custMap = new Map(), prodMap = new Map();
    for (const l of lines) {
      if (l.cid != null && !custMap.has(l.cid)) custMap.set(l.cid, { id: l.cid, name: l.cname });
      if (l.pid != null && !prodMap.has(l.pid)) prodMap.set(l.pid, { id: l.pid, name: l.pname, unit: l.unit, cost: l.cost || 0 });
    }
    const stores = [...custMap.values()], products = [...prodMap.values()];
    const rows = [];
    rows.push(['门店'].concat(products.map(p2 => p2.name + (p2.unit ? '（' + p2.unit + '）' : '')), ['合计']));
    rows.push(['成本价'].concat(products.map(p2 => Number(p2.cost) || 0), ['']));
    for (const s of stores) {
      const r = [s.name]; let tot = 0;
      for (const p2 of products) { const ln = lines.find(l => l.cid === s.id && l.pid === p2.id); const q = ln ? (ln.qty || 0) : 0; r.push(q); tot += q; }
      r.push(Math.round(tot * 100) / 100);
      rows.push(r);
    }
    const buf = xlsxUtil.buildXlsx(rows, '当天出货汇总');
    res.writeHead(200, { 'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 'Content-Disposition': 'attachment; filename="shipments_' + date + '.xlsx"' });
    return res.end(buf);
  }

  // ---------- 价格体系 ----------
  if (p === '/api/price-systems' && method === 'GET') {
    if (!need('price.view')) return;
    if (isCustomer(ctx)) return sendJSON(res, 403, { error: '客户账号不能查看价格体系' });
    return sendJSON(res, 200, db.prepare('SELECT * FROM price_systems WHERE book_id=? ORDER BY id').all(bookId));
  }
  if (p === '/api/price-systems' && method === 'POST') {
    if (!need('price.edit')) return;
    const b = await getBody(req);
    if (!b.name) return sendJSON(res, 400, { error: '名称必填' });
    const info = db.prepare('INSERT INTO price_systems(book_id,name) VALUES(?,?)').run(bookId, b.name);
    return sendJSON(res, 200, { id: info.lastInsertRowid });
  }
  m = p.match(/^\/api\/price-systems\/(\d+)$/);
  if (m && method === 'DELETE') {
    if (!need('price.edit')) return;
    const ps = db.prepare('SELECT * FROM price_systems WHERE id=? AND book_id=?').get(m[1], bookId);
    if (!ps) return sendJSON(res, 404, { error: '不存在' });
    const first = db.prepare('SELECT id FROM price_systems WHERE book_id=? ORDER BY id LIMIT 1').get(bookId);
    if (first && String(first.id) === String(m[1])) return sendJSON(res, 400, { error: '默认价体系不可删除' });
    db.prepare('DELETE FROM prices WHERE system_id=?').run(m[1]);
    db.prepare('DELETE FROM price_systems WHERE id=?').run(m[1]);
    return sendJSON(res, 200, { ok: true });
  }
  if (p === '/api/prices' && method === 'GET') {
    // 各价体系的底价属于经营数据，客户只能拿「本店价」（走 /api/products）
    if (!need('price.view')) return;
    if (isCustomer(ctx)) return sendJSON(res, 403, { error: '客户账号不能查看价目表' });
    const sid = u.searchParams.get('system_id') || '';
    const rows = db.prepare("SELECT p.id, p.name, p.spec, p.unit, COALESCE(pr.price, p.price) as price FROM products p LEFT JOIN prices pr ON pr.product_id=p.id AND pr.system_id=? WHERE p.book_id=? ORDER BY p.id DESC").all(sid, bookId);
    return sendJSON(res, 200, rows);
  }
  if (p === '/api/prices' && method === 'POST') {
    if (!need('price.edit')) return;
    const b = await getBody(req);
    if (!b.product_id || !b.system_id) return sendJSON(res, 400, { error: '缺少参数' });
    db.prepare('INSERT INTO prices(product_id,system_id,price) VALUES(?,?,?) ON CONFLICT(product_id,system_id) DO UPDATE SET price=excluded.price').run(b.product_id, b.system_id, Number(b.price) || 0);
    return sendJSON(res, 200, { ok: true });
  }

  // ---------- 门店历史价（按门店 × 商品 记录最近一次成交价） ----------
  // 开单 / 改单成功后自动记录：价格 + 整单折扣
  if (p === '/api/customer-prices' && method === 'GET') {
    if (!need('price.view')) return;
    // 🔴 客户传别家的 customer_id 就能拿到别店的成交价 → 锁死（多门店：查授权集合）
    const _qCid = u.searchParams.get('customer_id');
    if (isCustomer(ctx) && _qCid && !custAllowed(ctx, _qCid)) {
      return sendJSON(res, 403, { error: '只能查看自己门店的价格' });
    }
    const cid = isCustomer(ctx) ? String(custTarget(ctx, _qCid) || '') : _qCid;
    if (!cid) return sendJSON(res, 200, { prices: {}, discount: 1, has_history: false });
    const rows = db.prepare('SELECT product_id, price, discount FROM customer_prices WHERE book_id=? AND customer_id=?').all(bookId, cid);
    const prices = {}; const itemDiscounts = {};
    for (const r of rows) {
      prices[r.product_id] = r.price;
      const dc = Number(r.discount);
      // 历史迁移出来的旧数据 discount 可能是 NULL，按 1 处理（不打折）
      itemDiscounts[r.product_id] = (isFinite(dc) && dc > 0) ? Math.min(1, Math.max(0.1, dc)) : 1;
    }
    const d = db.prepare('SELECT discount, last_order_id, updated_at FROM customer_discounts WHERE book_id=? AND customer_id=?').get(bookId, cid);
    // has_item_discount：该门店是否已有「单品级」折扣历史。
    // 前端据此决定还要不要自动带出整单折扣 —— 两层都带会叠乘（0.97 × 0.97 = 0.9409），
    // 所以有单品折扣历史时整单折扣一律留 1，由业务员自己按需手填。
    const hasItemDisc = Object.keys(itemDiscounts).some(k => itemDiscounts[k] < 1 - 1e-9);
    return sendJSON(res, 200, { prices, item_discounts: itemDiscounts, discount: d ? d.discount : 1,
      has_history: rows.length > 0 || !!d, has_item_discount: hasItemDisc,
      last_order_id: d ? d.last_order_id : null, updated_at: d ? d.updated_at : null });
  }
  // 单商品在该门店的历史价（含最近若干单走势）
  m = p.match(/^\/api\/customer-prices\/(\d+)\/(\d+)$/);
  if (m && method === 'GET') {
    const cur = db.prepare('SELECT price, discount, updated_at FROM customer_prices WHERE book_id=? AND customer_id=? AND product_id=?').get(bookId, m[1], m[2]);
    // 📌 走势必须同时给出「单品折扣」和「整单折扣」：老数据全走整单折扣（oi.discount 恒为 1），
    // 少任一列都会把「上次这行实际打了几折」算错。
    const hist = db.prepare("SELECT o.id, o.order_no, COALESCE(o.order_date,o.created_at) as dt, oi.price, oi.qty, oi.orig_price, oi.discount AS item_discount, o.discount AS order_discount FROM order_items oi JOIN orders o ON o.id=oi.order_id WHERE o.book_id=? AND o.customer_id=? AND oi.product_id=? AND o.status<>'returned' ORDER BY o.id DESC LIMIT 10").all(bookId, m[1], m[2]);
    return sendJSON(res, 200, { current: cur || null, history: hist });
  }
  // 门店价目表（配合价格体系查看/维护）
  // 📌 discount 可选：带了就一并写（价 + 折扣一起记忆），不带则保留原有折扣不变。
  // 之前这里只写 price，手工维护的门店价会把单品折扣悄悄留在旧值上。
  if (p === '/api/customer-prices' && method === 'POST') {
    if (!need('price.edit')) return;
    const b = await getBody(req);
    if (!b.customer_id || !b.product_id) return sendJSON(res, 400, { error: '缺少参数' });
    const price = Number(b.price) || 0;
    if (b.discount != null) {
      const dc = Math.min(1, Math.max(0.1, Number(b.discount) || 1));
      db.prepare("INSERT INTO customer_prices(book_id,customer_id,product_id,price,discount) VALUES(?,?,?,?,?) ON CONFLICT(book_id,customer_id,product_id) DO UPDATE SET price=excluded.price, discount=excluded.discount, updated_at=datetime('now','localtime')").run(bookId, b.customer_id, b.product_id, price, dc);
    } else {
      db.prepare("INSERT INTO customer_prices(book_id,customer_id,product_id,price) VALUES(?,?,?,?) ON CONFLICT(book_id,customer_id,product_id) DO UPDATE SET price=excluded.price, updated_at=datetime('now','localtime')").run(bookId, b.customer_id, b.product_id, price);
    }
    return sendJSON(res, 200, { ok: true });
  }
  // 清除某门店的历史价（恢复用默认价）；带 product_ids 时只删所选商品
  if (p === '/api/customer-prices' && method === 'DELETE') {
    if (!need('price.edit')) return;
    const cid = u.searchParams.get('customer_id');
    if (!cid) return sendJSON(res, 400, { error: '缺少 customer_id' });
    const idsRaw = (u.searchParams.get('product_ids') || '').trim();
    if (idsRaw) {
      const ids = idsRaw.split(',').map(s => parseInt(s, 10)).filter(n => isFinite(n) && n > 0);
      if (!ids.length) return sendJSON(res, 400, { error: 'product_ids 无效' });
      const n = db.prepare('DELETE FROM customer_prices WHERE book_id=? AND customer_id=? AND product_id IN (' + ids.map(() => '?').join(',') + ')').run(bookId, cid, ...ids).changes;
      return sendJSON(res, 200, { ok: true, removed: n });
    }
    const n = db.prepare('DELETE FROM customer_prices WHERE book_id=? AND customer_id=?').run(bookId, cid).changes;
    db.prepare('DELETE FROM customer_discounts WHERE book_id=? AND customer_id=?').run(bookId, cid);
    return sendJSON(res, 200, { ok: true, removed: n });
  }

  // ---------- 送货单（可选功能，按套账） ----------
  if (p === '/api/deliveries' && method === 'GET') {
    if (!need('order.view')) return;
    const drows = db.prepare('SELECT * FROM deliveries WHERE book_id=? ORDER BY id DESC LIMIT 200').all(bookId);
    return sendJSON(res, 200, isCustomer(ctx)
      ? drows.filter(r => (ctx.customerIds || []).indexOf(Number(r.customer_id)) >= 0) : drows);
  }

  // ---------- 设置 ----------
  if (p === '/api/settings' && method === 'GET') {
    // 设置里有小票抬头/仓库/处理人/webhook 等内部信息，客户不需要也不该看
    if (isCustomer(ctx)) return sendJSON(res, 403, { error: '客户账号不能查看店铺设置' });
    return sendJSON(res, 200, {
      shop_name: effShopName(ctx.book),
      receipt_title: getSetting(bookId, 'receipt_title', '销售小票'),
      receipt_footer: getSetting(bookId, 'receipt_footer', '谢谢惠顾，欢迎再次光临'),
      list_title: getSetting(bookId, 'list_title', '商品清单'),
      receipt_warehouse: getSetting(bookId, 'receipt_warehouse', '天河仓'),
      receipt_handler: getSetting(bookId, 'receipt_handler', ''),
      feedback_webhook: getSetting(bookId, 'feedback_webhook', ''),
      join_password_set: !!getSetting(bookId, 'join_password', '')
    });
  }
  if (p === '/api/settings' && method === 'PUT') {
    if (!can(role, 'settings.edit')) return sendJSON(res, 403, { error: '只有老板可以修改设置' });
    const b = await getBody(req);
    if (b.shop_name != null) {
      const n = String(b.shop_name).trim();
      if (!n) return sendJSON(res, 400, { error: '店名不能为空' });
      setSetting(bookId, 'shop_name', n);
    }
    for (const k of ['receipt_title', 'receipt_footer', 'list_title', 'receipt_warehouse', 'receipt_handler', 'feedback_webhook']) {
      if (b[k] != null) setSetting(bookId, k, String(b[k]).trim());
    }
    // 加入口令不走明文通道：存在则哈希，空则清除
    if (b.join_password != null) {
      const jp = String(b.join_password).trim();
      setSetting(bookId, 'join_password', jp ? hashPassword(jp) : '');
    }
    return sendJSON(res, 200, { ok: true, shop_name: effShopName(ctx.book) });
  }
  if (p === '/api/settings/password' && method === 'PUT') {
    // 修改自己的登录密码
    const b = await getBody(req);
    if (!verifyPassword(String(b.old_password || ''), ctx.account.password)) return sendJSON(res, 400, { error: '旧密码错误' });
    if (String(b.new_password || '').length < 4) return sendJSON(res, 400, { error: '新密码至少 4 位' });
    db.prepare('UPDATE accounts SET password=? WHERE id=?').run(hashPassword(String(b.new_password)), ctx.account.id);
    return sendJSON(res, 200, { ok: true });
  }

  // ---------- 意见反馈（2026-10-15）----------
  // 任何登录用户都能提交；只有 is_admin 管理员能看全部 + 回复。
  // 附件走 uploads/feedback/ 目录，接口上传时只允许白名单扩展名，且重新生成安全文件名
  //（原始文件名仅作展示，杜绝 ../ 路径穿越与「双扩展名」伪装可执行文件）。
  if (p === '/api/feedback-types' && method === 'GET') {
    return sendJSON(res, 200, [
      { id: 'bug', label: 'Bug 反馈', icon: '🐞', hint: '功能报错、数据显示不对、打印异常等' },
      { id: 'feature', label: '功能需求', icon: '💡', hint: '希望新增或改进某个功能' },
      { id: 'account', label: '账户问题', icon: '👤', hint: '登录、密码、权限、角色等' },
      { id: 'other', label: '其他', icon: '💬', hint: '其他问题或建议，用文字说明' }
    ]);
  }
  // 提交反馈（登录即可）。attachments: [{name, data(base64)}]
  if (p === '/api/feedbacks' && method === 'POST') {
    const b = await getBody(req).catch(() => ({}));
    const type = String(b.type || 'other');
    if (!['bug', 'feature', 'account', 'other'].includes(type)) return sendJSON(res, 400, { error: '反馈类型不正确' });
    const content = String(b.content || '').trim();
    if (!content && !(Array.isArray(b.attachments) && b.attachments.length)) {
      return sendJSON(res, 400, { error: '请填写描述内容，或上传截图附件' });
    }
    if (content.length > 4000) return sendJSON(res, 400, { error: '描述太长（最多 4000 字）' });
    // ---- 附件落盘 ----
    const atts = Array.isArray(b.attachments) ? b.attachments.slice(0, 5) : [];
    if (atts.length === 5 && (b.attachments || []).length > 5) return sendJSON(res, 400, { error: '最多上传 5 个附件' });
    const saved = [];
    // 🔴 saved 存的是 saveFeedbackFile 的**整个返回对象 r**（含 keep），
    // 后面统一用 s.keep.xxx 取值。若这里只 push(r.keep)，下面写 s.safe_name/s.id 全是
    // undefined → UPDATE 绑不上参数 → 整条反馈提交直接 500。
    for (const a of atts) {
      const r = saveFeedbackFile(a, ctx.account.id);
      if (r.error) {
        // 中途失败要回滚：已落盘的附件文件删掉、feedback_files 记录删掉，
        // 否则会攒下一堆没人引用的孤儿文件（删反馈时文件是故意保留的，这里是另一回事）。
        for (const s of saved) {
          try { fs.unlinkSync(path.join(UPLOAD_DIR, s.keep.safe_name)); } catch (e) {}
          db.prepare('DELETE FROM feedback_files WHERE id=?').run(s.keep.id);
        }
        return sendJSON(res, 400, { error: r.error });
      }
      if (r.keep) saved.push(r);
    }
    const info = db.prepare('INSERT INTO feedbacks(book_id,type,title,content,contact,app_version,page,attachments,account_id,account_name) VALUES(?,?,?,?,?,?,?,?,?,?)')
      .run(bookId, type, String(b.title || '').trim().slice(0, 100), content,
        String(b.contact || '').trim().slice(0, 60), String(b.app_version || '').slice(0, 30),
        String(b.page || '').slice(0, 40),
//🔴 必须存 id（前端靠它调 /api/feedback-files/:id 下载附件）与 name（原文件名，仅供展示）
        JSON.stringify(saved.map(s => ({ id: s.keep.id, name: s.keep.name, size: s.keep.size, mime: s.keep.mime }))),
        ctx.account.id, ctx.account.display_name || ctx.account.username);
    const fid = Number(info.lastInsertRowid);
    // 🔴 saved 里装的是 saveFeedbackFile 的整个返回对象，取值必须走 s.keep.safe_name，
    // 直接写 s.safe_name 会得到 undefined → UPDATE 绑不上参数 → 整条反馈提交 500。
    for (const s of saved) db.prepare('UPDATE feedback_files SET feedback_id=? WHERE safe_name=?').run(fid, s.keep.safe_name);
    // 提醒管理员：站内未读角标（前端轮询 /api/me 的 feedbackUnread）+ 可选 Webhook 推送
    notifyAdminsOfFeedback(fid, type, content);
    return sendJSON(res, 200, { ok: true, id: fid });
  }
  // 侧栏红点：未读反馈数（单独一个轻接口，前端每 60s 轮询，不必拉整份 /api/me）
  if (p === '/api/feedback-unread' && method === 'GET') {
    const n = ctx.account.is_admin
      ? db.prepare("SELECT COUNT(*) c FROM feedbacks WHERE status='open'").get().c
      : db.prepare("SELECT COUNT(*) c FROM feedbacks WHERE account_id=? AND status='open'").get(ctx.account.id).c;
    return sendJSON(res, 200, { unread: n });
  }
  // 我的反馈（普通用户只能看自己提的；管理员走下面的全量接口）
  if (p === '/api/feedbacks/mine' && method === 'GET') {
    const rows = db.prepare('SELECT id,type,title,content,status,admin_note,created_at,updated_at FROM feedbacks WHERE account_id=? ORDER BY id DESC LIMIT 100').all(ctx.account.id);
    return sendJSON(res, 200, rows.map(r => Object.assign(r, { replies: db.prepare('SELECT id,content,created_at,account_name FROM feedback_replies WHERE feedback_id=? ORDER BY id').all(r.id) })));
  }
  // 全量反馈（仅管理员）
  if (p === '/api/feedbacks' && method === 'GET') {
    if (!ctx.account.is_admin) return sendJSON(res, 403, { error: '仅管理员可查看反馈' });
    const st = u.searchParams.get('status') || '';
    const ty = u.searchParams.get('type') || '';
    let sql = 'SELECT * FROM feedbacks WHERE 1=1', args = [];
    if (st) { sql += ' AND status=?'; args.push(st); }
    if (ty) { sql += ' AND type=?'; args.push(ty); }
    sql += ' ORDER BY (status=\'open\') DESC, id DESC LIMIT 500';
    const rows = db.prepare(sql).all(...args);
    const stc = db.prepare("SELECT status, COUNT(*) c FROM feedbacks GROUP BY status").all();
    const cnt = {}; stc.forEach(x => { cnt[x.status] = x.c; });
    return sendJSON(res, 200, { counts: { open: cnt.open || 0, replied: cnt.replied || 0, closed: cnt.closed || 0 },
      items: rows.map(r => Object.assign(r, { replies: db.prepare('SELECT id,content,created_at,account_name FROM feedback_replies WHERE feedback_id=? ORDER BY id').all(r.id) })) });
  }
  // 修改状态（仅管理员）
  m = p.match(/^\/api\/feedbacks\/(\d+)\/status$/);
  if (m && method === 'POST') {
    if (!ctx.account.is_admin) return sendJSON(res, 403, { error: '仅管理员可操作' });
    const b = await getBody(req).catch(() => ({}));
    const st = String(b.status || '');
    if (!['open', 'replied', 'closed'].includes(st)) return sendJSON(res, 400, { error: '状态不正确' });
    const r = db.prepare("UPDATE feedbacks SET status=?, admin_note=COALESCE(?,admin_note), updated_at=datetime('now','localtime') WHERE id=?")
      .run(st, b.note != null ? String(b.note) : null, m[1]);
    if (!r.changes) return sendJSON(res, 404, { error: '反馈不存在' });
    return sendJSON(res, 200, { ok: true });
  }
  // 管理员回复（仅管理员）
  m = p.match(/^\/api\/feedbacks\/(\d+)\/reply$/);
  if (m && method === 'POST') {
    if (!ctx.account.is_admin) return sendJSON(res, 403, { error: '仅管理员可回复' });
    const b = await getBody(req).catch(() => ({}));
    const content = String(b.content || '').trim();
    if (!content) return sendJSON(res, 400, { error: '回复内容不能为空' });
    if (!db.prepare('SELECT id FROM feedbacks WHERE id=?').get(m[1])) return sendJSON(res, 404, { error: '反馈不存在' });
    db.prepare('INSERT INTO feedback_replies(feedback_id,account_id,account_name,content) VALUES(?,?,?,?)')
      .run(m[1], ctx.account.id, ctx.account.display_name || ctx.account.username, content);
    db.prepare("UPDATE feedbacks SET status='replied', updated_at=datetime('now','localtime') WHERE id=?").run(m[1]);
    return sendJSON(res, 200, { ok: true });
  }
  // 删除反馈（仅管理员，附件文件保留不删，避免误删后回复里的链接全失效）
  m = p.match(/^\/api\/feedbacks\/(\d+)$/);
  if (m && method === 'DELETE') {
    if (!ctx.account.is_admin) return sendJSON(res, 403, { error: '仅管理员可操作' });
    db.prepare('DELETE FROM feedback_replies WHERE feedback_id=?').run(m[1]);
    db.prepare('DELETE FROM feedbacks WHERE id=?').run(m[1]);
    return sendJSON(res, 200, { ok: true });
  }
  // 附件下载：管理员或提交者本人
  m = p.match(/^\/api\/feedback-files\/(\d+)$/);
  if (m && method === 'GET') {
    const f = db.prepare('SELECT * FROM feedback_files WHERE id=?').get(m[1]);
    if (!f) return sendJSON(res, 404, { error: '附件不存在' });
    if (!ctx.account.is_admin && f.account_id !== ctx.account.id) return sendJSON(res, 403, { error: '无权查看该附件' });
    const fp = path.join(UPLOAD_DIR, f.safe_name);
    if (!fp.startsWith(UPLOAD_DIR) || !fs.existsSync(fp)) return sendJSON(res, 404, { error: '附件已丢失' });
    const buf = fs.readFileSync(fp);
    res.writeHead(200, {
      'Content-Type': f.mime || 'application/octet-stream',
      'Content-Length': buf.length,
      // 中文文件名走 RFC5987，浏览器才认得；这里不落 Content-Disposition:inline 之外的东西
      'Content-Disposition': "attachment; filename*=UTF-8''" + encodeURIComponent(f.orig_name),
      'Cache-Control': 'private, max-age=3600',
      'X-Content-Type-Options': 'nosniff'
    });
    return res.end(buf);
  }

  // ---------- 统计 ----------
  if (p === '/api/stats' && method === 'GET') {
    // 🔴 今日销售额 + 订单数 + 低库存预警 = 纯经营数据，客户一律不给
    if (isCustomer(ctx)) return sendJSON(res, 403, { error: '客户账号不能查看经营统计' });
    const today = db.prepare("SELECT COALESCE(SUM(COALESCE(payable,total)),0) as sales, COUNT(*) as cnt FROM orders WHERE book_id=? AND date(COALESCE(order_date,created_at))=date('now','localtime') AND status='paid'").get(bookId);
    const low = db.prepare("SELECT p.name, COALESCE(i.qty,0) as qty, COALESCE(i.low_threshold,0) as low FROM products p LEFT JOIN inventory i ON i.product_id=p.id WHERE p.book_id=? AND COALESCE(i.low_threshold,0)>0 AND COALESCE(i.qty,0) <= COALESCE(i.low_threshold,0)").all(bookId);
    return sendJSON(res, 200, { todaySales: today.sales, todayOrders: today.cnt, lowStock: low });
  }

  return sendJSON(res, 404, { error: '接口不存在' });
}

// 对外暴露的账户/套账字段（不含密码）
function pubAcc(a) { return { id: a.id, username: a.username, display_name: a.display_name, role: a.role, roleLabel: roleLabel(a.role), book_id: a.book_id, active: a.active, is_admin: !!a.is_admin, whitelisted: !!a.whitelisted, banned_reason: a.banned_reason || null, banned_at: a.banned_at || null, created_at: a.created_at, customer_id: a.customer_id ? Number(a.customer_id) : null,
    // 2026-10-19 多门店：客户端（手机版）拿它渲染门店切换器
    customer_ids: a.role === 'customer' ? custStoreIdsOf(a, a.book_id) : [] }; }
function pubBook(b) { return b ? { id: b.id, name: b.name } : null; }
function permsOf(role) { return can(role, '*') ? ['*'] : (ROLES[role] ? ROLES[role].perms.slice() : []); }
function myBooks(acc) {
  const rows = db.prepare('SELECT b.id, b.name, b.owner_account_id FROM books b JOIN account_books ab ON ab.book_id=b.id WHERE ab.account_id=? ORDER BY b.id').all(acc.id);
  if (rows.length) return rows.map(r => ({ id: r.id, name: r.name, role: acc.role }));
  return [{ id: acc.book_id, name: (db.prepare('SELECT name FROM books WHERE id=?').get(acc.book_id) || {}).name || '默认套账', role: acc.role }];
}

const compCache = new Map(); // key: etag + ':' + enc  ->  已压缩的 Buffer；避免每个请求重复 gzip 大文件（app.js 等）

function serveStatic(req, res, pathname) {
  let rel = pathname === '/' ? '/index.html' : pathname;
  const fp = path.normalize(path.join(PUBLIC_DIR, rel));
  if (!fp.startsWith(PUBLIC_DIR)) { res.writeHead(403); return res.end('forbidden'); }
  fs.readFile(fp, (err, data) => {
    if (err) { res.writeHead(404); return res.end('not found'); }
    const ext = path.extname(fp);
    let cacheCtrl;
    if (ext === '.html') {
      // HTML 本身永不缓存，并给 JS/CSS 引用注入基于文件修改时间的版本号：
      // 安卓 App(WebView) 直接缓存了 js/app.js，导致改了服务器也看不到更新。
      // 加 ?v=<mtime> 后，每次部署文件变化版本号就变，WebView 必然当新地址重新拉取。
      let html = data.toString('utf8');
      const mts = (p) => { try { return Math.floor(fs.statSync(path.join(PUBLIC_DIR, p)).mtimeMs); } catch (e) { return Date.now(); } };
      const av = mts('js/app.js'), cv = mts('css/style.css');
      html = html.replace(/src="js\/app\.js"/g, 'src="js/app.js?v=' + av + '"')
                 .replace(/href="css\/style\.css"/g, 'href="css/style.css?v=' + cv + '"');
      data = Buffer.from(html, 'utf8');
      cacheCtrl = 'no-store';
    } else {
      // 🔴🔴 带 ?v= 的静态资源**绝不能给 immutable**。
      //    原因：?v= 用的是文件 mtime（毫秒），而同一秒内多次部署会出现相同/相近 mtime；
      //    再叠加 gzip 压缩缓存层，浏览器会把旧版 CSS/JS 永久锁死 —— 实测
      //    「移动端媒体查询改了但线上仍按 220px 侧栏渲染，MAIN 只剩 170px」。
      //    改成 must-revalidate：命中 ETag 时只发 304，几乎零开销，但内容永远是最新的。
      cacheCtrl = pathname.indexOf('?v=') >= 0 || (req.url && req.url.indexOf('?v=') >= 0)
        ? 'public, max-age=0, must-revalidate'
        : 'no-cache';
    }
    // 用内容哈希做 ETag（必须先算：下方压缩缓存的 key 依赖它）
    const etag = 'W/"' + crypto.createHash('md5').update(data).digest('hex') + '"';
    // gzip/deflate 压缩：文本资源（js/css/html/json/svg）压缩比可达 70%+，显著加快首屏
    const compressible = ['.js', '.css', '.html', '.json', '.svg', '.txt', '.map'].includes(ext);
    const encHdr = req.headers['accept-encoding'] || '';
    let body = data, encoding = null;
    if (compressible && data.length > 512) {
      const enc = encHdr.includes('gzip') ? 'gzip' : (encHdr.includes('deflate') ? 'deflate' : null);
      if (enc) {
        const ck = etag + ':' + enc;
        let cb = compCache.get(ck);
        if (!cb) {
          cb = enc === 'gzip' ? zlib.gzipSync(data) : zlib.deflateSync(data);
          if (compCache.size > 200) compCache.clear(); // 防内存无限增长
          compCache.set(ck, cb);
        }
        body = cb; encoding = enc;
      }
    }
    const headers = { 'Content-Type': MIME[ext] || 'application/octet-stream' };
    if (encoding) headers['Content-Encoding'] = encoding;
    // 明确给出 Content-Length：否则走 chunked，客户端拿不到总大小，App 内更新进度条无法显示百分比
    headers['Content-Length'] = body.length;
    headers['ETag'] = etag;
    headers['Cache-Control'] = cacheCtrl;
    if (req.headers['if-none-match'] === etag) { res.writeHead(304, headers); return res.end(); }
    res.writeHead(200, headers);
    res.end(body);
  });
}

const requestHandler = async (req, res) => {
  // CORS：原生打包 App 的页面源是 capacitor://localhost，访问接口属跨域，需放行
  const origin = req.headers.origin;
  if (origin) {
    const reqHost = req.headers.host || 'localhost';
    let allow = false;
    if (ALLOWED_ORIGINS.length) {
      allow = ALLOWED_ORIGINS.includes(origin);
    } else {
      try {
        const oh = new URL(origin).host;
        allow = (oh === reqHost) || origin === 'capacitor://localhost' || origin === 'ionic://localhost';
      } catch (e) { allow = false; }
    }
    if (allow) {
      res.setHeader('Access-Control-Allow-Origin', origin);
      res.setHeader('Vary', 'Origin');
      res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
      res.setHeader('Access-Control-Allow-Methods', 'GET,POST,PUT,DELETE,OPTIONS');
    }
  }
  if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }
  const u = new URL(req.url, 'http://localhost');
  try {
    if (u.pathname.startsWith('/api/')) await handleApi(req, res, u);
    // 🔴 v5.5：/downloads/apk 短链（网页版二维码 / 手机版「下载手机版」按钮都指这里）。
    //    二维码里不能写 R2 预签名 URL（7天过期，扫不出来），所以固定指这个短链，
    //    由 refresh_apk_url.sh 持续把 update.json 指向最新版，短链永不变。
    //    以前这里落到 serveStatic 找 public/downloads/apk 这个文件→ 404，扫码必然失败。
    else if (u.pathname === '/downloads/apk') redirectApk(res);
    else serveStatic(req, res, u.pathname);
  } catch (e) {
    console.error(e);
    if (!res.headersSent) sendJSON(res, 500, { error: String(e.message || e) });
    else res.end();
  }
};

/** /downloads/apk → 302 到 update.json 里的最新版 R2 预签名地址 */
function redirectApk(res) {
  try {
    const j = JSON.parse(fs.readFileSync(path.join(PUBLIC_DIR, 'downloads/update.json'), 'utf8'));
    if (!j.url) throw new Error('update.json 无 url');
    // 302 而不是 307：APK 是 GET，302 后浏览器/微信会正常跟随下载
    res.writeHead(302, {
      Location: j.url,
      'Cache-Control': 'no-store',
      'Content-Type': 'text/plain; charset=utf-8',
    });
    return res.end('redirecting to v' + j.versionName);
  } catch (e) {
    res.writeHead(503, { 'Content-Type': 'text/plain; charset=utf-8' });
    return res.end('APK 暂不可用，请稍后重试');
  }
}

const server = http.createServer(requestHandler);

// ---- 内容合规治理：初始化 + 巡检定时器 ----
// 必须在 db 就绪后初始化。巡检每分钟一次：把「超期未整改」的违规记录
// 复核一遍（内容可能已被改名），仍违规则自动封禁该账号并踢下线。
MOD = modServer.init({ db, SESSIONS });
setInterval(() => { try { MOD.sweep(); } catch (e) { console.error('sweep:', e.message); } }, 60 * 1000);
MOD.sweep(); // 启动时先跑一次：重启期间超期的记录立刻生效，不必等满 1 分钟
console.log(`[moderation] 合规巡检已启动：整改窗口 ${MOD.GRACE_MINUTES} 分钟`);

// HTTP 监听地址：生产建议设 127.0.0.1（仅本机/反代可达），消除公网明文直连暴露；
// 反代(如 your-domain.com HTTPS)在同机转发到 127.0.0.1:PORT 即可。默认 0.0.0.0 便于本地开发。
const HTTP_BIND = process.env.ERP_BIND || '0.0.0.0';
server.listen(PORT, HTTP_BIND, () => console.log(`ERP running: http://${HTTP_BIND}:${PORT}`));

// ---- 可选 HTTPS 监听（启用网页蓝牙/USB 打印所需的安全上下文） ----
// 仅在存在证书时启动；证书由运维在服务器上生成到 /www/erp/tls/{key,cert}.pem。
// 自签证书在浏览器需手动信任一次，但 origin 仍属「安全上下文」，Web Bluetooth/Web Serial 即可用。
const TLS_KEY = process.env.ERP_TLS_KEY || path.join(__dirname, 'tls', 'key.pem');
const TLS_CRT = process.env.ERP_TLS_CRT || path.join(__dirname, 'tls', 'cert.pem');
if (fs.existsSync(TLS_KEY) && fs.existsSync(TLS_CRT)) {
  try {
    const https = require('https');
    const tlsPort = process.env.TLS_PORT || 3443;
    const httpsServer = https.createServer({ key: fs.readFileSync(TLS_KEY), cert: fs.readFileSync(TLS_CRT) }, requestHandler);
    httpsServer.listen(tlsPort, () => console.log(`ERP HTTPS running: https://localhost:${tlsPort}`));
  } catch (e) {
    console.error('HTTPS 启动失败:', e.message);
  }
} else {
  console.log('未找到 TLS 证书（tls/key.pem, tls/cert.pem），仅启动 HTTP；网页蓝牙/USB 打印不可用。');
}
module.exports = { server };
