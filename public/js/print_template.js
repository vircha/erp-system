// ============================================================================
// 打印模板引擎（2026-10-15）
// 一个模板 = 「纸张尺寸 + 字号 + 模块开关集合」。
// 渲染器（DOM 打印浮层 / ESC-POS 蓝牙 / Canvas 预览与存图）全部读同一份模板，
// 三个出口版式必然一致 —— 这是本模块存在的唯一理由。
//
// 暴露：
//   window.ERPTpl.DEFAULT      内置模板（只读，复制后可改）
//   window.ERPTpl.normalize(t) 补全缺省字段
//   window.ERPTpl.cols(t)      模板列宽（含三列数字宽度）
//   window.ERPTpl.renderHtml(d, t)        DOM 版小票 HTML
//   window.ERPTpl.renderEsc(d, t, H)      ESC/POS 字节流
//   window.ERPTpl.openManager(cur, onSave) 模板管理界面
//   window.ERPTpl.pick(list, onPick)       打单前选模板
// ============================================================================
(function () {
  'use strict';

  // 🔴🔴 2026-10-15 修「点击管理打印模板 → 弹窗卡死」
  // 根因：本文件全篇用 $('#tpl-title', o) 这种**带 scope 第二参**的写法选元素，
  //       但整个文件从未定义过 `$`。
  //       app.js 里的 `$` 是它 IIFE 内部的私有常量（const $ = s => document.querySelector(s)），
  //       既不挂到 window，也只接受 1 个参数 —— 所以这里 `$` 是 ReferenceError，不是「选不到」。
  // 现场表现（经理截图）：overlay() 第 434 行已把外壳 appendChild 到 body，
  //       第 435 行 `$('#tpl-title', o)` 立刻抛错 →
  //         · 标题文字没填、× 按钮 onclick 没绑（点不掉）
  //         · 异常冒泡出 openManager()，paint() 从未执行 → 内容区一片空白
  //       = 弹窗外壳在、内容空、怎么点都关不掉，只能刷新页面。
  // 修法：本文件自给自足定义支持 scope 的 $ / $$，不依赖任何外部全局。
  // ⚠️ 别再改回单参 `s => document.querySelector(s)` —— 那会在有重复 id 时选错节点，
  //    而且一旦将来别的文件也挂了 window.$ 就会互相污染。
  const $ = (sel, root) => (root || document).querySelector(sel);
  const $$ = (sel, root) => Array.prototype.slice.call((root || document).querySelectorAll(sel));

  // ---------- 模块清单（管理界面按这个顺序渲染） ----------
  const MODULES = [
    { g: '抬头', k: 'shop', label: '店铺名称', hint: '小票最上方的大字店名' },
    { g: '抬头', k: 'doc_title', label: '单据类型', hint: '销售订单 / 销售退货' },
    { g: '抬头', k: 'sep1', label: '抬头分隔线' },
    { g: '客户信息', k: 'customer', label: '往来单位名称' },
    { g: '客户信息', k: 'order_no', label: '单据编号' },
    { g: '客户信息', k: 'date', label: '单据日期' },
    { g: '客户信息', k: 'handler', label: '经手人' },
    { g: '客户信息', k: 'warehouse', label: '出/入库仓库' },
    { g: '商品明细', k: 'thead', label: '表头（序号/名称/数量…）' },
    { g: '商品明细', k: 'sep2', label: '表头分隔线' },
    { g: '商品明细', k: 'idx', label: '序号' },
    { g: '商品明细', k: 'name', label: '商品名称' },
    { g: '商品明细', k: 'barcode', label: '商品条码（数字）' },
    { g: '商品明细', k: 'qty', label: '数量' },
    { g: '商品明细', k: 'price', label: '单价' },
    { g: '商品明细', k: 'amount', label: '金额' },
    { g: '商品明细', k: 'sep3', label: '明细后分隔线' },
    { g: '合计', k: 'qty_sum', label: '合计数量' },
    { g: '合计', k: 'before_total', label: '合计折前金额' },
    { g: '合计', k: 'saved', label: '折扣优惠金额' },
    { g: '合计', k: 'payable', label: '优惠后金额' },
    { g: '合计', k: 'sign', label: '客户签收栏' },
    { g: '合计', k: 'sep4', label: '签收前分隔线' },
    { g: '页脚', k: 'footer', label: '页脚语' },
    { g: '页脚', k: 'tail', label: '底部签收留白', hint: '默认 2cm，方便客户手写签名' }
  ];
  const MKEYS = MODULES.map(m => m.k);
  // 🔴 extra（自定义模块）允许的类型：内置 25 项 + 只在 extra 存在的 'text'/'remark'
  const EXTRA_KEYS = MKEYS.concat(['text', 'remark']);

  function allOn() { const o = {}; MKEYS.forEach(k => { o[k] = true; }); return o; }

  // ---------- 纸张规格库（2026-10-15 加：经理要求「提供默认尺寸 A2-A5 或者单据通常是什么尺寸」）
  // 每个规格给出 width_mm / height_mm / 建议列数。
  // cols 的算法：热敏连续纸按「每半角列 1.667mm」算；A/B 系列按「每列 3.5mm」算（更接近真实观感）。
  // height_mm 只做展示与分页参考，热敏纸高度是连续的不填。
  const PAPERS = [
    { g: '热敏小票纸（连续，长度不限）', items: [
      { id: 'p58', name: '58mm 小票纸', w: 58, h: 0, cols: 32 },
      { id: 'p80', name: '80mm 小票纸（最常用）', w: 80, h: 0, cols: 48 },
      { id: 'p110', name: '110mm 宽面单据', w: 110, h: 0, cols: 66 }
    ] },
    { g: 'A 系列（单据/送货单最常用）', items: [
      { id: 'a5', name: 'A5（148×210mm）半页单据', w: 148, h: 210, cols: 42 },
      { id: 'a4', name: 'A4（210×297mm）整页送货单', w: 210, h: 297, cols: 60 },
      { id: 'a3', name: 'A3（297×420mm）多门店联单', w: 297, h: 420, cols: 84 },
      { id: 'a2', name: 'A2（420×594mm）大批量总单', w: 420, h: 594, cols: 118 }
    ] },
    { g: 'B 系列 / 其他常用', items: [
      { id: 'b5', name: 'B5（176×250mm）', w: 176, h: 250, cols: 50 },
      { id: 'j88', name: '单据 88×55mm（小票联）', w: 88, h: 55, cols: 26 },
      { id: 'j130', name: '单据 130×80mm（三联单）', w: 130, h: 80, cols: 37 },
      { id: 'z105', name: '增值税发票 105×170mm', w: 105, h: 170, cols: 30 }
    ] }
  ];
  const PAPER_FLAT = {};
  PAPERS.forEach(g => g.items.forEach(p => { PAPER_FLAT[p.id] = p; }));

  /** 从纸宽推默认列数：<100mm 走热敏（1.667mm/列），>=100mm 走单据（3.5mm/列） */
  function colsForWidth(w) {
    w = Number(w) || 80;
    return w < 100 ? Math.max(24, Math.round(w / 1.667)) : Math.max(32, Math.round(w / 3.5));
  }

  // ---------- 内置模板 ----------
  const DEFAULT = [
    {
      id: 'std80',
      name: '标准送货单 80mm',
      builtin: true,
      desc: '对齐纸质送货单：店名/单据/客户信息/明细/合计/签收，48 列',
      paper: { width_mm: 80, height_mm: 0, cols: 48, side_mm: 4, tail_mm: 20 },
      font: { scale: 1 },
      modules: allOn()
    },
    {
      id: 'std58',
      name: '窄款 58mm',
      builtin: true,
      desc: '58mm 热敏机用，32 列；关掉单价与折前金额，只留数量与金额',
      paper: { width_mm: 58, height_mm: 0, cols: 32, side_mm: 2, tail_mm: 15 },
      font: { scale: 1 },
      modules: Object.assign(allOn(), { price: false, before_total: false, saved: false, date: true })
    },
    {
      id: 'a4',
      name: 'A4 整页送货单',
      builtin: true,
      desc: 'A4 纵向 210×297mm，60 列；明细带行号与规格，适合正式送货单存档',
      paper: { width_mm: 210, height_mm: 297, cols: 60, side_mm: 12, tail_mm: 10 },
      font: { scale: 1 },
      modules: allOn()
    },
    {
      id: 'a5',
      name: 'A5 半页单据',
      builtin: true,
      desc: 'A5 148×210mm，42 列；A4 对折打印，适合门店留底联',
      paper: { width_mm: 148, height_mm: 210, cols: 42, side_mm: 10, tail_mm: 10 },
      font: { scale: 1 },
      modules: Object.assign(allOn(), { saved: false })
    },
    {
      id: 'simple',
      name: '简洁快打（无条码）',
      builtin: true,
      desc: '不印条码与经手人，20 行打完，适合自提/批发大单',
      paper: { width_mm: 80, height_mm: 0, cols: 48, side_mm: 4, tail_mm: 20 },
      font: { scale: 1 },
      modules: Object.assign(allOn(), { barcode: false, handler: false, sep2: false })
    }
  ];

  // ---------- 归一化：任何来源（内置/存储/手改）都补全成完整模板 ----------
  function normalize(t) {
    t = t || {};
    const p = Object.assign({ width_mm: 80, height_mm: 0, cols: 48, side_mm: 4, tail_mm: 20 }, t.paper || {});
    // 🔴 2026-10-15：原来这里把 width_mm 夹在 40~120、cols 夹在 24~64，
    // A4（210mm/60列）刚好卡在边界，A3（297mm/84列）、A2（420mm/118列）会被静默截断——
    // 经理要求提供 A2-A5 与单据常用尺寸，宽度上限必须放到 500mm、列数上限放到 130。
    p.width_mm = Math.min(500, Math.max(40, Number(p.width_mm) || 80));
    // 纸宽与列数必须自洽：优先用模板自带的 cols；没带就按纸宽推
    if (!p.cols) p.cols = colsForWidth(p.width_mm);
    // 窄纸有上限：<70mm 的机器物理上放不下 48 列（58mm 机只有 32 列）
    if (p.width_mm < 70) p.cols = Math.min(p.cols, 32);
    const mods = Object.assign(allOn(), t.modules || {});
    MKEYS.forEach(k => { if (typeof mods[k] !== 'boolean') mods[k] = true; });
    return {
      id: t.id || ('t_' + Date.now().toString(36)),
      name: String(t.name || '未命名模板').slice(0, 30),
      desc: String(t.desc || '').slice(0, 120),
      builtin: !!t.builtin,
      paper: {
        paper_id: p.paper_id || '',
        width_mm: p.width_mm,
        height_mm: Math.min(2000, Math.max(0, Number(p.height_mm) || 0)),   // 0 = 连续纸，长度不限
        cols: Math.min(130, Math.max(24, Number(p.cols) || 48)),
        side_mm: Math.min(30, Math.max(0, Number(p.side_mm) || 0)),
        tail_mm: Math.min(100, Math.max(0, Number(p.tail_mm) || 0))
      },
      font: {
        scale: Math.min(2.5, Math.max(0.6, Number((t.font || {}).scale) || 1)),
        mono: (t.font || {}).mono !== false// 等宽数字（A4/A3 单据建议开）
      },
      // 🔴 2026-10-15 经理要求「模块可以自动选择，模块可以重复添加」：
      // extra 存「额外添加的模块实例」数组，同一个 k 可以出现多次
      //（例如两行备注：备注1/备注2，各自独立开关）。
      // 🔴 EXTRA_KEYS 必须包含 'text'/'remark' 这类**只存在于 extra 里**的类型 ——
      // 拿MKEYS（内置 25 项）做白名单会把 'text' 静默改写成 'remark'，
      // 写死打印的自由文字内容就变成了订单备注，且不报任何错。
      extra: Array.isArray(t.extra) ? t.extra.slice(0, 20).map((x, i) => ({
        uid: 'x' + i + '_' + Math.random().toString(36).slice(2, 7),
        k: EXTRA_KEYS.indexOf(String((x || {}).k)) >= 0 ? String(x.k) : 'text',
        label: String((x || {}).label || '').slice(0, 20),
        on: (x || {}).on !== false,
        text: String((x || {}).text || '').slice(0, 100)
      })) : [],
      modules: mods
    };
  }

  /** 列宽：数字三列按固定比例分掉剩余宽度，比例与旧版一致（6:7:8） */
  function cols(t) {
    const p = normalize(t).paper;
    const C = p.cols;
    const numW = Math.max(9, Math.round(C * 21 / 48));      // 与旧 48列→21 等比
    const q = Math.max(4, Math.round(numW * 6 / 21));
    const pr = Math.max(5, Math.round(numW * 7 / 21));
    const a = Math.max(5, numW - q - pr);
    return { COLS: C, LEFT_W: C - (q + pr + a), QTY_W: q, PRICE_W: pr, AMT_W: a, NUM_W: q + pr + a, paper: p };
  }

  const on = (t, k) => !!(normalize(t).modules)[k];
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  // ==========================================================================
  // 渲染 1：DOM 版（打印浮层 #receipt，也用于浏览器直接 window.print）
  // ==========================================================================
  function renderHtml(d, t) {
    const T = normalize(t);
    const m = T.modules;
    const C = cols(T);
    const isRet = !!d.is_return;
    const items = Array.isArray(d.items) ? d.items : [];
    let qtySum = 0;
    for (const it of items) qtySum += Number(it.qty || 0);
    const before = Number(d.before_total != null ? d.before_total : (d.total || 0));
    const pay = Number(d.payable != null ? d.payable : before);
    const saved = before - pay;
    const qtyShow = qtySum % 1 === 0 ? qtySum : qtySum.toFixed(2);
    const o = [];
    const sep = () => o.push('<div class="sep"></div>');
    const line = (a, b, style) => o.push(`<div class="line" style="${style || 'font-size:11px'}"><span>${a}</span><span>${b}</span></div>`);

    o.push(`<div style="height:${T.paper.width_mm >= 70 ? 0 : 0}px"></div>`);
    if (m.shop) o.push(`<div class="center" style="font-size:16px;font-weight:700">${esc(d.shop || '')}</div>`);
    if (m.doc_title) o.push(`<div class="center" style="font-size:12px">${esc(d.doc_title || '')}</div>`);
    if (m.sep1) sep();
    if (m.customer) line('往来单位名称', esc(d.customer_name || ''));
    if (m.order_no) line('单据编号', esc(d.order_no || ''));
    if (m.date) line('单据日期', esc(d.date_time || ''));
    if (m.handler) line('经手人', esc(d.handler || ''));
    if (m.warehouse) line(isRet ? '入库仓库' : '出库仓库', esc(d.warehouse || ''));
    if (m.sep2) sep();
    // 表头也要跟模块开关走，否则关掉「单价」后表头还印着「单价」，行里却没有单价列
    if (m.thead) {
      const r = [];
      if (m.qty !== false) r.push('数量');
      if (m.price) r.push('单价');
      if (m.amount !== false) r.push('金额');
      line('<span>序号 名称 / 条码</span>', '<span>' + r.join(' ') + '</span>', 'font-size:11px;color:#888');
    }
    if (m.sep3 && m.thead) sep();

    items.forEach((it, i) => {
      const q = Number(it.qty || 0), p = Number(it.price || 0);
      const amt = (it.subtotal != null && Number(it.subtotal) !== 0) ? Number(it.subtotal) : q * p;
      const bc = String(it.barcode || '').trim();
      const name = ((m.idx ? (i + 1) + '. ' : '') + (it.name || ''));
      const left = (m.barcode && bc) ? '&nbsp;&nbsp;&nbsp;' + esc(bc) : '';
      const right = [m.qty ? esc(fmtQtyNum(q)) + esc(it.unit || '') : '',
      m.price ? esc(fmt2(p)) : '', m.amount ? esc(fmt2(amt)) : ''].filter(Boolean).join(' ');
      const st = m.name ? '' : 'display:none';
      o.push(`<div class="line" style="font-size:11px"${st}><span>${esc(name)}</span><span>${right}</span></div>`);
      if (m.barcode && bc) {
        const r2 = [m.qty ? esc(fmtQtyNum(q)) + esc(it.unit || '') : '',
        m.price ? esc(fmt2(p)) : '', m.amount ? esc(fmt2(amt)) : ''].filter(Boolean).join(' ');
        o.push(`<div class="line" style="font-size:10.5px;color:#888"><span>${left}</span><span>${r2}</span></div>`);
      }
    });
    if (m.sep3) sep();
    if (m.qty_sum) line('合计数量', esc(String(qtyShow)));
    if (m.before_total) line('合计折前金额', esc(fmt(before)));
    if (m.saved) line('折扣优惠金额', esc(fmt(Math.abs(saved))));
    if (m.payable) line('优惠后金额', esc(fmt(pay)), 'font-size:15px;font-weight:700');
    if (m.sign) line('客户签收', '＿＿＿＿＿＿');
    if (m.sep4) sep();
    // 🔴 经理要求「模块可以重复添加」：这里把 extra 里的自定义模块实例逐个渲染。
    // 内置的 25 个模块仍由 m[k] 开关控制（一个 k 只能出现一次）；
    // extra 里的实例可以同名重复（比如两行「备注」、三行「说明」），各自独立开关与内容。
    (T.extra || []).forEach(x => {
      if (!x || x.on === false) return;
      const k = x.k;
      if (k === 'remark' || k === 'sign' || k === 'footer' || k === 'handler' || k === 'warehouse'
        || k === 'customer' || k === 'order_no' || k === 'date' || k === 'doc_title' || k === 'shop'
        || k === 'payable' || k === 'qty_sum' || k === 'before_total' || k === 'saved') {
        // 这些是「标签 + 值」型：值取订单数据
        let v = '';
        if (k === 'remark') v = d.remark || d.note || d.footer || '';
        else if (k === 'sign') v = '＿＿＿＿＿＿';
        else if (k === 'footer') v = d.footer || '';
        else if (k === 'handler') v = d.handler || '';
        else if (k === 'warehouse') v = (d.is_return ? '入库仓库：' : '出库仓库：') + (d.warehouse || '');
        else if (k === 'customer') v = d.customer_name || d.store || '';
        else if (k === 'order_no') v = d.order_no || '';
        else if (k === 'date') v = d.date_time || '';
        else if (k === 'doc_title') v = d.doc_title || '';
        else if (k === 'shop') v = d.shop || '';
        else if (k === 'payable') v = fmt(pay);
        else if (k === 'qty_sum') v = fmtQtyNum(qtyShow);
        else if (k === 'before_total') v = fmt(before);
        else if (k === 'saved') v = fmt(Math.abs(saved));
        line(x.label || '', esc(v));
      } else if (k === 'text') {
        // 自由文字块：只印用户写死的内容
        line(esc(x.text || x.label || ''));
      } else if (k === 'sep') {
        sep();
      } else if (k === 'tail') {
        o.push(`<div style="height:${Math.min(100, Math.max(2, Number(x.text) || 10))}mm"></div>`);
      }
    });
    if (m.footer) o.push(`<div class="center" style="font-size:11px">${esc(d.footer || '')}</div>`);
    // 底部留白：签收区高度随模板变（默认 2cm）
    if (m.tail) o.push(`<div class="sign-tail" style="height:${T.paper.tail_mm}mm"></div>`);
    else o.push('<div style="height:8px"></div>');
    return o.join('\n');
  }

  // ==========================================================================
  // 渲染 2：ESC/POS（蓝牙热敏打印机）
  // ==========================================================================
  // H = app.js 提供的工具集：{EscBuilder, ESC, clipW, rPad, lPadN, wLen, fmt2, fmtQtyNum, feedTail}
  async function renderEsc(d, t, H) {
    const T = normalize(t);
    const m = T.modules;
    const C = cols(T);
    const b = new H.EscBuilder();
    const LF = H.ESC.LF;
    const out = [];
    const put = async (s) => { await b.text(s); b.raw(LF); };

    b.raw(H.ESC.INIT).raw(H.ESC.CODPAGE_GBK).raw(H.ESC.ALIGN_CENTER);
    if (m.shop) { b.raw(H.ESC.BOLD_ON).raw(H.ESC.BIG); await b.text(H.clipW(d.shop || '', 22)); b.raw(H.ESC.NORMAL).raw(H.ESC.BOLD_OFF); b.raw(LF); }
    if (m.doc_title) { await b.text(H.clipW(d.doc_title || '', C.COLS)); b.raw(LF); }
    b.raw(H.ESC.ALIGN_LEFT);
    if (m.sep1) await put('='.repeat(C.COLS));
    if (m.customer) await put('往来单位名称:' + H.clipW(d.customer_name || '', C.COLS - 14));
    if (m.order_no) await put('单据编号:' + H.clipW(d.order_no || '', C.COLS - 10));
    if (m.date) await put('单据日期:' + H.clipW(d.date_time || '', C.COLS - 10));
    if (m.handler) {
      const wh = m.warehouse ? ((d.is_return ? '入库仓库:' : '出库仓库:') + H.clipW(d.warehouse || '', 16)) : '';
      await b.text(H.rPad('经手人:' + H.clipW(d.handler || '', 12), C.COLS - H.wLen(wh)) + wh); b.raw(LF);
    } else if (m.warehouse) {
      await put((d.is_return ? '入库仓库:' : '出库仓库:') + H.clipW(d.warehouse || '', 16));
    }
    if (m.sep2) await put('-'.repeat(C.COLS));
    if (m.thead) {
      const num = [];
      if (m.qty) num.push(H.lPadN('数量', C.QTY_W));
      if (m.price) num.push(H.lPadN('单价', C.PRICE_W));
      if (m.amount) num.push(H.lPadN('金额', C.AMT_W));
      await put(H.rPad('序号 名称', C.LEFT_W) + num.join(''));
    }
    if (m.sep3 && m.thead) await put('-'.repeat(C.COLS));

    const items = Array.isArray(d.items) ? d.items : [];
    let qtySum = 0;
    for (let i = 0; i < items.length; i++) {
      const it = items[i];
      const q = Number(it.qty || 0), p = Number(it.price || 0);
      qtySum += q;
      const amt = (it.subtotal != null && Number(it.subtotal) !== 0) ? Number(it.subtotal) : q * p;
      if (m.name) {
        await b.text(H.clipW(((m.idx ? (i + 1) + ' ' : '') + (it.name || '')), C.COLS)); b.raw(LF);
      }
      const bc = m.barcode ? String(it.barcode || '').trim() : '';
      const num = [];
      if (m.qty) num.push(H.lPadN(H.fmtQtyNum(q) + (it.unit || ''), C.QTY_W));
      if (m.price) num.push(H.lPadN(H.fmt2(p), C.PRICE_W));
      if (m.amount) num.push(H.lPadN(H.fmt2(amt), C.AMT_W));
      // 🔴窄纸（58mm=32列，LEFT_W 只有 18）时，「条码:」+ 13 位数字放不进左列，
      // clipW 会把末位截成「690120921289.」—— 条码错一位就扫不出来。
      // 规则：左列放不下完整条码时，条码独占一行（不截断），数量/金额另起一行靠右。
      const bcFit = bc && (H.wLen(bc) <= Math.max(2, C.LEFT_W - 6));
      if (bc) {
        if (bcFit) {
          await b.text(H.rPad('条码:' + bc, C.LEFT_W) + num.join('')); b.raw(LF);
        } else {
          await put('条码:' + bc);
          if (num.length) { await b.text(H.rPad('', C.LEFT_W) + num.join('')); b.raw(LF); }
        }
      } else {
        await b.text(H.rPad('', C.LEFT_W) + num.join('')); b.raw(LF);
      }
    }
    if (m.sep3) await put('-'.repeat(C.COLS));
    const before = Number(d.before_total != null ? d.before_total : (d.total || 0));
    const pay = Number(d.payable != null ? d.payable : before);
    const saved = before - pay;
    if (m.qty_sum) {
      const r1 = m.before_total ? ('合计折前金额:' + H.fmt2(before)) : '';
      const ls = '合计数量:' + H.fmtQtyNum(qtySum);
      await b.text(r1 ? H.rPad(ls, C.COLS - H.wLen(r1)) + r1 : ls); b.raw(LF);
    }
    if (m.saved) {
      const r2 = m.payable ? ('优惠后金额:' + H.fmt2(pay)) : '';
      const ls = '折扣优惠金额:' + H.fmt2(saved);
      b.raw(H.ESC.BOLD_ON);
      await b.text(r2 ? H.rPad(ls, C.COLS - H.wLen(r2)) + r2 : ls);
      b.raw(H.ESC.BOLD_OFF); b.raw(LF);
    } else if (m.payable) {
      b.raw(H.ESC.BOLD_ON);
      await b.text('优惠后金额:' + H.fmt2(pay)); b.raw(H.ESC.BOLD_OFF); b.raw(LF);
    } else if (m.before_total) {
      await put('合计折前金额:' + H.fmt2(before));
    }
    if (m.sep4) await put('-'.repeat(C.COLS));
    if (m.sign) await put('客户签收:____________________');
    // 🔴 与 renderHtml 同规则：extra 里的自定义模块实例逐个渲染，可同名重复
    for (const x of (T.extra || [])) {
      if (!x || x.on === false) continue;
      const k = x.k, lb = x.label || '';
      if (k === 'remark') await put(H.rPad(lb, C.COLS - Math.min(C.COLS, H.wLen(H.clipW(String(d.remark || d.note || d.footer || ''), C.COLS - H.wLen(lb) - 1)))) + H.clipW(String(d.remark || d.note || d.footer || ''), Math.max(4, C.COLS - H.wLen(lb) - 1)));
      else if (k === 'text') await put(H.clipW(String(x.text || lb || ''), C.COLS));
      else if (k === 'sep') await put('-'.repeat(C.COLS));
      else if (k === 'sign') await put(lb + ':____________________');
      else if (k === 'handler') await put(lb + ':' + H.clipW(String(d.handler || ''), C.COLS - H.wLen(lb) - 1));
      else if (k === 'warehouse') await put(lb + ':' + H.clipW(String(d.warehouse || ''), C.COLS - H.wLen(lb) - 1));
      else if (k === 'customer') await put(lb + ':' + H.clipW(String(d.customer_name || d.store || ''), C.COLS - H.wLen(lb) - 1));
      else if (k === 'order_no') await put(lb + ':' + H.clipW(String(d.order_no || ''), C.COLS - H.wLen(lb) - 1));
      else if (k === 'date') await put(lb + ':' + H.clipW(String(d.date_time || ''), C.COLS - H.wLen(lb) - 1));
      else if (k === 'doc_title') await put(H.clipW(String(d.doc_title || ''), C.COLS));
      else if (k === 'shop') await put(H.clipW(String(d.shop || ''), C.COLS));
      else if (k === 'payable') await put(lb + ':' + H.fmt2(pay));
      else if (k === 'qty_sum') await put(lb + ':' + H.fmtQtyNum(qtySum));
      else if (k === 'before_total') await put(lb + ':' + H.fmt2(before));
      else if (k === 'saved') await put(lb + ':' + H.fmt2(saved));
      else if (k === 'tail') { const mm = Math.min(100, Math.max(2, Number(x.text) || 10)); b.raw(H.ESC.FEED(Math.round(mm * 8))); }
    }
    if (m.footer) { b.raw(H.ESC.ALIGN_CENTER); await b.text(H.clipW(d.footer || '', C.COLS)); b.raw(LF); b.raw(H.ESC.ALIGN_LEFT); }
    // 底部留白：按模板 tail_mm 换算进纸点（203dpi ≈ 8 点/mm）
    if (m.tail) H.feedTail(b, T.paper.tail_mm);
    b.raw(H.ESC.CUT);
    return b.build();
  }

  function fmt2(v) { return Number(v || 0).toFixed(2); }
  function fmtQtyNum(q) { const v = Number(q || 0); return (v % 1 === 0) ? String(Math.trunc(v)) : String(v); }
  function fmt(v) { return '¥' + Number(v || 0).toFixed(2); }

  // ==========================================================================
  // 模板管理界面
  // ==========================================================================
  /** 建一个通用弹窗外壳，返回内容容器（.tpl-body）。
   *  🔴 2026-10-15 修：原来收了 html 参数却**从不写进 DOM**，直接返回空容器。
   *     openManager 因为后面自己 paint() 填内容，看不出问题；
   *     但 pick()（打单前选模板）是「一次性把整段 HTML 交给 overlay」——
   *     于是弹窗是空的，紧接着 `$('#tpl-cancel', body).onclick = close`
   *     在 null 上赋值 → TypeError: Cannot set properties of null (setting 'onclick')
   *     → 「打单前选模板」整个流程直接崩掉。
   *  参数：title 标题文字 / html 内容 HTML（可为空串）/ wide 是否宽版。 */
  function overlay(title, html, wide) {
    let o = document.getElementById('tpl-overlay');
    if (o) o.remove();
    o = document.createElement('div');
    o.id = 'tpl-overlay';
    o.className = 'tpl-overlay' + (wide ? ' tpl-wide' : '');
    o.innerHTML = '<div class="tpl-modal"><div class="tpl-head"><span id="tpl-title"></span><button id="tpl-x">×</button></div><div id="tpl-body" class="tpl-body"></div></div>';
    document.body.appendChild(o);
    $('#tpl-title', o).textContent = title;
    $('#tpl-x', o).onclick = () => o.remove();
    o.addEventListener('click', e => { if (e.target === o) o.remove(); });
    const body = $('#tpl-body', o);
    // 👉 关键：把调用方给的内容写进去（openManager 传空串，自己随后 paint）
    if (html) body.innerHTML = html;
    return body;
  }

  /** 模板管理：左列列表，右列编辑。onSave(templates, defaultId) */
  function openManager(cur, onSave) {
    let list = (cur && cur.list && cur.list.length ? cur.list : DEFAULT.slice()).map(normalize);
    let defId = (cur && cur.def) || list[0].id;
    let editId = list[0] ? list[0].id : null;

    const body = overlay('打印模板管理', '', true);
    const paint = () => {
      const e = list.find(x => x.id === editId) || list[0];
      body.innerHTML = `
        <div class="tpl-wrap">
          <div class="tpl-left">
            <div class="tpl-left-head">已保存模板</div>
            ${list.map(x => `<div class="tpl-item${x.id === (e && e.id) ? ' on' : ''}" data-id="${x.id}" title="${esc(x.name)}">
              <b>${esc(x.name)}${x.builtin ? '<i class="tpl-bi">内置</i>' : ''}</b>
              <span>${esc(x.paper.width_mm)}${x.paper.height_mm ? '×' + esc(x.paper.height_mm) : ''}mm · ${x.paper.cols} 列${(x.extra||[]).length ? ' · ＋' + x.extra.length : ''}${x.id === defId ? '<i class="tpl-def">默认</i>' : ''}</span>
            </div>`).join('')}
            <div class="tpl-left-ops">
              <button class="btn-sm" id="tpl-add">＋ 新建</button>
              <button class="btn-sm gray" id="tpl-copy">复制</button>
              <button class="btn-sm gray" id="tpl-del">删除</button>
            </div>
            <label class="tpl-defsel">默认模板（打单时预选）</label>
            <select id="tpl-def">${list.map(x => `<option value="${x.id}"${x.id === defId ? ' selected' : ''}>${esc(x.name)}</option>`).join('')}</select>
            <div class="tpl-savebar"><button class="btn-primary" id="tpl-save">保存全部</button></div>
          </div>
          <div class="tpl-right">${e ? editor(e) : '<div class="muted" style="padding:20px">左侧选一个模板</div>'}</div>
        </div>`;
      // ---- 左列 ----
      body.querySelectorAll('.tpl-item').forEach(el => el.onclick = () => { editId = el.dataset.id; paint(); });
      $('#tpl-def', body).onchange = ev => { defId = ev.target.value; };
      $('#tpl-add', body).onclick = () => {
        const t = normalize({ id: 't_' + Date.now().toString(36), name: '新模板', desc: '', builtin: false, paper: DEFAULT[0].paper, modules: allOn() });
        list.push(t); editId = t.id; paint();
      };
      $('#tpl-copy', body).onclick = () => {
        if (!e) return;
        const t = normalize(JSON.parse(JSON.stringify(e)));
        t.id = 't_' + Date.now().toString(36); t.name = e.name + ' 副本'; t.builtin = false;
        list.push(t); editId = t.id; paint();
      };
      $('#tpl-del', body).onclick = () => {
        if (!e) return;
        if (list.length <= 1) { alert('至少保留一个模板'); return; }
        if (!confirm('删除模板「' + e.name + '」？')) return;
        list = list.filter(x => x.id !== e.id);
        if (defId === e.id) defId = list[0].id;
        editId = list[0].id; paint();
      };
      $('#tpl-save', body).onclick = () => {
        if (onSave) onSave(list.map(stripUI), defId);
        document.getElementById('tpl-overlay').remove();
      };
      if (!e) return;
      wireEditor(body, e, () => paint());
    };
    paint();
  }

  function editor(e) {
    const g = {};
    MODULES.forEach(m => { (g[m.g] = g[m.g] || []).push(m); });
    return `
      <div class="tpl-ed">
        <div class="row" style="gap:10px;align-items:flex-end;flex-wrap:wrap">
          <div style="flex:1;min-width:160px"><label>模板名称</label><input id="e-name" value="${esc(e.name)}" maxlength="30"/></div>
          <div style="flex:2;min-width:200px"><label>说明（仅自己可见）</label><input id="e-desc" value="${esc(e.desc || '')}" maxlength="60"/></div>
        </div>

        <div class="sub-title">📄 纸张规格（经理要求提供 A2-A5 与单据常用尺寸，也支持自己填mm）</div>
        <select id="e-paper" style="margin-bottom:8px">
          <option value="">— 自定义尺寸（自己填 mm）—</option>
          ${PAPERS.map(gr => `<optgroup label="${esc(gr.g)}">${gr.items.map(p =>
      `<option value="${p.id}"${e.paper.paper_id === p.id ? ' selected' : ''}>${esc(p.name)}</option>`).join('')}</optgroup>`).join('')}
        </select>
        <div class="muted" style="font-size:12px;margin:0 0 8px">
          选一个规格会自动填好纸宽/列数/建议边距；选「自定义」或直接改下面的数字即可用任意毫米尺寸。
        </div>
        <div class="row" style="gap:10px;flex-wrap:wrap">
          <div><label>纸宽 (mm)</label><input id="e-w" type="number" min="40" max="500" step="1" value="${e.paper.width_mm}" style="width:96px"/></div>
          <div><label>纸高 (mm)</label><input id="e-h" type="number" min="0" max="2000" step="1" value="${e.paper.height_mm || 0}" style="width:96px"/></div>
          <div><label>列数</label><input id="e-c" type="number" min="24" max="130" step="1" value="${e.paper.cols}" style="width:92px"/></div>
          <div><label>左右边距 (mm)</label><input id="e-s" type="number" min="0" max="30" step="0.5" value="${e.paper.side_mm}" style="width:100px"/></div>
          <div><label>底部留白 (mm)</label><input id="e-t" type="number" min="0" max="100" step="1" value="${e.paper.tail_mm}" style="width:100px"/></div>
          <div><label>字号缩放</label><input id="e-f" type="number" min="0.6" max="2.5" step="0.1" value="${e.font.scale}" style="width:92px"/></div>
          <button class="btn-sm" id="e-autocol">按纸宽算列数</button>
        </div>
        <div class="muted" style="font-size:12px;margin-top:6px">
          纸高填 0 表示连续纸（热敏小票，长度自动）。留白是签收区高度，默认 20mm（2cm）供客户手写签名。
          A4/A3 单据建议把「左右边距」设10-12mm；小票机 4mm 就够。
        </div>

        <div class="sub-title">🧩 内置模块（勾选决定打印哪些）</div>
        <div class="tpl-mods">
          ${Object.keys(g).map(k => `<div class="tpl-mg"><div class="tpl-mgt">${k}</div>
            ${g[k].map(m => `<label class="tpl-mc" title="${esc(m.hint || '')}">
              <input type="checkbox" data-mk="${m.k}"${e.modules[m.k] ? ' checked' : ''}/><span>${esc(m.label)}</span>
              ${m.hint ? `<i>${esc(m.hint)}</i>` : ''}</label>`).join('')}</div>`).join('')}
        </div>

        <div class="sub-title">➕ 自定义模块（可重复添加同一个模块，比如多行备注）</div>
        <div class="tpl-extra">
          ${(e.extra || []).map((x, i) => extraRow(x, i)).join('') || '<div class="muted" style="font-size:12px">还没有自定义模块。点下面按钮添加。</div>'}
        </div>
        <div class="tpl-extraidk">
          <select id="e-newk" style="width:150px">
            <option value="remark">备注（取订单备注）</option>
            <option value="text">自由文字（写死内容）</option>
            <option value="sign">签收栏</option>
            <option value="handler">经手人</option>
            <option value="warehouse">仓库</option>
            <option value="customer">往来单位</option>
            <option value="order_no">单据编号</option>
            <option value="date">单据日期</option>
            <option value="qty_sum">合计数量</option>
            <option value="payable">优惠后金额</option>
            <option value="before_total">合计折前金额</option>
            <option value="sep">分隔线</option>
            <option value="tail">额外留白(mm)</option>
          </select>
          <input id="e-newlbl" placeholder="标签，如：备注2/ 回执联" maxlength="20" style="width:170px"/>
          <button class="btn-sm" id="e-addx">＋ 添加</button>
          <button class="btn-sm gray" id="e-addx2">＋ 快速重复上一个</button>
        </div>
      </div>`;
  }

  /** 自定义模块的一行：勾选 + 类型 + 标签 + 内容 + 删除 */
  function extraRow(x, i) {
    const KINDS = { remark: '备注', text: '自由文字', sign: '签收栏', handler: '经手人', warehouse: '仓库',
      customer: '往来单位', order_no: '单据编号', date: '单据日期', qty_sum: '合计数量',
      payable: '优惠后金额', before_total: '合计折前金额', sep: '分隔线', tail: '留白' };
    const needText = (x.k === 'text' || x.k === 'tail');
    return `<div class="tpl-x" data-i="${i}">
      <label class="tpl-xsw"><input type="checkbox" data-xon="${i}"${x.on !== false ? ' checked' : ''}/></label>
      <select data-xk="${i}" style="width:110px">
        ${Object.keys(KINDS).map(k => `<option value="${k}"${x.k === k ? ' selected' : ''}>${KINDS[k]}</option>`).join('')}
      </select>
      <input data-xl="${i}" value="${esc(x.label || '')}" placeholder="标签" maxlength="20" style="width:130px"/>
      ${needText ? `<input data-xt="${i}" value="${esc(x.text || '')}" placeholder="${x.k === 'tail' ? '留白毫米数，如 15' : '写死打印的内容'}" maxlength="100" style="flex:1;min-width:150px"/>` : '<span class="muted" style="font-size:12px">值取自订单</span>'}
      <button class="tpl-xdel" data-xdel="${i}" title="删除">✕</button>
    </div>`;
  }

  function wireEditor(body, e, refresh) {
    const T = normalize(e);
    const bind = (id, fn) => { const el = $(id, body); if (el) el.onchange = fn; };
    bind('#e-name', () => { T.name = $('#e-name', body).value.trim() || '未命名模板'; e.name = T.name; });
    bind('#e-desc', () => { T.desc = $('#e-desc', body).value; e.desc = T.desc; });
    // 🔴 手改纸宽/列数 = 用户在用自定义尺寸，要把 paper_id 清空，
    // 否则下次打开编辑器 select 还停在 A4 上，跟下面的数字对不上。
    const touchPaper = () => { T.paper.paper_id = ''; e.paper = T.paper; };
    bind('#e-w', () => { T.paper.width_mm = +$('#e-w', body).value; touchPaper(); });
    bind('#e-h', () => { T.paper.height_mm = +$('#e-h', body).value; touchPaper(); });
    bind('#e-c', () => { T.paper.cols = +$('#e-c', body).value; touchPaper(); });
    bind('#e-s', () => { T.paper.side_mm = +$('#e-s', body).value; e.paper = T.paper; });
    bind('#e-t', () => { T.paper.tail_mm = +$('#e-t', body).value; e.paper = T.paper; });
    bind('#e-f', () => { T.font.scale = +$('#e-f', body).value; e.font = T.font; });

    // 选纸张规格：自动填纸宽/纸高/列数，边距给个适合该尺寸的建议值
    const ps = $('#e-paper', body);
    if (ps) ps.onchange = () => {
      const p = PAPER_FLAT[ps.value];
      if (!p) { T.paper.paper_id = ''; e.paper = T.paper; return; }   // 选「自定义」
      T.paper.paper_id = p.id;
      T.paper.width_mm = p.w;
      T.paper.height_mm = p.h;
      T.paper.cols = p.cols;
      // 建议边距：小票机窄边距省纸，单据留够装订边
      T.paper.side_mm = p.w < 100 ? (p.w < 60 ? 2 : 4) : Math.min(14, Math.max(8, Math.round(p.w / 22)));
      T.paper.tail_mm = p.w < 100 ? (p.w < 60 ? 15 : 20) : 10;
      e.paper = T.paper;
      refresh();
    };
    // 「按纸宽算列数」：经理自定义了纸宽后，一键推一个合适的列数
    const ac = $('#e-autocol', body);
    if (ac) ac.onclick = () => {
      const w = +$('#e-w', body).value || 80;
      T.paper.width_mm = w;
      T.paper.cols = colsForWidth(w);
      if (w < 70) T.paper.cols = Math.min(T.paper.cols, 32);
      T.paper.paper_id = '';
      e.paper = T.paper;
      refresh();
    };

    body.querySelectorAll('[data-mk]').forEach(cb => cb.onchange = () => { T.modules[cb.dataset.mk] = cb.checked; e.modules = T.modules; });

    // ---------- 自定义模块（可重复） ----------
    const syncX = () => { e.extra = T.extra; };
    const addExtra = (k, label, text) => {
      if (T.extra.length >= 20) { alert('自定义模块最多 20 个'); return; }
      T.extra.push({ uid: 'x_' + Math.random().toString(36).slice(2, 8), k: k || 'remark',
        label: String(label || '').slice(0, 20) || '备注' + (T.extra.length + 1),
        on: true, text: String(text || '').slice(0, 100) });
      syncX();
    };
    const ax = $('#e-addx', body);
    if (ax) ax.onclick = () => {
      addExtra($('#e-newk', body).value, $('#e-newlbl', body).value.trim(), '');
      refresh();
    };
    // 「快速重复上一个」：同一个模块一键加多个（备注1/备注2/…），这是经理要的「重复添加」
    const ax2 = $('#e-addx2', body);
    if (ax2) ax2.onclick = () => {
      const last = T.extra[T.extra.length - 1];
      if (!last) { addExtra($('#e-newk', body).value, $('#e-newlbl', body).value.trim(), ''); refresh(); return; }
      const n = T.extra.filter(x => x.k === last.k && x.label.indexOf(last.label) === 0).length;
      addExtra(last.k, last.label.replace(/\d+$/, '') + (n + 1), last.text);
      refresh();
    };
    body.querySelectorAll('[data-xon]').forEach(cb => cb.onchange = () => {
      const i = +cb.dataset.xon; if (T.extra[i]) { T.extra[i].on = cb.checked; syncX(); }
    });
    body.querySelectorAll('[data-xk]').forEach(sel => sel.onchange = () => {
      const i = +sel.dataset.xk; if (T.extra[i]) { T.extra[i].k = sel.value; syncX(); refresh(); }
    });
    body.querySelectorAll('[data-xl]').forEach(inp => inp.oninput = () => {
      const i = +inp.dataset.xl; if (T.extra[i]) { T.extra[i].label = inp.value.slice(0, 20); syncX(); }
    });
    body.querySelectorAll('[data-xt]').forEach(inp => inp.oninput = () => {
      const i = +inp.dataset.xt; if (T.extra[i]) { T.extra[i].text = inp.value.slice(0, 100); syncX(); }
    });
    body.querySelectorAll('[data-xdel]').forEach(btn => btn.onclick = () => {
      const i = +btn.dataset.xdel;
      if (!confirm('删除这个自定义模块？')) return;
      T.extra.splice(i, 1); syncX(); refresh();
    });
  }
  function stripUI(t) {
    // 🔴 extra 必须一起存：少了它，用户配的「重复模块」保存后就没了
    return { id: t.id, name: t.name, desc: t.desc, builtin: !!t.builtin,
      paper: t.paper, font: t.font, modules: t.modules,
      extra: Array.isArray(t.extra) ? t.extra.map(x => ({ k: x.k, label: x.label, on: x.on, text: x.text })) : [] };
  }

  /** 打单前选模板：列出已保存模板 + 「管理模板」+ 「以后用这个」 */
  function pick(list, onPick) {
    const L = (list && list.length ? list : DEFAULT).map(normalize);
    const body = overlay('选择打印模板', `
      <div class="tpl-pick">
        ${L.map((x, i) => `<label class="tpl-pickitem" title="${esc(x.name)}">
          <input type="radio" name="tplpick" value="${x.id}"${i === 0 ? ' checked' : ''}/>
          <b>${esc(x.name)}${x.builtin ? '<i class="tpl-bi">内置</i>' : ''}</b>
          <span>${x.paper.width_mm}${x.paper.height_mm ? '×' + x.paper.height_mm : ''}mm · ${x.paper.cols} 列 · 留白 ${x.paper.tail_mm}mm${(x.extra||[]).filter(y=>y.on!==false).length ? ' · 自定义模块 ' + (x.extra||[]).filter(y=>y.on!==false).length + ' 项' : ''}</span>
          <i class="tpl-pickd">${esc(x.desc || '')}</i>
        </label>`).join('')}
      </div>
      <label class="tpl-pick-remember"><input type="checkbox" id="tpl-remember"/> 以后打单都用这个模板（设为默认）</label>
      <div class="fb-actions">
        <button class="btn-sm gray" id="tpl-manage">管理模板</button>
        <button class="btn-sm" id="tpl-cancel">取消</button>
        <button class="btn-primary" id="tpl-ok">开始打单</button>
      </div>`, true);
    const close = () => { const o = document.getElementById('tpl-overlay'); if (o) o.remove(); };
    $('#tpl-cancel', body).onclick = close;
    $('#tpl-ok', body).onclick = () => {
      const id = (body.querySelector('input[name=tplpick]:checked') || {}).value || L[0].id;
      const remember = $('#tpl-remember', body).checked;
      const t = L.find(x => x.id === id) || L[0];
      close();
      onPick(t, remember);
    };
    $('#tpl-manage', body).onclick = () => {
      close();
      if (window.ERPApp && window.ERPApp.manageTemplates) window.ERPApp.manageTemplates();
    };
  }

  window.ERPTpl = {
    MODULES, PAPERS, DEFAULT, normalize, cols, colsForWidth, renderHtml, renderEsc, openManager, pick,
    fmtQtyNum, fmt2,
    DEFAULT_KEY: 'erp_print_templates'
  };
})();