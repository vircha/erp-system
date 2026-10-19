// 服务端合规治理接线：违规检测 → 30 分钟整改窗口 → 超期自动封禁 → 管理员接口
// 由 server.js require 注入。保持独立文件，避免 server.js（已 1400+ 行）继续膨胀。
const moderation = require('./moderation');

const GRACE_MINUTES = Number(process.env.ERP_MODERATION_GRACE || 30); // 整改宽限
const SCAN_INTERVAL_MS = 60 * 1000;       // 巡检间隔 1 分钟
const LV_ORDER = { high: 0, mid: 1, risky: 2 };

function init(ctx) {
  const { db, SESSIONS, pubAcc } = ctx;

  // ---------- 工具 ----------
  const ymdhm = (d) => `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}${String(d.getHours()).padStart(2, '0')}${String(d.getMinutes()).padStart(2, '0')}`;
  const sqlTime = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`;

  function killSessions(accountId) {
    for (const [tk, s] of SESSIONS) if (s.account_id === accountId) SESSIONS.delete(tk);
  }

  function logMod(accountId, action, reason, detail, operatorId) {
    try {
      db.prepare('INSERT INTO moderation_log(account_id,action,reason,detail,operator_id) VALUES(?,?,?,?,?)')
        .run(accountId, action, reason || '', detail || '', operatorId == null ? null : operatorId);
    } catch (e) { console.error('moderation_log:', e.message); }
  }

  function banAccount(accountId, reason, detail, operatorId) {
    const acc = db.prepare('SELECT * FROM accounts WHERE id=?').get(accountId);
    if (!acc) return false;
    if (acc.is_admin) {           // 管理员账号永不被自动/他人封禁
      console.error('[moderation] 拒绝封禁管理员账号: ' + acc.username);
      return false;
    }
    if (acc.whitelisted) {        // 白名单账号豁免封禁
      console.error('[moderation] 拒绝封禁白名单账号: ' + acc.username);
      return false;
    }
    db.prepare("UPDATE accounts SET active=0, banned_reason=?, banned_at=? WHERE id=?")
      .run(reason || '违反使用规范', sqlTime(new Date()), accountId);
    killSessions(accountId);      // 立刻踢下线，否则封禁后仍能用旧 token 继续操作
    logMod(accountId, operatorId == null ? 'auto_ban' : 'ban', reason, detail, operatorId);
    console.log(`[moderation] 已封禁 ${acc.username}（${reason}）`);
    return true;
  }

  function unbanAccount(accountId, operatorId) {
    const acc = db.prepare('SELECT * FROM accounts WHERE id=?').get(accountId);
    if (!acc) return false;
    db.prepare("UPDATE accounts SET active=1, banned_reason=NULL, banned_at=NULL WHERE id=?").run(accountId);
    logMod(accountId, 'unban', '管理员解封', '', operatorId);
    return true;
  }

  // 白名单切换：豁免内容检测 + 豁免自动封禁
  function setWhitelist(accountId, on, operatorId) {
    const acc = db.prepare('SELECT * FROM accounts WHERE id=?').get(accountId);
    if (!acc) return false;
    if (acc.is_admin) return false;      // 管理员天然豁免，不走白名单
    db.prepare('UPDATE accounts SET whitelisted=? WHERE id=?').run(on ? 1 : 0, accountId);
    logMod(accountId, 'whitelist', on ? '加入白名单' : '移出白名单', '', operatorId);
    return true;
  }

  // ---------- 写入时检测 ----------
  // 返回 null=干净；否则返回 {level, words, message, violation_id}
  // 注意：这里【不拦截写入】，只登记并回提示。理由是业务上误伤代价高
  // （改个名就整单录不进去），而 30 分钟窗口 + 超期封禁已经形成实质约束。
  function scanAndRecord(bookId, accountId, targetType, targetId, targetName, field, text) {
    const hits = moderation.detect(text);
    if (!hits.length) {
      // 内容已改干净 → 把该对象仍未处理的 pending 记录置为 resolved
      try {
        db.prepare("UPDATE violations SET status='resolved', resolved_at=? WHERE target_type=? AND target_id=? AND status='pending'")
          .run(sqlTime(new Date()), targetType, targetId);
      } catch (e) { /* ignore */ }
      return null;
    }
    // 白名单 / 管理员：命中也只提示，不登记、不计整改期、更不会被封禁
    const owner = db.prepare('SELECT is_admin, whitelisted FROM accounts WHERE id=?').get(accountId);
    const exempt = owner && (owner.is_admin || owner.whitelisted);
    const level = moderation.levelOf(hits);
    const words = hits.map(h => h.word).join(',');
    const deadline = sqlTime(new Date(Date.now() + GRACE_MINUTES * 60000));
    if (exempt) {
      return { level, words: hits.map(h => h.word), message: moderation.warnMessage(hits), violation_id: null, grace_minutes: GRACE_MINUTES, exempt: true };
    }
    let vid = null;
    try {
      // 同一对象已有 pending 记录 → 复用并刷新（避免反复改名刷出多条）
      const ex = db.prepare("SELECT id FROM violations WHERE target_type=? AND target_id=? AND status='pending'").get(targetType, targetId);
      if (ex) {
        db.prepare('UPDATE violations SET words=?, level=?, target_name=?, deadline=? WHERE id=?').run(words, level, targetName, deadline, ex.id);
        vid = ex.id;
      } else {
        const r = db.prepare('INSERT INTO violations(book_id,target_type,target_id,target_name,field,words,level,account_id,status,deadline) VALUES(?,?,?,?,?,?,?,?,?,?)')
          .run(bookId, targetType, targetId, targetName, field || 'name', words, level, accountId, 'pending', deadline);
        vid = r.lastInsertRowid;
      }
    } catch (e) { console.error('violation insert:', e.message); }
    return { level, words: hits.map(h => h.word), message: moderation.warnMessage(hits), violation_id: vid, grace_minutes: GRACE_MINUTES };
  }

  // ---------- 巡检：超期未整改 → 封禁 ----------
  function sweep() {
    try {
      const rows = db.prepare("SELECT * FROM violations WHERE status='pending' AND datetime(deadline) <= datetime('now','localtime')").all();
      if (!rows.length) return 0;
      let n = 0;
      for (const v of rows) {
        // 复核：内容可能已被后台改掉（前端未回调等），先重新扫一次目标当前值
        let stillBad = true;
        try {
          const tbl = v.target_type === 'product' ? 'products' : v.target_type === 'customer' ? 'customers' : null;
          if (tbl) {
            const cur = db.prepare(`SELECT name FROM ${tbl} WHERE id=?`).get(v.target_id);
            if (!cur) { // 对象已被删除 = 已自行整改
              db.prepare("UPDATE violations SET status='resolved', resolved_at=? WHERE id=?").run(sqlTime(new Date()), v.id);
              continue;
            }
            stillBad = moderation.detect(cur.name).length > 0;
          }
        } catch (e) { /* 查不到就按仍违规处理，宁可严 */ }
        if (!stillBad) {
          db.prepare("UPDATE violations SET status='resolved', resolved_at=? WHERE id=?").run(sqlTime(new Date()), v.id);
          continue;
        }
        const detail = `${v.target_type}#${v.target_id}「${v.target_name || ''}」命中：${v.words}`;
        const okBan = banAccount(v.account_id, `违规内容超期未整改（${v.words}）`, detail, null);
        db.prepare("UPDATE violations SET status=?, banned_at=? WHERE id=?")
          .run(okBan ? 'banned' : 'pending', okBan ? sqlTime(new Date()) : null, v.id);
        if (okBan) n++;
      }
      if (n) console.log(`[moderation] 本轮巡检封禁 ${n} 个账号`);
      return n;
    } catch (e) { console.error('moderation sweep:', e.message); return 0; }
  }

  // ---------- 提醒查询（登录后前端拉取）----------
  function myPending(accountId) {
    try {
      const rows = db.prepare("SELECT * FROM violations WHERE account_id=? AND status='pending' ORDER BY deadline ASC").all(accountId);
      return rows.map(v => ({
        id: v.id, type: v.target_type, target_id: v.target_id, name: v.target_name,
        words: String(v.words || '').split(','), level: v.level, deadline: v.deadline,
        minutes_left: Math.max(0, Math.round((new Date(String(v.deadline).replace(/-/g, '/')) - new Date()) / 60000)),
      }));
    } catch (e) { return []; }
  }

  // ---------- 管理端数据 ----------
  function adminOverview() {
    const accounts = db.prepare(`
      SELECT a.id,a.username,a.display_name,a.role,a.active,a.is_admin,a.whitelisted,
             a.banned_reason,a.banned_at,a.created_at,
             (SELECT COUNT(*) FROM accounts x WHERE x.book_id=a.book_id) AS book_total
      FROM accounts a ORDER BY a.id DESC`).all();
    const stats = db.prepare(`
      SELECT
        (SELECT COUNT(*) FROM accounts) AS total,
        (SELECT COUNT(*) FROM accounts WHERE active=0) AS banned,
        (SELECT COUNT(*) FROM accounts WHERE is_admin=1) AS admins,
        (SELECT COUNT(*) FROM accounts WHERE whitelisted=1) AS whitelisted,
        (SELECT COUNT(*) FROM violations WHERE status='pending') AS pending,
        (SELECT COUNT(*) FROM violations WHERE status='banned') AS auto_banned`).get();
    const violations = db.prepare(`
      SELECT v.*, a.username, a.display_name
      FROM violations v LEFT JOIN accounts a ON a.id=v.account_id
      ORDER BY (v.status='pending') DESC, v.id DESC LIMIT 200`).all();
    const logs = db.prepare(`
      SELECT m.*, a.username FROM moderation_log m
      LEFT JOIN accounts a ON a.id=m.account_id ORDER BY m.id DESC LIMIT 100`).all();
    return { stats, accounts, violations, logs };
  }

  return { scanAndRecord, sweep, myPending, adminOverview, banAccount, unbanAccount, setWhitelist, logMod, GRACE_MINUTES, ymdhm };
}

module.exports = { init };