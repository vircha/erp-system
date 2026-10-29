// 简单版 ERP 前端：vanilla JS，响应式，单页多视图
(function () {
  'use strict';
  const $ = s => document.querySelector(s);
  const $$ = s => document.querySelectorAll(s);
  // BASE：后端接口前缀。
  // 网页端与页面同域，用相对路径即可；原生打包 App 的页面源是 capacitor://localhost，
  // 必须指向绝对服务器地址才能访问接口（否则 /api/... 会打到 capacitor:// 而失败）。
  const BASE = (function () {
    const m = document.querySelector('meta[name="base"]');
    if (m && m.content) return m.content;
    // 原生 App 走服务器正式域名 + HTTPS（不再明文走 http://IP:3000）。
    // 3000 端口已收窄到 127.0.0.1，公网不可达，只有经 nginx 的 erp.example.com 才能访问。
    try { if (window.Capacitor && window.Capacitor.isNativePlatform && window.Capacitor.isNativePlatform()) return 'https://erp.example.com'; } catch (e) {}
    return '';
  })();

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }
  function debounce(fn, ms) { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; }
  function fmt(n) { return '¥' + Number(n || 0).toFixed(2); }

  // ---------- 模糊搜索 ----------
  // 归一化：去除空白与常见分隔符（空格 - _ / . , 、· : ; # ( ) 等），全角转半角并转小写。
  // 这样「万家 4店」「万-家4店」「ＸＳ2000」都能与原文命中。
  function norm(s) {
    return String(s == null ? '' : s)
      .replace(/[\uFF01-\uFF5E]/g, c => String.fromCharCode(c.charCodeAt(0) - 0xFEE0))
      .replace(/\u3000/g, ' ')
      .toLowerCase()
      .replace(/[\s\-_/\\.,，、。·:：;；#()（）\[\]【】"'’“”`]+/g, '');
  }
  // 子序列匹配：tok 的字符按序出现在 field 中（允许不连续）—— 打错顺序也能搜到
  function isSubseq(tok, field) {
    if (!tok) return true;
    let i = 0;
    for (let j = 0; j < field.length && i < tok.length; j++) if (field[j] === tok[i]) i++;
    return i === tok.length;
  }
  function hasCJK(s) { return /[\u3400-\u9fff]/.test(s); }
  // 单个关键词对单个字段的得分：前缀 1000 > 子串 600 > 子序列 120 > 不匹配 0
  function tokenScore(field, tok) {
    if (!tok) return 1;
    if (!field) return 0;
    const i = field.indexOf(tok);
    if (i === 0) return 1000 - Math.min(field.length, 200);
    if (i > 0) return 600 - Math.min(i, 100);
    // 子序列兜底只对含中文的关键词开放：中文常被跳跃输入（如「新希鲜奶」）；
    // 而条码/规格等字母数字要求精确子串，避免「250ml」误命中「950ml」。
    if (!hasCJK(tok)) return 0;
    return isSubseq(tok, field) ? 120 - Math.min(field.length, 100) : 0;
  }
  // 查询打分：按空白拆关键词，全部命中才返回分数（AND），否则 -1
  function fuzzyScore(query, fields) {
    const q = String(query || '').trim();
    if (!q) return 0;
    const toks = q.split(/\s+/).map(norm).filter(Boolean);
    if (!toks.length) return 0;
    const nf = (fields || []).map(norm).filter(Boolean);
    if (!nf.length) return 0;
    let total = 0;
    for (const t of toks) {
      let best = 0;
      for (const f of nf) { const s = tokenScore(f, t); if (s > best) best = s; }
      if (best === 0) return -1;
      total += best;
    }
    return total + (toks.length > 1 ? 50 : 0);
  }
  // 过滤 + 按相关度排序（稳定）；fieldsOf(item) 返回该条目参与匹配的字段数组
  function fuzzyFilter(list, query, fieldsOf) {
    const q = String(query || '').trim();
    if (!q) return list;
    return list
      .map((it, i) => ({ it, i, sc: fuzzyScore(q, fieldsOf(it)) }))
      .filter(x => x.sc >= 0)
      .sort((a, b) => b.sc - a.sc || a.i - b.i)
      .map(x => x.it);
  }
  // 命中高亮：返回 HTML（已转义），命中的片段包 <mark>
  function highlight(text, query) {
    const raw = String(text == null ? '' : text);
    const q = String(query || '').trim();
    if (!q) return esc(raw);
    const toks = q.split(/\s+/).map(norm).filter(Boolean);
    if (!toks.length) return esc(raw);
    let nz = ''; const idx = [];
    for (let i = 0; i < raw.length; i++) { const c = norm(raw[i]); if (c) { nz += c; idx.push(i); } }
    const marks = new Array(raw.length).fill(false);
    for (const t of toks) {
      let from = 0, at;
      while ((at = nz.indexOf(t, from)) >= 0) {
        for (let k = idx[at]; k <= idx[at + t.length - 1]; k++) marks[k] = true;
        from = at + 1;
      }
    }
    let out = '', open = false;
    for (let i = 0; i < raw.length; i++) {
      if (marks[i] && !open) { out += '<mark>'; open = true; }
      if (!marks[i] && open) { out += '</mark>'; open = false; }
      out += esc(raw[i]);
    }
    return open ? out + '</mark>' : out;
  }

  let TOKEN = null, SHOP = '';
  let ME = { role: 'staff', roleLabel: '员工', perms: [], book: null, account: null, books: [], customerId: null, customerIds: [] };
  let TPL = { receipt_title: '销售小票', receipt_footer: '谢谢惠顾，欢迎再次光临', list_title: '商品清单' };
  async function loadTpl() {
    // 客户账号读 /api/settings 会被服务端拒绝（里面是小票抬头/仓库等内部设置），
    // 这里静默用默认值，避免登录时弹一堆 403。
    if (ME.role === 'customer') return;
    try { const s = await API.req('GET', '/api/settings'); TPL = Object.assign(TPL, s); } catch (e) {}
  }

  // ---------- 打印模板库（模块化引擎，2026-10-15）----------
  // 存在本地：模板是「设备偏好」而不是业务数据，同一套模板要能同时用于
  // 网页版 DOM 打印 / 网页 ESC-POS / Canvas 预览存图，三者共用一份配置。
  // 若要多端共享，把这里换成 /api/settings 里的 print_templates 即可。
  const TPL_STORE_KEY = 'erp_print_templates_v1';
  let TPL_LIST = [], TPL_DEF = 'std80';
  function loadTplStore() {
    try {
      const raw = JSON.parse(localStorage.getItem(TPL_STORE_KEY) || 'null');
      if (raw && Array.isArray(raw.list) && raw.list.length) {
        TPL_LIST = raw.list.map(t => window.ERPTpl.normalize(t));
        TPL_DEF = raw.def && TPL_LIST.some(t => t.id === raw.def) ? raw.def : TPL_LIST[0].id;
        return;
      }
    } catch (e) {}
    TPL_LIST = (window.ERPTpl.DEFAULT || []).map(t => window.ERPTpl.normalize(t));
    TPL_DEF = 'std80';
    saveTplStore();
  }
  function saveTplStore() {
    try { localStorage.setItem(TPL_STORE_KEY, JSON.stringify({ list: TPL_LIST, def: TPL_DEF })); } catch (e) {}
  }
  function getTplById(id) { return TPL_LIST.find(t => t.id === id) || window.ERPTpl.normalize(window.ERPTpl.DEFAULT[0]); }
  /** 打开模板管理器（设置页入口） */
  function manageTemplates() {
    window.ERPTpl.openManager({ list: TPL_LIST, def: TPL_DEF }, (list, def) => {
      TPL_LIST = list.map(t => window.ERPTpl.normalize(t));
      TPL_DEF = def;
      saveTplStore();
      toast('打印模板已保存');
    });
  }
  /** 打单前选模板（Task #78：先选模板再打） */
  function pickTemplate() {
    return new Promise(resolve => {
      window.ERPTpl.pick(TPL_LIST, (t, remember) => {
        if (remember) { TPL_DEF = t.id; saveTplStore(); toast('已设为默认打印模板'); }
        resolve(t);
      });
    });
  }
  // 权限判断：perms 含 '*' 表示全部
  function can(perm) { return ME.perms && (ME.perms.includes('*') || ME.perms.includes(perm)); }
  let CURRENT = 'sale';
  let saleState = { customer_id: null, customer_name: '', items: [] };
  // 🔴 单据类型：'sale'=销售单 / 'return'=销售退货单。
  //    跟手机版 SaleScreen 的「销售单 / 退货单」模式切换完全一致（管家婆式手工录品）：
  //    退货单不需要指定原销售单，手工录要退的商品+数量，服务端存负数并回补库存。
  //    切换模式会清空购物车，避免把销售单的商品误当退货品提交。
  let saleDocType = 'sale';
  let allProducts = [], allCustomers = [];
  const isReturnMode = () => saleDocType === 'return';

  // 🔴 登录本身属于「凭据验证」接口：它们的 401 是密码错，不应触发退出登录。
  const AUTH_PATHS = new Set(['/api/login', '/api/register', '/api/trusted-login', '/api/login/precheck']);

  const API = {
    async req(method, path, body) {
      const opt = { method, headers: {} };
      if (TOKEN) opt.headers['Authorization'] = 'Bearer ' + TOKEN;
      if (body) { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
      const url = path.startsWith('/api/') ? BASE + path : path;
      let r;
      try { r = await fetch(url, opt); }
      catch (e) { throw new Error('无法连接服务器（' + (BASE || '当前站点') + '），请检查网络/服务是否可用'); }
      let data = {};
      try { data = await r.json(); } catch (e) {}
      // 🔴 2026-10-17 修：登录类接口的 401 = 「密码错」，不是「登录态失效」。
      //   之前一律 doLogout() → 它拿旧设备令牌异步重登 → 失败后 showLogin() 把密码框清空，
      //   同时把「账号或密码错误」提示一起清掉 → 用户看着就像「无法登录」。
      //   仅在 token 本身失效时才回到登录页。
      if (r.status === 401) {
        if (AUTH_PATHS.has(path)) {
          const e0 = new Error(data.error || '密码错误');
          e0.status = 401; e0.data = data;
          throw e0;
        }
        doLogout(); throw new Error(data.error || '未登录');
      }
      // 403 可能是「被封禁」（登录接口）或「无权限」；封禁要明确告知并停止操作
      if (r.status === 403 && data.banned_at !== undefined) {
        try { doLogout(); } catch (e) {}
        throw new Error(data.error || '账号已被封禁');
      }
      if (!r.ok) {
        const err = new Error(data.error || '请求失败');
        err.status = r.status; err.data = data;
        throw err;
      }
      return data;
    }
  };

  // ---------- 验证码 ----------
  let CAPTCHA_ID = '';
  async function refreshCaptcha() {
    try {
      const d = await API.req('GET', '/api/captcha');
      CAPTCHA_ID = d.cid;
      const img = $('#captcha-img');
      if (img) img.src = 'data:image/svg+xml;base64,' + btoa(unescape(encodeURIComponent(d.svg)));
      const inp = $('#login-captcha'); if (inp) inp.value = '';
    } catch (e) {}
  }
  function showCaptcha(on) { $('#captcha-row').classList.toggle('hidden', !on); if (on) refreshCaptcha(); }

  // ---------- 登录 / 注册 / 登出 ----------
  let REG_MODE = false;
  async function loadPublicBooks() {
    try {
      const books = await API.req('GET', '/api/books/public');
      const sel = $('#login-book'); if (!sel) return;
      sel.innerHTML = '<option value="">＋ 新建套账（成为老板）</option>' +
        books.map(b => `<option value="${b.id}">加入：${esc(b.name)}</option>`).join('');
      sel.onchange = () => {
        const joining = !!sel.value;
        $('#login-bookname').classList.toggle('hidden', joining);
        $('#login-joinpwd').classList.toggle('hidden', !joining);
      };
    } catch (e) {}
  }
  function setRegMode(on) {
    REG_MODE = on;
    $('#tab-login').className = 'btn-sm' + (on ? ' gray' : '');
    $('#tab-reg').className = 'btn-sm' + (on ? '' : ' gray');
    $('#reg-fields').classList.toggle('hidden', !on);
    $('#login-btn').textContent = on ? '注册并进入' : '登录';
    $('#login-err').textContent = '';
    showCaptcha(on); // 注册必填验证码
    if (on) { loadPublicBooks(); $('#login-bookname').classList.remove('hidden'); $('#login-joinpwd').classList.add('hidden'); }
  }
  async function doAuth() {
    const username = $('#login-user').value.trim();
    const password = $('#login-pwd').value;
    const captcha = $('#login-captcha') ? $('#login-captcha').value.trim() : '';
    $('#login-err').textContent = '';
    if (!username || !password) { $('#login-err').textContent = '请填写账号和密码'; return; }
    if (REG_MODE && !captcha) { $('#login-err').textContent = '请填写验证码'; return; }
    try {
      let d;
      const trust_device = $('#login-trust') ? $('#login-trust').checked : false;
      const device = deviceLabel();
      if (REG_MODE) {
        const sel = $('#login-book');
        d = await API.req('POST', '/api/register', {
          username, password, captcha_id: CAPTCHA_ID, captcha, trust_device, device,
          display_name: $('#login-name').value.trim(),
          book_id: sel && sel.value ? Number(sel.value) : null,
          book_name: $('#login-bookname').value.trim(),
          join_password: $('#login-joinpwd').value
        });
      } else {
        d = await API.req('POST', '/api/login', { username, password, captcha_id: CAPTCHA_ID, captcha, trust_device, device });
      }
      TOKEN = d.token;
      localStorage.setItem('erp_token', TOKEN);
      // 勾选「信任此设备」→ 保存 30 天设备令牌，下次免密
      if (d.device_token) localStorage.setItem('erp_device_token', d.device_token);
      else if (!REG_MODE) { /* 本次未勾选：不动已有令牌 */ }
      // 🔴 1.5.1：最多信任 4 台。已满时服务端不下发免密令牌（登录仍成功），
      //   必须明确告知 —— 否则经理会以为勾选框没生效、或以为登录失败。
if (d.trust_full) {
    toast('已达 4 台信任设备上限，本机未被记住。请到设置 → 信任设备 解除一台后再试', 5000);
      }
      // 🔴 v5.5：登录/注册成功后也停在首页选择页，不自动跳系统。
      //   （没勾信任的设备这次体验一样，下次才会真正免密）
      await afterAuth(true);
    } catch (e) {
      $('#login-err').textContent = e.message;
      // 登录失败达 3 次 → 显示验证码
      if (!REG_MODE) {
        try {
          const pc = await API.req('POST', '/api/login/precheck', { username });
          if (pc.need_captcha) showCaptcha(true);
          else refreshCaptchaSilent();
        } catch (err) {}
      } else { refreshCaptcha(); }
    }
  }
  async function refreshCaptchaSilent() {
    // 失败次数<3 时，若验证码区可见则顺带刷新
    if (!$('#captcha-row').classList.contains('hidden')) refreshCaptcha();
  }
  // ---------- 信任设备（30 天免密） ----------
  function deviceLabel() {
    const ua = navigator.userAgent || '';
    let os = '未知设备';
    if (/Android/i.test(ua)) os = '安卓';
    else if (/iPhone|iPad|iPod/i.test(ua)) os = '苹果';
    else if (/Windows/i.test(ua)) os = 'Windows';
    else if (/Mac OS X/i.test(ua)) os = 'Mac';
    else if (/Linux/i.test(ua)) os = 'Linux';
    let br = '';
    if (/MicroMessenger/i.test(ua)) br = '微信';
    else if (/Edg\//i.test(ua)) br = 'Edge';
    else if (/Chrome\//i.test(ua)) br = 'Chrome';
    else if (/Safari\//i.test(ua)) br = 'Safari';
    else if (/Firefox\//i.test(ua)) br = 'Firefox';
    return (os + (br ? ' · ' + br : '')).slice(0, 60);
  }
  function getDeviceToken() { return localStorage.getItem('erp_device_token') || ''; }

  // 🔴 v5.5：信任设备免密后不再自动跳进系统。
  //   拆成两步：tryTrustedLogin 只换 token + 拉 ME，**不调 afterAuth**；
  //   然后 showTrustedPanel() 停在首页，用户点「进入开单」才真正 enterApp。
  async function tryTrustedLogin() {
    const dt = getDeviceToken();
    if (!dt) return false;
    try {
      const d = await API.req('POST', '/api/trusted-login', { device_token: dt });
      TOKEN = d.token;
      localStorage.setItem('erp_token', TOKEN);
      if (d.device_token) localStorage.setItem('erp_device_token', d.device_token);
      // 只拉身份，**不进系统**（afterAuth 会调 enterApp）
      ME = await API.req('GET', '/api/me');
      return true;
    } catch (e) {
      // 过期/失效 → 清掉本地令牌，回退到账号密码登录
      localStorage.removeItem('erp_device_token');
      TOKEN = null;
      return false;
    }
  }

  // ---------- 信任设备面板（首页三按钮） ----------
  function showTrustedPanel() {
    const panel = $('#trusted-panel'), card = $('#login-card');
    if (!panel || !card) return showLogin();
    // 账号 / 账套信息
    const acc = ME.account || {};
    const nm = acc.display_name || acc.username || '本机用户';
    $('#trusted-name').textContent = nm;
    $('#trusted-initial').textContent = nm.slice(0, 1);
    const books = ME.books || [];
    const cur = ME.book || {};
    $('#trusted-sub').textContent = books.length > 1
      ? ('免密登录中· 当前「' + (cur.name || '-') + '」，共 ' + books.length + ' 个账套')
      : ('30 天内免密登录 · ' + (ME.roleLabel || ''));
    $('#trusted-books').classList.add('hidden');
    panel.classList.remove('hidden');
    card.classList.add('hidden');
    showLogin(true);
  }

  function hideTrustedPanel() {
    $('#trusted-panel') && $('#trusted-panel').classList.add('hidden');
    $('#login-card') && $('#login-card').classList.remove('hidden');
  }

  // 「进入开单」：用当前账套真正进系统
  async function trustedEnter() {
    hideTrustedPanel();
    $('#login-view').classList.remove('hidden');
    await loadTpl();
    enterApp();
    silentAutoConnect();
    if (ME.violations && ME.violations.length) showViolationBanner(ME.violations);
    startFeedbackBadge();
  }

  // 「选择其他账套」：展开账套列表
  async function trustedPickBook() {
    const box = $('#trusted-books');
    if (!box) return;
    if (!box.classList.contains('hidden')) { box.classList.add('hidden'); return; }
    const books = (ME.books || []).slice();
    if (!books.length) { box.classList.remove('hidden'); box.innerHTML = '<p class="muted" style="margin:0">该账号只有一个账套</p>'; return; }
    box.classList.remove('hidden');
    box.innerHTML = '<p class="muted lp-tc-bt">选择要进入的账套</p>' + books.map(b => {
      const cur = ME.book && ME.book.id === b.id;
      return '<button class="lp-tc-book' + (cur ? ' cur' : '') + '" data-tbid="' + b.id + '">'
        + '<b>' + esc(b.name) + '</b>' + (cur ? '<i>当前</i>' : '<i>切换并进入</i>') + '</button>';
    }).join('');
    box.querySelectorAll('[data-tbid]').forEach(btn => btn.onclick = async () => {
      const bid = +btn.dataset.tbid;
      if (ME.book && ME.book.id === bid) { box.classList.add('hidden'); return trustedEnter(); }
      btn.disabled = true; btn.textContent = '切换中…';
      try {
        const r = await API.req('POST', '/api/books/switch', { book_id: bid });
        ME.book = r.book; SHOP = r.shopName || r.book.name;
        localStorage.setItem('erp_shop', SHOP);
        // 换账套后重拉一次ME，保证权限/账套列表跟着变
        ME = Object.assign(ME, await API.req('GET', '/api/me'));
        box.classList.add('hidden');
        await trustedEnter();
        toast('已切换到 ' + r.book.name);
      } catch (e) {
        btn.disabled = false;
        btn.innerHTML = '<b>' + esc((books.find(x => x.id === bid) || {}).name || '账套') + '</b><i>重试</i>';
        toast(e.message || '切换失败');
      }
    });
  }

  // 「更换账号」：彻底解除本设备的信任，回到账号密码登录
  function trustedSwitchAcct() {
    localStorage.removeItem('erp_token');
    localStorage.removeItem('erp_device_token');
    localStorage.removeItem('erp_shop');
    localStorage.removeItem('erp_me');
    TOKEN = null;
    ME = { role: 'staff', roleLabel: '员工', perms: [], book: null, account: null, books: [], customerId: null, customerIds: [] };
    hideTrustedPanel();
    showLogin();
  }

  // 🔴 v5.5：stay=true 表示「只换身份，不进系统」（停在首页选择页）
  // 🔴 v5.5：「关闭本设备的免密信任」——设置页解绑之外的第二条路径。
//   为什么必须放首页：无痕窗口关掉后 erp_device_token 就没了，
//   下次打开进的是登录表单，**根本走不到设置页**，
//   服务端那条信任记录会一直留着白占 4 台名额，只能在免密状态下解。
  async function trustedUnbind() {
    if (!confirm('关闭后，下次打开需要重新输入账号密码登录。确认？')) return;
    try {
      // keep_token=当前令牌 → 服务端精确删掉这一台，其他设备不动
      await API.req('DELETE', '/api/trusted-devices?keep_token=' + encodeURIComponent(getDeviceToken()));
      trustedSwitchAcct();
      toast('已关闭本设备的免密信任');
    } catch (e) { toast(e.message || '关闭失败'); }
  }

  async function afterAuth(stay) {
    const m = await API.req('GET', '/api/me');
    ME = m; SHOP = m.shopName || (m.book ? m.book.name : '店铺');
    localStorage.setItem('erp_shop', SHOP);
    // feedback.js 是独立模块，读不到这里的 ME；把管理员标记落到 localStorage 供它判断
    try { localStorage.setItem('erp_me', JSON.stringify({ isAdmin: !!m.isAdmin, accountId: m.account ? m.account.id : 0, role: m.role })); } catch (e) {}
    if (stay) { showTrustedPanel(); return; }
    await loadTpl();
    enterApp();
    if (m.violations && m.violations.length) showViolationBanner(m.violations);
    startFeedbackBadge();
  }

  // ---------- 意见反馈：侧栏红点轮询（模块在 feedback.js，独立文件） ----------
  let FB_TIMER = null;
  function startFeedbackBadge() {
    if (!window.ERPFeedback) return;
    if (FB_TIMER) clearInterval(FB_TIMER);
    window.ERPFeedback.refreshBadge();
    FB_TIMER = setInterval(() => window.ERPFeedback.refreshBadge(), 60000);
    document.addEventListener('visibilitychange', () => {
      if (!document.hidden && window.ERPFeedback) window.ERPFeedback.refreshBadge();
    });
  }

  // ---------- 违规提醒横幅 ----------
  // 后端给 30 分钟整改窗口，这里显示倒计时 + 「去修改」直达按钮。
  // 每分钟刷新一次；到点后提示已上报并将被封禁（实际由后端巡检执行）。
  let VIOLATION_TIMER = null;
  function showViolationBanner(list) {
    const old = document.getElementById('violation-banner');
    if (old) old.remove();
    if (VIOLATION_TIMER) { clearInterval(VIOLATION_TIMER); VIOLATION_TIMER = null; }
    if (!list || !list.length) return;

    const el = document.createElement('div');
    el.id = 'violation-banner';
    el.className = 'violation-banner';
    document.body.appendChild(el);

    const paint = () => {
      const items = (list || []).filter(v => v && v.minutes_left > 0);
      if (!items.length) {
        el.innerHTML = '<div class="vb-inner" style="background:#7f1d1d;border-color:#991b1b">' +
          '<b>⚠ 内容违规已上报</b><span>整改时限已过，账号已被封禁。如有异议请联系平台管理员。</span></div>';
        if (VIOLATION_TIMER) { clearInterval(VIOLATION_TIMER); VIOLATION_TIMER = null; }
        return;
      }
      const mins = Math.min.apply(null, items.map(v => v.minutes_left));
      el.innerHTML = '<div class="vb-inner">' +
        '<b>⚠ 内容违规提醒</b>' +
        '<span>你的「' + esc(items[0].name || '内容') + '」包含违规词：' +
          esc((items[0].words || []).join('、')) +
        '。请在 <em>' + mins + '</em> 分钟内修改，逾期将上报并自动封禁账号。</span>' +
        '<button class="vb-fix" id="vb-fix-btn">立即修改</button>' +
        '</div>';
      const btn = document.getElementById('vb-fix-btn');
      if (btn) btn.onclick = () => {
        const t = items[0].type;
        navigate(t === 'customer' ? 'customers' : 'products');
        const ov = document.getElementById('violation-banner');
        if (ov) ov.style.display = 'none';
      };
      // 每分钟更新倒计时
      list.forEach(v => { if (v.minutes_left > 0) v.minutes_left -= 1; });
    };
    paint();
    VIOLATION_TIMER = setInterval(paint, 60000);
  }

  // 写入接口返回 violation 时即时弹提示（不必等重新登录）
  function notifyViolation(v) {
    if (!v) return;
    toast(v.message + '（' + v.grace_minutes + '分钟内修改）');
    if (v.exempt) { toast(v.message + '（白名单/管理员账号，仅提示）'); return; }
    showViolationBanner([{
      name: v.message, words: v.words || [],
      minutes_left: v.grace_minutes || 30, type: 'product'
    }]);
  }
  function doLogout() {
    // 🔴 v5.5：退出后仍保留设备令牌 → 回信任面板，而不是空登录表单。
    // 用户可在面板上点「更换账号」才真正解除信任。
    localStorage.removeItem('erp_token'); localStorage.removeItem('erp_shop');
    localStorage.removeItem('erp_me');
    TOKEN = null; ME = { role: 'staff', roleLabel: '员工', perms: [], book: null, account: null, books: [], customerId: null, customerIds: [] };
    if (FB_TIMER) { clearInterval(FB_TIMER); FB_TIMER = null; }
    if (getDeviceToken()) {
      // 免密令牌还在 → 直接重新走一次免密，停在选择页
      (async () => {
        const ok = await tryTrustedLogin();
        ok ? showTrustedPanel() : showLogin();
      })();
      return;
    }
    showLogin();
  }
  // 🔴 v5.5：keepTrusted=true 时**不碰登录表单**（免密模式停在信任面板），
  // 同时把侧栏下载卡片等无关元素藏起来，避免两个视图叠在一起
  function showLogin(keepTrusted) {
    $('#app-view').classList.add('hidden');
    $('#login-view').classList.remove('hidden');
    if (keepTrusted) return;
    hideTrustedPanel();
    // 🔴 2026-10-17 修：不在这里清错误提示。
    //   showLogin() 可能在 doAuth catch 写入提示之后被调用（如免密重登失败），
    //   清空会让「账号或密码错误」消失 → 用户只见到空白登录页，跟「无法登录」一样。
    //   清理改由 doAuth 开始时统4一做（doAuth 开头就清 #login-err）。
    $('#login-pwd').value = '';
    setRegMode(false);
  }
  function enterApp() {
    $('#login-view').classList.add('hidden');
    $('#app-view').classList.remove('hidden');
    $('#shop-name').textContent = (SHOP || '店铺') + ' · ' + (ME.roleLabel || '');
    $('#book-btn').classList.toggle('hidden', !(ME.books && ME.books.length > 1));
    applyPerms();
    navigate('sale');
  }
  // 按权限显隐底部 Tab
  function applyPerms() {
    $$('.sidebar nav button').forEach(b => {
      const pg = b.dataset.page;
      let show = true;
      if (pg === 'me' && !can('settings.edit')) show = false;         // 设置页仅老板
      if (pg === 'orders' && !can('order.view')) show = false;
      if (pg === 'customers' && !can('customer.view')) show = false;
      if (pg === 'products' && !can('product.view')) show = false;
      if (pg === 'analytics' && !can('analysis.view')) show = false;
      // 🔴 采购页：经理/老板有 purchase.view 才显示；员工只有 inventory.view → 仍需能看库存
      if (pg === 'purchase' && !can('purchase.view') && !can('inventory.view')) show = false;
      if (pg === 'sale' && !can('sale.create')) show = false;
      if (pg === 'admin' && !ME.isAdmin) show = false;                    // 管理页仅平台管理员
      if (pg === 'myar') show = (ME.role === 'customer');                 // 我的欠款：仅客户账号
      // 🔴 客户账号：一律只留「开单 / 订单 / 我的欠款」。其余全部不显示。
      //    注意 customer 的 perms 里带 product.view 只是为了让开单页能取商品价，
      //    不等于允许他逛商品库 —— 菜单必须在这里显式关掉。
      if (ME.role === 'customer' && ['sale', 'orders', 'myar'].indexOf(pg) < 0) show = false;
      b.classList.toggle('hidden', !show);
    });
  }

  async function boot() {
    TOKEN = localStorage.getItem('erp_token');
    // v5.5：即便是普通token 登录，也走「停在首页选账套」的流程，
    // 不再自动进系统。经理要的是：首页永远是选择页。
    if (TOKEN) {
      try {
        ME = await API.req('GET', '/api/me');
        showTrustedPanel();
        return;
      } catch (e) {
        TOKEN = null; localStorage.removeItem('erp_token');
      }
    }
    // 会话失效 → 尝试用「信任设备」令牌免密登录
    if (await tryTrustedLogin()) { showTrustedPanel(); return; }
    showLogin();
  }

  // ---------- 导航 ----------
  async function navigate(page) {
    CURRENT = page;
    $$('.sidebar nav button').forEach(b => b.classList.toggle('active', b.dataset.page === page));
    $('#page').innerHTML = '<div class="muted" style="padding:20px;text-align:center">加载中…</div>';
    try {
      if (page === 'sale') await renderSale();
      else if (page === 'myar') await renderMyAr();
      else if (page === 'orders') await renderOrders();
      else if (page === 'customers') await renderCustomers();
      else if (page === 'products') await renderProducts();
      else if (page === 'analytics') await renderAnalytics();
      else if (page === 'purchase') await renderPurchase();
      else if (page === 'admin') await renderAdmin();
      else if (page === 'me') await renderMe();
    } catch (e) { $('#page').innerHTML = '<div class="err" style="padding:20px">' + esc(e.message) + '</div>'; }
    loadTopStat();
  }

  // ---------- 我的欠款（仅客户账号，2026-10-18）----------
  // 服务端 /api/ar/unsettled 对客户角色会**强制锁定自己的门店**，
  // 所以这里传不传 customer_id 都一样，传了只为语义清晰。
  async function renderMyAr() {
    if (ME.role !== 'customer') {
      $('#page').innerHTML = '<div class="card muted" style="padding:16px">该页面仅客户账号可用</div>';
      return;
    }
    // 2026-10-19 多门店：与开单页当前门店一致，切了店这里就换一家店的欠款
    let cid = ME.customerId;
    if (ME.role === 'customer') {
      const allowed = (ME.customerIds || []).map(Number);
      if (!allowed.length) {
        $('#page').innerHTML = '<div class="card muted" style="padding:16px">账号尚未绑定门店，请联系商家开通</div>';
        return;
      }
      cid = Number(sessionStorage.getItem('sale_store') || 0);
      if (allowed.indexOf(cid) < 0) cid = Number(ME.customerId) || allowed[0];
    }
    if (!cid) {
      $('#page').innerHTML = '<div class="card muted" style="padding:16px">账号尚未绑定门店，请联系商家开通</div>';
      return;
    }
    $('#page').innerHTML = '<div class="muted" style="padding:20px;text-align:center">加载中…</div>';
    let r;
    try { r = await API.req('GET', '/api/ar/unsettled?customer_id=' + cid); }
    catch (e) { $('#page').innerHTML = '<div class="err" style="padding:20px">' + esc(e.message) + '</div>'; return; }
    const debt = Number(r.debt) || 0;
    const rows = r.orders || [];
    $('#page').innerHTML = `
      <div class="card" style="padding:16px;margin-bottom:12px">
        <div class="muted" style="font-size:12px">当前欠款合计</div>
        <div style="font-size:26px;font-weight:700;color:${debt > 0.005 ? '#b91c1c' : '#0B6B49'}">${fmt(debt)}</div>
        <div class="muted" style="font-size:12px;margin-top:6px">
          账期 ${r.term_days || 30} 天${Number(r.return_credit || 0) !== 0 ? ' · 已扣退货 ' + fmt(Math.abs(r.return_credit)) : ''}
        </div>
      </div>
      <div class="card" style="padding:16px">
        <div style="font-weight:600;margin-bottom:8px">未结单据（${rows.length}）</div>
        ${rows.length ? `
        <table style="width:100%;border-collapse:collapse;font-size:13px">
          <thead><tr class="muted" style="text-align:left">
            <th style="padding:6px 0">单号</th><th>日期</th><th style="text-align:right">金额</th>
            <th style="text-align:right">未结</th><th style="text-align:right">账期</th>
          </tr></thead>
          <tbody>${rows.map(o => `
            <tr style="border-top:1px solid #eee">
              <td style="padding:6px 0">${esc(o.order_no || '')}</td>
              <td>${esc(o.order_date || '')}</td>
              <td style="text-align:right">${fmt(o.due)}</td>
              <td style="text-align:right;font-weight:600">${fmt(o.left)}</td>
              <td style="text-align:right;color:${o.overdue ? '#b91c1c' : ''}">${o.days}天</td>
            </tr>`).join('')}</tbody>
        </table>` : '<div class="muted">暂无未结单据</div>'}
      </div>`;
  }

  async function loadTopStat() {
    // 客户账号：/api/stats 是经营统计，服务端已拒绝，前端干脆不请求
    if (ME.role === 'customer') { const el0 = $('#today-stat'); if (el0) el0.textContent = ''; return; }
    try {
      const s = await API.req('GET', '/api/stats');
      // 🔴 2026-10-18：这里原来是裸赋值。loadTopStat 是 await 之后才写 DOM，
      // 期间若用户退出登录（#app-view 被 hide / showLogin 重建视图）或快速切页，
      // #today-stat 可能已被移除 → `Cannot set properties of null (setting 'textContent')`。
      const el = $('#today-stat');
      if (el) el.textContent = '今日 ' + fmt(s.todaySales) + ' · ' + s.todayOrders + '单';
    }
    catch (e) {}
  }

  // ---------- 数据分析 ----------
  let AN = { range: 'today', from: '', to: '' };
  function ymdOf(dt) { return dt.getFullYear() + '-' + String(dt.getMonth() + 1).padStart(2, '0') + '-' + String(dt.getDate()).padStart(2, '0'); }
  function pctOf(v) { return (Number(v || 0) * 100).toFixed(1) + '%'; }
  function moneyOf(v) { return '¥' + Number(v || 0).toFixed(2); }
  function num2(v) { return Math.round(Number(v || 0) * 100) / 100; }


  // ================= 采购与库存（2026-10-21；管家婆「以销定购」口径）=================
  // 🔴 业务口径（经理 2026-10-21 明确）：
  //    「采购量按已有订单数量为准，实现采购入库 - 销售单 = 库存 0（不积压）」
  //    建议采购量 = max(0, 净销售数量 - 已采购入库数量)
  //    ✅ 前端只负责展示与提交，口径全部由服务端算，前端不重算（避免两处口径打架）。
  // 🔴 三口径含义（页面上必须标清楚，别让人误读）：
  //    账面库存 = inventory.qty（历史上有被误清零的老账）
  //    推导库存 = 已采购入库 - 已销售出库（按流水推出来的真实结余）
  //    差异     = 账面 - 推导（历史误差，只呈现不自动改）
  // ============================================================

  let PUR = { tab: 'sug' };        // sug | list | inv | mv
  const PUR_TABS = [
    { k: 'sug', t: '采购建议', need: 'purchase.view' },
    { k: 'list', t: '采购单', need: 'purchase.view' },
    { k: 'inv', t: '库存明细', need: 'inventory.view' },
    { k: 'mv', t: '库存流水', need: 'inventory.view' },
  ];
  // 采购单草稿（新建采购单用）
  let PUR_DRAFT = { supplier_id: '', supplier_name: '', lines: [], remark: '' };

  async function renderPurchase() {
    if (!can('purchase.view') && !can('inventory.view')) {
      $('#page').innerHTML = '<div class="card muted" style="padding:16px">无权限查看采购与库存</div>';
      return;
    }
    const tabs = PUR_TABS.filter(t => !t.need || can(t.need));
    if (!tabs.some(t => t.k === PUR.tab)) PUR.tab = tabs[0] ? tabs[0].k : 'sug';
    $('#page').innerHTML = `
      <div class="pu-tabs">
        ${tabs.map(t => `<button class="pu-tab${t.k === PUR.tab ? ' active' : ''}" data-pu="${t.k}">${t.t}</button>`).join('')}
      </div>
      <div id="pu-body"><div class="muted" style="padding:20px;text-align:center">加载中…</div></div>`;
    $$('#page [data-pu]').forEach(b => b.onclick = () => { PUR.tab = b.dataset.pu; renderPurchase(); });
    const body = $('#pu-body');
    try {
      if (PUR.tab === 'sug') await puSuggestions(body);
      else if (PUR.tab === 'list') await puList(body);
      else if (PUR.tab === 'inv') await puInventory(body);
      else if (PUR.tab === 'mv') await puMoves(body);
    } catch (e) { body.innerHTML = '<div class="card err" style="padding:14px">' + esc(e.message || '加载失败') + '</div>'; }
  }

  // ---------- 选择商品弹窗（v5.6.10 改造：多列自适应网格 + 多选 + 填数量 + 一次性确认）----------
  // 🔴 经理 2026-10-21 反馈：「可以多选产品并填写好数量再返回采购单，现在点一下就回去一下」
  //    原来这里是**单列 .pu-mv 列表 + 点一下就 closeModal() 再重开草稿弹窗**，来回闪。
  //    改法：在同一层弹窗内多列网格展示全部商品（可搜索），
  //    每张卡片有勾选框 + 数量输入 + 进价，底部「确定加入（N）」**一次性**写回 PUR_DRAFT.lines。
  //    卡片点击不关弹窗（除了点勾选框/数量框），所以可以连续勾很多个。
  // 🔴 列数用 CSS grid repeat(auto-fill,minmax(190px,1fr))：宽屏自动 4~5 列、窄屏 2 列。
  async function puPickModal(onDone) {
    let prods = [];
    try { prods = await API.req('GET', '/api/products'); } catch (e) { }
    if (!Array.isArray(prods) || !prods.length) { toast('商品列表读取失败'); return; }
    // 已选：Map(productId -> {qty, price})，初值取当前草稿已有行
    const sel = new Map();
    PUR_DRAFT.lines.forEach(l => sel.set(l.product_id, { qty: Number(l.qty) || 1, price: Number(l.price) || 0 }));
    let kw = '';

    const body = showModal('选择商品（可多选 · 可直接填数量）', `
      <input id="pu-pk-kw" placeholder="输入商品名 / 规格 / 条码筛选…" autocomplete="off"/>
      <div id="pu-pk-grid" class="pu-pk-grid"></div>
      <div class="pu-pk-bar">
        <span id="pu-pk-info" class="muted"></span>
        <button class="btn-sm gray" id="pu-pk-all">全选</button>
        <button class="btn-sm gray" id="pu-pk-none">清空</button>
        <button class="btn-sm gray" id="pu-pk-cancel">取消</button>
        <button class="btn-sm" id="pu-pk-ok">确定加入（<span id="pu-pk-n">${sel.size}</span>）</button>
      </div>`);
    if (!body) return;
    // 🔴 选商品是多列网格，默认 .modal 只有 420px 宽 → 只放得下 1 列（看着还是单列）。
    //    这里给弹窗加 .modal-wide 撑到 900px；关闭时 closeModal 会整体隐藏，下次不受影响。
    const mEl = $('#modal-overlay .modal');
    if (mEl) mEl.classList.add('modal-wide');

    const grid = $('#pu-pk-grid');
    const info = $('#pu-pk-info');
    const cnt = $('#pu-pk-n');
    const refreshCount = () => { if (cnt) cnt.textContent = String(sel.size); };

    const draw = () => {
      const key = kw.trim().toLowerCase();
      const view = key ? prods.filter(p =>
        String(p.name || '').toLowerCase().includes(key) ||
        String(p.spec || '').toLowerCase().includes(key) ||
        String(p.barcode || '').includes(key)) : prods;
      if (info) info.textContent = '共 ' + view.length + ' 个商品' + (key ? '（已筛选）' : '');
      if (!view.length) { grid.innerHTML = '<div class="muted" style="padding:14px;text-align:center">没有匹配的商品</div>'; return; }
      grid.innerHTML = view.map(p => {
        const s = sel.get(p.id);
        return `
        <div class="pu-pk-cd${s ? ' sel' : ''}" data-pk="${p.id}">
          <div class="pu-pk-top">
            <span class="pu-pk-ck">${s ? '☑' : '☐'}</span>
            <span class="pu-pk-nm" title="${esc(p.name)}">${esc(p.name)}</span>
          </div>
          <div class="pu-pk-sp">${esc(p.spec || '')}${p.unit ? ' · ' + esc(p.unit) : ''}</div>
          <div class="pu-pk-row">
            <label><span>数量</span><input type="number" step="0.01" min="0" class="pu-pk-qty" data-pk="${p.id}" value="${s ? s.qty : ''}" placeholder="0"/></label>
            <label><span>进价</span><input type="number" step="0.01" min="0" class="pu-pk-p" data-pk="${p.id}" value="${s ? s.price : (Number(p.cost) || 0)}"/></label>
          </div>
        </div>`;
      }).join('');

      // 点卡片（不在输入框上）→ 切换勾选
      $$('#pu-pk-grid .pu-pk-cd').forEach(cd => {
        cd.onclick = (ev) => {
          if (ev.target && ev.target.closest('.pu-pk-row')) return;   // 点数量/进价输入框不切换
          const id = Number(cd.dataset.pk);
          const p = prods.find(x => Number(x.id) === id);
          if (sel.has(id)) sel.delete(id);
          else sel.set(id, { qty: 1, price: Number(p && p.cost) || 0 });
          draw(); refreshCount();
        };
      });
      // 数量 / 进价
      $$('#pu-pk-grid .pu-pk-qty').forEach(inp => {
        inp.oninput = () => {
          const id = Number(inp.dataset.pk);
          const p = prods.find(x => Number(x.id) === id);
          const q = Number(inp.value) || 0;
          const cur = sel.get(id) || { qty: 0, price: Number(p && p.cost) || 0 };
          cur.qty = q;
          if (q > 0) sel.set(id, cur); else sel.delete(id);
          const cd = inp.closest('.pu-pk-cd');
          if (cd) {
            cd.classList.toggle('sel', sel.has(id));
            const b = cd.querySelector('.pu-pk-ck'); if (b) b.textContent = sel.has(id) ? '☑' : '☐';
          }
          refreshCount();
        };
      });
      $$('#pu-pk-grid .pu-pk-p').forEach(inp => {
        inp.oninput = () => {
          const id = Number(inp.dataset.pk);
          const cur = sel.get(id);
          if (cur) cur.price = Number(inp.value) || 0;
        };
      });
    };
    draw();
    refreshCount();

    const kwEl = $('#pu-pk-kw');
    if (kwEl) kwEl.oninput = () => { kw = kwEl.value || ''; draw(); };
    const bAll = $('#pu-pk-all');
    if (bAll) bAll.onclick = () => {
      $$('#pu-pk-grid .pu-pk-cd').forEach(cd => {
        const id = Number(cd.dataset.pk);
        const p = prods.find(x => Number(x.id) === id);
        if (!p) return;
        const cur = sel.get(id) || { qty: 1, price: Number(p.cost) || 0 };
        if (!(cur.qty > 0)) cur.qty = 1;
        sel.set(id, cur);
      });
      draw(); refreshCount();
    };
    const bNone = $('#pu-pk-none');
    if (bNone) bNone.onclick = () => { sel.clear(); draw(); refreshCount(); };
    const bCancel = $('#pu-pk-cancel');
    if (bCancel) bCancel.onclick = () => { closeModal(); if (typeof onDone === 'function') onDone(); };
    const bOk = $('#pu-pk-ok');
    if (bOk) bOk.onclick = () => {
      if (!sel.size) { toast('请至少勾选一个商品'); return; }
      // 一次性写回草稿：已存在则覆盖数量/进价，新商品则push
      sel.forEach((v, id) => {
        if (!(Number(v.qty) > 0)) return;
        const p = prods.find(x => Number(x.id) === id);
        if (!p) return;
        const ex = PUR_DRAFT.lines.find(l => l.product_id === id);
        if (ex) { ex.qty = Number(v.qty) || 0; ex.price = Number(v.price) || 0; }
        else PUR_DRAFT.lines.push({
          product_id: id, name: p.name, spec: p.spec || '', unit: p.unit || '个',
          qty: Number(v.qty) || 0, price: Number(v.price) || Number(p.cost) || 0
        });
      });
      closeModal();
      toast('已加入 ' + sel.size + ' 个商品');
      if (typeof onDone === 'function') onDone();
    };
  }

  // ---------- 采购建议（v5.6.10 改造：双列卡片 + 勾选批量加入；去掉每行「加入」按钮）----------
  // 🔴 经理 2026-10-21 反馈两条：
  //    ① 「采购首页的建议订单太占位置」→ 表格改双列自适应卡片网格（CSS grid，绝不用 column-count）。
  //    ② 「建议订单里面的加入不知道有什么功能」→ **取消每行的「加入」按钮**，
  //       改成「点卡片 = 勾选/取消 + 卡片内直接改数量 + 底部统一批量加入」，语义就明确了。
  //🔴 PUR_PICKED / PUR_QTY 只存在于当前页内存（不落库）；真正的采购单草稿仍是 PUR_DRAFT.lines。
  //    数量用 Map 存，不要从 DOM 反查（重绘会丢值）。
  let PUR_PICKED = new Set();
  let PUR_QTY = new Map();
  // 🔴 2026-10-21 经理反馈「选好产品要滑到下面才能找到按钮」→ 4 个按钮改右侧浮动。
  //    折叠状态放模块级，重画后仍保持经理手动收/展的选择。
  let PUR_FLOAT_FOLD = false;

  async function puSuggestions(body) {
    body.innerHTML = '<div class="card"><div class="muted">加载中…</div></div>';
    const rows = await API.req('GET', '/api/purchase-suggestions');
    const list = Array.isArray(rows) ? rows : [];
    const need = list.filter(r => Number(r.suggest_qty) > 0.005);
    const sumSug = need.reduce((s, r) => s + Number(r.suggest_qty || 0), 0);
    const sumAmt = need.reduce((s, r) => s + Number(r.suggest_qty || 0) * Number(r.cost || 0), 0);
    const sumDiff = list.reduce((s, r) => s + Math.abs(Number(r.diff || 0)), 0);
    const canEdit = can('purchase.edit');
    const inDraft = (id) => PUR_DRAFT.lines.some(l => l.product_id === id);
    body.innerHTML = `
      <div class="card">
        <div class="section-title">采购建议（以销定购）</div>
        <div class="muted" style="font-size:12px;margin-bottom:8px">
          建议采购量 = 已销售数量 − 已采购入库数量。全部采购入库后，库存正好回到 0（不积压）。<br>
          点击卡片勾选，卡片里可直接改采购数量，最后统一「加入采购单」。
        </div>
        <div class="pu-stats">
          <div class="pu-stat"><div class="k">需采购商品</div><div class="v">${need.length}</div></div>
          <div class="pu-stat"><div class="k">建议采购总量</div><div class="v">${fmtQtyNum(sumSug)}</div></div>
          <div class="pu-stat"><div class="k">按进价估算</div><div class="v">${fmt(sumAmt)}</div></div>
          <div class="pu-stat"><div class="k">账面账实差异</div><div class="v pu-diff">${fmtQtyNum(sumDiff)}</div></div>
        </div>
        ${need.length ? `
        <div class="pu-grid" id="pu-grid">
          ${need.map(r => `
            <div class="pu-cd${PUR_PICKED.has(r.id) ? ' sel' : ''}" data-pid="${r.id}">
              <div class="pu-cd-nm" title="${esc(r.name)}">${esc(r.name)}</div>
              ${r.spec ? `<div class="pu-cd-sp">${esc(r.spec)}</div>` : ''}
              <div class="pu-cd-qty">建议 <b>${fmtQtyNum(r.suggest_qty)}</b> ${esc(r.unit || '')}</div>
              <div class="pu-cd-meta">已销 ${fmtQtyNum(r.sold_qty)} · 已入库 ${fmtQtyNum(r.in_qty)} · 账面 ${fmtQtyNum(r.stock_qty)}</div>
              <div class="pu-cd-foot">
                <span>进价 ${fmt(r.cost)}</span>
                <span class="pu-cd-amt">≈ ${fmt(Number(r.suggest_qty) * Number(r.cost || 0))}</span>
              </div>
              ${canEdit ? `<label class="pu-cd-in"><span>采购数量</span><input type="number" step="0.01" min="0" data-qty="${r.id}" value="${PUR_QTY.has(r.id) ? PUR_QTY.get(r.id) : r.suggest_qty}"/></label>` : ''}
              ${inDraft(r.id) ? '<div class="pu-cd-badge">已在采购单</div>' : ''}
              <div class="pu-cd-ck">${PUR_PICKED.has(r.id) ? '✓ 已选' : '点击勾选'}</div>
            </div>`).join('')}
        </div>
        ${canEdit ? `
        <div class="pu-float${PUR_FLOAT_FOLD ? ' fold' : ''}" id="pu-float">
          <button class="pu-fb-main" id="pu-sel-add" title="把勾选的商品写入采购单草稿">
            <span>加入采购单</span>
            <span class="pu-fb-n">已选 <b id="pu-pick-n">${PUR_PICKED.size}</b></span>
          </button>
          <div class="pu-fb-sub">
            <button class="pu-fb-b gray" id="pu-sel-all" title="勾选全部建议商品">全选</button>
            <button class="pu-fb-b gray" id="pu-sel-cost" title="按商品进价估算这批要多少钱">估算</button>
            <button class="pu-fb-b gray" id="pu-sel-none" title="清空所有勾选">清空</button>
          </div>
          <button class="pu-fb-fold" id="pu-float-fold" title="收起/展开">${PUR_FLOAT_FOLD ? '‹' : '›'}</button>
        </div>` : ''}`
        : '<div class="muted" style="padding:16px;text-align:center">当前没有需要采购的商品（销售量都已被采购覆盖）</div>'}
      </div>
      <div id="pu-draft-slot"></div>
      <div class="card">
        <div class="section-title">全部商品库存对照</div>
        <div class="muted" style="font-size:12px;margin-bottom:8px">
          账面库存 = 系统记录的结余；推导库存 = 已入库 − 已销。差异是历史遗留误差，暂不自动改。
        </div>
        <div class="table-wrap">
        <table class="pu-tbl">
          <thead><tr>
            <th>商品</th><th class="num">账面</th><th class="num">已销</th><th class="num">已入库</th>
            <th class="num">推导</th><th class="num">差异</th><th class="num">建议采购</th>
          </tr></thead>
          <tbody>${list.map(r => `
            <tr>
              <td class="nm" title="${esc(r.name)}">${esc(r.name)}</td>
              <td class="num${Number(r.stock_qty) < 0 ? ' pu-neg' : ''}">${fmtQtyNum(r.stock_qty)}</td>
              <td class="num">${fmtQtyNum(r.sold_qty)}</td>
              <td class="num">${fmtQtyNum(r.in_qty)}</td>
              <td class="num">${fmtQtyNum(r.derive_qty)}</td>
              <td class="num${Math.abs(Number(r.diff)) > 0.005 ? ' pu-diff' : ''}">${fmtQtyNum(r.diff)}</td>
              <td class="num${Number(r.suggest_qty) > 0.005 ? ' pu-sug' : ''}">${fmtQtyNum(r.suggest_qty)}</td>
            </tr>`).join('')}</tbody>
        </table></div>
      </div>`;

    // ---- 草稿条 ----
    const drawDraft = () => {
      const slot = $('#pu-draft-slot');
      if (!slot) return;
      if (!PUR_DRAFT.lines.length) { slot.innerHTML = ''; return; }
      const n = PUR_DRAFT.lines.length;
      const qty = PUR_DRAFT.lines.reduce((s, l) => s + (Number(l.qty) || 0), 0);
      const amt = PUR_DRAFT.lines.reduce((s, l) => s + (Number(l.qty) || 0) * (Number(l.price) || 0), 0);
      slot.innerHTML = `
        <div class="pu-draft">
          <div class="d-t">采购单草稿：${n} 个商品 · ${fmtQtyNum(qty)} 件 · ${fmt(amt)}</div>
          <button class="d-b" id="pu-go-draft">去建采购单</button>
        </div>`;
      const go = $('#pu-go-draft');
      if (go) go.onclick = () => puDraftModal();
    };
    drawDraft();

    const refreshCount = () => { const el = $('#pu-pick-n'); if (el) el.textContent = String(PUR_PICKED.size); };
    const markCd = (cd, on) => {
      cd.classList.toggle('sel', on);
      const ck = cd.querySelector('.pu-cd-ck');
      if (ck) ck.textContent = on ? '✓ 已选' : '点击勾选';
    };

    // ---- 点卡片 = 勾选/取消 ----
    $$('#pu-grid .pu-cd').forEach(cd => {
      cd.onclick = (ev) => {
        if (ev.target && ev.target.closest('.pu-cd-in')) return;   // 点数量输入框不切换勾选
        const id = Number(cd.dataset.pid);
        if (PUR_PICKED.has(id)) PUR_PICKED.delete(id); else PUR_PICKED.add(id);
        markCd(cd, PUR_PICKED.has(id));
        refreshCount();
      };
    });
    // ---- 数量输入（oninput 只写 Map，不重绘，避免丢焦点）----
    $$('#pu-grid [data-qty]').forEach(inp => {
      inp.oninput = () => {
        const id = Number(inp.dataset.qty);
        PUR_QTY.set(id, inp.value);
        const cd = inp.closest('.pu-cd');
        if (cd) markCd(cd, true);
        PUR_PICKED.add(id);
        refreshCount();
      };
    });

    // ---- 折叠/展开（经理手动收起来时不挡卡片）----
    const btnFold = $('#pu-float-fold');
    const fl = $('#pu-float');
    if (btnFold && fl) btnFold.onclick = (ev) => {
      ev.stopPropagation();          // 🔴 别让它冒泡去点卡片
      PUR_FLOAT_FOLD = !PUR_FLOAT_FOLD;
      fl.classList.toggle('fold', PUR_FLOAT_FOLD);
      btnFold.textContent = PUR_FLOAT_FOLD ? '‹' : '›';
    };

    const btnNone = $('#pu-sel-none');
    if (btnNone) btnNone.onclick = () => {
      PUR_PICKED.clear();
      $$('#pu-grid .pu-cd').forEach(cd => markCd(cd, false));
      refreshCount(); toast('已清空勾选');
    };
    const btnAll = $('#pu-sel-all');
    if (btnAll) btnAll.onclick = () => {
      need.forEach(r => PUR_PICKED.add(r.id));
      $$('#pu-grid .pu-cd').forEach(cd => markCd(cd, true));
      refreshCount(); toast('已勾选 ' + need.length + ' 个商品');
    };
    const btnCost = $('#pu-sel-cost');
    if (btnCost) btnCost.onclick = () => {
      const t = need.reduce((s, r) => s + Number(r.suggest_qty) * Number(r.cost || 0), 0);
      toast('按进价估算合计 ' + fmt(t));
    };
    const btnAdd = $('#pu-sel-add');
    if (btnAdd) btnAdd.onclick = async () => {   // 🔴 这里必须 async：末尾要 await renderPurchase()
      if (!PUR_PICKED.size) { toast('请先点击卡片勾选商品'); return; }
      let n = 0, bad = 0;
      PUR_PICKED.forEach(id => {
        const r = need.find(x => Number(x.id) === id);
        if (!r) return;
        const q = PUR_QTY.has(id) ? (Number(PUR_QTY.get(id)) || 0) : (Number(r.suggest_qty) || 0);
        if (q <= 0) { bad++; return; }
        const ex = PUR_DRAFT.lines.find(l => l.product_id === id);
        if (ex) { ex.qty = q; ex.price = Number(r.cost) || 0; }
        else PUR_DRAFT.lines.push({
          product_id: r.id, name: r.name, spec: r.spec || '', unit: r.unit || '个',
          qty: q, price: Number(r.cost) || 0
        });
        n++;
      });
      PUR_PICKED.clear();
      if (bad) toast(bad + ' 个商品数量为 0，已跳过');
      if (n) toast('已加入 ' + n + ' 个商品到采购单草稿');
      await renderPurchase();     // 重画：更新「已在采购单」标记
    };
  }
  // ---------- 采购单列表 ----------
  async function puList(body) {
    body.innerHTML = '<div class="card"><div class="muted">加载中…</div></div>';
    const rows = await API.req('GET', '/api/purchases');
    const list = Array.isArray(rows) ? rows : [];
    const canEdit = can('purchase.edit');
    body.innerHTML = `
      <div class="card">
        <div class="section-title">采购单</div>
        <div class="row" style="gap:8px;flex-wrap:wrap;margin:8px 0">
          ${canEdit ? '<button class="btn-sm" id="pu-new">＋ 新建采购单</button>' : ''}
          <span class="muted" style="font-size:12px">建单即入库：提交后库存立即增加，作废则自动回退。</span>
        </div>
        ${list.length ? `
        <div class="table-wrap">
        <table class="pu-tbl">
          <thead><tr>
            <th>单号</th><th>日期</th><th>供应商</th><th class="num">明细</th>
            <th class="num">金额</th><th>状态</th><th>操作</th>
          </tr></thead>
          <tbody>${list.map(p => `
            <tr>
              <td>${esc(p.purchase_no || '')}</td>
              <td>${esc(p.purchase_date || '')}</td>
              <td class="nm" title="${esc(p.supplier_name || '')}">${esc(p.supplier_name || '-')}</td>
              <td class="num">${p.item_count || 0}</td>
              <td class="num">${fmt(p.payable)}</td>
              <td>${p.status === 'void' ? '<span class="pu-tag void">已作废</span>' : '<span class="pu-tag in">已入库</span>'}</td>
              <td>
                <button class="btn-sm gray" data-view="${p.id}">明细</button>
                ${canEdit && p.status !== 'void' ? ` <button class="btn-sm danger" data-void="${p.id}">作废</button>` : ''}
              </td>
            </tr>`).join('')}</tbody>
        </table></div>` : '<div class="muted" style="padding:16px;text-align:center">还没有采购单</div>'}
      </div>`;
    if ($('#pu-new')) $('#pu-new').onclick = () => puNewOrder();
    $$('#pu-body [data-view]').forEach(b => b.onclick = () => puViewOrder(b.dataset.view));
    $$('#pu-body [data-void]').forEach(b => b.onclick = () => puVoidOrder(b.dataset.void));
  }

  async function puViewOrder(id) {
    let d;
    try { d = await API.req('GET', '/api/purchases/' + id); }
    catch (e) { toast(e.message); return; }
    showModal('采购单 ' + (d.purchase_no || ''), `
      <div class="muted" style="font-size:12px;margin-bottom:8px">
        日期 ${esc(d.purchase_date || '')} · 供应商 ${esc(d.supplier_name || '-')} ·
        ${d.status === 'void' ? '<span class="pu-tag void">已作废</span>' : '<span class="pu-tag in">已入库</span>'}
      </div>
      <table class="pu-tbl">
        <thead><tr><th>商品</th><th class="num">数量</th><th class="num">进价</th><th class="num">小计</th></tr></thead>
        <tbody>${(d.items || []).map(i => `
          <tr>
            <td class="nm" title="${esc(i.product_name || '')}">${esc(i.product_name || '')}</td>
            <td class="num">${fmtQtyNum(i.qty)} ${esc(i.unit || '')}</td>
            <td class="num">${fmt(i.price)}</td>
            <td class="num">${fmt(i.subtotal)}</td>
          </tr>`).join('')}</tbody>
      </table>
      <div style="text-align:right;margin-top:10px;font-weight:700">合计 ${fmt(d.payable)}</div>
      ${d.remark ? '<div class="muted" style="font-size:12px;margin-top:6px">备注：' + esc(d.remark) + '</div>' : ''}
      <button class="btn-primary" id="pu-view-close" style="margin-top:14px">关闭</button>`);
    const c = $('#pu-view-close');
    if (c) c.onclick = closeModal;
  }

  async function puVoidOrder(id) {
    if (!confirm('作废该采购单？库存会自动回退（相当于没采购过）。')) return;
    try { await API.req('DELETE', '/api/purchases/' + id); toast('已作废，库存已回退'); }
    catch (e) { toast(e.message); }
    await renderPurchase();
  }

  // ---------- 新建采购单 ----------
  function puNewOrder() {
    PUR_DRAFT = { supplier_id: '', supplier_name: '', lines: [], remark: '' };
    puDraftModal();
  }
  async function puDraftModal() {
    // 供应商下拉
    let sups = [];
    try { sups = await API.req('GET', '/api/suppliers'); } catch (e) { }
    if (!Array.isArray(sups)) sups = [];
    const body = showModal('新建采购单', `
      <label>供应商</label>
      <select id="pu-sup">
        <option value="">（不指定）</option>
        ${sups.map(s => `<option value="${s.id}">${esc(s.name)}</option>`).join('')}
      </select>
      <input id="pu-sup-new" placeholder="或直接输入供应商名称" style="margin-top:6px"/>
      <label style="margin-top:10px">采购明细</label>
      <div id="pu-lines"></div>
      <button class="btn-sm gray" id="pu-addrow" style="width:100%;margin-top:8px">＋ 添加商品</button>
      <label style="margin-top:10px">备注</label>
      <input id="pu-remark" placeholder="选填"/>
      <div class="pu-bar">
        <button class="btn-sm gray" id="pu-cancel">取消</button>
        <button class="btn-sm gray" id="pu-save-draft">保存草稿</button>
        <button class="btn-sm" id="pu-submit">提交并入库</button>
      </div>`);
    if (!body) return;
    const drawLines = () => {
      const box = $('#pu-lines');
      if (!box) return;
      if (!PUR_DRAFT.lines.length) {
        box.innerHTML = '<div class="muted" style="font-size:12px;padding:8px 0">还没有明细，可从「采购建议」页加入，或点上方添加商品。</div>';
        return;
      }
      box.innerHTML = PUR_DRAFT.lines.map((l, i) => `
        <div class="pu-line" data-idx="${i}">
          <div>
            <span class="t">${esc(l.name || '')}</span>
            <input class="pu-l-name" value="${esc(l.name || '')}" placeholder="商品名"/>
          </div>
          <div><span class="t">数量</span><input class="pu-l-qty" type="number" step="0.01" min="0" value="${l.qty}"/></div>
          <div class="pu-l-price"><span class="t">进价</span><input class="pu-l-p" type="number" step="0.01" min="0" value="${l.price}"/></div>
          <div><span class="t">小计</span><div class="pu-l-sum" style="font-weight:600;padding:8px 0"></div></div>
          <button class="btn-sm gray pu-l-del" type="button" style="width:34px;padding:8px 0">✕</button>
        </div>`).join('');
      const recalc = () => {
        $$('#pu-lines .pu-line').forEach(row => {
          const i = Number(row.dataset.idx);
          const q = Number(row.querySelector('.pu-l-qty').value) || 0;
          const p = Number(row.querySelector('.pu-l-p').value) || 0;
          row.querySelector('.pu-l-sum').textContent = fmt(q * p);
        });
      };
      $$('#pu-lines .pu-l-qty, #pu-lines .pu-l-p').forEach(inp => inp.oninput = recalc);
      $$('#pu-lines .pu-l-name').forEach(inp => inp.oninput = () => {
        PUR_DRAFT.lines[Number(inp.closest('.pu-line').dataset.idx)].name = inp.value;
      });
      $$('#pu-lines .pu-l-del').forEach(b => b.onclick = () => {
        PUR_DRAFT.lines.splice(Number(b.closest('.pu-line').dataset.idx), 1);
        drawLines();
      });
      recalc();
    };
    drawLines();
    if ($('#pu-addrow')) $('#pu-addrow').onclick = () => puPickModal(() => puDraftModal());

    const sup = $('#pu-sup');
    if (sup) sup.onchange = () => { const s = sups.find(x => String(x.id) === sup.value); PUR_DRAFT.supplier_id = sup.value || ''; PUR_DRAFT.supplier_name = s ? s.name : ''; };
    const cancel = $('#pu-cancel');
    if (cancel) cancel.onclick = closeModal;
    const saveDraft = $('#pu-save-draft');
    if (saveDraft) saveDraft.onclick = () => { PUR_DRAFT.remark = ($('#pu-remark') || {}).value || ''; toast('草稿已保存（本次会话内）'); closeModal(); };
    const sub = $('#pu-submit');
    if (sub) sub.onclick = async () => {
      // 收集行数据
      const lines = [];
      const rows = $$('#pu-lines .pu-line');
      for (const row of rows) {
        const q = Number(row.querySelector('.pu-l-qty').value) || 0;
        const p = Number(row.querySelector('.pu-l-p').value) || 0;
        if (q <= 0) { toast('数量必须大于 0'); return; }
        const i = Number(row.dataset.idx);
        const l = PUR_DRAFT.lines[i];
        if (!l) continue;
        lines.push({ product_id: l.product_id, qty: q, price: p });
      }
      if (!lines.length) { toast('请先添加采购明细'); return; }
      const supNew = ($('#pu-sup-new') || {}).value || '';
      const supSel = $('#pu-sup') ? $('#pu-sup') : null;
      const sid = supSel && supSel.value ? Number(supSel.value) : null;
      const sname = supNew.trim() || (sid ? (sups.find(x => x.id === sid) || {}).name || '' : '');
      sub.disabled = true;
      try {
        const r = await API.req('POST', '/api/purchases', {
          supplier_id: sid, supplier_name: sname,
          remark: ($('#pu-remark') || {}).value || '',
          items: lines
        });
        closeModal();
        toast('采购单 ' + (r.purchase_no || '') + ' 已入库');
        PUR_DRAFT = { supplier_id: '', supplier_name: '', lines: [], remark: '' };
        await renderPurchase();
      } catch (e) { toast(e.message); sub.disabled = false; }
    };
  }

  // ---------- 库存明细 ----------
  async function puInventory(body) {
    body.innerHTML = '<div class="card"><div class="muted">加载中…</div></div>';
    const only = PUR.invOnly || '';
    const kw = PUR.invKw || '';
    let q = '';
    if (only) q += 'only=' + encodeURIComponent(only);
    if (kw) q += (q ? '&' : '') + 'kw=' + encodeURIComponent(kw);
    const rows = await API.req('GET', '/api/inventory/summary' + (q ? '?' + q : ''));
    const list = Array.isArray(rows) ? rows : [];
    const neg = list.filter(r => Number(r.stock_qty) < 0).length;
    const diff = list.filter(r => Math.abs(Number(r.diff)) > 0.005).length;
    const alert = list.filter(r => Number(r.low_threshold) > 0 && Number(r.stock_qty) <= Number(r.low_threshold)).length;
    body.innerHTML = `
      <div class="card">
        <div class="section-title">库存明细</div>
        <div class="row" style="gap:8px;flex-wrap:wrap;margin:8px 0;align-items:center">
          <input id="pu-inv-kw" placeholder="商品名 / 条码" value="${esc(kw)}" style="flex:1;min-width:140px;max-width:240px"/>
          <button class="btn-sm" id="pu-inv-search">搜索</button>
          <button class="btn-sm gray" id="pu-inv-all">全部</button>
          <button class="btn-sm gray" id="pu-inv-neg">负库存</button>
          <button class="btn-sm gray" id="pu-inv-diff">有差异</button>
          <button class="btn-sm gray" id="pu-inv-alert">低于预警</button>
        </div>
        <div class="pu-stats">
          <div class="pu-stat"><div class="k">商品数</div><div class="v">${list.length}</div></div>
          <div class="pu-stat"><div class="k">负库存</div><div class="v${neg ? ' pu-neg' : ''}">${neg}</div></div>
          <div class="pu-stat"><div class="k">账实差异</div><div class="v pu-diff">${diff}</div></div>
          <div class="pu-stat"><div class="k">低于预警</div><div class="v${alert ? ' pu-neg' : ''}">${alert}</div></div>
        </div>
        <div class="table-wrap">
        <table class="pu-tbl">
          <thead><tr>
            <th>商品</th><th class="num">账面库存</th><th class="num">已销</th><th class="num">已入库</th>
            <th class="num">推导库存</th><th class="num">差异</th><th class="num">预警值</th>
          </tr></thead>
          <tbody>${list.map(r => `
            <tr>
              <td class="nm" title="${esc(r.name)}">${esc(r.name)}</td>
              <td class="num${Number(r.stock_qty) < 0 ? ' pu-neg' : ''}">${fmtQtyNum(r.stock_qty)}</td>
              <td class="num">${fmtQtyNum(r.sold_qty)}</td>
              <td class="num">${fmtQtyNum(r.in_qty)}</td>
              <td class="num">${fmtQtyNum(r.derive_qty)}</td>
              <td class="num${Math.abs(Number(r.diff)) > 0.005 ? ' pu-diff' : ''}">${fmtQtyNum(r.diff)}</td>
              <td class="num">${Number(r.low_threshold) ? fmtQtyNum(r.low_threshold) : '-'}</td>
            </tr>`).join('')}</tbody>
        </table></div>
      </div>`;
    const kwEl = $('#pu-inv-kw');
    if (kwEl) kwEl.onkeydown = (e) => { if (e.key === 'Enter') { PUR.invKw = kwEl.value.trim(); puInventory($('#pu-body')); } };
    const sb = $('#pu-inv-search');
    if (sb) sb.onclick = () => { PUR.invKw = kwEl.value.trim(); puInventory($('#pu-body')); };
    const bind = (id, v) => { const b = $('#' + id); if (b) b.onclick = () => { PUR.invOnly = v; puInventory($('#pu-body')); }; };
    bind('pu-inv-all', '');
    bind('pu-inv-neg', 'neg');
    bind('pu-inv-diff', 'diff');
    bind('pu-inv-alert', 'alert');
  }

  // ---------- 库存流水 ----------
  async function puMoves(body) {
    body.innerHTML = '<div class="card"><div class="muted">加载中…</div></div>';
    const rows = await API.req('GET', '/api/stock-moves');
    const list = Array.isArray(rows) ? rows : [];
    const T = {
      purchase_in: ['采购入库', 'in'],
      purchase_void: ['采购作废回退', 'out'],
      sale_out: ['销售出库', 'out'],
      sale_return: ['销售退货入库', 'in'],
    };
    body.innerHTML = `
      <div class="card">
        <div class="section-title">库存流水</div>
        <div class="muted" style="font-size:12px;margin-bottom:8px">
          采购入库来自采购单；销售出库/退货由销售单明细反推（销售单不改，改单与退货自动体现在这里）。
        </div>
        ${list.length ? list.map(r => {
      const t = T[r.move_type] || [r.move_type, ''];
      const q = Number(r.qty) || 0;
      return `
          <div class="pu-mv">
            <span class="t">${esc(r.ts || '')}</span>
            <span class="pu-tag ${t[1]}">${t[0]}</span>
            <span class="n" title="${esc(r.product_name || '')}">${esc(r.product_name || '')}</span>
            <span class="q ${q >= 0 ? 'in' : 'out'}">${q >= 0 ? '+' : ''}${fmtQtyNum(q)}</span>
            <span class="t">${esc(r.ref_no || '')}</span>
          </div>`;
    }).join('') : '<div class="muted" style="padding:16px;text-align:center">暂无流水</div>'}
      </div>`;
  }

  // ========== 报表中心（2026-10-17，方案 C：销售 / 货款 / 库存 三组） ==========
  // 🔴 为什么用「一个菜单 + 内部分组」而不是侧栏拆多项：报表会越来越多（商品/门店/账龄/预警），
  //    拆菜单以后还得再合回来返工。现在侧栏只多 1 项，加报表只加格子。
  let RP = { tab: 'sales' };          // sales | ar | aging | flow | stock
  const RP_TABS = [
    { k: 'sales', t: '销售分析', grp: '销售' },
    { k: 'prods', t: '商品排行', grp: '销售' },
    { k: 'stores', t: '门店排行', grp: '销售' },
    { k: 'ar', t: '欠款汇总', grp: '货款', need: 'ar.view' },
    { k: 'aging', t: '账龄分析', grp: '货款', need: 'ar.view' },
    { k: 'flow', t: '收款流水', grp: '货款', need: 'ar.view' },
    { k: 'stock', t: '库存预警', grp: '库存', need: 'inventory.view' },
  ];
  async function renderReports() {
    if (!can('analysis.view')) { $('#page').innerHTML = '<div class="card muted" style="padding:16px">无权限查看报表</div>'; return; }
    const today = ymdOf(new Date());
    if (!AN.from) { AN.from = today; AN.to = today; }
    const tabs = RP_TABS.filter(t => !t.need || can(t.need));
    if (!tabs.some(t => t.k === RP.tab)) RP.tab = tabs[0] ? tabs[0].k : 'sales';
    // 三组分组渲染，一眼看清哪张报表属于哪块
    const groups = [];
    RP_TABS.forEach(t => {
      if (t.need && !can(t.need)) return;
      let g = groups.find(x => x.name === t.grp);
      if (!g) groups.push(g = { name: t.grp, items: [] });
      g.items.push(t);
    });
    $('#page').innerHTML = `
      <div class="rp-tabs">
        ${groups.map(g => `
          <div class="rp-grp">
            <div class="rp-grp-t">${g.name}</div>
            <div class="rp-grp-b">${g.items.map(t =>
              `<button class="rp-tab${t.k === RP.tab ? ' active' : ''}" data-rp="${t.k}">${t.t}</button>`).join('')}</div>
          </div>`).join('')}
      </div>
      <div id="rp-body"></div>`;
    $$('#page [data-rp]').forEach(b => b.onclick = () => { RP.tab = b.dataset.rp; renderReports(); });
    const body = $('#rp-body');
    body.innerHTML = '<div class="muted" style="padding:16px;text-align:center">加载中…</div>';
    try {
      if (RP.tab === 'sales') await rpSales(body);
      else if (RP.tab === 'prods') await rpProds(body);
      else if (RP.tab === 'stores') await rpStores(body);
      else if (RP.tab === 'ar') await rpAr(body);
      else if (RP.tab === 'aging') await rpAging(body);
      else if (RP.tab === 'flow') await rpFlow(body);
      else if (RP.tab === 'stock') await rpStock(body);
    } catch (e) { body.innerHTML = '<div class="card err" style="padding:14px">' + esc(e.message || '加载失败') + '</div>'; }
  }
  // 报表日期区间条（商品/门店/销售共用）
  function rpRangeBar() {
    return `
      <div class="card">
        <div class="section-title">销售分析</div>
        <div class="row" style="gap:8px;flex-wrap:wrap;margin:10px 0 6px;align-items:center">
          <button class="btn-sm" data-an="today">当天</button>
          <button class="btn-sm" data-an="month">本月</button>
          <button class="btn-sm" data-an="year">今年</button>
          <button class="btn-sm" data-an="custom">自定义</button>
          <span id="an-custom" style="gap:6px;align-items:center;${AN.range === 'custom' ? '' : 'display:none'}">
            <input type="date" id="an-from" value="${AN.from}"/> <span class="muted">~</span> <input type="date" id="an-to" value="${AN.to}"/>
            <button class="btn-sm" id="an-apply" style="white-space:nowrap">查询</button>
          </span>
        </div>
        <div class="muted" id="an-range" style="font-size:12px"></div>
        <div class="row" style="gap:12px;flex-wrap:wrap;margin-top:12px">
          <div class="card" style="flex:1;min-width:150px;margin:0"><div class="muted" style="font-size:12px">销售总额</div><div id="an-sales" style="font-size:22px;font-weight:800;color:var(--danger)">-</div></div>
          <div class="card" style="flex:1;min-width:150px;margin:0"><div class="muted" style="font-size:12px">利润</div><div id="an-profit" style="font-size:22px;font-weight:800">-</div></div>
          <div class="card" style="flex:1;min-width:150px;margin:0"><div class="muted" style="font-size:12px">利润率</div><div id="an-margin" style="font-size:22px;font-weight:800">-</div></div>
          <div class="card" style="flex:1;min-width:150px;margin:0"><div class="muted" style="font-size:12px">订单数 / 数量</div><div id="an-orders" style="font-size:18px;font-weight:700">-</div></div>
        </div>
        <div id="an-body" style="margin-top:12px"><div class="muted">加载中…</div></div>
      </div>
      <div class="card">
        <div class="section-title">当天出货汇总</div>
        <div class="row" style="gap:8px;align-items:center;margin:10px 0;flex-wrap:wrap">
          <input type="date" id="sh-date" value="${ymdOf(new Date())}"/>
          <button class="btn-sm" id="sh-load">查询</button>
          <button class="btn-sm gray" id="sh-export">导出 Excel</button>
        </div>
        <div id="sh-body"><div class="muted">加载中…</div></div>
      </div>`;
  }
  function bindRangeBar() {
    const btns = $$('#rp-body [data-an]');
    btns.forEach(b => b.classList.toggle('gray', b.dataset.an !== AN.range));
    btns.forEach(b => b.onclick = async () => {
      AN.range = b.dataset.an;
      btns.forEach(x => x.classList.toggle('gray', x.dataset.an !== AN.range));
      const cust = $('#an-custom');
      if (cust) { cust.style.display = AN.range === 'custom' ? 'inline-flex' : 'none'; }
      if (AN.range === 'custom') { $('#an-from').value = AN.from; $('#an-to').value = AN.to; return; }
      await loadAnalytics();
    });
    if ($('#an-apply')) $('#an-apply').onclick = async () => { AN.from = $('#an-from').value || AN.from; AN.to = $('#an-to').value || AN.to; await loadAnalytics(); };
    if ($('#sh-load')) $('#sh-load').onclick = () => loadShipments($('#sh-date').value);
    if ($('#sh-export')) $('#sh-export').onclick = () => downloadXlsx('/api/shipments/daily/export?date=' + encodeURIComponent($('#sh-date').value), '当天出货汇总_' + $('#sh-date').value + '.xlsx');
  }
  async function rpSales(body) {
    body.innerHTML = rpRangeBar();
    bindRangeBar();
    await loadAnalytics();
    await loadShipments($('#sh-date').value);
  }
  async function rpProds(body) {
    const q = AN.range === 'custom' ? 'from=' + AN.from + '&to=' + AN.to : '';
    body.innerHTML = `<div class="card">
      <div class="section-title">商品排行</div>
      <div class="muted" style="font-size:12px;margin:6px 0 10px">按销售额降序。利润 = Σ(售价 − 进价) × 数量。</div>
      <div id="rp-tbl"><div class="muted">加载中…</div></div></div>`;
    const d = await API.req('GET', '/api/report/products' + (q ? '?' + q : ''));
    $('#rp-tbl').innerHTML = d.length
      ? `<div class="erp-table"><div class="erp-scroll">
          <div class="erp-thead"><span class="c-name">商品</span><span class="c-spec">销量</span><span class="c-price">销售额</span><span class="c-price">利润</span></div>
          ${d.map(p => `<div class="erp-row"><span class="c-name">${esc(p.product_name)}<div class="muted" style="font-size:11px">${esc(p.spec || '')}</div></span>
            <span class="c-spec">${num2(p.qty)}</span><span class="c-price">${fmt(p.sales)}</span>
            <span class="c-price" style="color:${p.profit >= 0 ? 'var(--primary)' : 'var(--danger)'}">${fmt(p.profit)}</span></div>`).join('')}
        </div></div>`
      : '<div class="muted">暂无数据</div>';
  }
  async function rpStores(body) {
    const q = AN.range === 'custom' ? 'from=' + AN.from + '&to=' + AN.to : '';
    body.innerHTML = `<div class="card">
      <div class="section-title">门店排行</div>
      <div class="muted" style="font-size:12px;margin:6px 0 10px">按销售额降序，退货单自动抵减。</div>
      <div id="rp-tbl"><div class="muted">加载中…</div></div></div>`;
    const d = await API.req('GET', '/api/report/stores' + (q ? '?' + q : ''));
    $('#rp-tbl').innerHTML = d.length
      ? `<div class="erp-table"><div class="erp-scroll">
          <div class="erp-thead"><span class="c-name">门店</span><span class="c-spec">单数</span><span class="c-price">销售额</span><span class="c-price">利润</span></div>
          ${d.map(r => `<div class="erp-row"><span class="c-name">${esc(r.customer_name)}</span>
            <span class="c-spec">${r.orders}</span><span class="c-price">${fmt(r.sales)}</span>
            <span class="c-price" style="color:${r.profit >= 0 ? 'var(--primary)' : 'var(--danger)'}">${fmt(r.profit)}</span></div>`).join('')}
        </div></div>`
      : '<div class="muted">暂无数据</div>';
  }
  async function rpAr(body) {
    body.innerHTML = `<div class="card"><div class="section-title">欠款汇总</div>
      <div id="rp-tbl"><div class="muted">加载中…</div></div></div>`;
    const d = await API.req('GET', '/api/ar/summary');
    $('#rp-tbl').innerHTML = `
      <div class="row between" style="padding:10px 12px;background:var(--color-background-secondary);border-radius:8px;margin-bottom:10px">
        <span class="muted">欠款总额（${d.rows.length} 家有欠款）</span>
        <span style="font-weight:700;color:var(--danger);font-size:20px">${fmt(d.total_debt)}</span>
      </div>
      ${d.total_overdue ? `<div class="muted" style="font-size:12px;margin-bottom:10px;color:var(--danger)">${d.total_overdue} 单已超账期</div>` : ''}
      ${d.rows.length ? `<div class="erp-table"><div class="erp-scroll">
        <div class="erp-thead"><span class="c-name">门店</span><span class="c-spec">未结</span><span class="c-price">欠款</span></div>
        ${d.rows.map(r => {
          // 🔴 2026-10-18 经理报「退货金额没有计算进客户的货款计算中」：服务端已把退货额
          //    抵进 debt，这里把它单列显示，账目对得上才敢用。
          //    debt 可能是负数（退货冲减后客户反而欠我们 / 我们欠他）→ 标题与配色要跟着变。
          const isCredit = r.debt < -0.005;
          const retTip = r.return_orders ? ` · <span style="color:#0B6B49">退货 ${r.return_orders} 单已冲减 ${fmt(Math.abs(r.return_total))}</span>` : '';
          return `<div class="erp-row"${r.overdue_orders ? ' style="background:rgba(226,75,74,.06)"' : ''}>
          <span class="c-name">${esc(r.customer_name)}${r.overdue_orders ? ` <span style="color:var(--danger);font-size:11px">超期${r.overdue_orders}单</span>` : ''}
            <div class="muted" style="font-size:11px">最久 ${r.max_days} 天 · 账期 ${r.term_days} 天${retTip}</div></span>
          <span class="c-spec">${r.unsettled} / ${r.orders}</span>
          <span class="c-price" style="color:${isCredit ? '#0B6B49' : 'var(--danger)'};font-weight:700">${fmt(r.debt)}</span></div>`;
        }).join('')}
      </div></div>` : '<div class="muted">🎉 全部门店已结清</div>'}
      ${can('ar.edit') && d.rows.length ? '<button class="btn-sm" id="rp-pay" style="width:100%;margin-top:10px">💰 记一笔收款</button>' : ''}`;
    if ($('#rp-pay')) $('#rp-pay').onclick = () => openPayDialog(null, () => rpAr($('#rp-body')));
  }
  async function rpAging(body) {
    body.innerHTML = `<div class="card"><div class="section-title">账龄分析</div>
      <div class="muted" style="font-size:12px;margin:6px 0 10px">按开单距今天数分档。超期（默认 30 天）会在客户页标红。</div>
      <div id="rp-tbl"><div class="muted">加载中…</div></div></div>`;
    const d = await API.req('GET', '/api/ar/aging');
    $('#rp-tbl').innerHTML = `
      <div class="rp-aging">
        ${d.buckets.map((b, i) => `
          <div class="rp-ab${i >= 3 ? ' is-bad' : ''}">
            <div class="muted" style="font-size:12px">${b.label}</div>
            <div style="font-size:18px;font-weight:700;margin-top:2px">${fmt(b.amt)}</div>
            <div class="muted" style="font-size:11px">${b.n} 单</div>
          </div>`).join('')}
      </div>
      <div class="row between" style="padding:8px 10px;background:var(--color-background-secondary);border-radius:8px;margin:10px 0">
        <span class="muted">欠款总额${d.return_credit ? `（已扣退货 ${fmt(Math.abs(d.return_credit))}）` : ''}</span>
        <span style="font-weight:700;color:${d.total < -0.005 ? '#0B6B49' : 'var(--danger)'}">${fmt(d.total)}</span></div>
      ${d.detail.length ? `<div class="erp-table"><div class="erp-scroll" style="max-height:46vh">
        <div class="erp-thead"><span class="c-name">门店 / 单号</span><span class="c-spec">账龄</span><span class="c-price">未结</span></div>
        ${d.detail.slice(0, 100).map(r => `<div class="erp-row">
          <span class="c-name">${esc(r.customer_name)}<div class="muted" style="font-size:11px">${esc(r.order_no)} · ${esc(r.order_date || '')}</div></span>
          <span class="c-spec" style="${r.overdue ? 'color:var(--danger);font-weight:600' : ''}">${r.days} 天</span>
          <span class="c-price" style="color:var(--danger)">${fmt(r.left)}</span></div>`).join('')}
      </div></div>` : '<div class="muted">暂无未结单</div>'}`;
  }
  async function rpFlow(body) {
    body.innerHTML = `<div class="card"><div class="section-title">收款流水</div>
      <div class="muted" style="font-size:12px;margin:6px 0 10px">每次收款一张单，可撤销（会同时把核销金额退回对应销售单）。</div>
      <div id="rp-tbl"><div class="muted">加载中…</div></div></div>`;
    const d = await API.req('GET', '/api/ar/payments');
    $('#rp-tbl').innerHTML = `
      <div class="row" style="gap:12px;margin-bottom:10px">
        <div class="card" style="flex:1;margin:0"><div class="muted" style="font-size:12px">累计实收</div><div style="font-size:18px;font-weight:700;color:var(--primary)">${fmt(d.total_in)}</div></div>
        <div class="card" style="flex:1;margin:0"><div class="muted" style="font-size:12px">累计优惠</div><div style="font-size:18px;font-weight:700">${fmt(d.total_discount)}</div></div>
      </div>
      ${d.rows.length ? d.rows.map(r => `<div class="ar-flow">
        <div class="top">
          <span class="nm">${esc(r.pay_no)} · ${esc(r.customer_name)}</span>
          <span><b style="color:var(--primary)">${fmt(r.amount)}</b>${r.discount ? ` <span class="muted">优惠${fmt(r.discount)}</span>` : ''}
          ${can('ar.edit') ? ` <button class="btn-sm gray" data-undo="${r.id}" style="height:26px;line-height:1">撤销</button>` : ''}</span>
        </div>
        <div class="meta">${esc(r.pay_date || '')} · ${esc(r.method || '')}${r.remark ? ' · ' + esc(r.remark) : ''}</div>
      </div>`).join('') : '<div class="muted">还没有收款记录</div>'}`;
    $$('#rp-tbl [data-undo]').forEach(b => b.onclick = async () => {
      if (!confirm('撤销这张收款单？对应销售单的已收金额会退回。')) return;
      try { await API.req('DELETE', '/api/ar/pay/' + b.dataset.undo); toast('已撤销'); rpFlow($('#rp-body')); }
      catch (e) { toast(e.message); }
    });
  }
  async function rpStock(body) {
    body.innerHTML = `<div class="card"><div class="section-title">库存预警</div>
      <div class="muted" style="font-size:12px;margin:6px 0 10px">库存 ≤ 最低库存的商品（商品管理里设「最低库存」才会进这里）。</div>
      <div id="rp-tbl"><div class="muted">加载中…</div></div></div>`;
    const d = await API.req('GET', '/api/report/stock-alert');
    $('#rp-tbl').innerHTML = d.length
      ? `<div class="erp-table"><div class="erp-scroll">
          <div class="erp-thead"><span class="c-name">商品</span><span class="c-spec">库存</span><span class="c-price">最低</span></div>
          ${d.map(p => `<div class="erp-row"><span class="c-name">${esc(p.name)}<div class="muted" style="font-size:11px">${esc(p.spec || '')}</div></span>
            <span class="c-spec" style="color:var(--danger);font-weight:700">${num2(p.qty)}</span>
            <span class="c-price">${num2(p.min_stock)}</span></div>`).join('')}
        </div></div>`
      : '<div class="muted">🎉 没有缺货商品</div>';
  }
  // 旧入口保留：订单页里的「月度分析」仍走原逻辑
  async function renderAnalytics() { return renderReports(); }
  async function loadAnalytics() {
    let q = '/api/analytics?range=' + AN.range;
    if (AN.range === 'custom') q += '&from=' + encodeURIComponent(AN.from) + '&to=' + encodeURIComponent(AN.to);
    try {
      const d = await API.req('GET', q);
      $('#an-range').textContent = '统计区间：' + d.from + ' ~ ' + d.to;
      $('#an-sales').textContent = moneyOf(d.sales);
      $('#an-profit').textContent = moneyOf(d.profit);
      $('#an-margin').textContent = pctOf(d.margin);
      $('#an-orders').textContent = d.orders + ' 单 / ' + num2(d.qty);
      const list = d.products || [];
      $('#an-body').innerHTML = list.length
        ? '<table class="tbl"><thead><tr><th>产品</th><th style="text-align:right">数量</th><th style="text-align:right">销售额</th><th style="text-align:right">利润</th><th style="text-align:right">利润率</th></tr></thead><tbody>'
          + list.map(p => `<tr><td>${esc(p.name)}</td><td style="text-align:right">${num2(p.qty)}</td><td style="text-align:right">${moneyOf(p.sales)}</td><td style="text-align:right">${moneyOf(p.profit)}</td><td style="text-align:right">${pctOf(p.margin)}</td></tr>`).join('')
          + '</tbody></table>'
        : '<div class="muted">该区间暂无销售</div>';
    } catch (e) { toast(e.message || '查询失败'); }
  }
  async function loadShipments(date) {
    try {
      const d = await API.req('GET', '/api/shipments/daily?date=' + encodeURIComponent(date));
      const box = $('#sh-body');
      if (!d.products.length || !d.stores.length) { box.innerHTML = '<div class="muted">当天无出货/退货</div>'; return; }
      const head = '<tr><th>门店</th>' + d.products.map(p => `<th style="text-align:right">${esc(p.name)}<div class="muted" style="font-size:11px">成本¥${num2(p.cost)}</div></th>`).join('') + '<th style="text-align:right">合计</th></tr>';
      const body = d.rows.map(r => {
        const cells = d.products.map(p => { const c = r.cells.find(x => x.product_id === p.id) || { qty: 0 }; return `<td style="text-align:right;${c.qty < 0 ? 'color:var(--danger)' : ''}">${num2(c.qty)}</td>`; }).join('');
        const tot = r.cells.reduce((s, c) => s + (c.qty || 0), 0);
        return `<tr><td>${esc(r.customer_name)}</td>${cells}<td style="text-align:right;font-weight:700">${num2(tot)}</td></tr>`;
      }).join('');
      box.innerHTML = `<div style="overflow:auto"><table class="tbl"><thead>${head}</thead><tbody>${body}</tbody></table></div><div class="muted" style="font-size:12px;margin-top:6px">负数=退货红冲；仅列出当天有出货/退货的门店与产品</div>`;
    } catch (e) { toast(e.message || '查询失败'); }
  }
  async function downloadXlsx(url, filename) {
    try {
      const res = await fetch(url, { headers: TOKEN ? { 'Authorization': 'Bearer ' + TOKEN } : {} });
      if (!res.ok) { toast('导出失败'); return; }
      const blob = await res.blob();
      const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = filename;
      document.body.appendChild(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(a.href), 4000);
      toast('已导出');
    } catch (e) { toast('导出失败'); }
  }

  // 今天的 YYYY-MM-DD（与 <input type="date"> 的 value 同格式）
  function todayISO() {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  }

  // ---------- 开单 ----------
  let custPrice = {};        // 当前门店的历史价：{product_id: price}
  let custItemDisc = {};     // 当前门店的单品折扣历史：{product_id: discount}（1 = 不打折）
  let custDiscount = 1;      // 当前门店上次整单折扣
  let custHasHistory = false;
  // 🔴 2026-10-19 经理：「客户不会记得所有产品」→ 商品区默认只显示 8 个太少了。
  //   这里记「是否已展开全部」。收起时优先展示该门店**历史下单过的商品**
  //   （客户开单八成就是重复买那几样，摆在最前面根本不用翻）。
  let saleAllOpen = false;
  // 🔴 2026-10-20 经理：「选择商品的界面太小了」→ 全量商品挪进弹窗，行内只留常用的前 8 项。
  //   弹窗内是**双列**网格（一个滚动容器包两列 → 天然同步滚动，不用写 JS 对齐 scrollTop）。
  let prodPickKw = '';

  // 🔴 2026-10-19 客户多门店：商品价按「当前选中门店」取，所以这里必须带 customer_id。
//    不带的话客户切了门店，加进购物车的还是**默认门店**的价格 —— 看着能下单，钱算错了。
  async function ensureSaleData(storeId) {
    allCustomers = await API.req('GET', '/api/customers');
    const q = (ME.role === 'customer' && storeId) ? ('?customer_id=' + storeId) : '';
    allProducts = await API.req('GET', '/api/products' + q);
  }

  // 取某商品在当前门店的默认价：①门店历史价 ②默认价体系 ③商品基础价
  function priceFor(pid) {
    if (custPrice[pid] != null && custPrice[pid] > 0) return { price: custPrice[pid], from: 'store' };
    const p = allProducts.find(x => x.id === pid);
    return { price: p ? (Number(p.price) || 0) : 0, from: 'base' };
  }
  // 该商品在这家门店上次用的单品折扣（没有就 1 = 不打折）
  function itemDiscFor(pid) {
    const d = Number(custItemDisc[pid]);
    return (isFinite(d) && d > 0) ? Math.min(1, Math.max(0.1, d)) : 1;
  }
  // 明细行的成交单价 = 单价 × 单品折扣
  function linePrice(it) {
    return +((Number(it.price) || 0) * discOf(it)).toFixed(2);
  }
  function discOf(it) {
    const d = Number(it.discount);
    return (isFinite(d) && d > 0) ? Math.min(1, Math.max(0.1, d)) : 1;
  }

  // ---------- 门店切换弹窗（2026-10-19 重做） ----------
  // 🔴🔴 原实现两个问题：
  //   ① 列表用 class="search-item"，但 style.css 里**根本没有这个 class**（grep 计数 0）
  //      → 渲染成裸文本行，一眼看不出这是可选的列表；
  //   ② 选中态靠店名前拼一个 '✅ ' emoji，字形在不同系统不一致、也不够醒目。
  // 现在换成真正的卡片：整块可点、当前项主色描边 + 右上角「当前」pill。
  // 提到模块作用域是为了让顶部入口卡片（#sale-store-card）直接绑定它 —— 入口本身就是它。
  async function openStoreSwitch() {
    const mine = allCustomers.filter(c => (ME.customerIds || []).map(Number).indexOf(Number(c.id)) >= 0);
    if (!mine.length) { toast('账号没有绑定门店'); return; }
    const cur = Number(saleState.customer_id);
    showModal('选择下单门店', `
      <div class="muted" style="font-size:12px;margin-bottom:10px">
        切换后，商品价格会按<b>该门店的历史送货价</b>重新带出。
      </div>
      <div class="store-switch-list">
        ${mine.map(c => {
          const on = Number(c.id) === cur;
          return `<div class="store-switch-item${on ? ' is-cur' : ''}" data-store="${c.id}" role="button" tabindex="0">
            <span class="store-switch-ico" aria-hidden="true">🏪</span>
            <span class="store-switch-name">${esc(c.name)}</span>
            ${on ? '<span class="store-switch-pill">当前</span>' : '<span class="store-switch-go">选择 ›</span>'}
          </div>`;
        }).join('')}
      </div>`);
    const pick = async el => {
      const id = Number(el.dataset.store);
      if (id === cur) { closeModal(); return; }
      if (saleState.items.length && !confirm('切换门店会清空已录入的商品（价格要按新店重算），继续？')) return;
      sessionStorage.setItem('sale_store', String(id));
      saleState.customer_id = id;
      const c = allCustomers.find(x => Number(x.id) === id);
      saleState.customer_name = c ? c.name : '';
      saleState.items = [];
      closeModal();
      renderSale();          // 重走一遍：ensureSaleData 会带 customer_id 重取商品价
    };
    document.querySelectorAll('[data-store]').forEach(el => {
      el.onclick = () => pick(el);
      el.onkeydown = e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); pick(el); } };
    });
  }

  async function loadCustPrices(customerId) {
    custPrice = {}; custDiscount = 1; custHasHistory = false;
    if (!customerId) return;
    try {
      const d = await API.req('GET', '/api/customer-prices?customer_id=' + customerId);
      custPrice = d.prices || {};
      custItemDisc = d.item_discounts || {};
      custDiscount = d.discount != null && d.discount > 0 ? d.discount : 1;
      custHasHistory = !!d.has_history;
    } catch (e) { /* 忽略 */ }
  }

  // ---------- 批量设单品折扣：只作用于「勾选行」 ----------
  // 🔴 这三个必须在 renderSale() 之外（模块作用域）定义：renderSaleItems() 每次重渲染
  //    都要读 allSel() 来同步「全选」复选框状态。定义在 renderSale() 内部会
  //    ReferenceError: allSel is not defined —— 单行勾选后整张明细区的交互全废。
  // 🔴 也绝不能「一键改所有行」：有折扣和原价必须能同在一张单里，
  //    否则业务员又会被迫拆单（拆单 = 整单折扣 = 单品折扣永远记不上，历史折扣功能形同虚设）。
  const selIdx = () => saleState.items.map((it, i) => (it.sel ? i : -1)).filter(i => i >= 0);
  const allSel = () => saleState.items.length > 0 && saleState.items.every(it => it.sel);
  function applyDisc(idxs, v, tip) {
    if (!idxs.length) { toast('请先勾选要打折的行（或勾「全选所有行」）'); return; }
    idxs.forEach(i => { saleState.items[i].discount = v; saleState.items[i].disc_from = 'manual'; });
    renderSaleItems();
    if (tip) toast(tip.replace('%n', idxs.length).replace('%v', v));
  }

  async function renderSale() {
    await ensureSaleData(ME.role === 'customer' ? Number(sessionStorage.getItem('sale_store') || 0) || ME.customerId : 0);
    // 🔴 客户账号：只给自己门店下销售单。锁定门店、禁退货单、禁批量导入/改折扣。
    const isCust = ME.role === 'customer';
    if (isCust) {
      saleDocType = 'sale';   // 没有 setReturnMode()，退货模式是直接改 saleDocType
      // 🔴 2026-10-19 多门店：当前门店存 sessionStorage，切门店不用重新登录。
      //    校验合法性 —— 手工改 localStorage 塞一个不属于他的门店 id，必须回落到默认门店。
      const allowed = (ME.customerIds && ME.customerIds.length) ? ME.customerIds.map(Number) : [Number(ME.customerId)].filter(Boolean);
      if (!allowed.length) {
        $('#page').innerHTML = '<div class="card muted" style="padding:16px">账号还没有绑定门店，请联系商家开通。</div>';
        return;
      }
      let cur = Number(sessionStorage.getItem('sale_store') || 0);
      if (allowed.indexOf(cur) < 0) cur = Number(ME.customerId) || allowed[0];
      sessionStorage.setItem('sale_store', String(cur));
      saleState.customer_id = cur;
      const c0 = allCustomers.find(x => Number(x.id) === cur);
      saleState.customer_name = c0 ? c0.name : '';
      // 🔴 必须在这里就把该门店的历史价装上：客户模式没有 <select>，
      //    也就永远不会触发 custSel.onchange → loadCustPrices。
      //    漏了这一步的现象是「门店名对、价格全是 0」。
      await loadCustPrices(cur);
      const di0 = $('#sale-discount'); if (di0) di0.value = 1;
    }
    const page = $('#page');
    const ret = isReturnMode();
    page.innerHTML = `
      ${(EDITTING || isCust) ? '' : `
      <div class="row" style="gap:8px;margin-bottom:10px" id="sale-mode">
        <button class="btn-sm${ret ? ' gray' : ''}" data-mtype="sale"
          style="flex:1 1 0;min-width:0;white-space:nowrap;height:42px;line-height:1">销售单</button>
        <button class="btn-sm${ret ? ' danger' : ' gray'}" data-mtype="return"
          style="flex:1 1 0;min-width:0;white-space:nowrap;height:42px;line-height:1">退货单</button>
      </div>
      ${ret ? `<div class="muted" style="font-size:12px;margin:-4px 0 10px">
        手工录入要退的商品即可，<b>无需指定原销售单</b>。数量按正数填写，系统自动记为负数并回补库存。
      </div>` : ''}`}
      <div class="sale-top">
        <div class="card sale-top-cell">
          <label>客户 / 门店</label>
          ${isCust
            ? (() => {
              // 🔴🔴 2026-10-19 经理两次反馈「就这样看下去 我都不知道原来门店可以选择的」。
              //   根因不是弹窗里的列表不好看，而是**顶部入口本身**是个灰底小按钮，
              //   长得跟旁边一堆说明文字一样，完全看不出这里能点。
              //   现在整块做成可点击的门店卡：店名大、当前状态明确、右侧显眼切换提示。
              const _multi = (ME.customerIds || []).length > 1;
              return `<div id="sale-store-card" class="store-now${_multi ? ' is-clickable' : ''}"${_multi ? ' role="button" tabindex="0" title="点击切换下单门店"' : ''}>
                  <span class="store-now-ico" aria-hidden="true">🏪</span>
                  <span class="store-now-main">
                    <span class="store-now-cap">当前下单门店</span>
                    <span class="store-now-name">${esc(saleState.customer_name || '—')}</span>
                  </span>
                  ${_multi ? '<span class="store-now-act">切换<span class="store-now-arrow">›</span></span>' : '<span class="store-now-lock">已锁定</span>'}
                </div>
                <div class="muted" style="font-size:12px;margin-top:6px">价格按<b>当前门店的历史送货价</b>带出${_multi ? '；点上方卡片可换门店' : ''}</div>`;
            })()
            : `<select id="sale-customer">
            <option value="">— 请选择 —</option>
            ${allCustomers.map(c => `<option value="${c.id}">${esc(c.name)}</option>`).join('')}
          </select>`}
          <div class="muted" id="sale-cust-hint" style="font-size:12px;margin-top:6px"></div>
        </div>
        <div class="card sale-top-cell">
          <label>下单日期</label>
          <input type="date" id="sale-date"${isCust ? ' readonly title="客户下单日期固定为当天，不能修改"' : ''}/>
          <div class="muted" style="font-size:12px;margin-top:6px">${isCust ? '客户下单日期<b>固定为当天</b>，不能修改' : '批量表格开单按此日期生成订单'}</div>
        </div>
        <div class="card sale-top-cell${isCust ? ' hidden' : ''}">
          <div class="row between" style="align-items:center">
            <span style="font-weight:600;font-size:13px">批量开单（表格）</span>
            <span class="muted" style="font-size:12px">行=产品 · 列=门店 · EA</span>
          </div>
          <div class="row" style="gap:8px;margin-top:8px">
            <button class="btn-sm gray" id="sale-tpl" style="flex:1 1 0;min-width:0;white-space:nowrap">📥 下载开单模板</button>
            <button class="btn-sm gray" id="sale-sync" style="flex:1 1 0;min-width:0;white-space:nowrap">🔄 同步模板</button>
            <button class="btn-sm gray" id="sale-imp" style="flex:1 1 0;min-width:0;white-space:nowrap">📤 导入表格开单</button>
          </div>
          <div class="muted" style="font-size:12px;margin-top:6px">模板只填数量，价格按各门店上次成交价自动带出</div>
          <input type="file" id="sale-file" accept=".xlsx,.xlsm" class="hidden"/>
          <input type="file" id="sale-sync-file" accept=".xlsx,.xlsm" class="hidden"/>
        </div>
      </div>
      <div class="card">
        <label>添加商品</label>
        <input id="sale-search" placeholder="模糊搜索：商品名 / 条码 / 规格（空格拆多关键词）" />
        <div id="sale-results" class="search-result" style="margin-top:8px"></div>
      </div>
      <div id="sale-items"></div>
      <div id="sale-quickdisc" class="card" style="margin-top:10px;display:none">
        <div class="row between" style="margin-bottom:6px">
          <label style="margin:0">批量设单品折扣</label>
          <label class="muted" style="font-weight:400;font-size:12px;display:flex;align-items:center;gap:5px;cursor:pointer">
            <input type="checkbox" id="qd-all" style="width:auto;margin:0"/> 全选所有行
          </label>
        </div>
        <div class="muted" style="font-size:12px;margin-bottom:6px">勾选要打折的商品 → 点折扣率。只对勾选行生效，其余行保持原价，可以同在一张单里。</div>
        <div class="row" style="gap:6px;flex-wrap:wrap">
          ${[0.98, 0.97, 0.95, 0.92, 0.9, 0.85].map(v => `<button class="btn-sm gray qd" data-qd="${v}">${v}</button>`).join('')}
          <input id="qd-custom" type="number" min="0.1" max="1" step="0.01" placeholder="自定义" style="width:88px"/>
          <button class="btn-sm gray" id="qd-apply">应用</button>
          <button class="btn-sm gray" id="qd-clear">勾选行恢复1</button>
        </div>
        <div class="row" style="gap:6px;margin-top:8px">
          <button class="btn-sm" id="qd-restore" style="flex:1">↺ 全部恢复该门店上次折扣</button>
          <button class="btn-sm gray" id="qd-selhist" style="flex:1">☑勾选上次有折扣的行</button>
        </div>
      </div>
      <div class="sale-total"><span>${isReturnMode() ? '应退合计' : '合计'}</span><span class="amt" id="sale-amt">¥0.00</span></div>
      <div class="card" style="margin-top:10px">
        <label>整单折扣（0.1 - 1，1 = 不打折）<span class="muted" style="font-weight:400">${isCust ? ' · <b style="color:var(--primary)">折扣由商家定，你不能修改</b>' : ' · 额外全单让利，单品折扣已含在每行小计里，两者会叠乘'}</span></label>
        <input id="sale-discount" type="number" min="0.1" max="1" step="0.01" value="1"${isCust ? ' readonly title="折扣由商家定，客户不能修改"' : ''}/>
        <div id="sale-disc-sum" class="muted" style="display:none;font-size:12px;margin-top:6px;color:#f5a524"></div>
        <div class="row between" style="margin-top:10px"><span>${isReturnMode() ? '折后应退' : '折后应付'}</span><span id="sale-pay" style="color:var(--danger);font-weight:700">¥0.00</span></div>
      </div>
      <button class="btn-primary${ret ? ' danger' : ''}" id="sale-submit" style="margin-top:12px">${ret ? '提交退货单' : '提交开单'}</button>`;
    const qdCard = $('#sale-quickdisc');
    if (qdCard && ME.role === 'customer') qdCard.classList.add('hidden');   // 折扣只能商家给
    $('#sale-mode') && $('#sale-mode').querySelectorAll('[data-mtype]').forEach(b => b.onclick = () => {
      const t = b.dataset.mtype;
      if (t === saleDocType) return;
      if (saleState.items.length && !confirm('切换单据类型会清空已录入的商品，确定切换？')) return;
      saleDocType = t;
      saleState = { customer_id: null, customer_name: '', items: [] };
      renderSale();
    });
    // 🔴 客户模式下 select 根本不渲染 → 直接赋值 onchange 会 TypeError 并打断后面所有绑定
    const custSel = $('#sale-customer');
    // 客户多门店：切换门店 = 换默认门店 + 重载该店价格 + 重载该店商品价
    // 🔴 门店入口卡片绑定打开选择弹窗（可点击时才绑，单店锁定态不给任何点击反馈）
    const swBtn = $('#sale-store-card');
    if (swBtn && swBtn.classList.contains('is-clickable')) {
      swBtn.onclick = openStoreSwitch;
      swBtn.onkeydown = e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openStoreSwitch(); } };
    }
    if (custSel) custSel.onchange = async e => {
      saleState.customer_id = e.target.value ? +e.target.value : null;
      const c = allCustomers.find(x => x.id == saleState.customer_id);
      saleState.customer_name = c ? c.name : '';
      await loadCustPrices(saleState.customer_id);
      // 带出上次折扣
      // 🔴 整单折扣默认 1：单品折扣已经逐行记历史了，若这里再自动带出整单折扣会叠乘
      //（0.97 × 0.97 = 0.9409），金额直接少 3%。整单折扣留给「全单让利」的场景手填。
      const di = $('#sale-discount');
      if (di) di.value = 1;
      // 已在购物车的商品改用该门店价 + 该门店上次用过的单品折扣
      // （只覆盖「跟风带出来」的行；手工改过单价/折扣的行保持用户填的值）
      saleState.items.forEach(it => {
        const r = priceFor(it.product_id);
        if (it.from !== 'manual') { it.price = r.price; it.from = r.from; }
        if (it.disc_from !== 'manual') { it.discount = itemDiscFor(it.product_id); it.disc_from = custItemDisc[it.product_id] != null ? 'store' : 'base'; }
      });
      showCustHint();
      searchProducts($('#sale-search') ? $('#sale-search').value : '');
      renderSaleItems();
      updateTotal();
    };
    $('#sale-search').oninput = debounce(e => { saleAllOpen = false; searchProducts(e.target.value); }, 200);
    $('#sale-discount').oninput = updateTotal;
    // 批量折扣按钮（selIdx / allSel / applyDisc 定义在 renderSale 外的模块作用域）
    document.querySelectorAll('.qd').forEach(b => b.onclick = () => applyDisc(selIdx(), parseFloat(b.dataset.qd), '已给 %n 行设为 %v'));
    const qdApply = $('#qd-apply');
    if (qdApply) qdApply.onclick = () => {
      const v = parseFloat(($('#qd-custom') || {}).value);
      if (!isFinite(v) || v <= 0 || v > 1) { toast('请输入 0.1 - 1 之间的折扣率'); return; }
      applyDisc(selIdx(), Math.min(1, Math.max(0.1, v)), '已给 %n 行设为 %v');
    };
    const qdClear = $('#qd-clear');
    if (qdClear) qdClear.onclick = () => applyDisc(selIdx(), 1, '已把 %n 行恢复原价');
    // 全选开关
    const qdAll = $('#qd-all');
    if (qdAll) qdAll.onchange = () => { saleState.items.forEach(it => it.sel = qdAll.checked); renderSaleItems(); };
    // 全部恢复该门店上次折扣：换门店后不用重填
    const qdRestore = $('#qd-restore');
    if (qdRestore) qdRestore.onclick = () => {
      if (!saleState.customer_id) { toast('请先选择门店'); return; }
      if (!Object.keys(custItemDisc).length) { toast('该门店暂无单品折扣历史'); return; }
      let n = 0;
      saleState.items.forEach(it => {
        if (custItemDisc[it.product_id] != null) {
          it.discount = itemDiscFor(it.product_id);
          it.disc_from = 'store'; n++;
        }
      });
      renderSaleItems();
      toast(n ? '已恢复 ' + n + ' 个商品的上次折扣' : '购物车里没有该门店有折扣历史的商品');
    };
    // 勾选「上次有折扣的行」：配合折扣率按钮，一步完成
    const qdSelHist = $('#qd-selhist');
    if (qdSelHist) qdSelHist.onclick = () => {
      let n = 0;
      saleState.items.forEach(it => { it.sel = custItemDisc[it.product_id] != null && itemDiscFor(it.product_id) < 1 - 1e-9; if (it.sel) n++; });
      renderSaleItems();
      toast(n ? '已勾选 ' + n + ' 个上次打折的商品' : '没有商品上次有折扣');
    };
    $('#sale-submit').onclick = submitSale;
    $('#sale-tpl').onclick = downloadOrderTemplate;
    $('#sale-imp').onclick = () => $('#sale-file').click();
    $('#sale-file').onchange = e => { if (e.target.files[0]) importOrderTable(e.target.files[0]); e.target.value = ''; };
    // 模板自动同步：上传手上那份已填好的旧表，自动补齐新商品/新门店
    $('#sale-sync').onclick = () => $('#sale-sync-file').click();
    $('#sale-sync-file').onchange = e => { if (e.target.files[0]) syncOrderTemplate(e.target.files[0]); e.target.value = ''; };
    $('#sale-date').value = todayISO();
    searchProducts('');
    showCustHint();
    renderSaleItems();
  }

  function showCustHint() {
    const el = $('#sale-cust-hint'); if (!el) return;
    if (isReturnMode()) {
      if (!saleState.customer_id) { el.textContent = '选择门店后按该门店上次成交价录入要退的商品'; el.style.color = ''; return; }
      const n = Object.keys(custPrice).length;
      el.innerHTML = n ? `已载入该门店历史价 <b>${n}</b> 项，按上次成交价录入退货商品`
                       : '该门店暂无历史价，退货单价请手工填写';
      el.style.color = n ? 'var(--primary)' : '';
      return;
    }
    if (!saleState.customer_id) { el.textContent = '选择门店后，商品将自动带出该门店上次成交价与折扣'; el.style.color = ''; return; }
    const n = Object.keys(custPrice).length;
    if (!custHasHistory) { el.textContent = '该门店暂无历史价，按标准价开单'; el.style.color = ''; return; }
    const nd = Object.values(custItemDisc).filter(v => Number(v) > 0 && Number(v) < 1 - 1e-9).length;
    el.innerHTML = `已载入该门店历史价 <b>${n}</b> 项${nd ? `、其中 <b style="color:#f5a524">${nd}</b> 项带折扣` : ''}，开单时自动带出（折扣商品与原价商品可同单）`;
    el.style.color = 'var(--primary)';
  }

  function searchProducts(kw) {
    kw = (kw || '').trim();
    const box = $('#sale-results'); if (!box) return;
    let list, hint = '';
    if (kw) {
      const hit = fuzzyFilter(allProducts, kw, p => [p.name, p.barcode, p.spec, p.unit]);
      hint = `<div class="muted" style="padding:2px 2px 6px;font-size:12px">模糊匹配 ${hit.length} 项，按相关度排序</div>`;
      list = hit.slice(0, 30);
    } else {
      // 🔴 历史优先：该门店有历史成交价的商品排前面（custPrice 就是它的记忆表）。
      //   「点击关键词」会清空搜索词 → 这里要回到默认视图，展开状态一并复位。
      const hist = [], rest = [];
      allProducts.forEach(p => { (custPrice[p.id] != null && custPrice[p.id] > 0 ? hist : rest).push(p); });
      list = hist.concat(rest);
      // 🔴 行内固定只显示前 8 项（历史优先重排后的前 8），全量走弹窗
      list = list.slice(0, 8);
      const nh = Math.min(hist.length, 8);
      if (nh) hint += `<div class="muted" style="padding:2px 2px 6px;font-size:12px">
        <b style="color:var(--primary)">常买的前 ${nh} 项</b>已按本店历史成交价排在最前（该店有历史价 ${hist.length} 项 / 全部 ${allProducts.length} 项）</div>`;
    }
    const total = allProducts.length;
    const shown = list.length;
    // 🔴 2026-10-20 经理改需求：不再是「展开全部」把行内拉长，而是**弹窗**浏览全量。
    //   理由（经理原话）：行内那块本来就窄，106 项铺开要滚很久 → 改成弹窗里双列滚动，省位置。
    //   行内保留历史前 8 项做「快速入口」，两个入口并存不冲突。
    const expander = (!kw && total > 0)
      ? `<div class="sale-expand">
           <button class="btn-sm" id="sale-browse-all"
             style="flex:1 1 0;min-width:0;white-space:nowrap;height:40px">
             浏览全部 ${total} 项商品（双列）
           </button>
         </div>`
      : '';
    box.innerHTML = hint
      + (list.length ? list.map(p => {
        const r = priceFor(p.id);
        const histTag = r.from === 'store' ? '<span style="color:var(--primary);font-size:11px;margin-left:4px">上次价</span>' : '';
        return `<div class="sale-item"><div class="info"><div class="nm">${highlight(p.name, kw)}${histTag}</div><div class="sub">${highlight(p.spec || '', kw)} · ¥${r.price}/${highlight(p.unit || '', kw)}</div></div><button class="btn-sm" data-add="${p.id}">+</button></div>`;
      }).join('') : '<div class="muted" style="padding:8px">无匹配商品（试试少输几个字，或用空格拆关键词）</div>')
      + (list.length && !kw ? `<div class="muted" style="padding:6px 2px 0;font-size:12px">显示 ${shown} / ${total} 项</div>` : '')
      + expander;
    box.querySelectorAll('[data-add]').forEach(b => b.onclick = () => addSaleItem(+b.dataset.add));
    const br = $('#sale-browse-all');
    if (br) br.onclick = openProductPicker;
  }

  // ---------- 商品选择弹窗（双列滚动，2026-10-20） ----------
  // 🔴 经理需求：「展开所有商品改成弹窗滚动，现在选择商品的界面太小了」
  //            「商品上下滚动能调成双列同时滚动吗，节约位置」
  //
  // 📌 为什么双列能"同步滚动"却不用写任何 JS：
  //     同一个滚动容器里直接放两列（CSS multi-column），浏览器本来就把内容按列排满再往下续。
  //     如果写成两个并排的独立滚动 div，就必须 JS 对齐 scrollTop（会抖、会有 1px 误差）——
  //     那是错的做法。这里用 column-count，同一个 scrollTop，天然同步。
  //
  // 🔴 关闭只用既有的 closeModal()，**绝不新增遮罩监听**（见 closeModal 上方的教训注释）。
  function openProductPicker() {
    prodPickKw = '';
    renderProductPicker();
    showModal(`选择商品（共 ${allProducts.length} 项）`, `
      <div class="pick-search">
        <input id="pick-search" placeholder="在全部商品里搜：商品名 / 条码 / 规格（空格拆多关键词）" value=""/>
      </div>
      <div id="pick-list" class="pick-grid"></div>
      <div class="pick-foot">
        <span class="muted" id="pick-count"></span>
        <button class="btn" id="pick-done">完成</button>
      </div>`);
    const si = $('#pick-search');
    if (si) {
      si.oninput = debounce(e => { prodPickKw = e.target.value || ''; renderProductPicker(); }, 200);
      setTimeout(() => { try { si.focus(); } catch (_) {} }, 60);
    }
    const dn = $('#pick-done');
    if (dn) dn.onclick = closeModal;
    renderProductPicker();
  }

  function renderProductPicker() {
    const box = $('#pick-list'); if (!box) return;
    const kw = (prodPickKw || '').trim();
    // 排序口径与行内一致：该店有历史成交价的排前面（客户最常买的就是这些）
    let list;
    if (kw) {
      list = fuzzyFilter(allProducts, kw, p => [p.name, p.barcode, p.spec, p.unit]).slice(0, 300);
    } else {
      const hist = [], rest = [];
      allProducts.forEach(p => { (custPrice[p.id] != null && custPrice[p.id] > 0 ? hist : rest).push(p); });
      list = hist.concat(rest);
    }
    const cnt = $('#pick-count');
    if (cnt) cnt.textContent = kw
      ? `匹配 ${list.length} 项 / 全部 ${allProducts.length} 项`
      : `共 ${allProducts.length} 项 · 该店有历史价 ${allProducts.filter(p => custPrice[p.id] > 0).length} 项（已排在前面）`;
    if (!list.length) {
      box.innerHTML = '<div class="muted" style="padding:14px;text-align:center">无匹配商品（试试少输几个字，或用空格拆关键词）</div>';
      return;
    }
    box.innerHTML = list.map(p => {
      const r = priceFor(p.id);
      const histTag = r.from === 'store' ? '<span class="pick-hist">上次价</span>' : '';
      return `<div class="pick-item" data-add="${p.id}" role="button" tabindex="0">
          <div class="pick-nm">${highlight(p.name, kw)}${histTag}</div>
          <div class="pick-sub">${highlight(p.spec || '', kw)} · ¥${r.price}/${highlight(p.unit || '', kw)}</div>
          <span class="pick-add" aria-hidden="true">+</span>
        </div>`;
    }).join('');
    // 点商品：加购但**不关弹窗** —— 选货场景要连续加多件，只有点「完成」才关
    box.querySelectorAll('[data-add]').forEach(el => {
      const add = () => addSaleItem(+el.dataset.add);
      el.onclick = add;
      el.onkeydown = e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); add(); } };
    });
  }

  function addSaleItem(pid) {
    const p = allProducts.find(x => x.id === pid); if (!p) return;
    const r = priceFor(pid);
    const ex = saleState.items.find(it => it.product_id === pid);
    if (ex) ex.qty += 1;
    else saleState.items.push({ product_id: p.id, name: p.name, spec: p.spec, unit: p.unit,
                                price: r.price, from: r.from, qty: 1, sel: false,
                                pack_size: Number(p.pack_size) || 0,
                                discount: itemDiscFor(pid), disc_from: custItemDisc[pid] != null ? 'store' : 'base' });
    renderSaleItems();
  }

  // 🔴 件数（= 数量 ÷ 每箱装量）。口径与桌面《开单模板》「合计（件数）」列完全一致：
  //   保留 2 位小数、**不进位**（36÷24=1.5、6÷12=0.5）；pack_size=0（未维护）→ 返回 null。
  //   ❌ 绝不能算成 0 —— 0 会被当成真实的「0 件」。
  function packCountOf(it, qty) {
    const ps = Number(it.pack_size) || 0;
    if (ps <= 0) return null;
    const v = (Number(qty) || 0) / ps;
    return Math.round(v * 100) / 100;
  }
  // 件数显示文本：整数不带小数点（1 而不是 1.0），小数最多 2 位
  function fmtPack(n) {
    if (n == null) return '';
    const r = Math.round(n * 100) / 100;
    return String(r);
  }

  function renderSaleItems() {
    const box = $('#sale-items'); if (!box) return;
    // 退货单不显示批量折扣区：退的是原成交价，按门店上次成交价带出即可，不该在这里改折扣
    const qd = $('#sale-quickdisc'); if (qd) qd.style.display = (!isReturnMode() && saleState.items.length) ? '' : 'none';
    if (!saleState.items.length) box.innerHTML = '<div class="muted" style="padding:10px">尚未添加商品</div>';
    else {
      // 🔴 客户模式：单价/折扣只读。勾选框也一并去掉 —— 它只服务于「批量设单品折扣」，
      //   而客户没有折扣区，留一个勾选框点了没反应反而让人以为能操作。
      const _roPrice = (ME.role === 'customer');
      box.innerHTML = saleState.items.map((it, i) => {
        const d = discOf(it);
        const pay = linePrice(it);
        const qtyN = Number(it.qty) || 0;
        const amt = +(pay * qtyN).toFixed(2);
        const thereAreDisc = d < 1 - 1e-9;
        // 🔴 2026-10-17 经理反馈「单品设置价格后没显示优惠金额」：
        //   每行显式算出「这一行让了多少钱」，直接印在小计下面，不用去汇总区猜。
        const lineSave = +(((Number(it.price) || 0) - pay) * qtyN).toFixed(2);
        // 🔴 件数（= 数量 ÷ 每箱装量），与桌面《开单模板》「合计（件数）」列同口径：
        //   保留 2 位小数不进位；箱规未维护（pack_size=0）→ 不显示（❌ 不能显示 0）。
        const packCnt = packCountOf(it, qtyN);
        const packTxt = packCnt == null ? '' : ` · <span class="pack">${fmtPack(packCnt)} 件</span>`;
        return `<div class="sale-item${thereAreDisc ? ' has-disc' : ''}">
          ${_roPrice ? '' : `<label class="sale-sel" title="勾选后可批量设折扣"><input type="checkbox" data-sel="${i}" ${it.sel ? 'checked' : ''}/></label>`}
          <div class="info">
            <div class="nm">${esc(it.name)}</div>
            <div class="sub">${esc(it.spec || '')} · ${esc(it.unit || '')}${it.from === 'store' ? ' · <span style="color:var(--primary)">上次价</span>' : ''}${thereAreDisc ? ` · <span style="color:#f5a524">${it.disc_from === 'store' ? '上次折扣' : '已打折'}</span>` : ''}${packTxt}</div>
          </div>
          <div class="fields">
            ${_roPrice
              // 🔴 2026-10-19 经理：「客户不能修改折扣及金额 但是可以显示」。
              //   只读 input（而不是删掉）：经理明确要求「可以显示」，客户要能看见自己付多少。
              //   🔴 这只是交互层 —— 服务端已对客户强制按 customer_prices 取价（server.js
              //      _forceStorePrice），改报文也改不动。这里加 readonly 只是不让用户白点。
              ? `<label class="fd is-ro"><span>单价</span><input type="number" value="${it.price}" readonly title="单价按本店历史价，不能修改"/></label>
                 <label class="fd is-ro"><span>折扣</span><input type="number" value="${d}" readonly title="折扣由商家定，不能修改"/>${thereAreDisc ? `<em class="save">省 ¥${lineSave.toFixed(2)}</em>` : ''}</label>`
              : `<label class="fd"><span>单价</span><input type="number" min="0" step="0.01" value="${it.price}" data-pi="${i}"/></label>
                 <label class="fd"><span>折扣</span><input type="number" min="0.1" max="1" step="0.01" value="${d}" data-di="${i}"/>${thereAreDisc ? `<em class="save">省 ¥${lineSave.toFixed(2)}</em>` : ''}</label>`}
            <label class="fd"><span>数量</span><input type="number" min="1" step="1" value="${it.qty}" data-i="${i}"/></label>
            <div class="fd line-amt"><span>小计</span><b class="${thereAreDisc ? 'is-disc' : ''}">${amt.toFixed(2)}</b></div>
            <button class="btn-sm danger" data-del="${i}">删</button>
          </div>
        </div>`;
      }).join('');
      // 勾选（只用于批量设折扣，不影响提交内容）
      box.querySelectorAll('input[data-sel]').forEach(inp => inp.onchange = e => {
        saleState.items[+e.target.dataset.sel].sel = e.target.checked;
        const a = $('#qd-all'); if (a) a.checked = allSel();
      });
      // 🔴 数量变化要整表重渲染：否则「件数」（= 数量 ÷ 每箱装量）不会跟着变。
      box.querySelectorAll('input[data-i]').forEach(inp => inp.onchange = e => { const i = +e.target.dataset.i; let v = parseFloat(e.target.value); if (!(v > 0)) v = 1; saleState.items[i].qty = v; e.target.value = v; renderSaleItems(); });
      box.querySelectorAll('input[data-pi]').forEach(inp => inp.onchange = e => { const i = +e.target.dataset.pi; let v = parseFloat(e.target.value); if (!(v >= 0)) v = 0; saleState.items[i].price = v; saleState.items[i].from = 'manual'; e.target.value = v; updateTotal(); });
      // 单品折扣：改完统一刷新（要同步「小计」和「上次折扣」标记）
      box.querySelectorAll('input[data-di]').forEach(inp => inp.onchange = e => {
        const i = +e.target.dataset.di; let v = parseFloat(e.target.value);
        if (!isFinite(v) || v <= 0) v = 1; v = Math.min(1, Math.max(0.1, v));
        saleState.items[i].discount = v; saleState.items[i].disc_from = 'manual';
        renderSaleItems();
      });
      box.querySelectorAll('[data-del]').forEach(b => b.onclick = () => { saleState.items.splice(+b.dataset.del, 1); renderSaleItems(); });
    }
    updateTotal();
  }

  // 合计 = Σ(单价 × 单品折扣 × 数量)，即已含单品折扣、未含整单折扣
  function saleTotal() {
    let t = 0, saved = 0, gross = 0;
    saleState.items.forEach(it => {
      const g = (Number(it.price) || 0) * (Number(it.qty) || 0);
      const p = linePrice(it) * (Number(it.qty) || 0);
      gross += g; t += p;
    });
    saved = +(gross - t).toFixed(2);
    // 🔴 gross 一并返回：汇总区要显示「折前合计 / 单品优惠 / 整单优惠」三笔账
    return { total: +t.toFixed(2), saved, gross: +gross.toFixed(2) };
  }

  function updateTotal() {
    const { total: t, saved, gross } = saleTotal();
    const el = $('#sale-amt'); if (el) el.textContent = fmt(t);
    const disc = saleDiscount();
    const pay = $('#sale-pay');
    const payV = +(t * disc).toFixed(2);
    if (pay) pay.textContent = fmt(payV);
    // 🔴 2026-10-17 经理反馈「单品设置价格后没显示优惠金额」。
    //   原来只有一句「单品折扣已省 ¥X」，整单让利那部分完全没显示，对不上账。
    //   现在把账一次摊开，让业务员能自己验算：
    //     折前合计 − 单品优惠 − 整单优惠 = 折后应付
    //   🔴 优惠合计必须用 gross − payV（相对折前）而不是 t − payV（相对折后），
    //   否则只有单品折扣、没有整单折扣时，合计会算成 0，看着像「一分钱没让」。
    const dz = $('#sale-disc-sum');
    if (dz) {
      const cutOrder = +(t - payV).toFixed(2);      // 整单让利（相对含单品折扣的合计）
      const cutTotal = +(gross - payV).toFixed(2);  // 总让利（相对折前合计）
      if (cutTotal > 0.005) {
        const seg = ['折前合计 ¥' + gross.toFixed(2)];
        if (saved > 0.005) seg.push('单品优惠 -¥' + saved.toFixed(2));
        if (cutOrder > 0.005) seg.push('整单优惠 -¥' + cutOrder.toFixed(2));
        seg.push('优惠合计 -¥' + cutTotal.toFixed(2));
        seg.push('折后应付 ¥' + payV.toFixed(2));
        dz.style.display = '';
        dz.innerHTML = seg.join(' · ');
      } else dz.style.display = 'none';
    }
  }

  function saleDiscount() {
    const inp = $('#sale-discount');
    if (!inp) return 1;
    const v = parseFloat(inp.value);
    if (!isFinite(v)) return 1;
    return Math.min(1, Math.max(0.1, v));
  }

  // 提交的明细：price 传折前单价、discount 传单品折扣，由后端算成交价
  function salePayload() {
    return saleState.items.map(it => ({
      product_id: it.product_id, qty: it.qty,
      price: Number(it.price) || 0, discount: discOf(it)
    }));
  }

  async function submitSale() {
    const ret = isReturnMode();
    if (!saleState.customer_id) { toast(ret ? '请先选择退货客户' : '请先选择客户'); return; }
    if (!saleState.items.length) { toast(ret ? '请添加退货商品' : '请添加商品'); return; }
    const discount = saleDiscount();
    const order_date = $('#sale-date') ? $('#sale-date').value : '';
    const btn = $('#sale-submit');
    if (!btn) { toast('提交按钮缺失，请刷新页面'); return; }
    btn.disabled = true; btn.textContent = '提交中…';
    try {
      let d;
      if (EDITTING) {
        d = await API.req('PUT', '/api/orders/' + EDITTING, { customer_id: saleState.customer_id, items: salePayload(), discount, order_date });
        toast('已保存修改');
        const oid = EDITTING; EDITTING = null;
        // 🔴 编辑退货单会锁到 return 模式，保存完必须复位，
        //    否则回到开单页还停在「退货单」，业务员以为销售单按钮坏了。
        saleDocType = 'sale';
        saleState = { customer_id: null, customer_name: '', items: [] };
        const sel = $('#sale-customer'); if (sel) sel.value = '';
        renderSale();
        await openConfirm(oid);
        return;
      }
      // 🔴 type:'return' → 服务端强制 status='returned'、金额取负、TH 单号、库存回补。
      //    退货不回补客户历史价（服务端已按 isReturn 跳过），避免退完把价/折扣改掉。
      // 🔴 客户提交：整单折扣前端强制传 1（服务端也会再强制一次，这里是第三道保险）。
      //    日期同理：客户不能改日期，前端一律传当天，服务端 _forceStorePrice 段还会覆盖成 ymdTodayISO()。
      d = await API.req('POST', '/api/orders', {
        customer_id: saleState.customer_id, items: salePayload(),
        discount: (ME.role === 'customer') ? 1 : discount,
        order_date: (ME.role === 'customer') ? todayISO() : order_date,
        status: ret ? null : 'paid', type: ret ? 'return' : null
      });
      toast(ret ? ('退货单已生成 ' + d.order_no) : ('开单成功 ' + d.order_no));
      await openConfirm(d.order_id);
      saleState = { customer_id: null, customer_name: '', items: [] };
      custPrice = {}; custItemDisc = {}; custDiscount = 1; custHasHistory = false;
      const sel = $('#sale-customer'); if (sel) sel.value = '';
      const discInp = $('#sale-discount'); if (discInp) discInp.value = '1';
      // 🔴 2026-10-19 客户账号：单交完要把当前门店和该店价格**重新装回来**。
      //    否则下一单既没有门店名（显示「—」）、也没有任何价格 → 加商品全是 0 元。
      if (ME.role === 'customer') {
        renderSale();
        return;
      }
      showCustHint();
      renderSaleItems();
      searchProducts($('#sale-search') ? $('#sale-search').value : '');
    } catch (e) { toast(e.message); }
    finally { btn.disabled = false; btn.textContent = EDITTING ? ('保存修改') : (isReturnMode() ? '提交退货单' : '提交开单'); }
  }

  // ---------- 开单模板 / 表格批量开单 ----------
  async function downloadOrderTemplate() {
    const btn = $('#sale-tpl');
    if (btn) { btn.disabled = true; btn.textContent = '下载中…'; }
    try {
      const opt = { method: 'GET', headers: {} };
      if (TOKEN) opt.headers['Authorization'] = 'Bearer ' + TOKEN;
      const r = await fetch(BASE + '/api/orders/template', opt);
      if (!r.ok) throw new Error('模板下载失败');
      const blob = await r.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = '开单模板.xlsx';
      document.body.appendChild(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      toast('开单模板已下载');
    } catch (e) { toast(e.message); }
    finally { if (btn) { btn.disabled = false; btn.textContent = '📥 下载开单模板'; } }
  }

  // 模板自动同步（2026-10-17）
  // 问题：管理员下载一次开单模板就一直复用，无警之后新增的商品不会自动长进去，
  //       每次开单前都要重新下载、把已填数量手工抄过去，容易漏抄/抄错。
  // 做法：上传旧表 → 服务端按数据库现状重建新表，旧数量照字段搬迁，
  //       新商品行/新门店列自动补齐；已删的商品/门店在提示里列出供人工确认。
  //       只搬数量、不动任何业务数据；开单仍走原条「导入表格开单」确认流程。
  async function syncOrderTemplate(file) {
    const btn = $('#sale-sync');
    if (btn) { btn.disabled = true; btn.textContent = '同步中…'; }
    try {
      const buf = await file.arrayBuffer();
      const opt = { method: 'POST', headers: {} };
      if (TOKEN) opt.headers['Authorization'] = 'Bearer ' + TOKEN;
      opt.headers['Content-Type'] = 'application/octet-stream';
      opt.body = buf;
      const r = await fetch(BASE + '/api/orders/template-sync', opt);
      if (!r.ok) {
        const e0 = await r.json().catch(() => ({}));
        throw new Error(e0.error || '同步失败');
      }
      let info = null;
      try { info = JSON.parse(decodeURIComponent(r.headers.get('X-TplSync') || 'null')); } catch (e) {}
      const blob = await r.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = '开单模板_已同步.xlsx';
      document.body.appendChild(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      const bits = [];
      if (info) {
        if (info.newProducts && info.newProducts.length) bits.push('新增商品 ' + info.newProducts.length + ' 个');
        if (info.newStores && info.newStores.length) bits.push('新增门店 ' + info.newStores.length + ' 个');
        if (info.deadProducts && info.deadProducts.length) bits.push('已删商品 ' + info.deadProducts.length + ' 个');
        if (info.deadStores && info.deadStores.length) bits.push('已删门店 ' + info.deadStores.length + ' 个');
      }
      toast('模板已同步并下载' + (bits.length ? '（' + bits.join('、') + '）' : '（无变化）'), 4000);
    } catch (e) { toast(e.message); }
    finally { if (btn) { btn.disabled = false; btn.textContent = '🔄 同步模板'; } }
  }
  async function importOrderTable(file) {
    const btn = $('#sale-imp');
    if (btn) { btn.disabled = true; btn.textContent = '解析中…'; }
    try {
      const buf = await file.arrayBuffer();
      const opt = { method: 'POST', headers: {} };
      if (TOKEN) opt.headers['Authorization'] = 'Bearer ' + TOKEN;
      opt.headers['Content-Type'] = 'application/octet-stream';
      opt.body = buf;
      const r = await fetch(BASE + '/api/orders/parse', opt);
      const d = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(d.error || '解析失败');
      showOrderImportPreview(d);
    } catch (e) { toast(e.message); }
    finally { if (btn) { btn.disabled = false; btn.textContent = '📤 导入表格开单'; } }
  }

  // 表格开单确认弹窗：门店/商品/数量/折扣都可改，确认后按门店逐单创建
  // 表格开单确认弹窗：门店/商品/数量可改，确认后按门店逐单创建
  // 🔴 2026-10-17 折扣口径修复：单品历史折扣按行带出，整单默认 1 不再自动打折。
  //    每张门店卡片缓存该门店的 item_discounts，切门店时重取。
  const IMP_PRICE_CACHE = {};   // cid -> {prices:{}, item_discounts:{}}
  async function impLoadStorePrice(cid) {
    if (!cid) return null;
    if (IMP_PRICE_CACHE[cid]) return IMP_PRICE_CACHE[cid];
    try {
      const d = await API.req('GET', '/api/customer-prices?customer_id=' + cid);
      IMP_PRICE_CACHE[cid] = { prices: d.prices || {}, item_discounts: d.item_discounts || {} };
    } catch (e) { IMP_PRICE_CACHE[cid] = { prices: {}, item_discounts: {} }; }
    return IMP_PRICE_CACHE[cid];
  }
  // 取该门店该商品的历史单品折扣（无历史 = 1 = 不打折）
  function impItemDisc(m, pid) {
    const v = Number(m && m[pid]);
    return (isFinite(v) && v > 0) ? Math.min(1, Math.max(0.1, v)) : 1;
  }
  function impCountDisc(m) {
    if (!m) return 0;
    return Object.keys(m).filter(k => Number(m[k]) < 1 - 1e-9).length;
  }

  // 把「历史单品折扣」写到每一行的折扣框（留空框 = 显示 placeholder，不覆盖人工填的值）
  function paintRowDisc(box, m) {
    box.querySelectorAll('.imp-item').forEach(row => {
      const t = row.querySelector('.imp-disc-tag');
      const rd = row.querySelector('.imp-rowdisc');
      const pid = +row.querySelector('.imp-prod').value;
      const d = impItemDisc(m && m.item_discounts, pid);
      const has = pid && d < 1 - 1e-9;
      if (t) {
        t.style.display = has ? 'inline' : 'none';
        t.textContent = has ? `上次折扣 ${d}` : '';
      }
      if (rd && rd.value === '') rd.placeholder = has ? `上次${d}` : '折扣';
    });
  }
  // 🔴 整单折扣框 → 一键改写本单所有行的单品折扣（覆盖，不是叠乘）
  function applyAllDisc(box) {
    const ad = box.querySelector('.imp-alldisc');
    if (!ad) return;
    const raw = String(ad.value).trim();
    if (raw === '') return;
    const v = Number(raw);
    // 🔴 越界必须拒绝并清空输入框，绝不能 clamp 后批量写入。
    //   写入前取当前行值（用于失败时回滚）。
    //   之前输入 3 被 Math.min(1,3)=1 截断后写入全部行 → 原本不打折的行被强行锁成 1。
    const before = Array.from(box.querySelectorAll('.imp-item')).map(r => {
      const x = r.querySelector('.imp-rowdisc'); return x ? x.value : '';
    });
    if (!(isFinite(v) && v > 0 && v <= 1)) {
      toast('折扣必须大于 0 且不超过 1（1 = 不打折），已忽略本次输入');
      ad.value = '';
      return;
    }
    const d = v;
    const rows = box.querySelectorAll('.imp-item');
    if (rows.length === 0) { ad.value = ''; return; }
    rows.forEach(row => {
      const rd = row.querySelector('.imp-rowdisc');
      if (rd) rd.value = d;
      const t = row.querySelector('.imp-disc-tag');
      if (t) { t.style.display = 'inline'; t.textContent = `本单统一 ${d}`; }
    });
    toast(`本单 ${rows.length} 个商品已统一按 ${d} 折扣`);
  }

  function showOrderImportPreview(data) {
    const prodOpts = pid => '<option value="">— 选择商品 —</option>' + allProducts.map(p =>
      `<option value="${p.id}"${p.id === pid ? ' selected' : ''}>${esc(p.name)}</option>`).join('');
    const custOpts = cid => '<option value="">— 不导入该门店 —</option>' + allCustomers.map(c =>
      `<option value="${c.id}"${c.id === cid ? ' selected' : ''}>${esc(c.name)}</option>`).join('');
    const tag = (ok, amb) => ok
      ? (amb ? '<span class="pill" style="background:#fff4e5;color:#c47f00">多个相似，请确认</span>' : '<span class="pill">已匹配</span>')
      : '<span class="pill" style="background:#ffeaea;color:var(--danger)">未匹配</span>';
    for (const k of Object.keys(IMP_PRICE_CACHE)) delete IMP_PRICE_CACHE[k];

    const cards = data.stores.map((s, si) => {
      const items = data.lines
        .map((l, li) => ({ l, li }))
        .filter(x => x.l.qtys[si] != null);
      if (!items.length) return '';
      return `<div class="card imp-store" data-header="${esc(s.header)}" style="padding:10px">
        <div class="row between">
          <span style="font-weight:600">${si + 1}. ${esc(s.header)}</span>${tag(s.customer_id, s.ambiguous)}
        </div>
        <label>对应客户 / 门店</label>
        <select class="imp-cust">${custOpts(s.customer_id)}</select>
        <div class="imp-hint" style="font-size:12px;color:var(--muted);margin-top:4px">正在读取该门店的历史价…</div>
        <div class="row" style="margin-top:6px;gap:8px">
          <span style="flex:1;font-size:13px;color:var(--muted)" title="填了会把本单所有商品的单品折扣统一改成这个值">
            一键设全部单品折扣<span class="muted" style="font-size:11px">（留空=各自按上次折扣）</span>
          </span>
          <input class="imp-alldisc" type="number" min="0.1" max="1" step="0.01" placeholder="留空"
            style="width:86px;padding:8px" title="填入后本单所有商品都按这个折扣打，不是叠乘"/>
        </div>
        <div class="imp-hint imp-disc-note" style="font-size:12px;color:var(--muted);margin-top:6px">
          上面的折扣会<b>直接改写每一行的单品折扣</b>，不是叠在单品折扣之上（不会双重打折）。
          想只改某一行，在该行右侧的「折扣」框单独填。
        </div>
        <div style="margin-top:8px">
          ${items.map(({ l }) => `<div class="sale-item imp-item" data-pid="${l.product_id || ''}">
            <div class="info">
              <select class="imp-prod">${prodOpts(l.product_id)}</select>
              <span class="imp-disc-tag" style="display:none;font-size:11px;color:#f5a524;margin-left:4px">上次折扣</span>
              ${l.product_id ? '' : `<div class="sub" style="color:var(--danger)">表中写的是「${esc(l.name)}」</div>`}
            </div>
            <input class="imp-qty" type="number" min="0" step="1" value="${l.qtys[si]}" title="数量"/>
            <input class="imp-rowdisc" type="number" min="0.1" max="1" step="0.01" placeholder="折扣" title="单品折扣（留空=按上次折扣）" style="width:66px;padding:6px;font-size:13px"/>
          </div>`).join('')}
        </div>
      </div>`;
    }).filter(Boolean).join('');

    const warnHtml = (data.warnings || []).map(w =>
      `<div class="muted" style="font-size:12px;padding:8px 10px;margin:0 0 8px;border-radius:8px;background:#fff4e5;color:#a86300;border:1px solid #ffd9a0">⚠ ${esc(w)}</div>`).join('');

    const html = `
      <div class="muted" style="font-size:12px;margin:4px 0 10px">
        识别到 <b>${data.summary.storeCount}</b> 个门店、<b>${data.summary.lineCount}</b> 个产品行；
        未匹配门店 <b>${data.summary.unmatchedStores}</b>、未匹配产品 <b>${data.summary.unmatchedProducts}</b>。
        单价不用填，<b>按各门店上次成交价自动带出</b>；有历史折扣的商品会标「上次折扣」，
        <b>只对该商品打折，原价商品保持原价</b>。
        <div style="margin-top:4px;color:var(--muted)">数量口径：<b>按门店列填写的单个数量（EA）原样导入</b>；
        模板里的「合计」和「合计（件数）」两列只作参考，<b>不参与订单数量</b>。</div>
      </div>
      ${warnHtml}
      ${cards || '<div class="muted" style="padding:12px">没有可导入的门店行</div>'}
      <div id="imp-result"></div>
      <div class="row" style="gap:8px;margin-top:10px">
        <button class="btn-sm gray" id="imp-cancel" style="flex:1">取消</button>
        <button class="btn-sm" id="imp-go" style="flex:2">生成各门店订单</button>
      </div>`;
    showModal('表格开单 · 确认', html);
    $('#imp-cancel').onclick = closeModal;
    $('#imp-go').onclick = createOrdersFromImport;

    // 异步把每张卡片的单品历史折扣打上标记
    document.querySelectorAll('#modal-body .imp-store').forEach(async box => {
      const paint = async () => {
        const cid = +box.querySelector('.imp-cust').value;
        const hint = box.querySelector('.imp-hint');
        const n = box.querySelectorAll('.imp-item').length;
        if (!cid) {
          if (hint) hint.textContent = '未选门店 → 不导入该门店';
          box.querySelectorAll('.imp-disc-tag').forEach(t => t.style.display = 'none');
          return;
        }
        if (hint) hint.textContent = '正在读取该门店的历史价…';
        const m = await impLoadStorePrice(cid);
        if (!box.isConnected) return;
        const nd = impCountDisc(m.item_discounts);
        if (hint) hint.innerHTML = nd
          ? `该门店有 <b style="color:#f5a524">${nd}</b> 个商品有历史折扣，本次只对这些商品打折`
          : '该门店无单品折扣历史，本次全部按原价';
        paintRowDisc(box, m);
        // 折扣框刷新统一走 paintRowDisc()
      };
      const ad = box.querySelector('.imp-alldisc');
      if (ad) {
        ad.addEventListener('input', () => applyAllDisc(box));
        ad.addEventListener('change', () => applyAllDisc(box));
      }
      box.querySelector('.imp-cust').addEventListener('change', paint);
      paint();
    });
  }

  async function createOrdersFromImport() {
    const boxes = Array.from(document.querySelectorAll('#modal-body .imp-store'));
    const jobs = [];
    for (const box of boxes) {
      const cid = +box.querySelector('.imp-cust').value;
      if (!cid) continue;
      // 🔴 本次开单**不再有整单折扣**：打扣只走单品历史折扣。
      const items = [];
      box.querySelectorAll('.imp-item').forEach(row => {
        const pid = +row.querySelector('.imp-prod').value;
        const qty = parseFloat(row.querySelector('.imp-qty').value);
        // 行内手工折扣：留空 = 走历史折扣；填了则以填的为准（1 = 不打折）
        const rd = row.querySelector('.imp-rowdisc');
        const rdv = rd && rd.value !== '' ? Number(rd.value) : null;
        if (pid && qty > 0) items.push({
          product_id: pid, qty,
          manual_discount: (rdv != null && isFinite(rdv) && rdv > 0) ? Math.min(1, Math.max(0.1, rdv)) : null
        });
      });
      if (items.length) jobs.push({ cid, items, header: box.dataset.header });
    }
    if (!jobs.length) { toast('没有可生成的门店订单（请先指定客户并确认数量）'); return; }

    const go = $('#imp-go'); if (go) { go.disabled = true; go.textContent = '生成中…'; }
    const order_date = $('#sale-date') ? $('#sale-date').value : '';
    const out = [];
    for (const j of jobs) {
      // ① 取该门店历史价：prices = 折前单价（铁律，绝不能当成交价），item_discounts = 单品折扣
      const m = await impLoadStorePrice(j.cid);
      const payload = j.items.map(it => {
        const fromStore = m.prices[it.product_id] != null && m.prices[it.product_id] > 0;
        const p = allProducts.find(x => x.id === it.product_id);
        // 🔴 price 必须是「折前单价」，discount 必须随行下发 —— 服务端 ×discount 才是成交价。
        //    不带 discount 会让折前单价被当成交价直接写死，历史折扣被提前吃掉一次。
        const id = m.item_discounts[it.product_id];
        const has = id != null && isFinite(Number(id)) && Number(id) > 0;
        // 行内手工折扣优先于历史折扣
        const md = it.manual_discount;
        const useManual = md != null && isFinite(md) && md > 0;
        return {
          product_id: it.product_id, qty: it.qty,
          price: fromStore ? m.prices[it.product_id] : (p ? Number(p.price) || 0 : 0),
          discount: useManual ? md : (has ? impItemDisc(m.item_discounts, it.product_id) : 1)
        };
      });
      // 🔴 整单折扣恒为 1：打扣全部由每行 items[].discount 逐行决定。
      try {
        const d = await API.req('POST', '/api/orders', {
          // 🔴 discount 恒为 1 —— 整单折扣已在 applyAllDisc 里改写进每行 items[].discount
          customer_id: j.cid, items: payload, discount: 1, order_date, remark: '网页表格开单'
        });
        out.push({ ok: true, label: j.header, order_no: d.order_no, payable: d.payable });
      } catch (e) { out.push({ ok: false, label: j.header, error: e.message }); }
    }
    const okCount = out.filter(x => x.ok).length;
    if (go) { go.disabled = false; go.textContent = '生成各门店订单'; }
    const box = $('#imp-result');
    if (box) box.innerHTML = `<div class="muted" style="margin:10px 0 6px">结果：成功 ${okCount} / ${out.length}</div>` +
      out.map(r => `<div class="list-item" style="padding:8px 10px;margin-bottom:6px">
        <div class="top"><span class="nm">${esc(r.label)}</span>
        <span>${r.ok ? esc(r.order_no || '') : '<span style="color:var(--danger)">失败</span>'}</span></div>
        ${r.ok ? `<div class="meta">应收 ${fmt(r.payable)}</div>` : `<div class="meta" style="color:var(--danger)">${esc(r.error)}</div>`}
      </div>`).join('');
    toast('表格开单完成：成功 ' + okCount + ' / ' + out.length);
    if (okCount) loadTopStat();
  }

  // ---------- 提交后确认页：改整单折扣 + 选打印模板 ----------
  const PRINT_TPLS = [
    { id: 'standard', name: '标准销售订单', title: '销售订单', footer: '谢谢惠顾，欢迎再次光临' },
    { id: 'delivery', name: '出货单', title: '出货单', footer: '谢谢惠顾' },
    { id: 'custom', name: '自定义模板（设置页）', title: '', footer: '' }
  ];
  async function openConfirm(orderId) {
    const d = await API.req('GET', '/api/orders/' + orderId);
    const o = d.order, items = d.items;
    const disc = o.discount != null ? o.discount : 1;
    const pay = o.payable != null ? o.payable : o.total;
    const itemLines = items.map(it => `<div style="display:flex;justify-content:space-between;padding:3px 0"><span>${esc(it.product_name)} ×${it.qty}${esc(it.unit || '')}</span><span>${fmt(it.subtotal)}</span></div>`).join('');
    showModal('开单成功 · ' + o.order_no, `
      <div class="meta">客户：${esc(o.customer_name || '')}</div>
      <div class="meta">合计：${fmt(o.total)}</div>
      <div style="border-top:1px solid #eee;margin:10px 0"></div>
      ${itemLines}
      <div style="border-top:1px solid #eee;margin:10px 0"></div>
      <label>整单折扣（0.1 - 1，1 = 不打折）</label>
      <input id="cf-discount" type="number" min="0.1" max="1" step="0.05" value="${disc}"/>
      <div class="row between" style="margin-top:10px"><span>折后应付</span><span id="cf-pay" style="color:var(--danger);font-weight:700">${fmt(pay)}</span></div>
      <label style="margin-top:12px">打印模板</label>
      <select id="cf-tpl">${PRINT_TPLS.map(t => `<option value="${t.id}">${esc(t.name)}</option>`).join('')}</select>
      <button class="btn-primary" id="cf-print" style="margin-top:14px">🖨️ 打印</button>
      <button class="btn-primary gray" id="cf-done" style="margin-top:8px;background:#999">完成（不打印）</button>`);
    const curPay = () => {
      const dv = Math.min(1, Math.max(0.1, parseFloat($('#cf-discount').value) || 1));
      $('#cf-pay').textContent = fmt(+(o.total * dv).toFixed(2));
      return dv;
    };
    $('#cf-discount').oninput = curPay;
    $('#cf-print').onclick = async () => {
      const dv = curPay();
      try { await API.req('PUT', '/api/orders/' + orderId, { discount: dv }); } catch (e) {}
      const t = PRINT_TPLS.find(x => x.id === $('#cf-tpl').value) || PRINT_TPLS[0];
      const tpl = { title: t.id === 'custom' ? TPL.receipt_title : t.title, footer: t.id === 'custom' ? TPL.receipt_footer : t.footer };
      closeModal();
      // 开单后第一次打单：先让用户选打印模板（经理 2026-10-15 要求）
      openReceipt(orderId, tpl, true);
    };
    $('#cf-done').onclick = async () => {
      const dv = curPay();
      try { await API.req('PUT', '/api/orders/' + orderId, { discount: dv }); } catch (e) {}
      closeModal();
    };
  }

  // ---------- 订单 ----------
  let ORDER_VIEW = 'list';
  let ORDER_MULTI = false;
  let ORDER_SEL = new Set();
  async function renderOrders() {
    const canAna = can('analysis.view');
    if (!canAna) ORDER_VIEW = 'list';
    $('#page').innerHTML = `
      <div class="row" style="gap:8px;margin-bottom:10px">
        <button class="btn-sm ${ORDER_VIEW === 'list' ? '' : 'gray'}" id="ov-list" style="flex:1">订单列表</button>
        ${canAna ? `<button class="btn-sm ${ORDER_VIEW === 'analysis' ? '' : 'gray'}" id="ov-ana" style="flex:1">📈 月度分析</button>` : ''}
      </div>
      <div id="order-body"></div>`;
    $('#ov-list').onclick = () => { ORDER_VIEW = 'list'; renderOrders(); };
    if ($('#ov-ana')) $('#ov-ana').onclick = () => { ORDER_VIEW = 'analysis'; renderOrders(); };
    if (ORDER_VIEW === 'analysis' && canAna) await renderAnalysis();
    else await renderOrderList();
  }
  async function renderOrderList() {
    const body = $('#order-body'); if (!body) return;
    body.innerHTML = `
      <div class="card">
        <div style="margin-bottom:8px"><label>关键词</label><input id="order-kw" placeholder="🔍 模糊搜索：订单号 / 门店 / 备注（空格拆多关键词）"/></div>
        <div class="row" style="gap:8px;flex-wrap:wrap">
          <div style="flex:1;min-width:130px"><label>开始日期</label><input type="date" id="order-from"/></div>
          <div style="flex:1;min-width:130px"><label>结束日期</label><input type="date" id="order-to"/></div>
        </div>
        <div class="row" style="gap:8px;flex-wrap:wrap;margin-top:8px">
          <div style="flex:1;min-width:110px"><label>金额下限</label><input type="number" id="order-min" min="0" step="0.01" placeholder="0"/></div>
          <div style="flex:1;min-width:110px"><label>金额上限</label><input type="number" id="order-max" min="0" step="0.01" placeholder="不限"/></div>
          <button class="btn-sm" id="order-filter" style="align-self:flex-end;margin-bottom:2px">筛选</button>
        </div>
        <div class="muted" style="font-size:12px;margin-top:6px">关键词支持模糊匹配（部分字、大小写、空格多关键词、顺序颠倒）；日期/金额留空 = 不限</div>
      </div>
      <div id="order-list"></div>`;
    const doFilter = () => loadOrders();
    $('#order-filter').onclick = doFilter;
    ['order-kw', 'order-from', 'order-to', 'order-min', 'order-max'].forEach(id => { const el = $('#' + id); if (el) el.addEventListener('keydown', e => { if (e.key === 'Enter') doFilter(); }); });
    if ($('#order-kw')) $('#order-kw').oninput = debounce(() => loadOrders(), 300);
    await loadOrders();
  }
  async function loadOrders() {
    const box = $('#order-list'); if (!box) return;
    const from = $('#order-from') ? $('#order-from').value : '';
    const to = $('#order-to') ? $('#order-to').value : '';
    const min = $('#order-min') ? $('#order-min').value : '';
    const max = $('#order-max') ? $('#order-max').value : '';
    const q = $('#order-kw') ? $('#order-kw').value.trim() : '';
    const qs = new URLSearchParams();
    if (from) qs.set('from', from);
    if (to) qs.set('to', to);
    if (min !== '') qs.set('min', min);
    if (max !== '') qs.set('max', max);
    if (q) qs.set('q', q);
    const raw = await API.req('GET', '/api/orders?' + qs.toString());
    // 服务端已按关键词粗筛；这里再按相关度精排（前缀/子串/子序列）
    const list = q ? fuzzyFilter(raw, q, o => [o.order_no, o.customer_name, o.remark]) : raw;
    const hint = q ? `<div class="muted" style="font-size:12px;margin:0 2px 8px">模糊匹配 ${list.length} 张订单</div>` : '';
    if (!list.length) { box.innerHTML = hint + '<div class="muted" style="padding:20px;text-align:center">' + (q ? '无匹配订单（试试少输几个字，或用空格拆关键词）' : '无符合条件的订单') + '</div>'; return; }
    const canDel = can('order.delete');
    box.innerHTML = hint + (canDel ? `<div class="row between" style="gap:8px;align-items:center;margin-bottom:8px;flex-wrap:wrap">
        <div class="row" style="gap:8px;align-items:center">
          <button class="btn-sm ${ORDER_MULTI ? '' : 'gray'}" id="ol-multi">${ORDER_MULTI ? '退出多选' : '多选'}</button>
          ${ORDER_MULTI ? '<button class="btn-sm gray" id="ol-all">全选/取消</button>' : ''}
          ${ORDER_MULTI ? `<span class="muted" id="ol-count">已选 ${ORDER_SEL.size}</span>` : ''}
        </div>
        ${ORDER_MULTI ? `<button class="btn-sm danger" id="ol-del"${ORDER_SEL.size ? '' : ' disabled'}>删除所选 (${ORDER_SEL.size})</button>` : ''}
      </div>` : '') + list.map(o => {
      const returned = o.status === 'returned';
      const title = (returned ? '销售退货 ' : '') + highlight(o.order_no, q);
      const amt = o.payable != null ? o.payable : o.total;
      const cl = returned ? 'low' : '';
      // 🔴 结算状态标记：挂账/部分/已结 —— 退货单不参与，挂「—」
      const st = returned ? '' : (amt > 0 && Number(o.settled) > 0.005
        ? (Number(o.settled) >= amt - 0.005 ? '<span class="ar-tag ok">已结</span>' : '<span class="ar-tag part">部分收</span>')
        : '<span class="ar-tag none">未收</span>');
      // 🔴 2026-10-19 经理：「客户的订单要能显示出来」→ 客户自己下的单打上「客户自助」标识。
      //   判据用 placed_by_role === 'customer'（服务端新列），**不能**用「有备注」之类的弱信号。
      //   老订单该列为 NULL → 不显示标识（不能回填成 staff，那等于把全部历史单标成商家单）。
      const fromCust = String(o.placed_by_role || '') === 'customer';
      const whoTag = fromCust
        ? `<span class="ord-tag cust" title="这张单是客户用下单账号自助提交的${o.placed_by_name ? '（' + esc(String(o.placed_by_name)) + '）' : ''}">客户自助</span>`
        : '';
      return `<div class="list-item${ORDER_MULTI ? ' multi' : ''}${ORDER_SEL.has(o.id) ? ' sel' : ''}${fromCust ? ' is-cust-order' : ''}" data-oid="${o.id}">
        ${ORDER_MULTI ? `<input type="checkbox" data-sel="${o.id}" ${ORDER_SEL.has(o.id) ? 'checked' : ''}/>` : ''}
        <div style="flex:1;min-width:0">
          <div class="top"><span class="nm ${cl}">${title}${whoTag}${st}</span><span style="color:var(--danger);font-weight:700">${fmt(amt)}</span></div>
          <div class="meta">${highlight(o.customer_name || '', q)} · ${esc(o.order_date || o.created_at)}${!returned && Number(o.settled) > 0.005 ? ` · 已收 ${fmt(o.settled)}` : ''}${fromCust && o.placed_by_name ? ` · 账号 ${esc(String(o.placed_by_name))}` : ''}</div>
        </div></div>`;
    }).join('');
    const refreshSel = () => {
      const c = $('#ol-count'); if (c) c.textContent = '已选 ' + ORDER_SEL.size;
      const d = $('#ol-del');
      if (d) { d.textContent = '删除所选 (' + ORDER_SEL.size + ')'; d.disabled = ORDER_SEL.size === 0; }
      box.querySelectorAll('[data-sel]').forEach(cb => cb.checked = ORDER_SEL.has(+cb.dataset.sel));
      box.querySelectorAll('[data-oid]').forEach(el => el.classList.toggle('sel', ORDER_SEL.has(+el.dataset.oid)));
    };
    if ($('#ol-multi')) $('#ol-multi').onclick = () => { ORDER_MULTI = !ORDER_MULTI; ORDER_SEL.clear(); loadOrders(); };
    if ($('#ol-all')) $('#ol-all').onclick = () => { if (ORDER_SEL.size === list.length) ORDER_SEL.clear(); else list.forEach(o => ORDER_SEL.add(o.id)); refreshSel(); };
    if ($('#ol-del')) $('#ol-del').onclick = () => batchDeleteOrders();
    box.querySelectorAll('[data-sel]').forEach(cb => cb.onclick = (e) => { e.stopPropagation(); const id = +cb.dataset.sel; if (cb.checked) ORDER_SEL.add(id); else ORDER_SEL.delete(id); refreshSel(); });
    box.querySelectorAll('[data-oid]').forEach(el => el.onclick = (e) => {
      if (ORDER_MULTI) { const id = +el.dataset.oid; if (ORDER_SEL.has(id)) ORDER_SEL.delete(id); else ORDER_SEL.add(id); refreshSel(); return; }
      viewOrder(+el.dataset.oid);
    });
  }
  async function viewOrder(id) {
    const d = await API.req('GET', '/api/orders/' + id);
    const o = d.order;
    const pay = o.payable != null ? o.payable : o.total;
    const disc = o.discount != null ? o.discount : 1;
    const returned = o.status === 'returned';
    // 预览/存图用的小票数据（与打印版式同源，见 receipt_preview.js）
    const receiptData = buildReceiptData(d, { title: returned ? '销售退货单' : '' });
    showModal((returned ? '销售退货 ' : '订单详情 ') + o.order_no, `
      <div class="meta">客户：${esc(o.customer_name || '')}</div>
      <div class="meta">下单日期：${esc(o.order_date || o.created_at)}</div>
      ${o.remark ? `<div class="meta">备注：${esc(o.remark)}</div>` : ''}
      ${!returned ? (() => {
        const due = Number(o.payable != null ? o.payable : o.total) || 0;
        const paid = Number(o.settled) || 0;
        const left = +(due - paid).toFixed(2);
        const ok = left <= 0.005;
        return `<div class="meta">结算：应收 <b>${fmt(due)}</b> · 已收 <b>${fmt(paid)}</b> · 未结
          <b style="color:${ok ? 'var(--primary)' : 'var(--danger)'}">${fmt(left)}</b></div>`;
      })() : ''}
      <div class="meta">合计：${fmt(o.total)}</div>
      ${disc != 1 ? `<div class="meta">折扣：${disc}</div>` : ''}
      ${pay != o.total ? `<div class="meta" style="color:var(--danger);font-weight:700">折后应付：${fmt(pay)}</div>` : ''}
      <div style="border-top:1px solid #eee;margin:10px 0"></div>
      ${d.items.map(it => {
        const od = Number(it.discount); const has = isFinite(od) && od > 0 && od < 1 - 1e-9;
        // 🔴 2026-10-21 经理反馈「订单预览没显示原价，跟打印出来的不一样」：
        //    这里补上「原价/成交价」两列，与小票（管家婆版式 左原价右特价）保持同口径。
        //    🔴 老单 orig_price 为 NULL → 兜回 price 兼作原价；绝不显示 0.00。
        const pr = Number(it.price) || 0;
        const op = (it.orig_price != null && Number(it.orig_price) > 0) ? Number(it.orig_price) : pr;
        const opTxt = (has && Math.abs(op - pr) > 0.005)
          ? `<s style="color:#999;margin-right:4px">${fmt(op)}</s>` : '';
        return `<div style="display:flex;justify-content:space-between;padding:3px 0">
          <span>${esc(it.product_name)} ×${it.qty}${esc(it.unit || '')}${has ? ` <span style="color:#f5a524;font-size:12px">单品${od}</span>` : ''}</span>
          <span>${opTxt}<span style="margin-right:8px">${fmt(pr)}</span><b>${fmt(it.subtotal)}</b></span></div>`;
      }).join('')}
      <button class="btn-primary" id="m-print" style="margin-top:14px">🖨️打印小票</button>
      <div class="row" style="gap:8px;margin-top:8px">
        <button class="btn-sm gray" id="m-preview" style="flex:1">📄 订单预览</button>
        <button class="btn-sm gray" id="m-saveimg" style="flex:1">💾 保存为图片</button>
      </div>
      <div class="row" style="gap:8px;margin-top:8px">
        ${can('order.edit') ? '<button class="btn-sm gray" id="m-edit" style="flex:1">修改订单</button>' : ''}
        ${can('sale.create') ? `<button class="btn-sm gray" id="m-copy" style="flex:1">${returned ? '复制为新退货单' : '复制为新开单'}</button>` : ''}
        ${can('order.return') && !returned ? '<button class="btn-sm danger" id="m-return" style="flex:1">整张退货</button>' : ''}
      </div>
      ${can('order.delete') ? '<button class="btn-sm danger" id="m-del" style="width:100%;margin-top:8px">删除订单</button>' : ''}`);
    // 订单页打印：同样先选模板（第二次打单也走同一条路径，版式永远一致）
    $('#m-print').onclick = () => { closeModal(); openReceipt(id, { title: returned ? '销售退货单' : '' }, true); };
    // 预览：浮层显示打印效果（含 EAN-13 条码图形），可直接打印或存图发客户
    $('#m-preview').onclick = () => { closeModal(); previewReceipt(receiptData); };
    $('#m-saveimg').onclick = () => { closeModal(); saveReceiptImage(receiptData); };
    if ($('#m-edit')) $('#m-edit').onclick = () => { closeModal(); editOrder(id); };
    if ($('#m-copy')) $('#m-copy').onclick = () => { closeModal(); copyToNewOrder(d); };
    if ($('#m-return')) $('#m-return').onclick = () => doReturnOrder(id);
    if ($('#m-del')) $('#m-del').onclick = () => deleteOrderOne(id, o.order_no);
  }
  // 复制为新开单
  async function copyToNewOrder(d) {
    await ensureSaleData();
    const o = d.order;
    // 🔴 2026-10-29：复制「退货单」必须原样复制成退货单。
    //   退货单明细 qty 是负数 → 直接复制会被服务端 Math.abs 取正 → 复制出一张正数销售单（账目出错）；
    //   saleDocType 也必须锁到 'return'，否则提交时 type=null → 生成 XS 销售单而不是 TH 退货单。
    //   判据与 editOrder 一致：status==='returned' 或 total<0。
    const editingReturn = o.status === 'returned' || Number(o.total) < 0;
    saleDocType = editingReturn ? 'return' : 'sale';
    saleState.customer_id = o.customer_id;
    saleState.customer_name = o.customer_name;
    // 🔴 订单明细里没有 pack_size 快照（快照只存名称/条码/规格）→ 从商品表回查，否则复制后件数空白
    saleState.items = d.items.map(it => {
      const pr = allProducts.find(x => x.id === it.product_id);
      // 单品折扣：原单有 orig_price 说明是带单品折扣开出来的 → 回填折前单价 + 折扣率
      const op = Number(it.orig_price);
      const od = Number(it.discount);
      const pr0 = Math.abs(Number(it.price) || 0);
      // 🔴 只有「折前价 × 折扣 == 实际成交价」时才敢带 discount。
      //   老单的 orig_price 可能为 NULL → 若仍带 discount，服务端会拿「成交价」再乘一次折扣 = 二次打折。
      const hasDisc = isFinite(od) && od > 0 && od < 1 - 1e-9
        && isFinite(op) && op > 0 && Math.abs(op * od - pr0) < 0.01;
      const base = hasDisc ? op : pr0;
      return {
        product_id: it.product_id, name: it.product_name, spec: it.spec, unit: it.unit,
        price: base, from: 'store',
        qty: Math.abs(Number(it.qty) || 0),        // 🔴 退货单 qty 是负数 → 复制时取绝对值
        pack_size: Number(pr && pr.pack_size) || 0,
        discount: hasDisc ? od : 1, disc_from: hasDisc ? 'manual' : 'base'
      };
    });
    await loadCustPrices(o.customer_id);
    navigate('sale').then(() => {
      const sel = $('#sale-customer'); if (sel) sel.value = o.customer_id || '';
      showCustHint();
      renderSaleItems();
      toast(editingReturn ? ('已复制到退货单页（' + o.order_no + '），可修改后提交') : '已复制到开单页，可修改后提交');
    });
  }
  // 修改订单（复用开单界面：编辑模式）
  let EDITTING = null;
  async function editOrder(id) {
    const d = await API.req('GET', '/api/orders/' + id);
    await ensureSaleData();
    const o = d.order;
    EDITTING = id;
    // 🔴 编辑「退货单」必须锁到退货模式：否则界面显示「应退 xxx」而不是「应收 xxx」，
    //    提交也拿不到 type='return'（虽然服务端 PUT 仍按 status 锁定 returned）。
    //    先判断再改 saleDocType，然后 renderSale() 才会渲染对的口径。
    const editingReturn = o.status === 'returned' || Number(o.total) < 0;
    saleDocType = editingReturn ? 'return' : 'sale';
    saleState.customer_id = o.customer_id;
    saleState.customer_name = o.customer_name;
    // 退货单的 qty 是负数，编辑时要还原成正数方便改；服务端按 status 重新取负
    // 有 orig_price 的记录说明是带单品折扣开出来的：回填折前单价 + 折扣率，
    // 否则按老数据兼容（price 就是成交价、折扣 1）
    saleState.items = d.items.map(it => {
      const op = Number(it.orig_price);
      const od = Number(it.discount);
      const pr0 = Math.abs(Number(it.price) || 0);
      // 🔴 只有「折前价 × 折扣 == 实际成交价」时才敢带 discount。
      //   orig_price 为 NULL 的老单若仍带 discount → 服务端拿「成交价」再乘一次折扣 = 二次打折。
      const hasDisc = isFinite(od) && od > 0 && od < 1 - 1e-9
        && isFinite(op) && op > 0 && Math.abs(op * od - pr0) < 0.01;
      const base = hasDisc ? op : pr0;
      const q = editingReturn ? Math.abs(Number(it.qty) || 0) : Number(it.qty);
      // 🔴 订单明细没有 pack_size 快照 → 从商品表回查（否则编辑老单时件数空白）
      const pr = allProducts.find(x => x.id === it.product_id);
      return {
        product_id: it.product_id, name: it.product_name, spec: it.spec, unit: it.unit,
        price: base, from: 'manual', qty: q,
        pack_size: Number(pr && pr.pack_size) || 0,
        discount: hasDisc ? od : 1, disc_from: hasDisc ? 'manual' : 'base'
      };
    });
    await loadCustPrices(o.customer_id);
    await renderSale();
    const sel = $('#sale-customer'); if (sel) sel.value = o.customer_id || '';
    if ($('#sale-date')) $('#sale-date').value = (o.order_date || o.created_at || '').slice(0, 10);
    if ($('#sale-discount')) $('#sale-discount').value = (o.discount != null ? o.discount : 1);
    if ($('#sale-submit')) { $('#sale-submit').textContent = '保存修改（' + o.order_no + '）'; }
    showCustHint();
    renderSaleItems();
    toast('修改中：' + o.order_no);
  }
  // 销售退货（整张）
  async function doReturnOrder(id) {
    if (!confirm('确认为该订单生成退货单？（库存将回补，金额取负）')) return;
    try {
      const r = await API.req('POST', '/api/orders/' + id + '/return');
      toast('已生成 ' + r.order_no);
      closeModal();
      loadOrders();
    } catch (e) { toast(e.message); }
  }
  // 删除单张订单
  async function deleteOrderOne(id, no) {
    if (!confirm('确认删除订单 ' + (no || '') + '？库存会自动回补，操作不可恢复。')) return;
    try {
      await API.req('DELETE', '/api/orders/' + id);
      toast('已删除');
      closeModal();
      ORDER_SEL.delete(id);
      loadOrders();
    } catch (e) { toast(e.message); }
  }
  // 批量删除所选订单
  async function batchDeleteOrders() {
    const ids = Array.from(ORDER_SEL);
    if (!ids.length) { toast('未选择订单'); return; }
    if (!confirm('确认删除所选 ' + ids.length + ' 张订单？库存会自动回补，操作不可恢复。')) return;
    try {
      const r = await API.req('POST', '/api/orders/delete-batch', { ids });
      const skip = (r.skipped && r.skipped.length) ? '，跳过 ' + r.skipped.length + ' 张（如已退货）' : '';
      toast('已删除 ' + r.deleted + ' 张' + skip);
      ORDER_SEL.clear();
      loadOrders();
    } catch (e) { toast(e.message); }
  }
  // 月度分析
  async function renderAnalysis() {
    const body = $('#order-body'); if (!body) return;
    const rows = await API.req('GET', '/api/analysis/monthly');
    if (!rows.length) { body.innerHTML = '<div class="muted" style="padding:20px;text-align:center">暂无数据</div>'; return; }
    const sumSales = rows.reduce((a, r) => a + (r.sales || 0), 0);
    const sumProfit = rows.reduce((a, r) => a + (r.profit || 0), 0);
    const sumQty = rows.reduce((a, r) => a + (r.qty || 0), 0);
    body.innerHTML = `
      <div class="card">
        <div class="row between"><span class="muted">累计销售额</span><span style="font-weight:700;color:var(--danger)">${fmt(sumSales)}</span></div>
        <div class="row between" style="margin-top:6px"><span class="muted">累计销量</span><span style="font-weight:700">${sumQty}</span></div>
        <div class="row between" style="margin-top:6px"><span class="muted">累计利润</span><span style="font-weight:700;color:var(--danger)">${fmt(sumProfit)}</span></div>
      </div>
      <div class="erp-table"><div class="erp-scroll">
        <div class="erp-thead"><span class="c-name">月份</span><span class="c-spec">单数</span><span class="c-qty">销量</span><span class="c-price">销售额</span><span class="c-price">利润</span></div>
        ${rows.map(r => `<div class="erp-row"><span class="c-name">${esc(r.month || '-')}</span><span class="c-spec">${r.orders || 0}</span><span class="c-qty">${r.qty || 0}</span><span class="c-price">${fmt(r.sales)}</span><span class="c-price" style="color:var(--danger)">${fmt(r.profit)}</span></div>`).join('')}
      </div></div>
      <div class="muted" style="font-size:12px;margin-top:8px">利润 = Σ(售价 − 进价) × 数量；退货单自动抵减。未填进价的商品按 0 计。</div>`;
  }

  // ---------- 应收应付：收款单（参照管家婆「挂账 + 收款核销」） ----------
  // 🔴 口径：开销售单默认挂账；收到钱另开收款单，自动分配到未结清单（最早优先）。
  //    抹零/让利走「优惠金额」字段单独记账，**绝不改订单金额**。
  let AR_CUSTOMERS = [];

  // 收款弹窗：选门店 → 自动列未结单 → 填金额 → 自动分配 → 核销
  async function openPayDialog(cid, onDone) {
    const list = AR_CUSTOMERS.length ? AR_CUSTOMERS : (AR_CUSTOMERS = await API.req('GET', '/api/customers'));
    if (!list.length) { toast('还没有客户档案'); return; }
    const opts = list.map(c => `<option value="${c.id}"${c.id == cid ? ' selected' : ''}>${esc(c.name)}</option>`).join('');
    showModal('收款单', `
      <div class="muted" style="font-size:12px;margin-bottom:10px">
        挂账的销售单会列出在这。选门店 → 填今天收到的钱 → 点「自动分配」按最早的单开始结清。
      </div>
      <div class="row" style="gap:8px;align-items:flex-end">
        <div style="flex:1 1 0;min-width:0">
          <label>付款单位</label>
          <select id="ap-cust">${opts}</select>
        </div>
      </div>
      <div class="row between" style="margin-top:10px;padding:10px;background:var(--color-background-secondary);border-radius:8px">
        <span class="muted">该门店欠款合计</span>
        <span id="ap-debt" style="font-weight:700;color:var(--danger)">—</span>
      </div>
      <div id="ap-orders" style="margin-top:10px;border:1px solid var(--border);border-radius:8px;overflow:hidden"></div>
      <div class="row" style="gap:8px;margin-top:10px">
        <div style="flex:1 1 0;min-width:0"><label>实收金额</label><input type="number" id="ap-amount" min="0" step="0.01" placeholder="0.00"/></div>
        <div style="flex:1 1 0;min-width:0"><label>优惠/抹零</label><input type="number" id="ap-disc" min="0" step="0.01" placeholder="0.00"/></div>
      </div>
      <div class="row" style="gap:8px;margin-top:8px;align-items:flex-end">
        <div style="flex:1 1 0;min-width:0"><label>收款日期</label><input type="date" id="ap-date"/></div>
        <div style="flex:1 1 0;min-width:0"><label>收款方式</label>
          <select id="ap-method">
            <option>现金</option><option>微信</option><option>支付宝</option>
            <option>银行转账</option><option>其他</option>
          </select></div>
      </div>
      <div style="margin-top:8px"><label>备注</label><input id="ap-remark" placeholder="选填"/></div>
      <div class="row" style="gap:8px;margin-top:12px">
        <button class="btn-sm gray" id="ap-cancel" style="flex:1 1 0;min-width:0;white-space:nowrap">取消</button>
        <button class="btn-primary" id="ap-ok" style="flex:1 1 0;min-width:0;white-space:nowrap">确认收款</button>
      </div>`);

    const d0 = new Date();
    $('#ap-date').value = d0.getFullYear() + '-' + String(d0.getMonth() + 1).padStart(2, '0') + '-' + String(d0.getDate()).padStart(2, '0');

    let US = [];              // 当前未结清单
    let alloc = {};           // { order_id: {amount, discount} }

    // 管家婆逻辑：自动分配 = 从最早那张单开始结清，直到分完
    function autoAlloc() {
      const total = (parseFloat($('#ap-amount').value) || 0) + (parseFloat($('#ap-disc').value) || 0);
      alloc = {};
      if (!(total > 0)) { paintAlloc(); return; }
      let left = total;
      for (const o of US) {
        if (left <= 0.005) break;
        const use = +Math.min(left, o.left).toFixed(2);
        if (use <= 0.005) continue;
        alloc[o.order_id] = { amount: use, discount: 0 };
        left = +left.toFixed(2) - use;
      }
      paintAlloc();
    }
    function paintAlloc() {
      US.forEach(o => {
        const a = alloc[o.order_id];
        const amtIn = $('#ap-ai-' + o.order_id), diIn = $('#ap-di-' + o.order_id);
        if (amtIn) amtIn.value = a ? a.amount : '';
        if (diIn) diIn.value = (a && a.discount) ? a.discount : '';
      });
    }
    function recalcTotal() {
      let a = 0, d = 0;
      US.forEach(o => {
        const x = alloc[o.order_id] || { amount: 0, discount: 0 };
        a += Number(x.amount) || 0; d += Number(x.discount) || 0;
      });
      const want = (parseFloat($('#ap-amount').value) || 0) + (parseFloat($('#ap-disc').value) || 0);
      const gap = +(want - a - d).toFixed(2);
      const tip = $('#ap-gap');
      if (tip) {
        if (Math.abs(gap) < 0.005) { tip.textContent = '✓ 已分配 ' + (a + d).toFixed(2); tip.style.color = 'var(--primary)'; }
        else if (gap > 0) { tip.textContent = '还差 ' + gap.toFixed(2) + ' 未分配'; tip.style.color = 'var(--danger)'; }
        else { tip.textContent = '超额分配 ' + Math.abs(gap).toFixed(2); tip.style.color = 'var(--danger)'; }
      }
    }

    async function loadUnsettled() {
      const cid2 = +$('#ap-cust').value;
      $('#ap-orders').innerHTML = '<div class="muted" style="padding:12px;text-align:center">加载中…</div>';
      $('#ap-debt').textContent = '—';
      let r;
      try { r = await API.req('GET', '/api/ar/unsettled?customer_id=' + cid2); }
      catch (e) { $('#ap-orders').innerHTML = '<div class="muted" style="padding:12px;color:var(--danger)">' + esc(e.message) + '</div>'; return; }
      US = r.orders || []; alloc = {};
      // 🔴 2026-10-18：改用服务端 debt（含退货冲减），别用 total_left，
      //    否则同一个门店在收款弹窗 / 客户页 / 报表页会显示三个不同的欠款数字。
      const _rc = Number(r.return_credit) || 0;
      $('#ap-debt').textContent = fmt(r.debt != null ? r.debt : (r.total_left || 0))
        + '（' + US.length + ' 单未结' + (_rc ? ' · 已扣退货 ' + fmt(Math.abs(_rc)) : '') + '）';
      if (!US.length) {
        $('#ap-orders').innerHTML = '<div class="muted" style="padding:14px;text-align:center">该门店没有未结清单 🎉</div>';
        recalcTotal(); return;
      }
      $('#ap-orders').innerHTML =
        `<div class="ret-line" style="background:var(--color-background-secondary);font-weight:500;font-size:12px">
           <span style="flex:1">单号 / 日期</span><span style="width:74px;text-align:right">未结金额</span>
           <span style="width:74px;text-align:right">本次收款</span><span style="width:62px;text-align:right">优惠</span>
         </div>` +
        US.map(o => `<div class="ret-line${o.overdue ? ' is-overdue' : ''}">
          <div style="flex:1;min-width:0">
            <div style="font-size:12px;font-weight:500">${esc(o.order_no)}${o.overdue ? ' <span style="color:var(--danger);font-size:11px">超期' + o.days + '天</span>' : ''}</div>
            <div class="muted" style="font-size:11px">${esc(o.order_date || '')} · 应收 ${fmt(o.due)} · 已收 ${fmt(o.paid)}</div>
          </div>
          <span style="width:74px;text-align:right;font-size:12px;color:var(--danger);flex:none">${fmt(o.left)}</span>
          <input type="number" min="0" step="0.01" id="ap-ai-${o.order_id}" data-o="${o.order_id}" placeholder="0.00" style="width:74px;text-align:right;flex:none"/>
          <input type="number" min="0" step="0.01" id="ap-di-${o.order_id}" data-o="${o.order_id}" placeholder="0" style="width:62px;text-align:right;flex:none"/>
        </div>`).join('');

      US.forEach(o => {
        const ai = $('#ap-ai-' + o.order_id), di = $('#ap-di-' + o.order_id);
        const cl = () => {
          let a = parseFloat(ai.value) || 0, d = parseFloat(di.value) || 0;
          if (a < 0) a = 0; if (d < 0) d = 0;
          if (a + d > o.left + 0.005) {
            const over = a + d - o.left;
            toast(o.order_no + ' 最多可结 ' + o.left.toFixed(2) + '，已自动调整');
            if (a >= o.left) { a = +o.left.toFixed(2); d = 0; } else { d = +(o.left - a).toFixed(2); }
            ai.value = a; di.value = d;
          }
          alloc[o.order_id] = { amount: +a.toFixed(2), discount: +d.toFixed(2) };
          recalcTotal();
        };
        ai.oninput = cl; di.oninput = cl;
      });
      recalcTotal();
      autoAlloc();
    }

    $('#ap-cust').onchange = loadUnsettled;
    $('#ap-amount').oninput = autoAlloc;
    $('#ap-disc').oninput = autoAlloc;
    $('#ap-cancel').onclick = closeModal;
    $('#ap-ok').onclick = async () => {
      const items = Object.keys(alloc)
        .map(id => ({ order_id: +id, amount: alloc[id].amount, discount: alloc[id].discount }))
        .filter(x => (x.amount + x.discount) > 0.005);
      if (!items.length) { toast('请至少给一张单分配收款金额'); return; }
      const amount = +items.reduce((a, x) => a + x.amount, 0).toFixed(2);
      const disc = +items.reduce((a, x) => a + x.discount, 0).toFixed(2);
      if (amount <= 0 && disc <= 0) { toast('金额全为 0'); return; }
      const btn = $('#ap-ok');
      if (!btn) { toast('确认按钮缺失，请关闭弹窗后重试'); return; }
      btn.disabled = true; btn.textContent = '提交中…';
      try {
        const r = await API.req('POST', '/api/ar/pay', {
          customer_id: +$('#ap-cust').value,
          amount, discount: disc,
          method: $('#ap-method').value,
          pay_date: $('#ap-date').value,
          remark: $('#ap-remark').value,
          items,
        });
        closeModal();
        toast('收款单 ' + r.pay_no + ' 已入账，还剩 ' + r.unsettled_left + ' 单未结');
        if (onDone) onDone();
      } catch (e) { toast(e.message); btn.disabled = false; btn.textContent = '确认收款'; }
    };

    // 分配差额提示条（插在按钮上方）
    const gap = document.createElement('div');
    gap.className = 'muted';
    gap.style.cssText = 'font-size:12px;margin-top:8px;text-align:right';
    gap.id = 'ap-gap';
    $('#ap-ok').parentElement.parentElement.insertBefore(gap, $('#ap-ok').parentElement);

    loadUnsettled();
  }

  // ---------- 客户 ----------
  async function renderCustomers() {
    const list = await API.req('GET', '/api/customers');
    AR_CUSTOMERS = list;
    const ALL = list;
    // 欠款汇总：一次拿回全部门店，页面不再逐行发请求
    let ARS = {};
    if (can('ar.view')) {
      try {
        const s = await API.req('GET', '/api/ar/summary');
        (s.rows || []).forEach(r => { ARS[r.customer_id] = r; });
      } catch (e) { /* 忽略 */ }
    }
    const totDebt = Object.values(ARS).reduce((a, r) => a + (r.debt || 0), 0);
    const totOver = Object.values(ARS).reduce((a, r) => a + (r.overdue_orders || 0), 0);
    // 客户下单账号（仅老板/经理）：一次拉回，行内显示「已开通/未开通」
    //🔴 2026-10-19 一个门店可有多个下单员 → CACC 改成 customer_id → [账号] 的数组，
    //    单个map 会把同店第2、3 个账号悄悄吃掉（行内看起来像没开通）。
    let CACC = {};
    if (canManageCustAcc()) {
      try {
        const accs = await API.req('GET', '/api/customer-accounts');
        (accs || []).forEach(a => {
          (a.customer_ids || (a.customer_id ? [a.customer_id] : [])).forEach(cid => {
            if (!CACC[cid]) CACC[cid] = [];
            CACC[cid].push(a);
          });
        });
      } catch (e) { /* 非老板/经理会 403，忽略即可 */ }
    }
    $('#page').innerHTML = `
      <div class="row between" style="margin-bottom:10px"><span class="section-title">客户 / 门店（${list.length}）</span>
        <span>${can('customer.edit') ? '<button class="btn-sm" id="c-add">+ 新增</button>' : ''}</span></div>
      ${can('ar.view') ? `
      <div class="card ar-sum" style="margin-bottom:10px">
        <div class="row between">
          <span class="muted">全部门店欠款</span>
          <span style="font-weight:700;color:var(--danger);font-size:18px">${fmt(totDebt)}</span>
        </div>
        <div class="muted" style="font-size:12px;margin-top:4px">
          ${totOver ? `<span style="color:var(--danger)">${totOver} 单已超账期（默认 30 天）</span>` : '暂无超期单'}
        </div>
        ${can('ar.edit') ? '<button class="btn-sm gray" id="c-pay" style="width:100%;margin-top:10px">💰 记一笔收款</button>' : ''}
      </div>` : ''}
      <div class="card" style="padding:10px;margin-bottom:10px"><input id="c-search" placeholder="🔍 模糊搜索：名称 / 联系人 / 电话 / 地址 / 备注（空格拆多关键词）"/></div>
      <div id="c-match" class="muted" style="font-size:12px;margin:-4px 2px 8px"></div>
      <div id="c-list"></div>`;
    if ($('#c-add')) $('#c-add').onclick = () => editCustomer(null);
    if ($('#c-pay')) $('#c-pay').onclick = () => openPayDialog(null, () => renderCustomers());
    const draw = (kw) => {
      kw = (kw || '').trim();
      const arr = fuzzyFilter(ALL, kw, c => [c.name, c.contact, c.phone, c.address, c.remark]);
      const mi = $('#c-match'); if (mi) mi.textContent = kw ? `模糊匹配 ${arr.length} / 共 ${ALL.length} 家` : '';
      const btns = c => (can('customer.edit')
        ? `<button class="btn-sm gray" data-price="${c.id}">门店价目</button> <button class="btn-sm gray" data-edit="${c.id}">编辑</button> <button class="btn-sm danger" data-del="${c.id}">删</button>`
        : `<button class="btn-sm gray" data-price="${c.id}">门店价目</button>`)
        + (can('ar.edit') ? ` <button class="btn-sm" data-pay="${c.id}">💰 收款</button>` : '')
        + (can('ar.view') && ARS[c.id] ? ` <button class="btn-sm gray" data-orders="${c.id}">未结明细</button>` : '')
        + (canManageCustAcc() ? ` <button class="btn-sm${(CACC[c.id] || []).length ? '' : ' gray'}" data-acc="${c.id}">${(CACC[c.id] || []).length ? `🔑 账号·${(CACC[c.id] || []).length}` : '🔑 开账号'}</button>` : '');
      $('#c-list').innerHTML = (arr.length
        ? arr.map(c => {
          const ar = ARS[c.id];
          const od = ar && ar.overdue_orders ? ar.overdue_orders : 0;
          // 🔴 账期超期标红：这家店有单超过 term_days 还没结清
          const cls = od ? ' list-item ar-overdue' : ' list-item';
          // 🔴 退货额已由服务端抵进 debt；这里把「欠 / 预收」区分开，
          //    并亮出冲减了多少，免得业务员看到数字变小却不知道为什么。
          const arDebt = ar ? Number(ar.debt) || 0 : 0;
          const arIsCredit = arDebt < -0.005;
          const arRetTip = (ar && ar.return_orders)
            ? `<span class="muted" style="color:#0B6B49">退货 ${ar.return_orders} 单已冲减 ${fmt(Math.abs(ar.return_total || 0))}</span>` : '';
          const badge = ar && (arDebt > 0.005 || ar.unsettled || arIsCredit)
            ? `<div class="ar-badge${od ? ' is-od' : ''}">
                 <b style="${arIsCredit ? 'color:#0B6B49' : ''}">${arIsCredit ? '预收' : '欠'} ${fmt(Math.abs(arDebt))}</b>
                 <span>${ar.unsettled} 单未结${od ? ` · <em>${od} 单超账期 ${ar.term_days}天</em>` : ''}</span>
                 ${arRetTip}
                 ${ar.max_days ? `<span class="muted">最久 ${ar.max_days} 天</span>` : ''}
               </div>`
            : `<div class="ar-badge ar-clear"><b>已结清</b></div>`;
          return `<div class="${cls.trim()}"><div class="top"><span class="nm">${highlight(c.name, kw)}</span><span>${btns(c)}</span></div>
            <div class="meta">${c.type === 'store' ? '门店' : '客户'} · ${highlight(c.contact || '', kw)} · ${highlight(c.phone || '', kw)} ${highlight(c.address || '', kw)}</div>
            ${badge}</div>`;
        }).join('')
        : `<div class="muted" style="padding:16px 2px">${kw ? '无匹配门店（试试少输几个字或拆成空格多关键词）' : '暂无客户'}</div>`);
      $('#c-list').querySelectorAll('[data-price]').forEach(b => b.onclick = () => showCustomerPrices(+b.dataset.price, arr));
      $('#c-list').querySelectorAll('[data-edit]').forEach(b => b.onclick = () => editCustomer(+b.dataset.edit));
      $('#c-list').querySelectorAll('[data-pay]').forEach(b => b.onclick = () => openPayDialog(+b.dataset.pay, () => renderCustomers()));
      $('#c-list').querySelectorAll('[data-orders]').forEach(b => b.onclick = () => showUnsettledDetail(+b.dataset.orders, arr));
      $('#c-list').querySelectorAll('[data-del]').forEach(b => b.onclick = async () => { if (confirm('确认删除该客户？')) { try { await API.req('DELETE', '/api/customers/' + b.dataset.del); toast('已删除'); renderCustomers(); } catch (e) { toast(e.message); } } });
      $('#c-list').querySelectorAll('[data-acc]').forEach(b => b.onclick = () => openCustomerAccount(+b.dataset.acc, (CACC[+b.dataset.acc] || [])[0], arr, () => renderCustomers(), CACC[+b.dataset.acc] || []));
    };
    $('#c-search').oninput = debounce(e => draw(e.target.value), 150);
    draw('');
  }

  // 未结清单明细：哪家店、哪几张单、分别欠多少、账龄多少天
  async function showUnsettledDetail(cid, list) {
    const c = (list || []).find(x => x.id == cid) || { name: '' };
    const r = await API.req('GET', '/api/ar/unsettled?customer_id=' + cid);
    const us = r.orders || [];
    const term = r.term_days || 30;
    // 🔴 2026-10-18：原来用 r.total_left（纯待收单据未结合计），不含退货抵减，
    //    与客户页 / 报表页的 debt 对不上。改用服务端算好的 debt，并把冲减额显示出来。
    const rc = Number(r.return_credit) || 0;
    showModal('未结清单 · ' + c.name, `
      <div class="row between" style="padding:8px 10px;background:var(--color-background-secondary);border-radius:8px;margin-bottom:10px">
        <span class="muted">欠款合计（账期 ${term} 天）</span>
        <span style="font-weight:700;color:${r.debt < -0.005 ? '#0B6B49' : 'var(--danger)'}">${fmt(r.debt)}</span>
      </div>
      ${rc ? `<div class="row between" style="padding:6px 10px;margin:-4px 0 10px;font-size:12px">
        <span class="muted">待收单据 ${fmt(r.total_left)} − 退货冲减 ${fmt(Math.abs(rc))}</span>
        <span style="color:#0B6B49">${fmt(r.debt)}</span>
      </div>` : ''}
      </div>
      ${us.length ? `<div class="erp-table"><div class="erp-scroll">
        <div class="erp-thead"><span class="c-name">单号 / 日期</span><span class="c-spec">账龄</span><span class="c-price">未结金额</span></div>
        ${us.map(o => `<div class="erp-row"><span class="c-name">${esc(o.order_no)}<br><span class="muted" style="font-size:11px">${esc(o.order_date || '')}</span></span>
          <span class="c-spec"${o.overdue ? ' style="color:var(--danger);font-weight:600"' : ''}>${o.days} 天${o.overdue ? ' ⚠' : ''}</span>
          <span class="c-price" style="color:var(--danger)">${fmt(o.left)}</span></div>`).join('')}
      </div></div>` : '<div class="muted" style="padding:16px;text-align:center">该门店没有未结清单</div>'}
      ${can('ar.edit') ? `<div class="row" style="gap:8px;margin-top:12px">
        <button class="btn-sm gray" id="ud-term" style="flex:1 1 0;min-width:0;white-space:nowrap">改账期</button>
        <button class="btn-sm" id="ud-pay" style="flex:1 1 0;min-width:0;white-space:nowrap">💰 收款</button>
      </div>` : ''}`);
    if ($('#ud-pay')) $('#ud-pay').onclick = () => { closeModal(); openPayDialog(cid, () => renderCustomers()); };
    if ($('#ud-term')) $('#ud-term').onclick = () => {
      const v = prompt('该门店账期天数（超期会在列表标红；0 = 不提醒）', String(term));
      if (v == null) return;
      const n = Number(v);
      if (!isFinite(n) || n < 0 || n > 365) { toast('请填 0-365 之间的天数'); return; }
      API.req('POST', '/api/ar/term', { customer_id: cid, term_days: Math.round(n) })
        .then(() => { closeModal(); toast('账期已设为 ' + Math.round(n) + ' 天'); renderCustomers(); })
        .catch(e => toast(e.message));
    };
  }
  // 门店价目表：该门店所有历史成交价（可改可清）
  async function showCustomerPrices(cid, list) {
    const c = (list || []).find(x => x.id == cid) || { name: '' };
    const d = await API.req('GET', '/api/customer-prices?customer_id=' + cid);
    const prods = await API.req('GET', '/api/products');
    const byId = {}; prods.forEach(p => byId[p.id] = p);
    const ids = Object.keys(d.prices).map(Number).sort((a, b) => a - b);
    const staleIds = ids.filter(pid => !byId[pid]);
    const rows = ids.map(pid => {
      const p = byId[pid] || { name: '(商品已删除)', unit: '' };
      const stale = !byId[pid];
      return `<div class="sale-item"><input type="checkbox" data-sel="${pid}" style="width:18px;height:18px;flex:none"/><div class="info"><div class="nm"${stale ? ' style="color:#e5484d"' : ''}>${esc(p.name)}</div><div class="sub">${esc(p.spec || '')} · ${esc(p.unit || '')}</div></div><input type="number" min="0" step="0.01" value="${d.prices[pid]}" data-cp="${pid}" style="max-width:90px"/></div>`;
    }).join('');
    const editable = can('price.edit');
    showModal('门店价目 · ' + c.name, `
      <div class="meta">商品数：<b>${ids.length}</b> · 上次折扣：<b>${d.discount}</b>${d.updated_at ? ' · 更新于 ' + esc(d.updated_at) : ''}${staleIds.length ? ' · <span style="color:#e5484d">已删除商品 ' + staleIds.length + '</span>' : ''}</div>
      <div class="muted" style="font-size:12px;margin:6px 0 10px">开单时选中该门店，这些价格会自动带出。无历史的商品按标准价。</div>
      ${editable && ids.length ? `<div class="row" style="gap:8px;align-items:center;margin-bottom:6px"><button class="btn-sm gray" id="cp-sel-stale" style="flex:1">只勾已删除${staleIds.length ? ' (' + staleIds.length + ')' : ''}</button><button class="btn-sm gray" id="cp-sel-all" style="flex:1">全选 / 取消</button><span class="muted" id="cp-sel-n" style="font-size:12px;min-width:56px;text-align:right">已选 0</span></div>` : ''}
      <div style="max-height:46vh;overflow-y:auto">${rows || '<div class="muted" style="padding:10px">该门店暂无历史价</div>'}</div>
      ${ids.length ? '<button class="btn-primary" id="cp-save" style="margin-top:14px">保存价格</button>' : ''}
      ${editable && ids.length ? '<button class="btn-sm danger" id="cp-delsel" style="margin-top:8px;width:100%">删除所选价目（恢复标准价）</button>' : ''}
      ${editable && ids.length ? '<button class="btn-sm danger" id="cp-clear" style="margin-top:8px;width:100%">清除该门店历史价（恢复标准价）</button>' : ''}`);
    const selCbs = () => Array.from($$('#modal-body input[data-sel]'));
    const updN = () => { if ($('#cp-sel-n')) $('#cp-sel-n').textContent = '已选 ' + selCbs().filter(cb => cb.checked).length; };
    selCbs().forEach(cb => cb.onchange = updN);
    if ($('#cp-sel-all')) $('#cp-sel-all').onclick = () => {
      const cbs = selCbs(), all = cbs.length > 0 && cbs.every(cb => cb.checked);
      cbs.forEach(cb => cb.checked = !all); updN();
    };
    if ($('#cp-sel-stale')) $('#cp-sel-stale').onclick = () => {
      selCbs().forEach(cb => cb.checked = !byId[+cb.dataset.sel]); updN();
    };
    if ($('#cp-delsel')) $('#cp-delsel').onclick = async () => {
      const pids = selCbs().filter(cb => cb.checked).map(cb => cb.dataset.sel);
      if (!pids.length) return toast('先勾选要清除的价目');
      if (!confirm('清除所选 ' + pids.length + ' 项历史价（这些商品恢复按标准价）？确认？')) return;
      try {
        const r = await API.req('DELETE', '/api/customer-prices?customer_id=' + cid + '&product_ids=' + pids.join(','));
        toast('已清除 ' + (r.removed || 0) + ' 项');
        showCustomerPrices(cid, list);
      } catch (e) { toast(e.message); }
    };
    if ($('#cp-save')) $('#cp-save').onclick = async () => {
      try {
        for (const inp of $$('#modal-body input[data-cp]')) {
          const pid = +inp.dataset.cp, v = parseFloat(inp.value);
          if (isFinite(v)) await API.req('POST', '/api/customer-prices', { customer_id: cid, product_id: pid, price: v });
        }
        toast('已保存'); closeModal();
      } catch (e) { toast(e.message); }
    };
    if ($('#cp-clear')) $('#cp-clear').onclick = async () => {
      if (!confirm('清除后，该门店开单将回到标准价。确认？')) return;
      try { await API.req('DELETE', '/api/customer-prices?customer_id=' + cid); toast('已清除'); closeModal(); }
      catch (e) { toast(e.message); }
    };
  }

  async function editCustomer(id) {
    const all = await API.req('GET', '/api/customers');
    let c = { name: '', type: 'store', contact: '', phone: '', address: '', remark: '' };
    if (id) c = all.find(x => x.id == id) || c;
    showModal(id ? '编辑客户' : '新增客户', `
      <label>名称 *</label><input id="f-name" value="${esc(c.name)}"/>
      <label>类型</label><select id="f-type"><option value="store" ${c.type === 'store' ? 'selected' : ''}>门店</option><option value="customer" ${c.type === 'customer' ? 'selected' : ''}>客户</option></select>
      <label>联系人</label><input id="f-contact" value="${esc(c.contact || '')}"/>
      <label>电话</label><input id="f-phone" value="${esc(c.phone || '')}"/>
      <label>地址</label><input id="f-address" value="${esc(c.address || '')}"/>
      <label>备注</label><input id="f-remark" value="${esc(c.remark || '')}"/>
      <button class="btn-primary" id="f-save" style="margin-top:14px">保存</button>`);
    $('#f-save').onclick = async () => {
      const body = { name: $('#f-name').value.trim(), type: $('#f-type').value, contact: $('#f-contact').value.trim(), phone: $('#f-phone').value.trim(), address: $('#f-address').value.trim(), remark: $('#f-remark').value.trim() };
      if (!body.name) { toast('名称必填'); return; }
      try { const r = id ? await API.req('PUT', '/api/customers/' + id, body) : await API.req('POST', '/api/customers', body); closeModal(); toast('已保存'); notifyViolation(r.violation); renderCustomers(); }
      catch (e) { toast(e.message); }
    };
  }

  // ---------- 客户下单账号管理（仅老板/经理）----------
  function canManageCustAcc() { return ME.role === 'boss' || ME.role === 'manager'; }
  // 门店多选控件：2026-10-19 起一个下单员可负责多家门店。
//   allList = 本套账全部门店（renderCustomers 的 arr，含搜索过滤 → 用完整 list 更稳）
//   返回 HTML；读取用 readPickedStores()
  function storeMultiPickerHTML(allList, picked, prefix) {
    const sel = (picked || []).map(Number);
    return `<div class="store-picker" data-prefix="${prefix}">
      <div class="muted" style="font-size:12px;margin-bottom:6px">负责的门店（可多选；不勾这家门店他就不能给它下单）</div>
      <div class="store-picker-list">
        ${(allList || []).map(c => `
          <label class="store-picker-item">
            <input type="checkbox" data-st="${c.id}" ${sel.indexOf(Number(c.id)) >= 0 ? 'checked' : ''}/>
            <span>${esc(c.name)}</span>
          </label>`).join('')}
      </div>
      <div class="row between" style="margin-top:8px">
        <span class="muted" id="${prefix}-cnt" style="font-size:12px"></span>
        <span>
          <button type="button" class="btn-sm gray" id="${prefix}-none">全不选</button>
        </span>
      </div>
    </div>`;
  }
  // 🔴 动态节点必须「先入DOM 再 getElementById 绑定」——弹窗 HTML 是 showModal 之后才插进页面的
  function bindStoreMultiPicker(prefix, onChange) {
    const box = document.querySelector('.store-picker[data-prefix="' + prefix + '"]');
    if (!box) return () => [];
    const cnt = document.getElementById(prefix + '-cnt');
    const read = () => Array.from(box.querySelectorAll('input[data-st]:checked')).map(i => Number(i.dataset.st));
    const upd = () => { if (cnt) cnt.textContent = '已选 ' + read().length + ' 家'; if (onChange) onChange(read()); };
    box.querySelectorAll('input[data-st]').forEach(i => i.onchange = upd);
    const nb = document.getElementById(prefix + '-none');
    if (nb) nb.onclick = () => { box.querySelectorAll('input[data-st]').forEach(i => { i.checked = false; }); upd(); };
    upd();
    return read;
  }

  async function openCustomerAccount(cid, acc, list, done, sameStoreAccs) {
    const c = (list || []).find(x => x.id == cid) || { name: '' };
    const others = (sameStoreAccs || []).filter(a => !acc || a.id !== acc.id);
    if (!acc) {
      showModal('开通下单账号 · ' + c.name, `
        <div class="muted" style="font-size:12px;margin-bottom:10px">
          开通后该门店可用「手机号 + 密码」登录，只能给自己下单、看自己的订单和欠款。
          一个下单员可以负责多家门店，勾几家就下几家的单。
        </div>
        ${storeMultiPickerHTML(list, [cid], 'ca')}
        <label>登录手机号 *</label><input id="ca-phone" placeholder="11 位手机号" inputmode="numeric"/>
        <label>初始密码 *</label><input id="ca-pwd" placeholder="至少 4 位"/>
        <label>显示名（可留空）</label><input id="ca-name" value="${esc(c.name)}"/>
        <button class="btn-primary" id="ca-create" style="margin-top:14px">开通账号</button>`);
      const readStores = bindStoreMultiPicker('ca');
      const btn = $('#ca-create');
      if (!btn) return;
      btn.onclick = async () => {
        const phone = String(($('#ca-phone') || {}).value || '').trim();
        const pwd = String(($('#ca-pwd') || {}).value || '');
        const ids = readStores();
        if (!/^1[3-9]\d{9}$/.test(phone)) { toast('请填写正确的 11 位手机号'); return; }
        if (pwd.length < 4) { toast('密码至少 4 位'); return; }
        if (!ids.length) { toast('至少要勾一家门店'); return; }
        try {
          await API.req('POST', '/api/customer-accounts', {
            phone, password: pwd, customer_ids: ids,
            display_name: String(($('#ca-name') || {}).value || '').trim(),
          });
          closeModal(); toast('已开通：' + phone + '（' + ids.length + ' 家门店）'); if (done) done();
        } catch (e) { toast(e.message || '开通失败'); }
      };
      return;
    }
    const accIds = (acc.customer_ids || (acc.customer_id ? [acc.customer_id] : [])).map(Number);
    const nameOf = id => { const x = (list || []).find(y => Number(y.id) === Number(id)); return x ? x.name : ('门店#' + id); };
    showModal('下单账号 · ' + c.name, `
      <div class="row between" style="padding:8px 10px;background:var(--color-background-secondary);border-radius:8px;margin-bottom:10px">
        <span class="muted">登录手机号</span>
        <b>${esc(acc.username)}</b>
      </div>
      <div class="row between" style="padding:4px 10px 10px;font-size:12px">
        <span class="muted">状态</span>
        <span style="color:${acc.active ? '#0B6B49' : 'var(--danger)'}">${acc.active ? '已启用' : '已停用'}</span>
      </div>
      <div class="row between" style="padding:0 10px 8px;font-size:12px">
        <span class="muted">负责门店</span>
        <b>${accIds.length} 家</b>
      </div>
      <div style="padding:0 10px 10px;font-size:12px">${accIds.map(id => esc(nameOf(id))).join('、') || '<span class="muted">未绑定</span>'}</div>
      <button class="btn-sm gray" id="ca-stores" style="width:100%;margin-bottom:8px">🏪 调整负责门店</button>
      <div class="muted" style="font-size:12px;margin-bottom:10px">该账号只能给自己负责的门店下单、看自己的订单和欠款，看不到商品成本/库存/报表。</div>
      <label>新密码（重置）</label><input id="ca-npwd" placeholder="至少 4 位"/>
      <button class="btn-sm" id="ca-reset" style="width:100%;margin-top:10px">重置密码</button>
      <button class="btn-sm gray" id="ca-toggle" style="width:100%;margin-top:8px">${acc.active ? '停用账号' : '启用账号'}</button>
      <button class="btn-sm danger" id="ca-del" style="width:100%;margin-top:8px">删除账号</button>
      ${others.length ? `<div class="muted" style="font-size:12px;margin-top:12px;border-top:1px solid var(--color-border);padding-top:10px">
          本门店还有${others.length} 个下单账号：${others.map(a => esc(a.username)).join('、')}
        </div>` : ''}`);
    const b0 = $('#ca-stores');
    // 🔴 调整门店要传**全部门店**清单，不能用搜索过滤后的 arr —— 否则改一次门店就把其余的搞丢了
    if (b0) b0.onclick = async () => {
      let all = [];
      try { all = await API.req('GET', '/api/customers'); } catch (e) { all = list || []; }
      showModal('调整负责门店 · ' + acc.username, `
        ${storeMultiPickerHTML(all, accIds, 'cs')}
        <button class="btn-primary" id="cs-save" style="margin-top:14px">保存</button>`);
      const read = bindStoreMultiPicker('cs');
      const sv = $('#cs-save');
      if (!sv) return;
      sv.onclick = async () => {
        const ids = read();
        if (!ids.length) { toast('至少要保留一家门店'); return; }
        try {
          await API.req('PUT', '/api/customer-accounts/' + acc.id + '/stores', { customer_ids: ids });
          closeModal(); toast('已更新：' + ids.length + ' 家门店'); if (done) done();
        } catch (e) { toast(e.message); }
      };
    };
    const b1 = $('#ca-reset');
    if (b1) b1.onclick = async () => {
      const np = String(($('#ca-npwd') || {}).value || '');
      if (np.length < 4) { toast('新密码至少 4 位'); return; }
      try { await API.req('PUT', '/api/customer-accounts/' + acc.id + '/password', { password: np }); closeModal(); toast('密码已重置'); if (done) done(); }
      catch (e) { toast(e.message); }
    };
    const b2 = $('#ca-toggle');
    if (b2) b2.onclick = async () => {
      try { await API.req('PUT', '/api/customer-accounts/' + acc.id + '/active', { active: !acc.active }); closeModal(); toast(acc.active ? '已停用' : '已启用'); if (done) done(); }
      catch (e) { toast(e.message); }
    };
    const b3 = $('#ca-del');
    if (b3) b3.onclick = async () => {
      if (!confirm('删除后该门店将无法登录下单，确认删除？')) return;
      try { await API.req('DELETE', '/api/customer-accounts/' + acc.id); closeModal(); toast('已删除'); if (done) done(); }
      catch (e) { toast(e.message); }
    };
  }

  // ---------- 商品（管家婆风格密集表格） ----------
  let PRODUCTS = [];
  let multiMode = false;
  let selectedProducts = new Set();
  let lastProductList = PRODUCTS;
  async function renderProducts() {
    PRODUCTS = await API.req('GET', '/api/products');
    $('#page').innerHTML = `
      <div class="erp-toolbar">
        <input id="p-search" placeholder="🔍 模糊搜索 名称 / 条码 / 规格（空格拆多关键词）"/>
        <div class="erp-tools">
          <button class="btn-sm gray" id="p-multi">☑ 多选</button>
          <button class="btn-sm gray" id="p-print">🖨️清单</button>
          ${can('product.import') ? '<button class="btn-sm gray" id="p-tpl">📥模板</button><button class="btn-sm gray" id="p-imp">📤导入</button>' : ''}
          ${can('product.edit') ? '<button class="btn-sm" id="p-add">+新增</button>' : ''}
        </div>
      </div>
      <div id="p-match" class="muted" style="font-size:12px;margin:-2px 2px 8px"></div>
      <div class="batch-bar hidden" id="p-batch">
        <label class="sel-all"><input type="checkbox" id="p-selall"/> 全选</label>
        <span id="p-selcount" class="muted">已选 0 项</span>
        <span class="spacer"></span>
        ${can('product.edit') ? '<button class="btn-sm danger" id="p-delmany">🗑 批量删除</button>' : ''}
        <button class="btn-sm gray" id="p-multioff">退出多选</button>
      </div>
      <input type="file" id="p-file" accept=".xlsx" class="hidden"/>
      <div class="erp-table" id="p-table">
        <div class="erp-scroll">
          <div class="erp-thead">
            <span class="c-check"></span>
            <span class="c-code">编码</span><span class="c-name">名称</span><span class="c-spec">规格·单位</span><span class="c-price">单价</span><span class="c-qty">进价</span><span class="c-act">操作</span>
          </div>
          <div id="p-list"></div>
        </div>
      </div>`;
    $('#p-add') && ($('#p-add').onclick = () => editProduct(null));
    $('#p-tpl') && ($('#p-tpl').onclick = downloadTemplate);
    $('#p-imp') && ($('#p-imp').onclick = () => $('#p-file').click());
    $('#p-print').onclick = () => printProductList(PRODUCTS);
    $('#p-file') && ($('#p-file').onchange = e => { if (e.target.files[0]) importFile(e.target.files[0]); e.target.value = ''; });
    $('#p-search').oninput = debounce(e => renderProductRows(filterProducts(e.target.value.trim())), 200);
    $('#p-multi') && ($('#p-multi').onclick = () => setMulti(true));
    $('#p-multioff') && ($('#p-multioff').onclick = () => setMulti(false));
    $('#p-delmany') && ($('#p-delmany').onclick = deleteSelected);
    $('#p-selall') && ($('#p-selall').onchange = e => { const list = lastProductList; if (e.target.checked) list.forEach(p => selectedProducts.add(p.id)); else list.forEach(p => selectedProducts.delete(p.id)); renderProductRows(list); });
    applyMultiUI();
    renderProductRows(PRODUCTS);
  }
  function filterProducts(kw) {
    // 模糊搜索：多关键词 AND + 名称/条码/规格/单位，按相关度排序
    return fuzzyFilter(PRODUCTS, kw, p => [p.name, p.barcode, p.spec, p.unit]);
  }
  function renderProductRows(list) {
    lastProductList = list || PRODUCTS;
    const list2 = lastProductList;
    const kw = $('#p-search') ? $('#p-search').value.trim() : '';
    const mi = $('#p-match');
    if (mi) mi.textContent = kw ? `模糊匹配 ${list2.length} / 共 ${PRODUCTS.length} 个商品` : '';
    const box = $('#p-list'); if (!box) return;
    if (!list2.length) { box.innerHTML = `<div class="muted" style="padding:20px;text-align:center">${kw ? '无匹配商品（试试少输几个字或拆成空格多关键词）' : '暂无商品'}</div>`; updateSelUI(); return; }
    box.innerHTML = list2.map(p => {
      const sel = selectedProducts.has(p.id);
      return `<div class="erp-row ${sel ? 'sel' : ''}" data-id="${p.id}">
        <span class="c-check"><input type="checkbox" class="p-chk" data-id="${p.id}" ${sel ? 'checked' : ''}/></span>
        <span class="c-code">${highlight(p.barcode || ('ID' + p.id), kw)}</span>
        <span class="c-name">${highlight(p.name, kw)}</span>
        <span class="c-spec">${highlight(p.spec || '', kw)}·${highlight(p.unit || '', kw)}</span>
        <span class="c-price">¥${p.price}</span>
        <span class="c-qty">${p.cost != null ? '¥' + p.cost : '-'}</span>
        <span class="c-act">${can('product.edit') ? `<button class="btn-sm gray" data-edit="${p.id}">编辑</button><button class="btn-sm danger" data-del="${p.id}">删</button>` : '<span class="muted">只读</span>'}</span>
      </div>`;
    }).join('');
    box.querySelectorAll('[data-edit]').forEach(b => b.onclick = () => editProduct(+b.dataset.edit));
    box.querySelectorAll('[data-del]').forEach(b => b.onclick = async () => { if (confirm('确认删除该商品？')) { try { await API.req('DELETE', '/api/products/' + b.dataset.del); toast('已删除'); selectedProducts.delete(+b.dataset.del); removeRowsFromView([+b.dataset.del]); } catch (e) { toast(e.message); } } });
    box.querySelectorAll('.p-chk').forEach(cb => cb.onchange = () => toggleSel(+cb.dataset.id, cb.checked));
    if (multiMode) box.querySelectorAll('.erp-row').forEach(row => row.onclick = (e) => { if (e.target.closest('button') || e.target.closest('.p-chk')) return; toggleSel(+row.dataset.id); });
    updateSelUI();
  }
  function updateSelUI() {
    const cnt = $('#p-selcount'); if (cnt) cnt.textContent = '已选 ' + selectedProducts.size + ' 项';
    const all = $('#p-selall');
    if (all) { const vis = (lastProductList || []).length; all.checked = selectedProducts.size > 0 && selectedProducts.size === vis; all.indeterminate = selectedProducts.size > 0 && selectedProducts.size < vis; }
  }
  function toggleSel(id, checked) {
    if (checked) selectedProducts.add(id); else selectedProducts.delete(id);
    const box = $('#p-list'); const row = box && box.querySelector('.erp-row[data-id="' + id + '"]');
    if (row) { row.classList.toggle('sel', !!checked); const cb = row.querySelector('.p-chk'); if (cb && cb.checked !== checked) cb.checked = checked; }
    updateSelUI();
  }
  function applyMultiUI() {
    const t = $('#p-table'); if (t) t.classList.toggle('multi', multiMode);
    const bar = $('#p-batch'); if (bar) bar.classList.toggle('hidden', !multiMode);
    const btn = $('#p-multi'); if (btn) btn.style.display = multiMode ? 'none' : '';
  }
  function setMulti(on) {
    multiMode = on;
    if (!on) selectedProducts.clear();
    applyMultiUI();
    renderProductRows(lastProductList);
  }
  // 删除后不再整表重建：只从本地数据与 DOM 中删掉对应行，避免整表刷新/闪动
  function removeRowsFromView(ids) {
    const idset = new Set(ids.map(Number));
    PRODUCTS = PRODUCTS.filter(p => !idset.has(p.id));
    lastProductList = (lastProductList || []).filter(p => !idset.has(p.id));
    idset.forEach(id => { const row = document.querySelector('#p-list .erp-row[data-id="' + id + '"]'); if (row) row.remove(); });
    const box = $('#p-list');
    if (box && !box.querySelector('.erp-row')) box.innerHTML = '<div class="muted" style="padding:20px;text-align:center">暂无商品</div>';
    updateSelUI();
  }
  async function deleteSelected() {
    const ids = [...selectedProducts];
    if (!ids.length) { toast('请先勾选要删除的商品'); return; }
    if (!confirm('确认删除选中的 ' + ids.length + ' 个商品？此操作不可恢复')) return;
    let ok = 0, fail = 0;
    await Promise.all(ids.map(id => API.req('DELETE', '/api/products/' + id).then(() => { ok++; selectedProducts.delete(id); }).catch(() => { fail++; })));
    toast('已删除 ' + ok + ' 个' + (fail ? ('，失败 ' + fail + ' 个') : ''));
    removeRowsFromView(ids);
  }
  async function downloadTemplate() {
    try {
      const opt = { method: 'GET', headers: {} };
      if (TOKEN) opt.headers['Authorization'] = 'Bearer ' + TOKEN;
      const r = await fetch(BASE + '/api/products/template', opt);
      if (!r.ok) throw new Error('模板下载失败');
      const blob = await r.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = '商品导入模板.xlsx';
      document.body.appendChild(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      toast('模板已下载');
    } catch (e) { toast(e.message); }
  }
  async function importFile(file) {
    try {
      const buf = await file.arrayBuffer();
      const opt = { method: 'POST', headers: {} };
      if (TOKEN) opt.headers['Authorization'] = 'Bearer ' + TOKEN;
      opt.headers['Content-Type'] = 'application/octet-stream';
      opt.body = buf;
      const r = await fetch(BASE + '/api/products/import', opt);
      const d = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(d.error || '导入失败');
      let msg = '导入成功 ' + d.imported + ' 条';
      if (d.skipped) msg += '，跳过 ' + d.skipped + ' 条';
      toast(msg);
      renderProducts();
    } catch (e) { toast(e.message); }
  }
  // 商品清单打印（HTML 预览 / 系统打印 / 蓝牙打印）
  async function printProductList(list) {
    const now = new Date().toLocaleString('zh-CN');
    const html = `
      <div class="center" style="font-size:16px;font-weight:700">${esc(SHOP)}</div>
      <div class="center" style="font-size:12px">${esc(TPL.list_title || '商品清单')}</div>
      <div class="sep"></div>
      <div class="line" style="font-size:11px"><span>打印时间</span><span>${esc(now)}</span></div>
      <div class="sep"></div>
      ${list.map(p => `<div class="line" style="font-size:12px"><span>${esc(p.name)}</span><span>${esc(p.unit || '')}</span></div><div class="line" style="font-size:11px"><span>${esc(p.spec || '')} 条码:${esc(p.barcode || '-')}</span><span>¥${p.price}</span></div>`).join('')}
      <div class="sep"></div>
      <div class="center" style="font-size:11px">共 ${list.length} 项<br>${esc(TPL.receipt_footer || '')}</div>`;
    const ov = $('#print-products');
    $('#products-receipt').innerHTML = html;
    ov.classList.remove('hidden');
    if (window.Capacitor) { const sb = $('#pp-sys'); if (sb) sb.style.display = 'none'; }
    $('#pp-bt').onclick = () => nativePrint('products', { shop: SHOP, items: list.map(p => ({ name: p.name, spec: p.spec, unit: p.unit, price: p.price, qty: p.qty, barcode: p.barcode })) });
    if (!getErpPrinter()) { const pb = $('#pp-bt'); if (pb) { if (webPrintSupported()) pb.textContent = '🖨️打印'; else pb.style.display = 'none'; } }
    $('#pp-sys').onclick = () => window.print();
    $('#pp-close').onclick = () => ov.classList.add('hidden');
  }
  // 原生插件桥接。
  // 重要：Capacitor 不会自动为「纯原生插件」在 JS 侧创建代理，必须由 JS 主动
  // registerPlugin(name) 一次（core 内部 Plugins[name]=proxy，方法走 nativePromise）。
  // 否则 window.Capacitor.Plugins.ErpPrinter 恒为 undefined，会掉到网页/系统打印分支。
  const capPluginCache = {};
  // 判断是否在原生 App（安卓/iOS）中运行
  function isNativeEnv() {
    const C = window.Capacitor;
    if (!C) return false;
    if (typeof C.isNativePlatform === 'function') return !!C.isNativePlatform();
    if (typeof C.isNativePlatform !== 'undefined') return !!C.isNativePlatform;
    if (typeof C.getPlatform === 'function') {
      const p = C.getPlatform();
      return p === 'android' || p === 'ios';
    }
    return false;
  }
  function capPlugin(name) {
    const C = window.Capacitor;
    if (!C) return null;
    if (capPluginCache[name] !== undefined) return capPluginCache[name];
    // 1) 已自动注入的代理优先（如社区插件）
    if (C.Plugins && C.Plugins[name]) { capPluginCache[name] = C.Plugins[name]; return C.Plugins[name]; }
    // 2) 原生环境：直接 registerPlugin 创建代理。
    //    关键坑：MainActivity 里 registerPlugin(Xxx.class) 是在 super.onCreate() 之后调用的，
    //    此时 WebView 早已注入 cap.PluginHeaders（不含这些手动注册的插件），且 Capacitor 不会
    //    重新注入，导致 isPluginAvailable(name) 恒为 false；而 JS 侧的 registerPlugin(name) 创建的
    //    代理走 nativePromise，原生端按名字从 bridge 解析插件（已注册），与 PluginHeaders 无关。
    //    因此这里【不依赖 isPluginAvailable】，直接尝试注册即可。
    if (isNativeEnv() && typeof C.registerPlugin === 'function') {
      try {
        const p = C.registerPlugin(name);
        if (p) { capPluginCache[name] = p; return p; }
      } catch (e) { /* 忽略，回退 */ }
    }
    return null;
  }
  // 蓝牙打印桥接（安卓原生 ErpPrinter 插件；无则退回网页/系统打印）
  function getErpPrinter() {
    const p = capPlugin('ErpPrinter');
    if (p) return p;
    return window.ErpPrinter || null;
  }
  function getBtPrinter() { return getErpPrinter(); }
  // 在线更新桥接（安卓原生 ErpUpdate 插件；无则不可用）
  function getErpUpdate() {
    const p = capPlugin('ErpUpdate');
    if (p) return p;
    return window.ErpUpdate || null;
  }
  // ============================================================
  // 网页打印：Web Bluetooth(BLE) + Web Serial(USB) + 系统打印兜底
  // 仅安全上下文(HTTPS/localhost)可用。原生 App 现已走 https://erp.example.com，
  // 属于安全上下文，Web Bluetooth / Web Serial 可用（蓝牙打印仍优先走原生 ErpPrinter）。
  // ============================================================
  const ESC = {
    INIT: [0x1B, 0x40], ALIGN_LEFT: [0x1B, 0x61, 0x00], ALIGN_CENTER: [0x1B, 0x61, 0x01],
    BOLD_ON: [0x1B, 0x45, 0x01], BOLD_OFF: [0x1B, 0x45, 0x00], BIG: [0x1B, 0x21, 0x30],
    NORMAL: [0x1B, 0x21, 0x00], CUT: [0x1D, 0x56, 0x00], CODPAGE_GBK: [0x1B, 0x74, 0x0F], LF: [0x0A],
    // 进纸 n 点（ESC d n）：底部留白用，比堆LF 精确。80mm 机常见 203dpi，2cm ≈ 160 点
    FEED: n => [0x1B, 0x64, n]
  };
  /** 小票底部签收留白（经理要求）：203dpi ≈ 8 点/mm。
   *  mm 传 null 时用 20mm（2cm）。旧调用 feedTail2cm(b) 行为不变。 */
  function feedTail(b, mm) {
    const t = (mm == null ? 20 : Number(mm)) * 8;
    b.raw(ESC.FEED(Math.max(0, Math.round(t)))).raw(ESC.LF).raw(ESC.LF);
  }
  const feedTail2cm = b => feedTail(b, 20);
  // 纸宽（半角列数）：80mm 机型 = 48；若换 58mm 机型改成 32 即可
  const RCOLS = 48;
  const R_QTYW = 6, R_PRICEW = 7, R_AMTW = 8;      // 数量 / 单价 / 金额 三列
  const R_NUMW = R_QTYW + R_PRICEW + R_AMTW;       // 21
  const R_LEFTW = RCOLS - R_NUMW;                  // 27，留给商品条码
  const DASHES = '-'.repeat(RCOLS);
  const EQEQ = '='.repeat(RCOLS);
  function fmt2(n) { try { return Number(n || 0).toFixed(2); } catch (e) { return '0.00'; } }
  /** 数量显示：整数不带小数点 */
  function fmtQtyNum(q) { const v = Number(q || 0); return (v % 1 === 0) ? String(Math.trunc(v)) : v.toFixed(2).replace(/0+$/, '').replace(/\.$/, ''); }
  /** 显示宽度：中日韩全角字符按 2 列计（排版用，不是字节长度） */
  function wLen(s) {
    s = String(s == null ? '' : s);
    let w = 0;
    for (const ch of s) {
      const c = ch.codePointAt(0);
      w += ((c >= 0x1100 && c <= 0x115f) || (c >= 0x2e80 && c <= 0x303e) || (c >= 0x3041 && c <= 0x33ff) ||
        (c >= 0x3400 && c <= 0x4dbf) || (c >= 0x4e00 && c <= 0x9fff) || (c >= 0xa000 && c <= 0xa4cf) ||
        (c >= 0xac00 && c <= 0xd7a3) || (c >= 0xf900 && c <= 0xfaff) || (c >= 0xfe10 && c <= 0xfe19) ||
        (c >= 0xfe30 && c <= 0xfe6f) || (c >= 0xff00 && c <= 0xff60) || (c >= 0xffe0 && c <= 0xffe6)) ? 2 : 1;
    }
    return w;
  }
  function rPad(s, n) { s = String(s == null ? '' : s); const d = n - wLen(s); return d > 0 ? s + ' '.repeat(d) : s; }
  function lPadN(s, n) { s = String(s == null ? '' : s); const d = n - wLen(s); return d > 0 ? ' '.repeat(d) + s : s; }
  /** 按显示宽度截断，避免长商品名把一行撑爆导致折行 */
  function clipW(s, n) {
    s = String(s == null ? '' : s);
    if (wLen(s) <= n) return s;
    let out = '', w = 0;
    for (const ch of s) { const cw = wLen(ch); if (w + cw > n) break; out += ch; w += cw; }
    return out;
  }
  async function encodeGBK(str) {
    if (str == null) str = '';
    const buf = await new Blob([String(str)], { type: 'text/plain;charset=gbk' }).arrayBuffer();
    return new Uint8Array(buf);
  }
  class EscBuilder {
    constructor() { this.parts = []; }
    raw(a) { this.parts.push(Uint8Array.from(a)); return this; }
    async text(s) { if (s == null || s === '') return this; this.parts.push(await encodeGBK(s)); return this; }
    async line(left, right) { return this.text((left || '') + '  ' + (right || '')); }
    /** 打印 EAN-13 条码图形（GS ( k）：m=69(GSV)、k=67(格式 1)、长度 12+1=13 数据 */
    ean13(code) {
      const s = String(code == null ? '' : code).replace(/\D/g, '');
      if (s.length === 12) return this.ean13('0' + s);        // UPC-A → EAN-13 左补 0
      if (s.length !== 13) return this;
      // 校验位：前 12 位加权（奇位×1，偶位×3），补到10 的整数倍
      let sum = 0;
      for (let i = 0; i < 12; i++) sum += (+s[i]) * (i % 2 === 0 ? 1 : 3);
      const chk = (10 - (sum % 10)) % 10;
      const data = s.slice(0, 12) + String(chk);
      this.raw([0x1D, 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32]);   // GS ( k m=65 n=4 w=1 h=2
      this.raw([0x1D, 0x28, 0x6B, data.length, 0x00, ...data.split('').map(c => +c)]);
      return this;
    }
    async build() {
      let len = 0; for (const p of this.parts) len += p.length;
      const out = new Uint8Array(len); let off = 0;
      for (const p of this.parts) { out.set(p, off); off += p.length; }
      return out;
    }
  }
  /**
   * 销售单据小票（对齐纸质送货单版式，48 列）：
   *   店名 / 单据类型
   *   往来单位名称 / 单据编号 / 单据日期 / 经手人 + 出库(入库)仓库
   *   表列：序号 名称（下带商品条码） 数量 单价 金额
   *   合计数量 / 合计折前金额 / 折扣优惠金额 / 优惠后金额 / 客户签收 / 页脚
   * 明细按订单实际单价（原价）打印，金额=数量×单价，其合计即「合计折前金额」，
   * 整单折扣在底部两栏体现。退货单明细与合计原样为负数。
   * 金额一律裸数字——ESC/POS 文本禁用半角 ¥(U+00A5)，GBK 编不出会乱码。
   *
   * 🔴 2026-10-15：版式改为完全由打印模板驱动（print_template.js），
   * 纸宽/列数/模块开关全部读 data.tpl。这里只提供与具体版式无关的工具函数。
   */
  async function buildReceipt(data) {
    if (!window.ERPTpl) {   // 模板模块没加载上时的兜底：至少别让整个打印流程挂掉
      console.error('[print] ERPTpl 未加载，回退到固定 48 列版式');
    }
    const tpl = (window.ERPTpl && data.tpl) ? window.ERPTpl.normalize(data.tpl)
      : window.ERPTpl ? window.ERPTpl.normalize(window.ERPTpl.DEFAULT[0]) : null;
    if (!tpl) return buildReceiptFallback(data);
    return window.ERPTpl.renderEsc(data, tpl, {
      EscBuilder, ESC, clipW, rPad, lPadN, wLen, fmt2, fmtQtyNum, feedTail
    });
  }
  /** 模板模块缺失时的最后兜底（等同旧版固定版式） */
  async function buildReceiptFallback(data) {
    const b = new EscBuilder();
    const isRet = !!data.is_return;
    b.raw(ESC.INIT).raw(ESC.CODPAGE_GBK).raw(ESC.ALIGN_CENTER).raw(ESC.BOLD_ON).raw(ESC.BIG);
    await b.text(clipW(data.shop || SHOP || '销售小票', 22));
    b.raw(ESC.NORMAL).raw(ESC.BOLD_OFF); b.raw(ESC.LF);
    await b.text(clipW(data.doc_title || '销售订单', RCOLS)); b.raw(ESC.LF);
    b.raw(ESC.ALIGN_LEFT);
    await b.text(EQEQ); b.raw(ESC.LF);
    await b.text('往来单位名称:' + clipW(data.customer_name || '', RCOLS - 14)); b.raw(ESC.LF);
    await b.text('单据编号:' + clipW(data.order_no || '', RCOLS - 10)); b.raw(ESC.LF);
    await b.text('单据日期:' + clipW(data.date_time || '', RCOLS - 10)); b.raw(ESC.LF);
    const wh = (isRet ? '入库仓库:' : '出库仓库:') + clipW(data.warehouse || '天河仓', 16);
    await b.text(rPad('经手人:' + clipW(data.handler || '', 12), RCOLS - wLen(wh)) + wh); b.raw(ESC.LF);
    await b.text(DASHES); b.raw(ESC.LF);
    await b.text(rPad('序号 名称', R_LEFTW) + lPadN('数量', R_QTYW) + lPadN('单价', R_PRICEW) + lPadN('金额', R_AMTW)); b.raw(ESC.LF);
    await b.text(DASHES); b.raw(ESC.LF);
    const items = Array.isArray(data.items) ? data.items : [];
    let qtySum = 0;
    for (let i = 0; i < items.length; i++) {
      const it = items[i];
      const q = Number(it.qty || 0), p = Number(it.price || 0);
      qtySum += q;
      await b.text(clipW((i + 1) + ' ' + (it.name || ''), RCOLS)); b.raw(ESC.LF);
      const bc = String(it.barcode || '').trim();
      const amt = (it.subtotal != null && Number(it.subtotal) !== 0) ? Number(it.subtotal) : q * p;
      await b.text(rPad(bc ? '条码:' + clipW(bc, R_LEFTW - 6) : '', R_LEFTW)
        + lPadN(fmtQtyNum(q) + (it.unit || ''), R_QTYW) + lPadN(fmt2(p), R_PRICEW) + lPadN(fmt2(amt), R_AMTW));
      b.raw(ESC.LF);
    }
    await b.text(DASHES); b.raw(ESC.LF);
    const before = Number(data.before_total != null ? data.before_total : (data.total || 0));
    const pay = Number(data.payable != null ? data.payable : before);
    const saved = before - pay;
    const r1 = '合计折前金额:' + fmt2(before);
    await b.text(rPad('合计数量:' + fmtQtyNum(qtySum), RCOLS - wLen(r1)) + r1); b.raw(ESC.LF);
    const r2 = '优惠后金额:' + fmt2(pay);
    b.raw(ESC.BOLD_ON);
    await b.text(rPad('折扣优惠金额:' + fmt2(saved), RCOLS - wLen(r2)) + r2);
    b.raw(ESC.BOLD_OFF); b.raw(ESC.LF);
    await b.text(DASHES); b.raw(ESC.LF);
    await b.text('客户签收:____________________'); b.raw(ESC.LF);
    b.raw(ESC.ALIGN_CENTER);
    await b.text(clipW(data.footer || '谢谢惠顾，欢迎再次光临', RCOLS)); b.raw(ESC.LF);
    b.raw(ESC.ALIGN_LEFT);
    feedTail2cm(b);
    b.raw(ESC.CUT);
    return b.build();
  }
  async function buildProducts(data) {
    const b = new EscBuilder();
    b.raw(ESC.INIT).raw(ESC.CODPAGE_GBK).raw(ESC.ALIGN_CENTER).raw(ESC.BIG);
    await b.text(data.shop || SHOP || '');
    b.raw(ESC.NORMAL); await b.text((TPL && TPL.list_title) || '商品清单'); b.raw(ESC.LF);
    b.raw(ESC.ALIGN_LEFT); await b.text(DASHES);
    await b.line('打印时间', new Date().toLocaleString('zh-CN'));
    await b.text(DASHES);
    const items = Array.isArray(data.items) ? data.items : [];
    for (const it of items) {
      const name = it.name || '';
      const spec = it.spec || '';
      const unit = it.unit || '';
      const price = fmt2(it.price);
      const qty = it.qty || 0;
      const barcode = it.barcode || '';
      await b.text(name);
      await b.line((spec ? spec + ' ' : '') + (unit ? unit : ''), price); // 小票禁用 ¥(GBK 不可编码会乱码)，金额裸数字
      if (barcode) await b.line('条码:' + barcode, '');
      await b.line('库存:' + qty, '');
      await b.text(DASHES);
    }
    await b.text('共 ' + items.length + ' 项');
    feedTail2cm(b);
    b.raw(ESC.CUT);
    return b.build();
  }

  // ---- 网页打印机（localStorage 记忆默认） ----
  const WP_KEY = 'erp_web_printer';
  function wpLoad() { try { return JSON.parse(localStorage.getItem(WP_KEY) || 'null'); } catch (e) { return null; } }
  function wpSave(o) { localStorage.setItem(WP_KEY, JSON.stringify(o)); }
  function wpClear() { localStorage.removeItem(WP_KEY); }
  function webPrintSupported() { return !!(navigator.bluetooth || navigator.serial); }

  // BLE
  let bleDevice = null, bleChar = null;
  async function wbConnect(knownId) {
    if (!navigator.bluetooth) throw new Error('当前浏览器不支持 Web Bluetooth（需 HTTPS + 支持的浏览器）');
    let device;
    if (knownId) { try { const devs = await navigator.bluetooth.getDevices(); device = devs.find(d => d.id === knownId); } catch (e) {} }
    if (!device) device = await navigator.bluetooth.requestDevice({ acceptAllDevices: true, optionalServices: ['000018f0-0000-1000-8000-00805f9b34fb', '49535343-fe7f-4ae5-8fa9-9fafd205e455'] });
    const server = await device.gatt.connect();
    let char = null;
    const services = await server.getPrimaryServices();
    for (const svc of services) {
      const chars = await svc.getCharacteristics();
      for (const c of chars) { if (c.properties.write || c.properties.writeWithoutResponse) { char = c; break; } }
      if (char) break;
    }
    if (!char) throw new Error('该蓝牙设备未找到可写特征（可能不是打印机）');
    bleDevice = device; bleChar = char;
    wpSave({ kind: 'ble', id: device.id, name: device.name || '蓝牙打印机' });
    return { kind: 'ble', name: device.name || '蓝牙打印机' };
  }
  async function wbWrite(bytes) {
    if (!bleChar) throw new Error('蓝牙打印机未连接');
    const chunk = bleChar.properties.writeWithoutResponse ? 20 : 512;
    for (let i = 0; i < bytes.length; i += chunk) {
      const slice = bytes.subarray(i, i + chunk);
      if (bleChar.properties.writeWithoutResponse) await bleChar.writeValueWithoutResponse(slice);
      else await bleChar.writeValue(slice);
    }
  }
  // USB (Web Serial)
  let usbPort = null;
  async function wsConnect() {
    if (!navigator.serial) throw new Error('当前浏览器不支持 Web Serial（需 HTTPS + Chrome/Edge 桌面端）');
    const port = await navigator.serial.requestPort();
    await port.open({ baudRate: 9600 });
    usbPort = port;
    const info = port.getInfo();
    wpSave({ kind: 'usb', name: 'USB 打印机 ' + (info.usbVendorId ? ('0x' + info.usbVendorId.toString(16)) : '') });
    return { kind: 'usb', name: 'USB 打印机' };
  }
  async function wsWrite(bytes) {
    if (!usbPort || !usbPort.writable) throw new Error('USB 打印机未连接');
    const writer = usbPort.writable.getWriter();
    try { await writer.write(bytes); } finally { writer.releaseLock(); }
  }
  async function webPrintBytes(bytes) {
    const saved = wpLoad();
    let kind = saved && saved.kind;
    if (kind === 'ble' && !bleChar) { try { await wbConnect(saved.id); } catch (e) { kind = null; } }
    if (kind === 'usb' && !usbPort) { try { await wsConnect(); } catch (e) { kind = null; } }
    if (kind !== 'ble' && kind !== 'usb') {
      const choice = await chooseWebPrinter();
      if (!choice) throw new Error('未选择打印机');
      if (choice === 'ble') await wbConnect();
      else await wsConnect();
    }
    if (bleChar) await wbWrite(bytes);
    else if (usbPort) await wsWrite(bytes);
    else throw new Error('未连接到打印机');
  }
  function chooseWebPrinter() {
    return new Promise(resolve => {
      const opts = [];
      if (navigator.bluetooth) opts.push('<button class="btn-sm" data-wp="ble" style="margin:4px 0;width:100%;text-align:left">🔵 蓝牙打印机（BLE）</button>');
      if (navigator.serial) opts.push('<button class="btn-sm" data-wp="usb" style="margin:4px 0;width:100%;text-align:left">🔌 USB 打印机（Web Serial）</button>');
      opts.push('<button class="link" id="wp-cancel" style="width:100%;margin-top:8px">取消</button>');
      const note = (!navigator.bluetooth && !navigator.serial) ? '<div class="muted" style="margin-bottom:8px">当前环境不支持网页蓝牙/USB 打印（需通过 HTTPS 访问且用 Chrome/Edge 桌面端）。可改用「系统打印」。</div>' : '<div class="muted" style="margin-bottom:8px">选择打印机连接方式（首次连接需授权）</div>';
      showModal('选择网页打印机', note + opts.join(''));
      $('#wp-cancel').onclick = () => { closeModal(); resolve(null); };
      document.querySelectorAll('#modal-body [data-wp]').forEach(b => b.onclick = () => { closeModal(); resolve(b.dataset.wp); });
    });
  }
  async function webPrint(type, data) {
    if (!webPrintSupported()) {
      if (window.Capacitor) toast('未检测到原生打印插件，请更新/重装 App 后重试');
      else toast('当前环境不支持网页蓝牙/USB 打印（需 HTTPS 访问），改用系统打印');
      window.print();
      return;
    }
    try {
      const bytes = type === 'receipt' ? await buildReceipt(data) : await buildProducts(data);
      await webPrintBytes(bytes);
      toast('已发送打印');
    } catch (e) { toast('打印失败：' + (e.message || e)); }
  }

  function choosePrinter(list) {
    return new Promise(resolve => {
      const devices = Array.isArray(list) ? list : (list && list.devices) || [];
      if (!devices.length) { resolve(null); return; }
      const opts = devices.map(d => `<button class="btn-sm" data-mac="${esc(d.address)}" style="margin:4px 0;width:100%;text-align:left">${esc(d.name || d.address)}<br><span class="muted">${esc(d.address)}</span></button>`).join('');
      showModal('选择蓝牙打印机', `<div class="muted" style="margin-bottom:8px">从已配对设备中选择（如未列出请先在系统蓝牙配对）</div>${opts}<button class="link" id="cp-cancel" style="width:100%;margin-top:8px">取消</button>`);
      $('#cp-cancel').onclick = () => { closeModal(); resolve(null); };
      document.querySelectorAll('#modal-body [data-mac]').forEach(b => b.onclick = () => { closeModal(); resolve(b.dataset.mac); });
    });
  }
  async function nativePrint(type, data) {
    const P = getErpPrinter();
    if (P) {
      try {
        let st = await P.status();
        if (!st.connected) {
          let connected = false;
          if (st.lastMac) {
            const r = await P.autoConnect();
            connected = !!r.ok;
          }
          if (!connected) {
            const list = await P.list();
            const devices = Array.isArray(list) ? list : (list && list.devices) || [];
            if (!devices.length) { toast('未找到已配对打印机，请先在设置页扫描/配对'); return; }
            const mac = await choosePrinter(list);
            if (!mac) return;
            await P.connect({ mac });
            await P.setLastPrinter({ mac, name: '' });
            localStorage.setItem('erp_printer_mac', mac);
          }
        }
        if (type === 'receipt') await P.printReceipt(data);
        else await P.printProducts(data);
        toast('已发送打印');
      } catch (e) { toast('打印失败：' + (e.message || e)); }
      return;
    }
    // 网页路径：Web Bluetooth / Web Serial
    await webPrint(type, data);
  }

  // ============ 蓝牙打印机管理（软件内独立蓝牙，不调用系统打印） ============
  let btScanSubscribed = false;
  let btFound = {};

  function onBtDevice(evt) {
    if (!evt || !evt.address) return;
    btFound[evt.address] = { name: evt.name || evt.address, address: evt.address, bonded: !!evt.bonded };
    btRenderFound();
  }
  function onBtScanDone(evt) {
    const el = $('#bt-scan-status');
    if (el) el.textContent = '扫描完成，共发现 ' + (evt && evt.count != null ? evt.count : Object.keys(btFound).length) + ' 台设备';
    const btn = $('#bt-scan');
    if (btn) { btn.disabled = false; btn.textContent = '🔍 扫描附近打印机'; }
  }
  function btRenderFound() {
    const box = $('#bt-found');
    if (!box) return;
    const arr = Object.values(btFound);
    if (!arr.length) { box.innerHTML = '<div class="muted" style="padding:6px 0">未发现设备，请点击「扫描」并确保打印机处于可发现模式</div>'; return; }
    box.innerHTML = arr.map(d => `
      <div class="list-item"><div class="top">
        <span class="nm">${esc(d.name)} ${d.bonded ? '· 已配对' : ''}</span>
        <button class="btn-sm" data-bt="${esc(d.address)}">连接并设为默认</button>
      </div><div class="meta">${esc(d.address)}</div></div>`).join('');
    box.querySelectorAll('[data-bt]').forEach(b => b.onclick = () => btConnectDevice(b.dataset.bt));
  }

  async function btRefreshStatus() {
    const P = getBtPrinter();
    const box = $('#bt-status'); if (!box) return;
    if (!P) { box.innerHTML = '<span class="muted">当前环境不支持蓝牙打印（请使用安卓 App）</span>'; return; }
    try {
      const st = await P.status();
      const def = st.lastMac ? `${esc(st.lastName || st.lastMac)}（${esc(st.lastMac)}）` : '未设置';
      box.innerHTML = `
        <div class="row between"><span class="muted">蓝牙</span><span>${st.supported ? (st.adapterOn ? '已开启' : '未开启') : '不支持'}</span></div>
        <div class="row between" style="margin-top:6px"><span class="muted">连接状态</span><span style="font-weight:700;color:${st.connected ? 'var(--primary)' : 'var(--danger)'}">${st.connected ? '已连接' : '未连接'}</span></div>
        <div class="row between" style="margin-top:6px"><span class="muted">默认打印机</span><span>${def}</span></div>`;
    } catch (e) { box.innerHTML = '<span class="muted">读取状态失败</span>'; }
  }

  async function btScan() {
    const P = getBtPrinter(); if (!P) { toast('当前环境不支持蓝牙打印'); return; }
    if (!btScanSubscribed) {
      try { await P.addListener('devices', onBtDevice); await P.addListener('scanFinished', onBtScanDone); btScanSubscribed = true; } catch (e) {}
    }
    btFound = {};
    btRenderFound();
    const status = $('#bt-scan-status'); if (status) status.textContent = '扫描中…（请让打印机进入可发现模式）';
    const btn = $('#bt-scan'); if (btn) { btn.disabled = true; btn.textContent = '扫描中…'; }
    try { await P.scan(); }
    catch (e) { if (status) status.textContent = '扫描失败：' + (e.message || e); if (btn) { btn.disabled = false; btn.textContent = '🔍 扫描附近打印机'; } }
  }

  async function btConnectDevice(mac) {
    const P = getBtPrinter(); if (!P) { toast('当前环境不支持蓝牙打印'); return; }
    const dev = btFound[mac];
    const name = dev ? dev.name : mac;
    const btn = document.querySelector(`[data-bt="${mac}"]`);
    if (btn) { btn.disabled = true; btn.textContent = '连接中…'; }
    try {
      await P.connect({ mac });
      await P.setLastPrinter({ mac, name });
      localStorage.setItem('erp_printer_mac', mac);
      toast('已连接并设为默认：' + name);
      btRefreshStatus();
    } catch (e) { toast('连接失败：' + (e.message || e)); }
    finally { if (btn) { btn.disabled = false; btn.textContent = '连接并设为默认'; } }
  }

  async function btAutoConnect() {
    const P = getBtPrinter(); if (!P) { toast('当前环境不支持蓝牙打印'); return; }
    try { const r = await P.autoConnect(); toast(r.ok ? ('已连接 ' + (r.name || '')) : ('未连接：' + (r.reason || '无默认打印机'))); btRefreshStatus(); }
    catch (e) { toast('一键连接失败：' + (e.message || e)); }
  }

  async function btDisconnect() {
    const P = getBtPrinter(); if (!P) return;
    try { await P.disconnect(); toast('已断开'); btRefreshStatus(); } catch (e) { toast('断开失败：' + (e.message || e)); }
  }

  async function btClear() {
    const P = getBtPrinter(); if (!P) return;
    try { await P.removeSaved(); localStorage.removeItem('erp_printer_mac'); toast('已清除默认打印机'); btRefreshStatus(); } catch (e) { toast('清除失败：' + (e.message || e)); }
  }

  async function renderBtPrinter() {
    const P = getErpPrinter();
    if (P) { renderAndroidPrinter(); return; }
    if (webPrintSupported()) { renderWebPrinter(); return; }
    const page = $('#page'); if (!page) return;
    page.innerHTML = `<div class="card"><div class="section-title">🖨️ 打印机</div><div class="muted">当前环境不支持蓝牙/USB 打印（需通过 HTTPS 访问且用 Chrome/Edge 桌面端，或改用安卓 App）。网页端可用「系统打印」。</div></div><button class="btn-sm gray" id="bt-back" style="width:100%;margin-top:10px">← 返回设置</button>`;
    $('#bt-back').onclick = renderMe;
  }

  async function renderAndroidPrinter() {
    const page = $('#page'); if (!page) return;
    page.innerHTML = `
      <div class="card"><div class="section-title">🖨️ 蓝牙打印机</div>
        <div id="bt-status" class="muted">读取中…</div>
        <div class="row" style="gap:8px;margin-top:12px">
          <button class="btn-primary" id="bt-autoconn" style="flex:1">🔌 一键连接默认打印机</button>
          <button class="btn-sm gray" id="bt-disconn">断开</button>
        </div>
        <div class="row" style="gap:8px;margin-top:8px">
          <button class="btn-sm" id="bt-scan" style="flex:1">🔍 扫描附近打印机</button>
          <button class="btn-sm gray" id="bt-clear">清除默认</button>
        </div>
        <div id="bt-scan-status" class="muted" style="font-size:12px;margin-top:8px"></div>
      </div>
      <div class="card"><div class="section-title">发现的打印机</div>
        <div id="bt-found"><div class="muted" style="padding:6px 0">点击上方「扫描附近打印机」开始</div></div>
      </div>
      <button class="btn-sm gray" id="bt-back" style="width:100%;margin-top:10px">← 返回设置</button>`;
    $('#bt-autoconn').onclick = btAutoConnect;
    $('#bt-disconn').onclick = btDisconnect;
    $('#bt-scan').onclick = btScan;
    $('#bt-clear').onclick = btClear;
    $('#bt-back').onclick = renderMe;
    btRefreshStatus();
  }

  async function renderWebPrinter() {
    const page = $('#page'); if (!page) return;
    page.innerHTML = `
      <div class="card"><div class="section-title">🖨️ 网页打印机（蓝牙/USB）</div>
        <div id="wp-status" class="muted">读取中…</div>
        <div class="row" style="gap:8px;margin-top:12px">
          <button class="btn-primary" id="wp-usb" style="flex:1">🔌 连接 USB 打印机</button>
        </div>
        <div class="row" style="gap:8px;margin-top:8px">
          <button class="btn-sm" id="wp-scan" style="flex:1">🔵 扫描蓝牙打印机</button>
          <button class="btn-sm gray" id="wp-disconn">断开</button>
        </div>
        <div class="row" style="gap:8px;margin-top:8px">
          <button class="btn-sm gray" id="wp-clear">清除默认</button>
        </div>
        <div id="wp-scan-status" class="muted" style="font-size:12px;margin-top:8px"></div>
      </div>
      <div class="card"><div class="section-title">已选蓝牙打印机</div>
        <div id="wp-found"><div class="muted" style="padding:6px 0">点击上方「扫描蓝牙打印机」选择设备</div></div>
      </div>
      <button class="btn-sm gray" id="bt-back" style="width:100%;margin-top:10px">← 返回设置</button>`;
    $('#wp-usb').onclick = async () => { try { await wsConnect(); toast('已连接 USB 打印机'); wpRenderStatus(); } catch (e) { toast('连接失败：' + (e.message || e)); } };
    $('#wp-scan').onclick = wpBleScan;
    $('#wp-disconn').onclick = wpDisconnect;
    $('#wp-clear').onclick = () => { wpClear(); bleDevice = null; bleChar = null; if (usbPort) { try { usbPort.close(); } catch (e) {} usbPort = null; } toast('已清除默认打印机'); wpRenderStatus(); };
    $('#bt-back').onclick = renderMe;
    wpRenderStatus();
  }
  async function wpRenderStatus() {
    const el = $('#wp-status'); if (!el) return;
    const saved = wpLoad();
    let conn = '未连接';
    if (bleChar) conn = '蓝牙已连接' + (bleDevice && bleDevice.name ? '（' + bleDevice.name + '）' : '');
    else if (usbPort && usbPort.writable) conn = 'USB 已连接';
    el.innerHTML = `
      <div class="row between"><span class="muted">默认打印机</span><span>${saved ? esc(saved.name || saved.kind) : '未设置'}</span></div>
      <div class="row between" style="margin-top:6px"><span class="muted">连接状态</span><span style="font-weight:700;color:${conn !== '未连接' ? 'var(--primary)' : 'var(--danger)'}">${conn}</span></div>`;
  }
  let wpFound = {};
  function wpOnDevice(d) { wpFound[d.id] = { id: d.id, name: d.name || d.id }; wpRenderFound(); }
  function wpRenderFound() {
    const box = $('#wp-found'); if (!box) return;
    const arr = Object.values(wpFound);
    if (!arr.length) { box.innerHTML = '<div class="muted" style="padding:6px 0">未选择设备，请点击「扫描蓝牙打印机」</div>'; return; }
    box.innerHTML = arr.map(d => `<div class="list-item"><div class="top"><span class="nm">${esc(d.name)}</span><button class="btn-sm" data-wpid="${esc(d.id)}">连接并设为默认</button></div><div class="meta">${esc(d.id)}</div></div>`).join('');
    box.querySelectorAll('[data-wpid]').forEach(b => b.onclick = () => wpBleConnect(b.dataset.wpid));
  }
  async function wpBleScan() {
    if (!navigator.bluetooth) { toast('当前环境不支持 Web Bluetooth'); return; }
    const st = $('#wp-scan-status'); if (st) st.textContent = '请在弹出的设备列表中选择打印机…';
    try {
      const device = await navigator.bluetooth.requestDevice({ acceptAllDevices: true, optionalServices: ['000018f0-0000-1000-8000-00805f9b34fb', '49535343-fe7f-4ae5-8fa9-9fafd205e455'] });
      wpOnDevice(device);
      if (st) st.textContent = '已选择：' + (device.name || device.id) + '，正在连接…';
      await wbConnect(device.id);
      toast('已连接：' + (device.name || device.id));
      wpRenderStatus();
    } catch (e) { if (st) st.textContent = '扫描/连接失败：' + (e.message || e); }
  }
  async function wpBleConnect(id) {
    try { await wbConnect(id); toast('已连接并设为默认'); wpRenderStatus(); }
    catch (e) { toast('连接失败：' + (e.message || e)); }
  }
  async function wpDisconnect() {
    try { if (bleDevice && bleDevice.gatt) await bleDevice.gatt.disconnect(); } catch (e) {}
    try { if (usbPort) await usbPort.close(); } catch (e) {}
    bleDevice = null; bleChar = null; usbPort = null;
    toast('已断开'); wpRenderStatus();
  }

  // 登录后静默自动重连默认打印机（仅安卓 App 内有效）
  async function silentAutoConnect() {
    const P = getBtPrinter(); if (!P) return;
    try { await P.autoConnect(); } catch (e) {}
  }
  // ---------- 浏览器扫码（商品条码 / 二维码） ----------
  // 用浏览器原生 BarcodeDetector（Chrome/Edge 桌面 + Android Chrome 都支持）。
  // 识别到就填进目标输入框并自动关闭窗口。
  // ⚠️ getUserMedia 要求 HTTPS，erp.example.com 满足；localhost 也行。
  // 🔴 注意：BarcodeDetector 是实验 API，老 Safari 不支持，此时提示改用原生 App。
  function scanIntoField(input) {
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      toast('当前浏览器不支持摄像头扫码，请用手机 App'); return;
    }
    if (typeof window.BarcodeDetector === 'undefined') {
      toast('当前浏览器不支持条码识别（需 Chrome/Edge），请用手机 App'); return;
    }
    const fmt = ['ean_13', 'ean_8', 'upc_a', 'upc_e', 'code_128', 'code_39', 'itf', 'qr_code'];
    let det = null;
    try { det = new window.BarcodeDetector({ formats: fmt }); }
    catch (e) { try { det = new window.BarcodeDetector(); } catch (e2) { toast('条码识别初始化失败'); return; } }

    const ov = document.createElement('div');
    ov.id = 'scan-overlay';
    ov.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,.82);z-index:99999;'
      + 'display:flex;flex-direction:column;align-items:center;justify-content:center;gap:12px;padding:20px';
    ov.innerHTML = '<video id="scan-v" playsinline autoplay muted '
      + 'style="width:100%;max-width:420px;border-radius:12px;background:#000"></video>'
      + '<div id="scan-hint" style="color:#fff;font-size:14px">把条码/二维码对准取景框</div>'
      + '<button id="scan-x" style="padding:8px 22px;border:0;border-radius:8px;background:#333;color:#fff;font-size:14px">取消</button>';
    document.body.appendChild(ov);
    const video = ov.querySelector('#scan-v');
    let stream = null, raf = 0, closed = false, lastRaw = '', lastAt = 0;

    function close() {
      if (closed) return; closed = true;
      cancelAnimationFrame(raf);
      if (stream) stream.getTracks().forEach(t => t.stop());
      ov.remove();
    }
    function finish(raw) {
      input.value = String(raw).trim();
      try { input.dispatchEvent(new Event('input', { bubbles: true })); } catch (e) {}
      close();
      toast('已识别：' + raw);
    }
    async function loop() {
      if (closed) return;
      try {
        const list = await det.detect(video);
        if (list && list.length) {
          const raw = list[0].rawValue;
          if (raw) {
            const now = Date.now();
            if (raw !== lastRaw || now - lastAt > 1200) { lastRaw = raw; lastAt = now; finish(raw); return; }
          }
        }
      } catch (e) { /* 首帧可能还没准备好，忽略 */ }
      raf = requestAnimationFrame(loop);
    }
    ov.querySelector('#scan-x').onclick = close;
    navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' } }, audio: false })
      .then(s => {
        if (closed) { s.getTracks().forEach(t => t.stop()); return; }
        stream = s;
        video.srcObject = s;
        return video.play();
      })
      .then(() => { raf = requestAnimationFrame(loop); })
      .catch(e => { close(); toast('打不开摄像头：' + (e.message || e)); });
  }

  async function editProduct(id) {
    const all = await API.req('GET', '/api/products');
    let p = { name: '', barcode: '', spec: '', unit: '个', price: 0, cost: 0, qty: 0, low_threshold: 0, pack_size: 0 };
    if (id) p = all.find(x => x.id == id) || p;
    showModal(id ? '编辑商品' : '新增商品', `
      <label>名称 *</label><input id="f-name" value="${esc(p.name)}"/>
      <label style="display:flex;align-items:center;gap:8px">条码
        <input id="f-barcode" value="${esc(p.barcode || '')}" style="flex:1;margin:0"/>
        <button class="btn-sm gray" id="f-scan" type="button" style="flex:0 0 auto">📷 扫码</button>
      </label>
      <label>规格</label><input id="f-spec" value="${esc(p.spec || '')}"/>
      <label>单位</label><input id="f-unit" value="${esc(p.unit || '个')}"/>
      <label>每箱装量（件数换算用，0=不换算）</label><input id="f-pack" type="number" min="0" step="1" value="${Number(p.pack_size) || 0}"/>
      <div class="muted" style="font-size:11px;margin:-6px 0 10px">例：100g*8 的酸奶填 8。开单模板的「合计（件数）」= 数量 ÷ 这个值，保留 2 位小数（不足一箱显示小数，不进位）。单品销售（瓶/杯/支）填 0。</div>
      <label>单价（元）</label><input id="f-price" type="number" step="0.01" value="${p.price}"/>
      <label>进价（元，用于利润分析）</label><input id="f-cost" type="number" step="0.01" value="${p.cost || 0}"/>
      <button class="btn-primary" id="f-save" style="margin-top:14px">保存</button>`);
    $('#f-scan').onclick = () => scanIntoField($('#f-barcode'));
    $('#f-save').onclick = async () => {
      const body = { name: $('#f-name').value.trim(), barcode: $('#f-barcode').value.trim(), spec: $('#f-spec').value.trim(), unit: $('#f-unit').value.trim() || '个', price: parseFloat($('#f-price').value) || 0, cost: parseFloat($('#f-cost').value) || 0, pack_size: Math.max(0, parseInt($('#f-pack').value, 10) || 0) };
      if (!body.name) { toast('名称必填'); return; }
      try { const r = id ? await API.req('PUT', '/api/products/' + id, body) : await API.req('POST', '/api/products', body); closeModal(); toast('已保存'); notifyViolation(r.violation); renderProducts(); }
      catch (e) { toast(e.message); }
    };
  }

  // ---------- 设置 ----------
  // 可折叠分区的展开/收起
  function wireCollapse() {
    document.querySelectorAll('#page .sec-head').forEach(h => {
      h.onclick = () => { const sec = h.closest('.collapse-sec'); if (sec) sec.classList.toggle('open'); };
    });
  }
  // 信任设备管理：列出 / 解除
  // 🔴 1.5.1：接口返回结构从「数组」改成 { devices, max, used, left }。
  //   兼容旧结构（还是数组）以防服务端未更新时前端不崩。
  let TRUST_MAX = 4;
  async function renderTrustedDevices() {
    const box = $('#td-list'); if (!box) return;
    let list = [], meta = null;
    try {
      const r = await API.req('GET', '/api/trusted-devices');
      if (Array.isArray(r)) { list = r; } else { list = r.devices || []; meta = r; }
    } catch (e) { box.innerHTML = '<div class="muted">读取失败</div>'; return; }
    if (meta && meta.max) TRUST_MAX = meta.max;
    const cur = getDeviceToken();
    const used = meta ? (meta.used != null ? meta.used : list.length) : list.length;
    const left = meta && meta.left != null ? meta.left : Math.max(0, TRUST_MAX - used);
    // 满额提示：让经理在解除之前就知道上限是多少、还剩几台
    const head = `
      <div class="meta" style="margin-bottom:8px;display:flex;align-items:center;gap:6px;flex-wrap:wrap">
        <span>已信任 <b style="color:var(--primary)">${used}</b> / ${TRUST_MAX} 台</span>
        ${used > TRUST_MAX
          ? '<span style="color:var(--warn)">已超出上限（新设备不再被记住），请解除多余的设备</span>'
          : left <= 0
          ? '<span style="color:var(--warn)">已满额 · 新设备勾选信任将不再生效，请先解除一台</span>'
          : `<span class="muted">还可添加 ${left} 台</span>`}
      </div>`;
    if (!list.length) {
      box.innerHTML = head + '<div class="muted" style="padding:6px 0">暂无信任设备（登录时勾选「信任此设备」即可添加）</div>'
        + selfUnbindHTML() + wireSelfUnbind(box);
      return;
    }
    box.innerHTML = head + list.map(d => `
      <div class="list-item"><div class="top">
        <span class="nm">${esc(d.device || '未命名设备')}</span>
        <button class="btn-sm danger" data-td="${d.id}">解除</button>
      </div>
      <div class="meta">添加 ${esc((d.created_at || '').slice(0, 16))} · 最近使用 ${esc((d.last_used_at || '未使用').slice(0, 16))}</div>
      <div class="meta">有效期至 ${esc((d.expires_at || '').slice(0, 16))} ${d.expired ? '<span style="color:var(--danger)">（已过期）</span>' : ''}</div>
      </div>`).join('') +
      (list.length > 1 ? '<div class="row" style="gap:8px;margin-top:10px"><button class="btn-sm gray" id="td-clear" style="flex:1">解除其他所有设备</button></div>' : '') +
      selfUnbindHTML() + wireSelfUnbind(box);
    box.querySelectorAll('[data-td]').forEach(b => b.onclick = async () => {
      if (!confirm('解除后，该设备需要重新输入密码登录。确认？')) return;
      try { await API.req('DELETE', '/api/trusted-devices/' + b.dataset.td); toast('已解除'); renderTrustedDevices(); }
      catch (e) { toast(e.message); }
    });
    if ($('#td-clear')) $('#td-clear').onclick = async () => {
      if (!confirm('将解除除本机外的全部信任设备，确认？')) return;
      try {
        await API.req('DELETE', '/api/trusted-devices?keep_token=' + encodeURIComponent(cur));
        toast('已解除其他设备'); renderTrustedDevices();
      } catch (e) { toast(e.message); }
    };
  }

  // 🔴 v5.5：本机解绑。
  //   痛点：经理在「无痕/隐私窗口」里勾了「信任此设备」，当场能进，
  //   但关掉窗口 localStorage 就没了 —— 服务端那条信任记录还在，
  //   列表里也看不出哪条是自己，于是永远解不掉、直接占满 4 台名额。
  //   这个按钮按「浏览器特征」匹配本机记录，解掉后顺手清掉本地令牌。
  function selfUnbindHTML() {
    if (!getDeviceToken()) return '';
    return `<div style="margin-top:12px;padding-top:12px;border-top:1px solid var(--border)">
      <div class="meta" style="margin-bottom:8px">当前这台设备已被信任。若想关闭免密：</div>
      <button class="btn-sm gray" id="td-self" style="width:100%">解除本机的设备信任</button>
    </div>`;
  }
  function wireSelfUnbind(box) {
    const b = box.querySelector('#td-self');
    if (!b) return;
    b.onclick = async () => {
      if (!confirm('解除本机信任后，下次打开需重新输入账号密码。确认？')) return;
      const cur = getDeviceToken();
      b.disabled = true; b.textContent = '解除中…';
      try {
        // keep_token=当前令牌 → 服务端只删这一台，其他设备不受影响
        await API.req('DELETE', '/api/trusted-devices?keep_token=' + encodeURIComponent(cur));
        localStorage.removeItem('erp_device_token');
        toast('已解除本机信任，下次需重新登录');
        renderTrustedDevices();
      } catch (e) {
        b.disabled = false; b.textContent = '解除本机的设备信任';
        toast(e.message || '解除失败');
      }
    };
  }

  // ---------- 平台管理（仅 is_admin） ----------
  async function renderAdmin() {
    if (!ME.isAdmin) { $('#page').innerHTML = '<div class="card muted" style="padding:16px">无权限：仅平台管理员可进入</div>'; return; }
    let d;
    try { d = await API.req('GET', '/api/admin/overview'); }
    catch (e) { $('#page').innerHTML = '<div class="err" style="padding:16px">' + esc(e.message) + '</div>'; return; }

    const st = d.stats || {};
    const lvTxt = { high: '严重', mid: '中等', risky: '风险' };
    const lvColor = { high: 'var(--danger)', mid: '#d97706', risky: '#0891b2' };
    const stTxt = { pending: '待整改', resolved: '已整改', banned: '已封禁' };
    const stColor = { pending: '#d97706', resolved: '#059669', banned: 'var(--danger)' };

    $('#page').innerHTML = `
      <div class="card">
        <div class="section-title">平台概览</div>
        <div class="admin-stat-row">
          <div class="admin-stat"><div class="as-n">${st.total || 0}</div><div class="as-l">总账号</div></div>
          <div class="admin-stat"><div class="as-n" style="color:#d97706">${st.pending || 0}</div><div class="as-l">待整改</div></div>
          <div class="admin-stat"><div class="as-n" style="color:var(--danger)">${st.banned || 0}</div><div class="as-l">已封禁</div></div>
          <div class="admin-stat"><div class="as-n" style="color:#059669">${st.whitelisted || 0}</div><div class="as-l">白名单</div></div>
          <div class="admin-stat"><div class="as-n">${st.auto_banned || 0}</div><div class="as-l">自动封禁</div></div>
        </div>
      </div>

      <div class="card">
        <div class="section-title">用户账号</div>
        <div class="table-wrap">
        <table class="admin-table">
          <thead><tr><th>账号</th><th>姓名</th><th>角色</th><th>状态</th><th>标记</th><th style="width:210px">操作</th></tr></thead>
          <tbody>
          ${(d.accounts || []).map(a => `
            <tr>
              <td>${esc(a.username)}</td>
              <td>${esc(a.display_name || '')}</td>
              <td>${esc(a.roleLabel || a.role)}</td>
              <td>${a.active
                    ? '<span style="color:#059669">正常</span>'
                    : '<span style="color:var(--danger)" title="' + esc(a.banned_reason || '') + '">已封禁</span>'}</td>
              <td>${a.is_admin ? '<span class="tag-admin">管理员</span>' : ''}${a.whitelisted ? '<span class="tag-wl">白名单</span>' : ''}</td>
              <td>
                ${a.is_admin ? '<span class="muted">—</span>' : (a.active
                  ? '<button class="btn-sm" style="background:var(--danger)" data-ban="' + a.id + '">封禁</button>'
                  : '<button class="btn-sm" style="background:#059669" data-unban="' + a.id + '">解封</button>')}
                ${a.is_admin ? '' : '<button class="btn-sm gray" data-wl="' + a.id + '" data-on="' + (a.whitelisted ? 1 : 0) + '">' + (a.whitelisted ? '移出白名单' : '加白名单') + '</button>'}
              </td>
            </tr>`).join('')}
          </tbody>
        </table>
        </div>
      </div>

      <div class="card">
        <div class="section-title">违规记录</div>
        ${(d.violations || []).length ? `
        <div class="table-wrap">
        <table class="admin-table">
          <thead><tr><th>状态</th><th>级别</th><th>内容</th><th>命中词</th><th>操作人</th><th>截止</th><th style="width:70px"></th></tr></thead>
          <tbody>
          ${d.violations.map(v => `
            <tr>
              <td><span style="color:${stColor[v.status] || '#666'};font-weight:600">${stTxt[v.status] || v.status}</span></td>
              <td><span style="color:${lvColor[v.level] || '#666'}">${lvTxt[v.level] || v.level}</span></td>
              <td style="max-width:180px;overflow:hidden;text-overflow:ellipsis">${esc(v.target_name || '')}</td>
              <td style="color:#b91c1c">${esc(v.words || '')}</td>
              <td>${esc(v.display_name || v.username || '')}</td>
              <td class="muted" style="font-size:12px">${esc(v.deadline || '')}</td>
              <td>${v.status === 'pending' ? '<button class="btn-sm gray" data-vres="' + v.id + '">标记已改</button>' : ''}</td>
            </tr>`).join('')}
          </tbody>
        </table>
        </div>` : '<div class="muted" style="padding:12px">暂无违规记录</div>'}
      </div>

      <div class="card">
        <div class="section-title">治理日志</div>
        ${(d.logs || []).length ? `
        <div class="table-wrap">
        <table class="admin-table">
          <thead><tr><th>时间</th><th>账号</th><th>动作</th><th>原因</th><th>操作人</th></tr></thead>
          <tbody>
          ${d.logs.map(l => `
            <tr>
              <td class="muted" style="font-size:12px">${esc(l.created_at || '')}</td>
              <td>${esc(l.username || '')}</td>
              <td>${{ ban: '<span style="color:var(--danger)">人工封禁</span>', auto_ban: '<span style="color:var(--danger)">自动封禁</span>', unban: '<span style="color:#059669">解封</span>', whitelist: '<span style="color:#0891b2">白名单</span>' }[l.action] || esc(l.action)}</td>
              <td style="max-width:240px;overflow:hidden;text-overflow:ellipsis">${esc(l.reason || '')}</td>
              <td>${l.operator_id ? '管理员' : '系统'}</td>
            </tr>`).join('')}
          </tbody>
        </table>
        </div>` : '<div class="muted" style="padding:12px">暂无治理记录</div>'}
      </div>`;

    // 意见反馈入口（仅管理员可见可回复）
    const fbc = document.createElement('div');
    fbc.className = 'card';
    fbc.innerHTML = '<div class="section-title">💬 意见反馈</div>'
      + '<div class="muted" style="font-size:12px;margin-bottom:10px">用户提交的 Bug / 功能需求 / 账户问题只有管理员可查看与回复。附件仅管理员与提交者本人可见。</div>'
      + '<button class="btn-primary" id="adm-fb-open">打开反馈收件箱</button>';
    $('#page').appendChild(fbc);
    const fbo = $('#adm-fb-open', fbc);
    if (fbo && window.ERPFeedback) fbo.onclick = () => window.ERPFeedback.openInbox();

    // 封禁（需二次确认 + 填原因）
    $$('#page [data-ban]').forEach(btn => btn.onclick = () => {
      const id = btn.dataset.ban;
      const acc = (d.accounts || []).find(a => String(a.id) === String(id));
      showModal('封禁账号', `
        <div style="margin-bottom:10px">确定封禁 <b>${esc(acc ? acc.username : '')}</b>（${esc(acc ? (acc.display_name || '') : '')}）？</div>
        <div class="muted" style="margin-bottom:10px">封禁后该账号立即踢下线且无法登录。</div>
        <label>封禁原因</label>
        <input id="ban-reason" placeholder="如：违规内容超期未整改" value="违反使用规范"/>`);
      const foot = document.createElement('div');
      foot.className = 'row';
      foot.style.cssText = 'gap:8px;justify-content:flex-end;margin-top:14px';
      foot.innerHTML = '<button class="btn-sm gray" id="ban-cancel">取消</button>' +
        '<button class="btn-sm" style="background:var(--danger)" id="ban-ok">确认封禁</button>';
      const mb = document.getElementById('modal-body');
      if (!mb) return;
      // 🔴 2026-10-18：原来这段 appendChild 写在 ban-ok 的绑定**下面**，
      // 而 ban-ok 是 foot.innerHTML 里才生成的 —— 绑定时节点根本不在 DOM 里，
      // getElementById 返回 null → `Cannot set properties of null (setting 'onclick')`，
      // 结果「取消」能用、「确认封禁」点了没反应。必须先入 DOM 再绑定。
      mb.appendChild(foot);
      const banOk = document.getElementById('ban-ok');
      const banCancel = document.getElementById('ban-cancel');
      const banReason = document.getElementById('ban-reason');
      if (banCancel) banCancel.onclick = closeModal;
      if (banOk) banOk.onclick = async () => {
        const reason = banReason ? banReason.value : '违反使用规范';
        banOk.disabled = true;
        try {
          await API.req('POST', '/api/admin/accounts/' + id + '/ban', { reason });
          closeModal(); toast('已封禁'); renderAdmin();
        } catch (e) { banOk.disabled = false; toast(e.message); }
      };
    });

    // 解封
    $$('#page [data-unban]').forEach(btn => btn.onclick = async () => {
      if (!confirm('确定解封该账号？')) return;
      try {
        await API.req('POST', '/api/admin/accounts/' + btn.dataset.unban + '/unban', {});
        toast('已解封'); renderAdmin();
      } catch (e) { toast(e.message); }
    });

    // 白名单切换
    $$('#page [data-wl]').forEach(btn => btn.onclick = async () => {
      const on = btn.dataset.on === '1';
      try {
        await API.req('POST', '/api/admin/accounts/' + btn.dataset.wl + '/whitelist', { on: !on });
        toast(on ? '已移出白名单' : '已加入白名单'); renderAdmin();
      } catch (e) { toast(e.message); }
    });

    // 违规标记已整改
    $$('#page [data-vres]').forEach(btn => btn.onclick = async () => {
      if (!confirm('确认该违规内容已被整改？')) return;
      try {
        await API.req('POST', '/api/admin/violations/' + btn.dataset.vres + '/resolve', { note: '管理员确认已整改' });
        toast('已标记'); renderAdmin();
      } catch (e) { toast(e.message); }
    });
  }

  async function renderMe() {    const s = await API.req('GET', '/api/stats');
    const isBoss = ME.role === 'boss';
    $('#page').innerHTML = `
      <div class="card"><div class="section-title">账号信息</div>
        <div class="row between"><span class="muted">账号</span><span>${esc(ME.account ? ME.account.username : '')}</span></div>
        <div class="row between" style="margin-top:6px"><span class="muted">姓名</span><span>${esc(ME.account ? (ME.account.display_name || '') : '')}</span></div>
        <div class="row between" style="margin-top:6px"><span class="muted">角色</span><span style="font-weight:700">${esc(ME.roleLabel || '')}</span></div>
        <div class="row between" style="margin-top:6px"><span class="muted">当前套账</span><span>${esc(ME.book ? ME.book.name : '')}</span></div>
      </div>
      <div class="card"><div class="section-title">今日概览</div>
        <div class="row between"><span class="muted">销售额</span><span style="font-weight:700;color:var(--danger)">${fmt(s.todaySales)}</span></div>
        <div class="row between" style="margin-top:6px"><span class="muted">订单数</span><span style="font-weight:700">${s.todayOrders}</span></div>
      </div>
      ${isBoss ? `
      <div class="collapse-sec" id="sec-books">
        <button class="sec-head" type="button"><span class="sec-title">📚 套账管理</span><span class="sec-arrow">▸</span></button>
        <div class="sec-body">
          <label>新增套账名称</label>
          <div class="row" style="gap:8px"><input id="set-bookname" placeholder="如：示例套账"/><button class="btn-sm" id="set-bookadd" style="white-space:nowrap">创建</button></div>
          <label style="margin-top:10px">本套账加入口令（员工注册加入时需填；已加密存储，保存后不可查看）</label>
          <div class="row" style="gap:8px"><input id="set-joinpwd" type="password" placeholder="${TPL.join_password_set ? '已设置（留空不改，输入新值可重设）' : '未设置'}"/><button class="btn-sm" id="set-joinpwd-save" style="white-space:nowrap">保存</button><button class="btn-sm" id="set-joinpwd-clear" style="white-space:nowrap">清除</button></div>
          <div style="margin-top:12px;border-top:1px solid #f0f0f0;padding-top:10px">
            <label>当前套账设置</label>
            <div class="row" style="gap:8px;margin-top:6px">
              <button class="btn-sm" id="set-book-share" style="white-space:nowrap">邀请员工加入</button>
              <button class="btn-sm danger" id="set-book-del" style="white-space:nowrap">删除当前套账</button>
            </div>
          </div>
          <div id="book-list" style="margin-top:10px"></div>
        </div>
      </div>
      <div class="collapse-sec" id="sec-accounts">
        <button class="sec-head" type="button"><span class="sec-title">👥 账户与角色管理</span><span class="sec-arrow">▸</span></button>
        <div class="sec-body">
          <label>新增账户</label>
          <input id="acc-user" placeholder="账号（手机号）"/>
          <input id="acc-name" placeholder="姓名" style="margin-top:8px"/>
          <input id="acc-pwd" placeholder="初始密码（至少4位）" style="margin-top:8px"/>
          <select id="acc-role" style="margin-top:8px">
            <option value="staff">员工（开单+订单+商品查看）</option>
            <option value="manager">经理（除设置/账户管理外全部）</option>
            <option value="boss">老板（全部权限）</option>
          </select>
          <button class="btn-primary" id="acc-add" style="margin-top:10px">添加账户</button>
          <div id="acc-list" style="margin-top:12px"></div>
        </div>
      </div>` : ''}
      <div class="collapse-sec open" id="sec-basic">
        <button class="sec-head" type="button"><span class="sec-title">⚙️ 基础设置</span><span class="sec-arrow">▸</span></button>
        <div class="sec-body">
          <label>公司 / 店铺名称</label>
          <div class="row" style="gap:8px"><input id="set-shop" value="${esc(SHOP)}"/><button class="btn-sm" id="set-shop-save" style="white-space:nowrap">保存</button></div>
          <div class="sub-title">🖨️ 打印模板</div>
          <label>小票标题（默认：销售小票）</label><input id="set-rtitle" value="${esc(TPL.receipt_title || '')}"/>
          <label style="margin-top:8px">小票页脚（默认：谢谢惠顾）</label><input id="set-rfooter" value="${esc(TPL.receipt_footer || '')}"/>
          <label style="margin-top:8px">商品清单标题（默认：商品清单）</label><input id="set-ltitle" value="${esc(TPL.list_title || '')}"/>
          <label style="margin-top:8px">出库仓库（打印在小票抬头，默认：天河仓）</label><input id="set-rwarehouse" value="${esc(TPL.receipt_warehouse || '')}"/>
          <label style="margin-top:8px">经手人（留空=用登录账号名）</label><input id="set-rhandler" value="${esc(TPL.receipt_handler || '')}"/>
          <button class="btn-primary" id="set-tpl-save" style="margin-top:10px">保存打印模板</button>
          <div class="sub-title">📐 打印模板引擎（模块化）</div>
          <div class="muted" style="font-size:12px;margin-bottom:8px">
            可自定义打印尺寸（58mm / 80mm / 任意宽度、列数、边距、底部留白）与打印内容模块
            （店名、单据类型、客户信息、条码、单价、金额、合计、签收…）。
            蓝牙打印 / 系统打印 / 订单预览 / 保存为图片 四个出口全部套用同一份模板，版式永远一致。
          </div>
          <div class="row" style="gap:8px;flex-wrap:wrap">
            <button class="btn-primary" id="set-tpl-manage">管理打印模板</button>
            <button class="btn-sm gray" id="set-tpl-pick">看看打单时选模板的效果</button>
          </div>
          <div class="muted" style="font-size:12px;margin-top:8px">
            当前默认模板：<b id="set-tpl-def">—</b>　共 <b id="set-tpl-count">0</b> 套。<br>
            打单时会先弹出模板选择器，勾选「以后都用这个」即可固化为默认。
          </div>
          <div class="sub-title">🔑 修改密码</div>
          <input id="set-oldpwd" type="password" placeholder="旧密码" autocomplete="off" style="margin-bottom:8px"/>
          <input id="set-newpwd" type="password" placeholder="新密码（至少 4 位）" autocomplete="off"/>
          <button class="btn-primary" id="set-pwd-save" style="margin-top:10px">保存新密码</button>
        </div>
      </div>
      <div class="collapse-sec" id="sec-fb">
        <button class="sec-head" type="button"><span class="sec-title">💬 意见反馈</span><span class="sec-arrow">▸</span></button>
        <div class="sec-body">
          <div class="muted" style="font-size:12px;margin-bottom:8px">提交 Bug 反馈、功能需求、账户问题，可附截图。管理员可在本页查看全部反馈并回复。</div>
          <div class="row" style="gap:8px;flex-wrap:wrap">
            <button class="btn-primary" id="set-fb-new">提交反馈</button>
            <button class="btn-sm" id="set-fb-list">我的反馈${ME.isAdmin ? ' / 全部反馈' : ''}</button>
            <a class="btn-sm gray" href="privacy.html" target="_blank" style="text-decoration:none;line-height:28px">📄 隐私政策</a>
          </div>
          ${isBoss ? `<label style="margin-top:12px">管理员推送 Webhook（选填，企业微信/钉钉机器人地址；留空则只在站内红点提醒）</label>
          <div class="row" style="gap:8px"><input id="set-fb-hook" value="${esc(TPL.feedback_webhook || '')}" placeholder="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=..."/><button class="btn-sm" id="set-fb-hook-save" style="white-space:nowrap">保存</button></div>` : ''}
        </div>
      </div>
      <div class="collapse-sec" id="set-update-card" style="display:none">
        <button class="sec-head" type="button"><span class="sec-title">📦 版本与更新</span><span class="sec-arrow">▸</span></button>
        <div class="sec-body">
          <div class="row between" style="align-items:center"><span class="muted">当前版本</span><span id="set-ver">加载中…</span></div>
          <div id="set-update-info" class="muted" style="font-size:12px;margin:8px 0;display:none"></div>
          <button class="btn-primary" id="set-checkupd" style="margin-top:6px">检查更新</button>
          <button class="btn-primary" id="set-doupd" style="margin-top:8px;display:none">立即更新</button>
        </div>
      </div>
      <div class="collapse-sec" id="sec-bt">
        <button class="sec-head" type="button"><span class="sec-title">🖨️ 蓝牙打印</span><span class="sec-arrow">▸</span></button>
        <div class="sec-body">
          <div class="muted" style="font-size:12px;margin-bottom:8px">软件内独立蓝牙打印（不调用手机系统打印）。可扫描并记住默认打印机，开单后一键连接打印、App 启动自动重连。</div>
          <button class="btn-primary" id="set-btprinter" style="margin-top:4px">管理蓝牙打印机</button>
        </div>
      </div>
      <div class="collapse-sec" id="sec-td">
        <button class="sec-head" type="button"><span class="sec-title">🔐 信任设备</span><span class="sec-arrow">▸</span></button>
        <div class="sec-body">
          <div class="muted" style="font-size:12px;margin-bottom:8px">勾选「信任此设备」登录后，该设备在 30 天内无需再输入密码。最多信任 4 台，已满后新设备不再被记住（需先解除一台）。以下为当前账户已信任的设备，可随时解除。</div>
          <div id="td-list"></div>
        </div>
      </div>
      <div class="card muted" style="font-size:12px">店铺：${esc(SHOP)}<br>数据存于服务器 SQLite（按套账隔离），本地不落盘。</div>`;
    renderTrustedDevices();
    wireCollapse();
    if (isBoss) { renderBooks(); renderAccounts(); $('#set-bookadd').onclick = addBook; $('#set-joinpwd-save').onclick = saveJoinPwd; $('#set-joinpwd-clear').onclick = clearJoinPwd; $('#set-book-share').onclick = openBookShare; $('#set-book-del').onclick = openBookDelete; $('#acc-add').onclick = addAccount; }
    $('#set-shop-save').onclick = async () => {
      const n = $('#set-shop').value.trim();
      if (!n) { toast('店名不能为空'); return; }
      try {
        const d = await API.req('PUT', '/api/settings', { shop_name: n });
        SHOP = d.shop_name || n;
        localStorage.setItem('erp_shop', SHOP);
        $('#shop-name').textContent = SHOP + ' · ' + (ME.roleLabel || '');
        toast('店名已保存');
        renderMe();
      } catch (e) { toast(e.message); }
    };
    $('#set-tpl-save').onclick = async () => {
      try {
        await API.req('PUT', '/api/settings', { receipt_title: $('#set-rtitle').value.trim(), receipt_footer: $('#set-rfooter').value.trim(), list_title: $('#set-ltitle').value.trim(), receipt_warehouse: $('#set-rwarehouse').value.trim(), receipt_handler: $('#set-rhandler').value.trim() });
        await loadTpl();
        toast('打印模板已保存');
      } catch (e) { toast(e.message); }
    };
    $('#set-pwd-save').onclick = async () => {
      const old_password = $('#set-oldpwd').value, new_password = $('#set-newpwd').value;
      if (!old_password || !new_password) { toast('请填写旧密码和新密码'); return; }
      if (new_password.length < 4) { toast('新密码至少 4 位'); return; }
      try {
        await API.req('PUT', '/api/settings/password', { old_password, new_password });
        toast('密码已修改');
        $('#set-oldpwd').value = ''; $('#set-newpwd').value = '';
      } catch (e) { toast(e.message); }
    };
    $('#set-btprinter').onclick = renderBtPrinter;
    // 打印模板引擎
    const tm = $('#set-tpl-manage'), tp = $('#set-tpl-pick');
    if (tm) tm.onclick = manageTemplates;
    if (tp) tp.onclick = () => { pickTemplate().then(() => toast('已按所选模板打单（此处仅演示选择流程）')); };
    const td = $('#set-tpl-def'), tc = $('#set-tpl-count');
    if (td && window.ERPTpl) {
      const d0 = getTplById(TPL_DEF);
      td.textContent = d0.name + '（' + d0.paper.width_mm + 'mm / ' + d0.paper.cols + ' 列）';
      tc.textContent = String(TPL_LIST.length);
    }
    // 意见反馈
    const fbn = $('#set-fb-new'), fbl = $('#set-fb-list');
    if (fbn && window.ERPFeedback) fbn.onclick = () => window.ERPFeedback.openSubmit();
    if (fbl && window.ERPFeedback) fbl.onclick = () => window.ERPFeedback.openInbox();
    const fbh = $('#set-fb-hook-save');
    if (fbh) fbh.onclick = async () => {
      const v = $('#set-fb-hook').value.trim();
      try { await API.req('PUT', '/api/settings', { feedback_webhook: v }); await loadTpl(); toast(v ? '已保存推送地址' : '已关闭站外推送'); }
      catch (e) { toast(e.message); }
    };

    // 在线更新（仅安卓 App 内可用）
    const U = getErpUpdate();
    if (isNativeEnv()) {
      // 原生环境一定显示「版本与更新」卡片，避免功能静默消失
      const updCard = $('#set-update-card');
      if (updCard) { updCard.style.display = ''; updCard.classList.add('open'); }
      if (U) {
        try {
          const ver = await U.getVersion();
          const vEl = $('#set-ver');
          if (vEl) vEl.textContent = (ver.versionName || '') + ' (build ' + (ver.versionCode != null ? ver.versionCode : '?') + ')';
        } catch (e) { $('#set-ver').textContent = '读取失败'; }
        const box = { current: null };   // 当前可用的更新信息（#set-doupd 点击时读取）
        $('#set-checkupd').onclick = async () => {
          const btn = $('#set-checkupd');
          btn.disabled = true; btn.textContent = '检查中…';
          try {
            const u = await U.checkUpdate();
            if (u.available) {
              _applyApkUpdate(U, u, box);
              try { localStorage.setItem(APK_SEEN, String(u.versionCode || 0)); } catch (e) {}
              toast('发现新版本 ' + u.versionName);
            } else {
              box.current = null;
              $('#set-update-info').style.display = 'block';
              $('#set-update-info').textContent = '已是最新版本（' + (u.localVersionName || '') + '）';
              $('#set-doupd').style.display = 'none';
            }
          } catch (e) { toast('检查更新失败：' + e.message); }
          finally { btn.disabled = false; btn.textContent = '检查更新'; }
        };
        // 打开「版本与更新」时自动静默查一次，不用用户手动点
        (async () => {
          try {
            const u = await U.checkUpdate();
            if (u.available) { _applyApkUpdate(U, u, box); try { localStorage.setItem(APK_SEEN, String(u.versionCode || 0)); } catch (e) {} }
          } catch (e) { /* 静默 */ }
        })();
        $('#set-doupd').onclick = async () => {
          const url = box.current && box.current.url;
          if (!url) { toast('缺少下载地址'); return; }
          const btn = $('#set-doupd');
          btn.disabled = true; btn.textContent = '下载并安装中…';
          try {
            await U.downloadAndInstall({ url });
            toast('已调起系统安装，请按提示完成更新');
          } catch (e) {
            toast('自动安装失败，改用浏览器下载：' + e.message);
            if (url) window.open(url, '_blank');
          } finally { btn.disabled = false; btn.textContent = '立即更新'; }
        };
      } else {
        $('#set-ver').textContent = '更新组件未就绪';
        $('#set-checkupd').onclick = () => toast('更新组件未加载，请完全退出后重新打开 App');
      }
    }
  }

  // 当前套账：分享（邀请码）
  async function openBookShare() {
    let inv;
    try { inv = await API.req('GET', '/api/books/invite'); } catch (e) { toast(e.message || '获取邀请码失败'); return; }
    const shareTxt = `【${inv.book_name}】进销存注册邀请\n套账：${inv.book_name}\n邀请码：${inv.invite_code}\n注册时选择「加入：${inv.book_name}」，加入口令处填邀请码即可`;
    showModal('邀请员工加入「' + esc(inv.book_name) + '」', `
      <div class="muted" style="margin-bottom:8px;font-size:13px">员工注册时选择「加入：${esc(inv.book_name)}」，在加入口令处填以下邀请码即可（免口令）：</div>
      <div class="row" style="gap:10px;justify-content:center;margin:12px 0">
        <span style="font-size:24px;font-weight:800;letter-spacing:5px;font-family:monospace">${esc(inv.invite_code)}</span>
        <button class="btn-sm" id="inv-copy">复制</button>
      </div>
      <button class="btn-sm" id="inv-copy-all" style="width:100%">复制邀请文案</button>
      <div class="muted" style="font-size:12px;margin-top:10px">加入口令：${inv.has_password ? '已设置' : '未设置'} · 邀请码可随时重置，重置后旧邀请码立即失效。</div>
      <div style="margin-top:8px;text-align:right"><button class="btn-sm gray" id="inv-reset">重置邀请码</button></div>`);
    $('#inv-copy').onclick = () => copyText(inv.invite_code);
    $('#inv-copy-all').onclick = () => copyText(shareTxt);
    $('#inv-reset').onclick = async () => {
      if (!confirm('确定重置邀请码？重置后旧邀请码立即失效。')) return;
      try { const r = await API.req('POST', '/api/books/invite/reset'); inv.invite_code = r.invite_code; toast('已重置'); openBookShare(); } catch (e) { toast(e.message || '重置失败'); }
    };
  }

  // 当前套账：删除（危险操作，需输入套账名确认）
  function openBookDelete() {
    const name = ME.book ? ME.book.name : '';
    if (!name) return;
    showModal('删除套账「' + esc(name) + '」', `
      <div style="color:var(--danger);font-weight:700;margin-bottom:8px">⚠️ 此操作不可恢复！</div>
      <div class="muted" style="font-size:13px;margin-bottom:8px">将永久删除该套账的全部数据：订单、客户、商品、库存、价目、送货单及账户。只属于该套账的账号将被删除。</div>
      <label>请输入套账名「${esc(name)}」确认：</label>
      <input id="del-book-confirm" placeholder="${esc(name)}" style="margin-top:6px"/>
      <button class="btn-sm danger" id="del-book-go" style="width:100%;margin-top:12px">永久删除该套账</button>`);
    $('#del-book-go').onclick = async () => {
      if ($('#del-book-confirm').value.trim() !== name) { toast('名称不一致'); return; }
      if (!confirm('最后确认：永久删除套账「' + name + '」及其全部数据？')) return;
      try {
        const r = await API.req('POST', '/api/books/delete', { confirm_name: name });
        if (r.logged_out) { toast('套账已删除'); setTimeout(() => location.reload(), 800); return; }
        ME.book = r.book; SHOP = r.shopName || r.book.name;
        $('#modal-overlay').classList.add('hidden');
        toast('已删除，已切换到 ' + r.book.name);
        const m = await API.req('GET', '/api/me'); ME = Object.assign(ME, m); renderMe();
      } catch (e) { toast(e.message || '删除失败'); }
    };
  }

  // 套账列表
  async function renderBooks() {
    const box = $('#book-list'); if (!box) return;
    const books = await API.req('GET', '/api/books/my');
    box.innerHTML = books.map(b => `<div class="row between" style="padding:6px 0;border-bottom:1px solid #f0f0f0"><span>${esc(b.name)}${ME.book && ME.book.id === b.id ? '（当前）' : ''}</span>${ME.book && ME.book.id === b.id ? '' : `<button class="btn-sm gray" data-book="${b.id}">切换</button>`}</div>`).join('');
    box.querySelectorAll('[data-book]').forEach(btn => btn.onclick = async () => {
      try {
        const r = await API.req('POST', '/api/books/switch', { book_id: +btn.dataset.book });
        ME.book = r.book; SHOP = r.shopName || r.book.name;
        await loadTpl();
        toast('已切换到 ' + r.book.name);
        renderMe();
        $('#shop-name').textContent = SHOP + ' · ' + (ME.roleLabel || '');
      } catch (e) { toast(e.message); }
    });
  }
  async function addBook() {
    const name = $('#set-bookname').value.trim();
    if (!name) { toast('套账名称必填'); return; }
    try { await API.req('POST', '/api/books', { name }); toast('已创建套账 ' + name); const m = await API.req('GET', '/api/me'); ME = m; renderMe(); }
    catch (e) { toast(e.message); }
  }
  async function saveJoinPwd() {
    const jp = $('#set-joinpwd').value.trim();
    if (!jp) { toast('留空未修改（如需取消口令请点「清除」）'); return; }
    try {
      await API.req('PUT', '/api/books/join-password', { join_password: jp });
      TPL.join_password_set = true; $('#set-joinpwd').value = '';
      toast('加入口令已保存（已加密，不可查看）');
    } catch (e) { toast(e.message); }
  }
  async function clearJoinPwd() {
    if (!confirm('确定清除本套账的加入口令？清除后员工注册加入无需口令。')) return;
    try {
      await API.req('PUT', '/api/books/join-password', { join_password: '' });
      TPL.join_password_set = false; $('#set-joinpwd').value = '';
      toast('加入口令已清除');
    } catch (e) { toast(e.message); }
  }
  // 账户与角色
  async function renderAccounts() {
    const box = $('#acc-list'); if (!box) return;
    const list = await API.req('GET', '/api/accounts');
    box.innerHTML = list.map(a => `<div class="list-item"><div class="top"><span class="nm">${esc(a.display_name || a.username)}${a.active ? '' : '（停用）'}</span>
      <span><button class="btn-sm gray" data-aedit="${a.id}">编辑</button> ${a.id === (ME.account && ME.account.id) ? '' : `<button class="btn-sm danger" data-adel="${a.id}">删</button>`}</span></div>
      <div class="meta">账号 ${esc(a.username)} · 角色 ${esc(a.roleLabel)}</div></div>`).join('');
    box.querySelectorAll('[data-aedit]').forEach(b => b.onclick = () => editAccount(+b.dataset.aedit, list));
    box.querySelectorAll('[data-adel]').forEach(b => b.onclick = async () => {
      if (!confirm('确认删除该账户？')) return;
      try { await API.req('DELETE', '/api/accounts/' + b.dataset.adel); toast('已删除'); renderAccounts(); } catch (e) { toast(e.message); }
    });
  }
  async function addAccount() {
    const body = { username: $('#acc-user').value.trim(), display_name: $('#acc-name').value.trim(), password: $('#acc-pwd').value, role: $('#acc-role').value };
    if (!body.username || body.password.length < 4) { toast('账号必填、密码至少4位'); return; }
    try { await API.req('POST', '/api/accounts', body); toast('已添加账户'); $('#acc-user').value = ''; $('#acc-name').value = ''; $('#acc-pwd').value = ''; renderAccounts(); }
    catch (e) { toast(e.message); }
  }
  function editAccount(id, list) {
    const a = list.find(x => x.id === id); if (!a) return;
    showModal('编辑账户 ' + a.username, `
      <label>姓名</label><input id="ae-name" value="${esc(a.display_name || '')}"/>
      <label>角色</label>
      <select id="ae-role">
        <option value="staff" ${a.role === 'staff' ? 'selected' : ''}>员工</option>
        <option value="manager" ${a.role === 'manager' ? 'selected' : ''}>经理</option>
        <option value="boss" ${a.role === 'boss' ? 'selected' : ''}>老板</option>
      </select>
      <label>重置密码（留空=不改）</label><input id="ae-pwd" type="password" placeholder="新密码"/>
      <label>状态</label>
      <select id="ae-active"><option value="1" ${a.active ? 'selected' : ''}>启用</option><option value="0" ${!a.active ? 'selected' : ''}>停用</option></select>
      <button class="btn-primary" id="ae-save" style="margin-top:14px">保存</button>`);
    $('#ae-save').onclick = async () => {
      const body = { display_name: $('#ae-name').value.trim(), role: $('#ae-role').value, active: $('#ae-active').value === '1' };
      const pwd = $('#ae-pwd').value; if (pwd) body.password = pwd;
      try { await API.req('PUT', '/api/accounts/' + id, body); closeModal(); toast('已保存'); renderAccounts(); } catch (e) { toast(e.message); }
    };
  }

  // ---------- 打单 ----------
  /** 把 /api/orders/:id 的返回整理成小票数据结构（打印 / 预览 / 存图三处共用）
   *  注意条码只取 it.barcode：it.spec 装的是规格文字（106g/180ml），
   *  老单的 barcode 服务端 GET /api/orders/:id 会用商品表回填。 */
  function buildReceiptData(d, tpl) {
    tpl = tpl || {};
    const o = d.order, items = d.items || [];
    const pay = o.payable != null ? o.payable : o.total;
    const isRet = (o.status === 'returned') || Number(o.total || 0) < 0;
    const dt = String(o.order_date || o.created_at || '').replace('T', ' ').slice(0, 16);
    const handler = (TPL.receipt_handler || '').trim() || ((ME && ME.account && (ME.account.display_name || ME.account.username)) || '');
    const warehouse = TPL.receipt_warehouse || '天河仓';
    return {
      shop: SHOP,
      doc_title: isRet ? '销售退货' : (tpl.title || '销售订单'),
      order_no: o.order_no,
      date_time: dt,
      customer_name: o.customer_name,
      handler: handler,
      warehouse: warehouse,
      is_return: isRet,
      before_total: o.total,
      total: pay,
      payable: pay,
      // 🔴 2026-10-15：退货单页脚不能是「谢谢惠顾」，客户看到退货单还写欢迎光临会起争议。
      // buildReceiptData 是「打印 / 预览 / 存图」三处共用的数据入口，改这里三处一起生效。
      footer: isRet ? (tpl.footer_return || '退货单已入账，感谢配合')
        : (tpl.footer || TPL.receipt_footer || '谢谢惠顾，欢迎再次光临'),
      items: items.map(it => ({
        name: it.product_name,
        barcode: it.barcode || '',
        unit: it.unit,
        price: it.price,
        qty: it.qty,
        // 🔴 2026-10-21 经理反馈「预览没显示原价，跟打印不一样」：
        //    折前原价（order_items.orig_price）。少了这个字段，
        //    打印引擎（print_template.js）根本没有「原价」可印 → 只能印折后价。
        //    🔴 2026-10-15 前的订单 orig_price 为 NULL → 兜回 price 兼作原价，
        //    绝不传 0（比没有原价更糟）。与手机版 origOrPrice() 同口径。
        orig_price: (it.orig_price != null && Number(it.orig_price) > 0) ? Number(it.orig_price) : Number(it.price),
        // 小票金额优先用服务端 subtotal（退货单本身就是负数），不要自己 qty*price 再取绝对值
        subtotal: it.subtotal
      }))
    };
  }
  /** 订单预览：浮层里显示打印效果（含 EAN-13 条码图形），可打印 / 存图 */
  function previewReceipt(data) {
    if (!window.ERPReceipt) { toast('预览组件未加载，请刷新页面'); return; }
    window.ERPReceipt.open(data);
  }
  /** 订单保存为图片：直接出 PNG，供微信发给客户 */
  function saveReceiptImage(data) {
    if (!window.ERPReceipt) { toast('预览组件未加载，请刷新页面'); return; }
    window.ERPReceipt.download(data);
  }

  /** 打单浮层。
   *  `mod` = 打印模板（模块化引擎）。不传则用当前默认模板；
   *  `askTpl=true` 时先弹模板选择器（电脑版打单入口用）。 */
  async function openReceipt(orderId, mod, askTpl) {
    let tplMod = null;
    if (askTpl) tplMod = await pickTemplate();
    else if (mod && mod.modules) tplMod = window.ERPTpl.normalize(mod);
    else if (mod && mod.id) tplMod = getTplById(mod.id);
    else tplMod = getTplById(TPL_DEF);
    const tpl = { title: mod && mod.title, footer: mod && mod.footer };
    const d = await API.req('GET', '/api/orders/' + orderId);
    const o = d.order, items = d.items;
    const isRet = (o.status === 'returned') || Number(o.total || 0) < 0;
    // 🔴 2026-10-15：退货单页脚不能是「谢谢惠顾」，客户看到退货单还写欢迎光临会起争议
    const rFooter = isRet ? (tpl.footer_return || '退货单已入账，感谢配合')
      : (tpl.footer || TPL.receipt_footer || '谢谢惠顾，欢迎再次光临');
    const pay = o.payable != null ? o.payable : o.total;
    // 单据类型：退货单固定「销售退货」，其余用模板/设置里的标题
    const docTitle = isRet ? '销售退货' : (tpl.title || TPL.receipt_title || '销售订单');
    const dt = String(o.order_date || o.created_at || '').replace('T', ' ').slice(0, 16);
    const handler = (TPL.receipt_handler || '').trim() || ((ME && ME.account && (ME.account.display_name || ME.account.username)) || '');
    const warehouse = TPL.receipt_warehouse || '天河仓';
    // 🔴 2026-10-15：DOM 版小票也改成模板驱动，与 ESC/POS / Canvas 预览三处完全同源。
    // 原来的 abs() 全删：退货单明细与合计一律原样透传负数。
    const rc = buildReceiptData(d, tpl);
    rc.doc_title = docTitle;
    rc.footer = rFooter;
    rc.tpl = tplMod;
    // 纸宽/留白写成 CSS 变量：DOM 小票与 @media print 都读它（换 58mm 模板不用改样式表）
    const root = document.documentElement;
    root.style.setProperty('--rc-mm', tplMod.paper.width_mm + 'mm');
    root.style.setProperty('--rc-tail', tplMod.paper.tail_mm + 'mm');
    $('#receipt').innerHTML = window.ERPTpl.renderHtml(rc, tplMod);
    $('#print-overlay').classList.remove('hidden');
    if (window.Capacitor) { const sb = $('#print-btn'); if (sb) sb.style.display = 'none'; }
    $('#bt-print-btn').onclick = () => nativePrint('receipt', rc);
    // 打印浮层上也挂「预览 / 存图」，方便打完顺手发客户
    const pv = $('#ov-preview'); if (pv) pv.onclick = () => previewReceipt(rc);
    const sv = $('#ov-saveimg'); if (sv) sv.onclick = () => saveReceiptImage(rc);
    if (!getErpPrinter()) { const bb = $('#bt-print-btn'); if (bb) { if (webPrintSupported()) bb.textContent = '🖨️打印'; else bb.style.display = 'none'; } }
  }

  // ---------- 弹窗 / toast ----------
  // 🔴 2026-10-18：showModal 被全站 20+ 处调用（含封禁/收款/改账户），
  // 它一旦抛 TypeError，调用方的 catch 会把 TypeError 当成业务错误弹出来，
  // 真正原因被掩盖。加判空后最坏情况是弹窗内容不显示，而不是整条流程报错。
  function showModal(title, bodyHtml) {
    const t = $('#modal-title'), b = $('#modal-body'), o = $('#modal-overlay');
    if (!t || !b || !o) return null;
    t.textContent = title; b.innerHTML = bodyHtml; o.classList.remove('hidden');
    return b;
  }
  function closeModal() { $('#modal-overlay').classList.add('hidden'); }
  // 📌 弹窗关闭能力**本来就已存在**（两处，都在绑定区）：
  //    ① $('#modal-close').onclick  → 「×」按钮（line 4362）
  //    ② $('#modal-overlay').onclick → 点遮罩空白处关闭（line 4363，判 e.target.id）
  //    ESC 也能关（下面这处）。
  //
  // 🔴🔴 2026-10-20 踩过的坑（写下来防止再犯）：
  //    曾误判成「全站弹窗没有关闭入口」，于是又加了一个 document 级遮罩点击监听。
  //    结果**弹窗一打开就被关掉**（display:none、offsetHeight=0，但 title/body 已写入）。
  //    原因：点入口卡片 → showModal() 摘掉 .hidden → 同一 click 继续冒泡到 #modal-overlay
  //    → 它的 onclick 里 e.target.id 不是 'modal-overlay'（target 是入口卡片）本不该关，
  //    但**新加的 document 监听在 setTimeout(0) 之后再判一次**，那时 target 仍不在 .modal 内
  //    → 判定「点了遮罩」→ 关闭。
  //    结论：遮罩关闭只能有**一个**判据，且必须用 e.target.id 精确匹配（不能用「不在 .modal 内」，
  //    因为点击发生时 target 可能来自别处）。ESC 是安全的补充，不是替代。
  if (!document.__modalEscBound) {
    document.__modalEscBound = true;
    document.addEventListener('keydown', e => {
      if (e.key !== 'Escape') return;
      const o = document.querySelector('#modal-overlay');
      if (o && !o.classList.contains('hidden')) closeModal();
    });
  }
  // ---------- 自动检查更新（弹窗提醒 + 30天不再提醒） ----------
  const UP_SEEN = 'erp_update_seen_ts';
  const UP_SUPPRESS = 'erp_update_suppress_until';
  const APK_SEEN = 'erp_apk_seen_code';     // 已提示过的最高 APK versionCode（避免每次开 App 都弹）
  // ---------- 原生 APK 更新：抽出结果应用逻辑，供手动/自动两条路复用 ----------
  function _applyApkUpdate(U, u, updateInfoBox) {
    updateInfoBox.current = u;
    $('#set-update-info').style.display = 'block';
    $('#set-update-info').innerHTML =
      '发现新版本 <b>' + esc(u.versionName) + '</b>（当前 ' + esc(u.localVersionName || '') + '）<br>' +
      esc(u.desc || '') +
      (u.force ? '<br><span style="color:#d33">此更新为强制更新</span>' : '');
    $('#set-doupd').style.display = 'block';
    $('#set-doupd').textContent = u.force ? '立即更新（强制）' : '立即更新';
  }
  function showUpdateModal(d) {
    const body = `<div style="padding:4px 2px">
      <p style="margin:0 0 6px">检测到新版本（构建于 ${new Date(d.ts).toLocaleString('zh-CN')}）。</p>
      <p class="muted" style="font-size:13px;margin:0">建议刷新页面以使用最新功能与修复。若暂不更新，可选择 30 天内不再提醒。</p>
      <div style="display:flex;gap:10px;justify-content:flex-end;margin-top:14px">
        <button class="btn-sm gray" id="up-later">30天内不再提醒</button>
        <button class="btn-sm" id="up-now">立即更新</button>
      </div></div>`;
    showModal('有新版本可用', body);
    const nowBtn = $('#up-now'); if (nowBtn) nowBtn.onclick = () => { try { localStorage.setItem(UP_SEEN, String(d.ts)); } catch (e) {} location.reload(); };
    const later = $('#up-later'); if (later) later.onclick = () => { try { localStorage.setItem(UP_SEEN, String(d.ts)); localStorage.setItem(UP_SUPPRESS, String(Date.now() + 30 * 86400000)); } catch (e) {} closeModal(); };
  }
  async function checkUpdate() {
    try {
      const d = await API.req('GET', '/api/version');
      const now = Date.now();
      const suppress = +localStorage.getItem(UP_SUPPRESS) || 0;
      if (suppress > now) return;
      const seen = +localStorage.getItem(UP_SEEN) || 0;
      if (d.ts > seen) showUpdateModal(d);
    } catch (e) { /* 静默 */ }
  }
  async function initUpdateChecker() {
    try { const d = await API.req('GET', '/api/version'); localStorage.setItem(UP_SEEN, String(d.ts)); } catch (e) {}
    setInterval(() => checkUpdate(), 120000);
    document.addEventListener('visibilitychange', () => { if (!document.hidden) checkUpdate(); });
    // 原生 App：启动时静默查一次 APK 新版，只在新版本首次出现时提示一次
    try {
      const U = getErpUpdate();
      if (isNativeEnv() && U) {
        const u = await U.checkUpdate();
        if (u && u.available) {
          const seen = +localStorage.getItem(APK_SEEN) || 0;
          if ((+u.versionCode || 0) > seen) {
            try { localStorage.setItem(APK_SEEN, String(u.versionCode || 0)); } catch (e) {}
            setTimeout(() => toast('发现新版本 ' + (u.versionName || '') + '，可在「设置 - 版本与更新」升级', 4000), 1500);
          }
        }
      }
    } catch (e) { /* 静默 */ }
  }
  let toastTimer;
  // 🔴 1.5.1：ms 可指定展示时长（信任设备淘汰提示较长，1.8s 看不全）
  function toast(msg, ms) {
    // 🔴 2026-10-18：toast 是所有报错文案的出口，一旦它自己抛异常，
    // 真实错误原因就被这个 TypeError 顶掉了，排查时完全看不到根因。必须先判空。
    const t = $('#toast');
    if (!t) return;
    t.textContent = msg; t.classList.remove('hidden'); clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.add('hidden'), ms || 1800);
  }
  function copyText(txt) {
    const fallback = () => { const ta = document.createElement('textarea'); ta.value = txt; ta.style.position = 'fixed'; ta.style.opacity = '0'; document.body.appendChild(ta); ta.select(); try { document.execCommand('copy'); toast('已复制'); } catch (e) { toast('复制失败，请手动复制'); } document.body.removeChild(ta); };
    if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(txt).then(() => toast('已复制')).catch(fallback); else fallback();
  }

  // ---------- 事件绑定 ----------
  function wire() {
    // 🔴 v5.5：信任设备面板的三个按钮
    $('#trusted-enter').onclick = () => trustedEnter();
    $('#trusted-book').onclick = () => trustedPickBook();
    $('#trusted-switch').onclick = () => trustedSwitchAcct();
    $('#trusted-unbind').onclick = () => trustedUnbind();
    $('#tab-login').onclick = () => setRegMode(false);
    $('#tab-reg').onclick = () => setRegMode(true);
    $('#captcha-img').onclick = refreshCaptcha;
    $('#login-btn').onclick = doAuth;
    $('#login-pwd').addEventListener('keydown', e => { if (e.key === 'Enter') $('#login-btn').click(); });
    $('#logout-btn').onclick = doLogout;
    $('#book-btn').onclick = openBookSwitcher;
    $('#modal-close').onclick = closeModal;
    $('#modal-overlay').onclick = e => { if (e.target.id === 'modal-overlay') closeModal(); };
    $('#print-btn').onclick = () => window.print();
    $('#print-close').onclick = () => $('#print-overlay').classList.add('hidden');
    $$('.sidebar nav button').forEach(b => b.onclick = () => navigate(b.dataset.page));
    // 意见反馈（模块在 feedback.js）：管理员直接进收件箱，普通用户进提交框
    const fb = $('#fb-open');
    if (fb && window.ERPFeedback) fb.onclick = () => {
      const admin = (function () { try { return !!(JSON.parse(localStorage.getItem('erp_me') || '{}').isAdmin); } catch (e) { return false; } })();
      if (admin) window.ERPFeedback.openInbox();
      else window.ERPFeedback.openSubmit();
    };
  }

  // 套账切换
  async function openBookSwitcher() {
    const books = ME.books && ME.books.length ? ME.books : [];
    showModal('切换套账', `
      <div class="muted" style="margin-bottom:8px">当前：${esc(ME.book ? ME.book.name : '')}</div>
      ${books.map(b => `<button class="btn-sm ${ME.book && ME.book.id === b.id ? '' : 'gray'}" data-bid="${b.id}" style="margin:4px 0;width:100%;text-align:left">${esc(b.name)}</button>`).join('')}`);
    document.querySelectorAll('#modal-body [data-bid]').forEach(btn => btn.onclick = async () => {
      try {
        const r = await API.req('POST', '/api/books/switch', { book_id: +btn.dataset.bid });
        ME.book = r.book; SHOP = r.shopName || r.book.name;
        await loadTpl();
        closeModal();
        $('#shop-name').textContent = SHOP + ' · ' + (ME.roleLabel || '');
        toast('已切换到 ' + r.book.name);
        navigate(CURRENT);
      } catch (e) { toast(e.message); }
    });
  }

  document.addEventListener('DOMContentLoaded', () => {
    wire();
    // 打印模板库要在任何一次打单之前就绪
    if (window.ERPTpl) loadTplStore();
    boot(); initUpdateChecker();
  });
})();
