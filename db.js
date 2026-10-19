// 数据库初始化：建表 + 迁移 + 种子（SQLite，单文件，零依赖）
// 多套账（books）架构：所有业务表以 book_id 隔离；账户（accounts）隶属某套账并带角色。
const { DatabaseSync } = require('node:sqlite');
const crypto = require('crypto');
const path = require('path');
const fs = require('fs');

const DATA_DIR = path.join(__dirname, 'data');
if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });
const DB_PATH = process.env.DB_PATH || path.join(DATA_DIR, 'erp.db');

const db = new DatabaseSync(DB_PATH);
db.exec('PRAGMA journal_mode = WAL;');
// 流畅度核心：node:sqlite 默认 synchronous=FULL，WAL 下每次提交都 fsync，
// 在云盘/网络块存储上单次写要 0.4~1.5s。WAL 模式下 NORMAL 仅在 checkpoint 时 fsync，
// 断电最多丢上一个检查点（默认 ~1s），对 ERP 业务完全可接受，写性能提升 10~50 倍。
db.exec('PRAGMA synchronous = NORMAL;');
db.exec('PRAGMA busy_timeout = 5000;');        // 并发写遇锁时等待 5s，而非立刻 SQLITE_BUSY 报错
db.exec('PRAGMA wal_autocheckpoint = 1000;');  // 攒满 1000 页再 checkpoint，减少 fsync 次数
db.exec('PRAGMA mmap_size = 268435456;');      // 库文件映射进内存（256MB），读查询免磁盘 IO
db.exec('PRAGMA foreign_keys = OFF;');

// 密码哈希：scrypt + 随机盐（无第三方依赖）
function hashPassword(pwd, salt) {
  salt = salt || crypto.randomBytes(12).toString('hex');
  const h = crypto.scryptSync(String(pwd), salt, 32).toString('hex');
  return salt + ':' + h;
}
function verifyPassword(pwd, stored) {
  if (!stored || stored.indexOf(':') < 0) return false;
  const [salt, h] = stored.split(':');
  const calc = crypto.scryptSync(String(pwd), salt, 32).toString('hex');
  try { return crypto.timingSafeEqual(Buffer.from(h, 'hex'), Buffer.from(calc, 'hex')); } catch (e) { return false; }
}

db.exec(`
-- 套账（每组独立数据）
CREATE TABLE IF NOT EXISTS books (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  owner_account_id INTEGER,
  created_at TEXT DEFAULT (datetime('now','localtime'))
);

-- 账户（隶属套账、角色：boss 老板 / manager 经理 / staff 员工 / super 平台超管）
CREATE TABLE IF NOT EXISTS accounts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT UNIQUE NOT NULL,
  password TEXT NOT NULL,
  display_name TEXT,
  role TEXT DEFAULT 'staff',
  book_id INTEGER,
  active INTEGER DEFAULT 1,
  created_at TEXT DEFAULT (datetime('now','localtime'))
);

-- 账户可访问的套账（多对多、老板/经理可跨套账）
CREATE TABLE IF NOT EXISTS account_books (
  account_id INTEGER,
  book_id INTEGER,
  PRIMARY KEY(account_id, book_id)
);

CREATE TABLE IF NOT EXISTS customers (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  book_id INTEGER DEFAULT 1,
  name TEXT NOT NULL,
  type TEXT DEFAULT 'store',
  contact TEXT,
  phone TEXT,
  address TEXT,
  remark TEXT,
  created_at TEXT DEFAULT (datetime('now','localtime'))
);

CREATE TABLE IF NOT EXISTS products (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  book_id INTEGER DEFAULT 1,
  name TEXT NOT NULL,
  barcode TEXT,
  spec TEXT,
  unit TEXT DEFAULT '个',
  price REAL DEFAULT 0,
  created_at TEXT DEFAULT (datetime('now','localtime'))
);

CREATE TABLE IF NOT EXISTS inventory (
  product_id INTEGER PRIMARY KEY,
  book_id INTEGER DEFAULT 1,
  qty REAL DEFAULT 0,
  low_threshold REAL DEFAULT 0,
  updated_at TEXT DEFAULT (datetime('now','localtime')),
  FOREIGN KEY(product_id) REFERENCES products(id)
);

CREATE TABLE IF NOT EXISTS orders (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  book_id INTEGER DEFAULT 1,
  order_no TEXT,
  customer_id INTEGER,
  customer_name TEXT,
  total REAL DEFAULT 0,
  status TEXT DEFAULT 'paid',
  remark TEXT,
  created_at TEXT DEFAULT (datetime('now','localtime')),
  FOREIGN KEY(customer_id) REFERENCES customers(id)
);

CREATE TABLE IF NOT EXISTS order_items (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  book_id INTEGER DEFAULT 1,
  order_id INTEGER,
  product_id INTEGER,
  product_name TEXT,
  spec TEXT,
  -- 商品条码快照（2026-10-15 加）：开单那一刻把 products.barcode 存下来。
  -- 为什么必须有：products.spec 装的是规格文字（106g/180ml），真条码在 products.barcode，
  -- 而 order_items 以前只快照了 spec → 订单页/小票按 spec 取条码必然取不到（打印全无条码）。
  barcode TEXT,
  unit TEXT,
  qty REAL,
  price REAL,
  subtotal REAL,
  FOREIGN KEY(order_id) REFERENCES orders(id)
);

-- ---------- 应收应付 / 货款往来（2026-10-17 加，参照管家婆「挂账 + 收款核销」模型） ----------
-- 🔴 口径说明（跟管家婆一致，改前先想清楚）：
--   挂账：开销售单不填收款 → 默认欠着，应收自动累加
--   核销：收到钱另开「收款单」，把钱分配到具体销售单，应收自动减少
--   优惠：抹零/让利单独记在 payments.discount，**绝不改订单金额**
--   为什么收款要独立表而不是 orders 上加个 paid：一次还3 张单的钱时，
--   业务员根本不知道是哪几张 —— 必须靠「自动分配(最早优先)」来核销。
CREATE TABLE IF NOT EXISTS payments (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  book_id INTEGER DEFAULT 1,
  pay_no TEXT,                       -- 收款单号 SK20261017-0001
  customer_id INTEGER,
  customer_name TEXT,
  amount REAL DEFAULT 0,              -- 实收现金/转账总额（正数）
  discount REAL DEFAULT 0,           -- 优惠/抹零额（正数）—— 单独记账，不动订单金额
  method TEXT,                       -- 收款方式：现金/微信/支付宝/银行转账/其他
  pay_date TEXT,                     -- 收款日期 YYYY-MM-DD
  remark TEXT,
  created_by INTEGER,
  created_at TEXT DEFAULT (datetime('now','localtime')),
  FOREIGN KEY(customer_id) REFERENCES customers(id)
);

-- 核销明细：这张收款单分配到哪几张单、各抵了多少、优惠摊到哪张
CREATE TABLE IF NOT EXISTS payment_items (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  book_id INTEGER DEFAULT 1,
  payment_id INTEGER,
  order_id INTEGER,
  order_no TEXT,
  amount REAL DEFAULT 0,             -- 本单本次核销金额（正数）
  discount REAL DEFAULT 0,          -- 摊到本单的优惠额
  FOREIGN KEY(payment_id) REFERENCES payments(id)
);

-- 门店账期设置：默认 30 天，超期未结清 → 标红提醒（不拦开单）
CREATE TABLE IF NOT EXISTS customer_terms (
  book_id INTEGER DEFAULT 1,
  customer_id INTEGER PRIMARY KEY,
  term_days INTEGER DEFAULT 30,     -- 账期天数，<=0 = 不提醒
  remark TEXT,
  updated_at TEXT DEFAULT (datetime('now','localtime'))
);

-- 价格体系（按套账）
CREATE TABLE IF NOT EXISTS price_systems (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  book_id INTEGER DEFAULT 1,
  name TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS prices (
  product_id INTEGER,
  system_id INTEGER,
  price REAL DEFAULT 0,
  PRIMARY KEY(product_id, system_id),
  FOREIGN KEY(product_id) REFERENCES products(id)
);

-- 门店历史价（按门店 × 商品 记录最近一次成交价、开单时自动带出）
CREATE TABLE IF NOT EXISTS customer_prices (
  book_id INTEGER DEFAULT 1,
  customer_id INTEGER,
  product_id INTEGER,
  price REAL DEFAULT 0,
  updated_at TEXT DEFAULT (datetime('now','localtime')),
  PRIMARY KEY(book_id, customer_id, product_id),
  FOREIGN KEY(customer_id) REFERENCES customers(id),
  FOREIGN KEY(product_id) REFERENCES products(id)
);
-- 门店最近一次整单折扣
CREATE TABLE IF NOT EXISTS customer_discounts (
  book_id INTEGER DEFAULT 1,
  customer_id INTEGER,
  discount REAL DEFAULT 1,
  last_order_id INTEGER,
  updated_at TEXT DEFAULT (datetime('now','localtime')),
  PRIMARY KEY(book_id, customer_id)
);

-- 信任设备（勾选「30天内免密登录」）：库里只存令牌的 SHA-256，明文不落库
CREATE TABLE IF NOT EXISTS trusted_devices (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  book_id INTEGER DEFAULT 1,
  token_hash TEXT NOT NULL UNIQUE,
  account_id INTEGER NOT NULL,
  device TEXT,
  created_at TEXT DEFAULT (datetime('now','localtime')),
  last_used_at TEXT,
  expires_at TEXT NOT NULL,
  FOREIGN KEY(account_id) REFERENCES accounts(id)
);
CREATE INDEX IF NOT EXISTS idx_trusted_account ON trusted_devices(account_id);

-- 送货单（按套账）
CREATE TABLE IF NOT EXISTS deliveries (  id INTEGER PRIMARY KEY AUTOINCREMENT,
  book_id INTEGER DEFAULT 1,
  delivery_no TEXT,
  system_id INTEGER DEFAULT 1,
  discount REAL DEFAULT 1,
  total REAL DEFAULT 0,
  remark TEXT,
  status TEXT DEFAULT 'done',
  created_at TEXT DEFAULT (datetime('now','localtime'))
);
CREATE TABLE IF NOT EXISTS delivery_items (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  book_id INTEGER DEFAULT 1,
  delivery_id INTEGER,
  product_id INTEGER,
  product_name TEXT,
  spec TEXT,
  unit TEXT,
  qty REAL,
  price REAL,
  subtotal REAL,
  FOREIGN KEY(delivery_id) REFERENCES deliveries(id)
);

-- 系统设置（按套账，含打印模板/店名等）
CREATE TABLE IF NOT EXISTS settings (
  book_id INTEGER DEFAULT 1,
  key TEXT,
  value TEXT,
  PRIMARY KEY(book_id, key)
);

-- ========== 内容合规与平台治理 ==========
-- 违规记录：商品/客户等自由文本命中违禁词时写入一条，
-- 先给 created_by 30 分钟自助整改（pending），超期未改由巡检任务改判并封禁。
-- 注：注释里不要出现半角分号，SQLite 会把它当语句结束符（会导致整段 SQL 语法错误）。
CREATE TABLE IF NOT EXISTS violations (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  book_id INTEGER DEFAULT 1,
  target_type TEXT NOT NULL,        -- product / customer
  target_id INTEGER NOT NULL,
  target_name TEXT,                 -- 快照：便于管理员直接看到违规内容
  field TEXT,                      -- 命中的字段（name）
  words TEXT,                      -- 命中的违禁词，逗号分隔
  level TEXT,                      -- high / mid / risky
  account_id INTEGER NOT NULL,      -- 违规创建人（被治理对象）
  status TEXT DEFAULT 'pending',    -- pending 待整改 / resolved 已整改 / banned 已封禁
  created_at TEXT DEFAULT (datetime('now','localtime')),
  deadline TEXT NOT NULL,           -- 30 分钟整改截止
  resolved_at TEXT,
  banned_at TEXT,
  admin_note TEXT
);
CREATE INDEX IF NOT EXISTS idx_violations_pending ON violations(status, deadline);
CREATE INDEX IF NOT EXISTS idx_violations_account ON violations(account_id, status);
CREATE INDEX IF NOT EXISTS idx_violations_book ON violations(book_id, id DESC);

-- 治理审计日志：所有自动/人工封禁解封都留痕，便于追溯与申诉
CREATE TABLE IF NOT EXISTS moderation_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  account_id INTEGER,
  action TEXT,        -- ban / unban / auto_ban / whitelist
  reason TEXT,
  detail TEXT,
  operator_id INTEGER, -- 人工操作的管理员；自动封禁为 NULL
  created_at TEXT DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_modlog_account ON moderation_log(account_id, id DESC);

-- ========== 意见反馈（2026-10-15）==========
-- 任何登录用户都能提交（bug / 功能需求 / 账户问题 / 其他），
-- 附件走 uploads 目录，这里只存 JSON 元数据。
-- 权限：普通用户只能看自己的；只有 accounts.is_admin=1 能查看全部并回复。
CREATE TABLE IF NOT EXISTS feedbacks (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  book_id INTEGER DEFAULT 1,
  type TEXT NOT NULL,             -- bug / feature / account / other
  title TEXT,
  content TEXT,
  contact TEXT,                   -- 联系方式（可选，方便管理员回访）
  app_version TEXT,               -- 提交时的客户端版本，便于定位问题
  page TEXT,                      -- 提交时所在页面
  attachments TEXT DEFAULT '[]',  -- JSON 数组：[{name,safe,size,mime,url}]
  account_id INTEGER NOT NULL,
  account_name TEXT,              -- 快照：账户改名后仍能看到当时是谁提的
  status TEXT DEFAULT 'open',     -- open 待处理 / replied 已回复 / closed 已关闭
  admin_note TEXT,
  created_at TEXT DEFAULT (datetime('now','localtime')),
  updated_at TEXT DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_fb_book ON feedbacks(book_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_fb_acc ON feedbacks(account_id, id DESC);
CREATE INDEX IF NOT EXISTS idx_fb_status ON feedbacks(status, id DESC);

-- 管理员回复（一条反馈可多条回复，形成对话）
CREATE TABLE IF NOT EXISTS feedback_replies (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  feedback_id INTEGER NOT NULL,
  account_id INTEGER,
  account_name TEXT,
  content TEXT NOT NULL,
  created_at TEXT DEFAULT (datetime('now','localtime')),
  FOREIGN KEY(feedback_id) REFERENCES feedbacks(id)
);
CREATE INDEX IF NOT EXISTS idx_fbr_fb ON feedback_replies(feedback_id, id);

-- 反馈附件元数据（文件本体在 uploads/feedback/ 目录）
CREATE TABLE IF NOT EXISTS feedback_files (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  feedback_id INTEGER,
  account_id INTEGER,
  orig_name TEXT NOT NULL,        -- 用户上传时的原始文件名（仅展示，已转义）
  safe_name TEXT NOT NULL,        -- 磁盘上的安全文件名（随机 hex + 扩展名）
  size INTEGER DEFAULT 0,
  mime TEXT,
  created_at TEXT DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS idx_fbf_fb ON feedback_files(feedback_id);
`);

// ---------- 迁移：为业务表补 book_id 列 ----------
function ensureColumn(table, col, ddl) {
  // 🔴 防呆：ddl 必须以「列名 」开头。否则参数顺序写错会静默加出一列名叫 ddl 第一段的垃圾列
  //   （曾把 ensureColumn('orders','settled','REAL DEFAULT 0') 写成 → 多了个叫 REAL 的列）。
  if (!String(ddl).trim().startsWith(col + ' ')) {
    console.error('ensureColumn 参数顺序错误：', table, col, ddl); return;
  }
  try {
    const cols = db.prepare(`PRAGMA table_info(${table})`).all().map(c => c.name);
    if (!cols.includes(col)) db.exec(`ALTER TABLE ${table} ADD COLUMN ${ddl}`);
  } catch (e) { console.error('migrate ' + table + '.' + col + ':', e.message); }
}
try {
  const oc = db.prepare('PRAGMA table_info(orders)').all().map(c => c.name);
  if (!oc.includes('payable')) db.exec('ALTER TABLE orders ADD COLUMN payable REAL');
  if (!oc.includes('discount')) db.exec('ALTER TABLE orders ADD COLUMN discount REAL DEFAULT 1');
  if (!oc.includes('order_date')) db.exec('ALTER TABLE orders ADD COLUMN order_date TEXT');
  if (!oc.includes('return_of')) db.exec('ALTER TABLE orders ADD COLUMN return_of INTEGER');
  ensureColumn('products', 'cost', 'cost REAL DEFAULT 0');
  // pack_size = 每箱装量（件数 = EA ÷ pack_size）。0 / NULL = 未维护，
  // 此时件数列留空而不是算成 0，避免「0 件」被误当成真实数字。
  ensureColumn('products', 'pack_size', 'pack_size INTEGER DEFAULT 0');
  ensureColumn('order_items', 'cost', 'cost REAL DEFAULT 0');
  // ---- 单品折扣（2026-10-14 加）：有的单品有折扣，有的没有 ----
  // discount   = 该明细行的单品折扣率（1 = 不打折，0.95 = 打 95%）
  // orig_price = 折前单价（系统带出的历史价/标准价），price = 折后成交单价
  ensureColumn('order_items', 'discount', 'discount REAL DEFAULT 1');
  ensureColumn('order_items', 'orig_price', 'orig_price REAL DEFAULT 0');
  // 商品条码快照：小票/订单页打印必须用这一列（spec 是规格文字，不是条码）
  ensureColumn('order_items', 'barcode', 'barcode TEXT');
  // 门店 × 商品的单品折扣记忆
  ensureColumn('customer_prices', 'discount', 'discount REAL DEFAULT 1');
  // ---- 应收应付（2026-10-17 加）：已结算金额，欠款 = payable − settled ----
  // 🔴 settled 是「这张单已经被收款核销掉多少钱」，不是订单金额。
  //    订单 payable 永远保持原样，抹零走 payments.discount，绝不回写订单。
  ensureColumn('orders', 'settled', 'settled REAL DEFAULT 0');
  // 平台治理：is_admin 标记唯一管理员；whitelisted 白名单账号豁免检测与封禁
  ensureColumn('accounts', 'is_admin', 'is_admin INTEGER DEFAULT 0');
  ensureColumn('accounts', 'whitelisted', 'whitelisted INTEGER DEFAULT 0');
  ensureColumn('accounts', 'banned_reason', 'banned_reason TEXT');
  ensureColumn('accounts', 'banned_at', 'banned_at TEXT');
  // 2026-10-18 客户下单角色：账号绑定门店，决定他能看/下哪一家的单
  ensureColumn('accounts', 'customer_id', 'customer_id INTEGER');
  ['customers', 'products', 'inventory', 'orders', 'order_items', 'price_systems', 'deliveries', 'delivery_items', 'customer_prices', 'customer_discounts'].forEach(t => ensureColumn(t, 'book_id', 'book_id INTEGER DEFAULT 1'));} catch (e) { console.error('migrate:', e.message); }

// settings 表结构迁移：旧表为主键 key，需改为 (book_id,key)
try {
  const sc = db.prepare('PRAGMA table_info(settings)').all().map(c => c.name);
  if (!sc.includes('book_id')) {
    const old = db.prepare('SELECT key, value FROM settings').all();
    db.exec('ALTER TABLE settings RENAME TO settings_old');
    db.exec('CREATE TABLE settings (book_id INTEGER DEFAULT 1, key TEXT, value TEXT, PRIMARY KEY(book_id,key))');
    for (const r of old) db.prepare('INSERT OR REPLACE INTO settings(book_id,key,value) VALUES(1,?,?)').run(r.key, r.value);
    db.exec('DROP TABLE settings_old');
  }
} catch (e) { console.error('migrate settings:', e.message); }

// ---------- 种子：示例套账套账 + 老板账户 + 角色 ----------
function seed() {
  // 1) 示例套账套账（把现有全部业务数据归属 book_id=1）
  let book = db.prepare('SELECT * FROM books WHERE id=1').get();
  if (!book) {
    db.prepare('INSERT INTO books(id,name,owner_account_id) VALUES(1,?,NULL)').run('示例套账');
    book = db.prepare('SELECT * FROM books WHERE id=1').get();
    db.prepare('UPDATE customers SET book_id=1 WHERE book_id IS NULL').run();
    db.prepare('UPDATE products SET book_id=1 WHERE book_id IS NULL').run();
    db.prepare('UPDATE inventory SET book_id=1 WHERE book_id IS NULL').run();
    db.prepare('UPDATE orders SET book_id=1 WHERE book_id IS NULL').run();
    db.prepare('UPDATE order_items SET book_id=1 WHERE book_id IS NULL').run();
    db.prepare('UPDATE price_systems SET book_id=1 WHERE book_id IS NULL').run();
    db.prepare('UPDATE deliveries SET book_id=1 WHERE book_id IS NULL').run();
    db.prepare('UPDATE delivery_items SET book_id=1 WHERE book_id IS NULL').run();
  }

  // 2) 首次初始化的管理员账户
  //    ⚠️ 开源版为了开箱即用提供默认账号 admin / admin123，
  //       但**它写在公开源码里，任何人都知道**。部署到公网前必须：
  //       ① 用 ERP_BOSS_USERNAME / ERP_BOSS_PASSWORD 指定你自己的账号，或
  //       ② 启动后立刻在「设置」里改掉默认密码。
  //       启动时若检测到仍是默认密码，会打印醒目告警（见下）。
  const DEFAULT_BOSS_USER = 'admin';
  const DEFAULT_BOSS_PASS = 'admin123';
  const bossUser = (process.env.ERP_BOSS_USERNAME || '').trim() || DEFAULT_BOSS_USER;
  const bossPass = (process.env.ERP_BOSS_PASSWORD || '').trim() || DEFAULT_BOSS_PASS;
  const usingDefault = !process.env.ERP_BOSS_USERNAME || !process.env.ERP_BOSS_PASSWORD;
  const boss = db.prepare('SELECT * FROM accounts WHERE username=?').get(bossUser);
  if (!boss) {
    const info = db.prepare('INSERT INTO accounts(username,password,display_name,role,book_id,active) VALUES(?,?,?,?,?,1)')
      .run(bossUser, hashPassword(bossPass), '管理员', 'boss', 1);
    const aid = info.lastInsertRowid;
    db.prepare('UPDATE books SET owner_account_id=? WHERE id=1').run(aid);
    db.prepare('INSERT OR IGNORE INTO account_books(account_id,book_id) VALUES(?,1)').run(aid);
    console.log('已创建管理员账户: ' + bossUser + ' / ' + bossPass);
    if (usingDefault) {
      console.log('\n' + '='.repeat(64));
      console.log('⚠️  安全告警：当前使用源码内置的默认账号密码（admin / admin123）。');
      console.log('   这是公开在 GitHub 上的默认凭据，任何人都能用它登录你的系统。');
      console.log('   请立即：设置环境变量 ERP_BOSS_USERNAME / ERP_BOSS_PASSWORD 后重启，');
      console.log('   或登录后到「设置」页修改密码。');
      console.log('='.repeat(64) + '\n');
    }
  }

  // 2.1) 平台管理员：默认即老板账号。
  // 与「套账老板」解耦 —— is_admin=1 才能看全平台用户 / 违规记录 / 执行封禁。
  // 用环境变量 ERP_ADMIN_ACCOUNTS 覆盖（逗号分隔），默认只有这一个。
  const adminList = (process.env.ERP_ADMIN_ACCOUNTS || bossUser).split(',').map(s => s.trim()).filter(Boolean);
  for (const u of adminList) {
    const r = db.prepare('UPDATE accounts SET is_admin=1 WHERE username=?').run(u);
    if (r.changes === 0) console.log('[moderation] 管理员账号不存在，跳过标记: ' + u);
    else if (process.env.ERP_ADMIN_ACCOUNTS) console.log('[moderation] 已标记平台管理员: ' + u);
  }

  // 2.2) 白名单账号（ERP_WHITELIST_ACCOUNTS，逗号分隔）永久豁免内容检测与自动封禁。
  // 用途：管理员自己、以及确实需要写「微信/原单」这类词做内部备注的账号。
  const wlList = (process.env.ERP_WHITELIST_ACCOUNTS || '').split(',').map(s => s.trim()).filter(Boolean);
  for (const u of wlList) {
    const r = db.prepare('UPDATE accounts SET whitelisted=1 WHERE username=?').run(u);
    if (r.changes === 0) console.log('[moderation] 白名单账号不存在，跳过: ' + u);
    else console.log('[moderation] 已加入白名单: ' + u);
  }

  // 3) 默认价格体系（book 1）
  const ps = db.prepare('SELECT COUNT(*) as n FROM price_systems WHERE book_id=1').get();
  if (ps.n === 0) {
    db.prepare('INSERT INTO price_systems(book_id,name) VALUES(1,?)').run('默认价');
    const sid = db.prepare('SELECT id FROM price_systems WHERE book_id=1').get().id;
    db.prepare('INSERT OR IGNORE INTO prices(product_id,system_id,price) SELECT id,?,price FROM products WHERE book_id=1').run(sid);
  }

  // 4) 基础客户（book 1）
  const c = db.prepare('SELECT COUNT(*) as n FROM customers WHERE book_id=1').get();
  if (c.n === 0) {
    db.prepare('INSERT INTO customers(book_id,name,type,contact,phone,address) VALUES(1,?,?,?,?,?)').run('示例连锁超市·广州店', 'store', '经理', '13800000000', '广州');
    db.prepare('INSERT INTO customers(book_id,name,type,contact,phone,address) VALUES(1,?,?,?,?,?)').run('示例食品批发·肇庆店', 'store', '店长', '13900000000', '肇庆');
    db.prepare('INSERT INTO customers(book_id,name,type,contact,phone,address) VALUES(1,?,?,?,?,?)').run('示例商贸·清远店', 'store', '老板', '13700000000', '清远');
  }
}
seed();

// 默认密码持续告警：只要库里 admin 账号仍是默认密码，每次启动都提醒一次。
// 只比对哈希、不打印明文，避免日志里再泄一次。
try {
  const a = db.prepare("SELECT username, password FROM accounts WHERE username='admin'").get();
  if (a && verifyPassword && verifyPassword('admin123', a.password)) {
    console.log('⚠️  安全告警：账号 admin 仍在使用默认密码 admin123，请到「设置」页修改。');
  }
} catch (e) { /* verifyPassword 未导出时静默跳过 */ }

// ---------- 性能索引：绝大多数查询以 book_id 为入口并 ORDER BY id DESC ----------
// 建索引后读查询走索引而非全表扫描；随业务量增长，开单/盘点/统计不会变慢。
// 全部 IF NOT EXISTS，重复执行无副作用（仅在首次启动建一次）。
try {
  db.exec(`
    CREATE INDEX IF NOT EXISTS idx_orders_book_id_id        ON orders(book_id, id DESC);
    CREATE INDEX IF NOT EXISTS idx_orders_return_of         ON orders(return_of);
    CREATE INDEX IF NOT EXISTS idx_products_book_id_id      ON products(book_id, id DESC);
    CREATE INDEX IF NOT EXISTS idx_customers_book_id_id     ON customers(book_id, id DESC);
    CREATE INDEX IF NOT EXISTS idx_deliveries_book_id_id    ON deliveries(book_id, id DESC);
    CREATE INDEX IF NOT EXISTS idx_order_items_book_id      ON order_items(book_id);
    CREATE INDEX IF NOT EXISTS idx_order_items_order_id     ON order_items(order_id);
    CREATE INDEX IF NOT EXISTS idx_order_items_product_id   ON order_items(product_id);
    CREATE INDEX IF NOT EXISTS idx_inventory_book_id        ON inventory(book_id);
    CREATE INDEX IF NOT EXISTS idx_customer_prices_bc       ON customer_prices(book_id, customer_id);
    CREATE INDEX IF NOT EXISTS idx_customer_discounts_bc    ON customer_discounts(book_id, customer_id);
    CREATE INDEX IF NOT EXISTS idx_delivery_items_book_id   ON delivery_items(book_id);
    CREATE INDEX IF NOT EXISTS idx_delivery_items_delivery   ON delivery_items(delivery_id);
    CREATE INDEX IF NOT EXISTS idx_prices_product           ON prices(product_id);
    CREATE INDEX IF NOT EXISTS idx_price_systems_book_id    ON price_systems(book_id);
    CREATE INDEX IF NOT EXISTS idx_payments_book_id_id      ON payments(book_id, id DESC);
    CREATE INDEX IF NOT EXISTS idx_payments_book_cust       ON payments(book_id, customer_id);
    CREATE INDEX IF NOT EXISTS idx_payment_items_payment    ON payment_items(payment_id);
    CREATE INDEX IF NOT EXISTS idx_payment_items_order      ON payment_items(order_id);
    CREATE INDEX IF NOT EXISTS idx_customer_terms_book      ON customer_terms(book_id, customer_id);
  `);
} catch (e) { console.error('create indexes:', e.message); }

module.exports = { db, hashPassword, verifyPassword };
