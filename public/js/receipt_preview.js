/* 订单小票「预览 / 导出图片」模块（2026-10-15 新增）
 *
 * 为什么不用 HTML 截图（html2canvas 之类）：
 *   ① 要引第三方库，离线缓存里没有，弱网打不开；
 *   ② 截图受字体/缩放影响，客户收到的电子单版式会飘。
 * 这里用 Canvas 自己按ESC/POS 的列宽（80mm / 48 列）等宽绘制，
 * 预览、导出图片、系统打印三处共用同一份绘制逻辑，所见即所打。
 *
 * 两个关键改动：
 *   1. 底部预留 2cm 空白（TAIL_MM），方便客户手写签收
 *   2. 🔴 2026-10-15 经经理确认：小票**只显示条码数字，不画条形码图形**。
 *      图形在 80mm 热敏纸上又粗又占地方，实际没人扫，纯浪费纸。
 *      （ean13Bits() 函数保留但不再调用，将来要图形条码时可直接启用）
 */
(function () {
  'use strict';

  // ===================== 显示宽度（中文/全角按 2 列） =====================
  function isWide(ch) {
    const k = ch.codePointAt(0);
    return (k >= 0x1100 && k <= 0x115F) || (k >= 0x2E80 && k <= 0x303E) ||
      (k >= 0x3041 && k <= 0x33FF) || (k >= 0x3400 && k <= 0x4DBF) ||
      (k >= 0x4E00 && k <= 0x9FFF) || (k >= 0xA000 && k <= 0xA4CF) ||
      (k >= 0xAC00 && k <= 0xD7A3) || (k >= 0xF900 && k <= 0xFAFF) ||
      (k >= 0xFE10 && k <= 0xFE19) || (k >= 0xFE30 && k <= 0xFE6F) ||
      (k >= 0xFF00 && k <= 0xFF60) || (k >= 0xFFE0 && k <= 0xFFE6);
  }
  function wOf(s) {
    let w = 0;
    for (const c of String(s == null ? '' : s)) w += isWide(c) ? 2 : 1;
    return w;
  }
  function padR(s, n) { const d = n - wOf(s); return d > 0 ? s + ' '.repeat(d) : s; }
  function padL(s, n) { const d = n - wOf(s); return d > 0 ? ' '.repeat(d) + s : s; }
  function padC(s, n) {
    const d = n - wOf(s); if (d <= 0) return s;
    const l = Math.floor(d / 2);
    return ' '.repeat(l) + s + ' '.repeat(d - l);
  }
  function clip(s, n) {
    let w = 0, out = '';
    for (const c of String(s == null ? '' : s)) {
      const cw = isWide(c) ? 2 : 1;
      if (w + cw > n) break;
      out += c; w += cw;
    }
    return out;
  }

  // ===================== 数值格式（与app.js 保持一致） =====================
  function fmt2(v) { return Number(v || 0).toFixed(2); }
  function fmtQty(q) {
    const n = Number(q || 0);
    return n % 1 === 0 ? String(n) : n.toFixed(2).replace(/0+$/, '').replace(/\.$/, '');
  }

  // ===================== EAN-13 条码编码 =====================
  const EAN_L = ['0001101', '0011001', '0010011', '0111101', '0100011',
    '0110001', '0101111', '0111011', '0110111', '0001011'];
  const EAN_G = ['0100111', '0110011', '0011011', '0100001', '0011101',
    '0111001', '0000101', '0010001', '0001001', '0010111'];
  const EAN_R = ['1110010', '1100110', '1101100', '1000010', '1011100',
    '1001110', '1010000', '1000100', '1001000', '1110100'];
  //首位数字决定左侧 6 位的奇偶（L=左码，G=右码的补码）
  const EAN_PARITY = ['LLLLLL', 'LLGLGG', 'LLGGLG', 'LLGGGL', 'LGLLGG',
    'LGGLLG', 'LGGGLL', 'LGLGLG', 'LGLGGL', 'LGGLGL'];

  /** @returns {string|null} 95 位 0/1 串（1=黑条0=白空）；不是 12/13 位数字则返回 null */
  function ean13Bits(code) {
    let s = String(code == null ? '' : code).replace(/\D/g, '');
    if (s.length === 12) s = '0' + s;          // UPC-A → EAN-13 左侧补 0
    if (s.length !== 13) return null;
    const par = EAN_PARITY[+s[0]];
    let bits = '101';                           // 起始符
    for (let i = 1; i <= 6; i++) bits += (par[i - 1] === 'L' ? EAN_L[+s[i]] : EAN_G[+s[i]]);
    bits += '01010';                            // 中间分隔符
    for (let i = 7; i <= 12; i++) bits += EAN_R[+s[i]];
    bits += '101';                              // 结束符
    return bits;
  }

  // ===================== 版式常量 =====================
  // 🔴 2026-10-15：全部改为「按打印模板动态计算」（geo(d)）。
  // 纸宽、列数、边距、留白、字号都来自 d.tpl（print_template.js 的模板对象），
  // 没给模板时退回旧的 80mm/48 列/2cm 留白，版式与升级前完全一致。
  const MM = 96 / 25.4;                        // CSS px 每毫米
  const FALLBACK_PAPER = { width_mm: 80, cols: 48, side_mm: 4, tail_mm: 20 };

  /** 从 d.tpl 算出这一张小票的全部几何量（1x px） */
  function geo(d) {
    const tp = (d && d.tpl && d.tpl.paper) ? d.tpl.paper : FALLBACK_PAPER;
    const fscale = (d && d.tpl && d.tpl.font && d.tpl.font.scale) || 1;
    const COLS = tp.cols || FALLBACK_PAPER.cols;
    const SIDE_MM = tp.side_mm == null ? FALLBACK_PAPER.side_mm : tp.side_mm;
    const TAIL_MM = tp.tail_mm == null ? FALLBACK_PAPER.tail_mm : tp.tail_mm;
    const PAPER_W = tp.width_mm * MM;
    // 🔴 列宽必须用「纸宽 − 左右边距」再除以列数，否则 48 列画完正好超出纸宽，
    // 右侧金额会被裁掉（实测踩过：数字列只剩「84.」）。
    const CONTENT_W = PAPER_W - SIDE_MM * 2 * MM;
    const COL_W = CONTENT_W / COLS;
    // Courier New 半角步进宽 = 0.6 × 字号，反推字号保证半角不重叠
    const FS = COL_W / 0.6 * fscale;
    const LH = FS * 1.55;
    // 数字三列按旧版 6:7:8 的比例分配，与 48 列时完全一致
    // 🔴 2026-10-18：数字区从 21 份放大到 28 份（数量6 + 原价7 + 特价7 + 金额8）。
    const numW = Math.max(9, Math.round(COLS * 28 / 48));
    const QTY_W = Math.max(4, Math.round(numW * 6 / 28));
    const PRICE_W = Math.max(5, Math.round(numW * 7 / 28));
    // 🔴 2026-10-18：新增「特价」列（折后成交价），与「原价」列左右并排（管家婆版式）。
    //    数字区总宽从 21 份放大到 28 份：原「单价」7 份拆成 原价7 + 特价7，再加金额8。
    const SALE_W = Math.max(5, Math.round(numW * 7 / 28));
    const AMT_W = Math.max(5, numW - QTY_W - PRICE_W - SALE_W);
    // 🔴🔴 2026-10-29：数字区加 **2 条竖线分区**，版式与原生实打印（BtPrinter.kt）、
    //    网页版模板引擎（print_template.js）完全一致：
    //       名称/条码区 | 数量 原价 特价 | 金额
    //    竖线各占 1 个半角列，从左区（LEFT_W）里抠出来。
    //    48 列时：LEFT_W 18 + | 1 + 6 + 7 + 7 + | 1 + 8 = 48 ✓
    //    （LEFT_W 18 恰好放下「条码:」5 列 + 13 位数字 = 18 列，条码仍与数字同行、不多占行）
    //    🔴 三套引擎的列宽算法必须同步，否则「预览」和「打印」会错位。
    const mods = ((d && d.tpl && d.tpl.modules) || null);
    const on = k => !mods || mods[k] !== false;
    const hasA = on('qty') || on('price') || on('sale');   // 数量/原价/特价 组
    const hasAmt = on('amount');                          // 金额 列
    const SEP_W = (hasA || hasAmt ? 1 : 0) + (hasA && hasAmt ? 1 : 0);
    return {
      COLS, MM, PAPER_W, SIDE_MM, TAIL_MM, CONTENT_W, COL_W, FS, LH,
      QTY_W, PRICE_W, SALE_W, AMT_W, SEP_W, VBAR: '|',
      LEFT_W: COLS - (QTY_W + PRICE_W + SALE_W + AMT_W) - SEP_W,
      mods: mods
    };
  }
  /** 模块开关：没模板时全部按开启处理（等于旧行为） */
  function modOn(G, k) { return !G.mods || G.mods[k] !== false; }

  /**
   * 数字区分区段（与 print_template.js 的 numSegs 完全同规则）。
   *
   * 版式： 名称/条码区 | 数量 原价 特价 | 金额
   *   · 第 1 条竖线：名称区与数字区之间（只要有数字列就有）
   *   · 第 2 条竖线：数量/原价/特价组与金额列之间（两组都在才有）
   *
   * 🔴 与 print_template.js 的同名函数必须保持一致，否则「预览」和「打印」会错位。
   */
  function numSegs(G, vals) {
    const hasA = modOn(G, 'qty') || modOn(G, 'price') || modOn(G, 'sale');
    const hasAmt = modOn(G, 'amount');
    const segs = [];
    if (hasA || hasAmt) segs.push({ sep: true });
    if (modOn(G, 'qty')) segs.push({ t: vals.qty, w: G.QTY_W });
    if (modOn(G, 'price')) segs.push({ t: vals.price, w: G.PRICE_W });
    if (modOn(G, 'sale')) segs.push({ t: vals.sale, w: G.SALE_W });
    if (hasAmt) {
      if (hasA) segs.push({ sep: true });
      segs.push({ t: vals.amount, w: G.AMT_W });
    }
    return segs;
  }

  /** 画布初始高度（1x px）。draw() 自己会返回实际墨迹底部（yEnd），
   *  这里先画在一张「足够高」的画布上，再用 yEnd + 2cm 反推裁剪——
   *  比在这里猜公式可靠得多（公式估小了会导致 2cm 留白被吃掉）。
   * 系数只需保证 ≥ 实际需要，最后都会被裁掉。 */
  function estimateHeight(d, G) {
    const items = Array.isArray(d.items) ? d.items : [];
    const fixed = 6 * G.MM + G.LH * 16;
    // 2026-10-15 起不画条码图形了，每行明细 = 名称 1 行 + 数字/金额 1 行
    // （关掉条码模块时每行只需 1 行）
    // 🔴 窄纸（58mm）下左列放不下 13 位条码，条码会独占一行 → 那一行是3 行不是 2 行。
    // 估算按最坏情况给足，宁高勿矮（宁可多留白，也不能让底部裁剪吃掉内容）。
    const narrow = (G.COLS - (Math.max(9, Math.round(G.COLS * 21 / 48)) )) < 20;
    const per = modOn(G, 'name') && modOn(G, 'barcode') ? (narrow ? 3 : 2) : 1;
    const itemH = items.length * G.LH * per;
    // 🔴 这里必须给足高度：新Canvas 是「先画高、再裁到 墨迹+2cm」。
    // 若初始画布比「墨迹+2cm」还矮，min() 会把高度原样返回，裁剪形同虚设，
    // 结果底部留白就是「估算和实际的差值」（实测 8.8cm）而不是 2cm。
    // 乘 4 是保险——反正最后会裁掉，多余的只是内存。
    return Math.ceil((fixed + itemH + G.TAIL_MM * G.MM + 8) * 4);
  }

  /**
   * 把小票画到 ctx。
   * @param {CanvasRenderingContext2D} ctx
   * @param {object} d  与 buildReceipt 同样的数据结构
   * @param {number} scale 像素倍率（导出高清图用 3）
   */
  function draw(ctx, d, scale, G) {
    const s = scale || 1;
    G = G || geo(d);
    // 🔴 SALE_W（特价列）必须在解构里，漏了会变 undefined 拼进小票
    const { COLS, LH, SIDE_MM, COL_W, LEFT_W, QTY_W, PRICE_W, SALE_W, AMT_W, FS } = G;
    const M = SIDE_MM * G.MM;                   // 左边距
    const cw = COL_W;                            // 每列宽（1x）
    const px = v => v * s;
    const isRet = !!d.is_return;
    const items = Array.isArray(d.items) ? d.items : [];
    const M_ = k => modOn(G, k);                 // 模块开关速记

    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, ctx.canvas.width, ctx.canvas.height);
    ctx.textBaseline = 'alphabetic';
    ctx.textAlign = 'left';

    let y = 4 * G.MM;

    // 🔴 逐列定位绘制等宽文本。中文按 2 个半角列算，
//  但中文字面宽 ≠ 2×Courier 半角宽，所以中文要缩到格内居中画，否则会挤到右邻列。
    function text(str, opt) {
      opt = opt || {};
      const cols = opt.cols == null ? COLS : opt.cols;
      const size = (opt.fs || FS) * s;
      const mono = '"Courier New",Menlo,Consolas,monospace';
      const cjk = '"Microsoft YaHei","SimSun","PingFang SC",monospace';
      let str2 = clip(str, cols);
      if (opt.align === 'right') str2 = padL(str2, cols);
      else if (opt.align === 'center') str2 = padC(str2, cols);
      else str2 = padR(str2, cols);
      const start = opt.col || 0;
      const baseY = opt.y != null ? opt.y : y;
      let c = 0;
      for (const ch of str2) {
        const wide = isWide(ch);
        const span = wide ? 2 : 1;
        if (c + span > cols) break;
        const cellW = cw * span * s;
        if (wide) {
          // 中文按格宽缩放到 <= 0.92 格，居中放
          ctx.font = (opt.bold ? 'bold ' : '') + (size * 0.92) + 'px ' + cjk;
          const wCh = ctx.measureText(ch).width;
          const scaleF = Math.min(1, (cellW * 0.94) / Math.max(1, wCh));
          if (scaleF < 1) {
            ctx.save();
            ctx.translate(px(M + (start + c) * cw), px(baseY));
            ctx.scale(scaleF, 1);
            ctx.fillText(ch, 0, 0);
            ctx.restore();
          } else {
            ctx.fillText(ch, px(M + (start + c) * cw), px(baseY));
          }
        } else {
          ctx.font = (opt.bold ? 'bold ' : '') + size + 'px ' + mono;
          ctx.fillText(ch, px(M + (start + c) * cw), px(baseY));
        }
        c += span;
      }
    }
    function rule(ch) {
      ctx.fillStyle = '#000';
      const th = Math.max(1, Math.round(0.25 * G.MM * s));
      // ⚠️ y 已是缩放后坐标，不能再 px()；线画在当前基线上方约 0.35 个字号
      const yy = y - FS * s * 0.35;
      if (ch === '=') {
        ctx.fillRect(px(M), px(yy), px(cw * COLS), th);
      } else {
        for (let c = 0; c < COLS; c += 2) ctx.fillRect(px(M + c * cw), px(yy), px(cw), th);
      }
    }
    /**
     * 🔴 2026-10-29：倍宽绘制（模拟 ESC/POS 的 `GS ! 0x01` = 宽×2、高×1）。
     *
     * Canvas 没有 ESC/POS 指令，但用 `ctx.scale(2,1)` 横向拉伸效果完全等价：
     * 字号不变（→ 高度不变、行高不变、纸长不变），每个字符横向拉伸 2 倍、
     * 正好占 2 个半角列（Courier 半角步进 0.6×字号 → 2×0.6×fs = 2 列）。
     *
     * 用途：合计「优惠后金额」的数字用倍宽大字 → 竖笔画被拉成 2 点宽，
     * 「6」和「8」绝不混淆（本机热敏打印机 Font A 12×24 点阵下最细的 1 点竖易糊断）。
     *
     * @param str    文本
     * @param baseY  基线（1x 坐标）
     * @param startCol 起始列号（0 基）
     */
    function textWide(str, baseY, startCol, opt) {
      opt = opt || {};
      const size = (opt.fs || FS) * s;
      const mono = '"Courier New",Menlo,Consolas,monospace';
      let c = startCol;
      for (const ch of String(str == null ? '' : str)) {
        if (c + 2 > COLS) break;
        ctx.save();
        ctx.font = (opt.bold ? 'bold ' : '') + size + 'px ' + mono;
        ctx.translate(px(M + c * cw), px(baseY));
        ctx.scale(2, 1);                    // 横向 2 倍、高不变
        ctx.fillText(ch, 0, 0);
        ctx.restore();
        c += 2;                            // 倍宽字符占 2 列
      }
    }
    function lf(n) { y += (n || 1) * LH * s; }

    // ---- 抬头 ----
    ctx.fillStyle = '#000';
    if (M_('shop')) { text(clip(d.shop || '销售小票', Math.round(COLS * 0.46)), { align: 'center', fs: FS * 1.45, bold: true }); lf(1.3); }
    if (M_('doc_title')) { text(clip(d.doc_title || '销售订单', COLS), { align: 'center', fs: FS * 1.15 }); lf(0.9); }
    if (M_('sep1')) { rule('='); lf(0.8); }

    if (M_('customer')) { text('往来单位名称:' + (d.customer_name || ''), { cols: COLS }); lf(1); }
    if (M_('order_no')) { text('单据编号:' + (d.order_no || ''), { cols: COLS }); lf(1); }
    if (M_('date')) { text('单据日期:' + (d.date_time || ''), { cols: COLS }); lf(1); }
    if (M_('handler')) {
      const wh = M_('warehouse') ? ((isRet ? '入库仓库:' : '出库仓库:') + (d.warehouse || '天河仓')) : '';
      text(padR('经手人:' + (d.handler || ''), COLS - wOf(wh)) + wh, { cols: COLS });
    } else if (M_('warehouse')) {
      text((isRet ? '入库仓库:' : '出库仓库:') + (d.warehouse || '天河仓'), { cols: COLS });
    }
    lf(1.05);

    if (M_('sep2')) { rule('-'); lf(0.9); }
    if (M_('thead')) {
      // 🔴 2026-10-29：数字区加竖线分区 + 整块加粗（6/8 一眼可辨）
      const segs = numSegs(G, { qty: '数量', price: '原价', sale: '特价', amount: '金额' });
      const num = segs.map(s => s.sep ? G.VBAR : padL(s.t, s.w)).join('');
      text(padR((M_('idx') ? '序号 ' : '') + '名称', LEFT_W) + num, { bold: true });
      lf(0.9);
    }
    if (M_('sep3') && M_('thead')) { rule('-'); lf(0.9); }

    // ---- 明细：名称一行 + 条码/金额一行 ----
    let qtySum = 0;
    items.forEach(function (it, i) {
      // 🔴 2026-10-15：原来对 qty / price 取绝对值，退货单每行都印成正数，
      // 与负数的「合计折前金额 / 优惠后金额」自相矛盾。
      // 现在原样使用服务端落库值：退货单 qty 是负数，price 是正数（折后成交单价）。
      const q = Number(it.qty || 0);
      const p = Number(it.price || 0);
      qtySum += q;
      const bc = M_('barcode') ? String(it.barcode || '').trim() : '';
      const amt = (it.subtotal != null && Number(it.subtotal) !== 0)
        ? Number(it.subtotal) : q * p;
      // 🔴 2026-10-18：原「单价」列拆成 原价 / 特价。
      //    原价 = it.orig_price（折前），特价 = it.price（折后成交单价）。
      //    老单 orig_price 为 null 时兜回 p，绝不印 0.00。
      const op = (it.orig_price != null && Number(it.orig_price) > 0) ? Number(it.orig_price) : p;
      // 🔴 2026-10-29：明细数字区加竖线分区（与 print_template.js / BtPrinter.kt 同规则）
      const segs = numSegs(G, {
        qty: fmtQty(q) + (it.unit || ''),
        price: fmt2(op), sale: fmt2(p), amount: fmt2(amt)
      });
      const num = segs.map(s => s.sep ? G.VBAR : padL(s.t, s.w)).join('');

      if (M_('name')) {
        // 🔴 2026-10-29：明细行整块加粗 —— 商品名里有数字（200ml/125g）、条码更是纯数字
        text(clip((M_('idx') ? (i + 1) + ' ' : '') + (it.name || ''), COLS), { bold: true });
        lf(0.85);
      }
      // 🔴 经理要求（2026-10-15）：小票上只显示条码「数字」，不画条形码图形。
      // 图形在 80mm 热敏纸上又粗又占地方，实际没人扫，纯浪费纸。
      // 布局：左边「条码:1234567890128」紧贴序号列，右边数量/单价/金额靠右对齐，
      // 比原来「数字独占左27 列」紧凑得多，也省一行高度。
      // 关掉 name 模块时，名称与数字合并到同一行（省一整行纸）。
      // 🔴 与 renderEsc 同一条规则：左列放不下完整条码时条码独占一行，绝不截断末位
      //（58mm = 32 列，LEFT_W 只有 18，「条码:」+13 位数字塞不下）
      const bcFit = bc && wOf('条码:' + bc) <= LEFT_W;
      const left = bc ? '条码:' + bc : '';
      if (M_('name') || bc) {
        if (bc && !bcFit) {
          text(clip(left, COLS), { bold: true });          // 条码整行，不截断
          lf(0.85);
          if (num.length) { text(padR('', LEFT_W) + num, { cols: COLS, bold: true }); lf(0.95); }
        } else {
          text(padR(clip(left, LEFT_W), LEFT_W) + num, { cols: COLS, bold: true });
          lf(0.95);
        }
      } else if (num.length) {
        text(padR('', LEFT_W) + num, { cols: COLS, bold: true });
        lf(0.95);
      }
    });

    // ---- 合计 ----
    if (M_('sep3')) { rule('-'); lf(0.95); }
    // 🔴 同上：这里原来也取了绝对值，退货单会印成正数合计。改为原样透传负数。
    const before = Number(d.before_total != null ? d.before_total : (d.total || 0));
    const pay = Number(d.payable != null ? d.payable : before);
    const saved = before - pay;
    if (M_('qty_sum')) {
      const r = M_('before_total') ? ('合计折前金额:' + fmt2(before)) : '';
      const ls = '合计数量:' + fmtQty(qtySum);
      text(r ? padR(ls, COLS - wOf(r)) + r : ls, { bold: true });
      lf(0.95);
    }
    if (M_('saved') || M_('payable')) {
      // 🔴 2026-10-29：最关键的「优惠后金额」用**倍宽大字**（模拟 ESC/POS 的 GS ! 0x01：
      //    宽×2、高×1）→ 行高不变、纸长不变，但每个数字的竖笔画被拉成 2 点宽 → 6/8 绝不混淆。
      //    与原生实打印 / 网页版 ESC 版式一致。
      const ls = M_('saved') ? ('折扣优惠金额:' + fmt2(saved)) : '';
      const r = M_('payable') ? ('优惠后金额:' + fmt2(pay)) : '';
      if (r) {
        // 右半段：标签 + 倍宽数字（倍宽字符占 2 列）
        const payNum = fmt2(pay);
        const payNumW = payNum.length * 2;
        const rightW = wOf('优惠后金额:') + payNumW;
        const avail = COLS - rightW;
        if (wOf(ls) <= avail) {
          text(padR(ls, avail), { bold: true });
          textWide('优惠后金额:', y, avail, { bold: true });
          textWide(payNum, y, avail + wOf('优惠后金额:'), { bold: true });
        } else {
          text(ls, { bold: true });
          lf(0.95);
          text(padR('', avail), { bold: true });
          textWide('优惠后金额:', y, avail, { bold: true });
          textWide(payNum, y, avail + wOf('优惠后金额:'), { bold: true });
        }
      } else {
        text(ls, { bold: true });
      }
      lf(1);
    } else if (M_('before_total')) {
      text('合计折前金额:' + fmt2(before), { bold: true }); lf(1);
    }
    if (M_('sep4')) { rule('-'); lf(1); }
    if (M_('sign')) { text('客户签收:____________________'); lf(1); }
    if (M_('footer')) { text(clip(d.footer || '谢谢惠顾，欢迎再次光临', COLS), { align: 'center', fs: FS * 0.9 }); lf(1); }

    return y;   // 返回墨迹底部（1x），尾部 2cm 由 newCanvas 按实际墨迹再补
  }

  /** 建一张画好小票的 canvas：高度按实际墨迹回扫，底部再补2cm 签收留白 */
  function newCanvas(d, scale) {
    const s = scale || 1;
    const G = geo(d);
    // 先画在一张明显更高的画布上，保证内容不会被下沿切掉
    const cv = document.createElement('canvas');
    cv.width = Math.round(G.PAPER_W * s);
    cv.height = Math.round(estimateHeight(d, G) * s);
    const ctx = cv.getContext('2d', { willReadFrequently: true });
    const yEnd = draw(ctx, d, s, G);       // draw 返回墨迹底部（已含 scale）

    // 回扫墨迹真实底部（yEnd 是估算，draw 里text 有 baseline 偏移，取实际更准）
    let last = 0;
    try {
      const img = ctx.getImageData(0, 0, cv.width, cv.height).data;
      outer:
      for (let y = cv.height - 1; y >= 0; y--) {
        const row = y * cv.width * 4;
        for (let x = 0; x < cv.width; x++) {
          if (img[row + x * 4] < 250) { last = y; break outer; }
        }
      }
    } catch (e) { last = Math.round(yEnd); }

    // 墨迹底部 + 2cm 签收留白（经理要求：底部预留 2cm 方便客户手写签名）
    // 两种墨迹底取较大值：yEnd 是排版推算（含 lf 余量），last 是像素回扫（含 baseline 下沿）
    const inkBottom = Math.max(last, Math.round(yEnd));
    const finalH = Math.min(cv.height, Math.round(inkBottom + G.TAIL_MM * G.MM * s));
    if (finalH < cv.height) {
      const out = document.createElement('canvas');
      out.width = cv.width;
      out.height = finalH;
      out.getContext('2d').drawImage(cv, 0, 0);
      return out;
    }
    return cv;
  }

  // ===================== 预览浮层 =====================
  const TPL = `
<div id="rc-overlay" class="overlay print-overlay hidden">
  <div class="print-wrap rc-wrap">
    <div class="rc-head">
      <span class="rc-title">订单预览（打印效果）</span>
      <button id="rc-close" class="rc-x" aria-label="关闭">✕</button>
    </div>
    <div class="rc-scroll"><canvas id="rc-canvas"></canvas></div>
    <div class="overlay-actions rc-actions">
      <button id="rc-dl" class="btn-primary">保存图片</button>
      <button id="rc-print" class="btn-primary" style="background:#1aad19">打印</button>
    </div>
    <div class="rc-tip">底部已预留 2cm 签收空白 · 只显示条码数字，不打印条形码图形</div>
  </div>
</div>
<img id="rc-print-img" alt=""/>`;

  let injected = false;
  function ensureDom() {
    if (injected) return;
    const box = document.createElement('div');
    box.innerHTML = TPL;
    while (box.firstChild) document.body.appendChild(box.firstChild);
    const st = document.createElement('style');
    st.textContent = `
/* 🔴 经理 2026-10-15 反馈「按钮占太多空间、预览被遮挡」：
   预览图是主角，按钮压成一条 44px 矮条贴底，滚动区吃掉剩余高度。 */
/* 🔴 经理 2026-10-15 反馈「预览会隐藏在页面后面」。
   .overlay 通用类是 z-index:50，订单详情弹窗也是 50，
   同层级时靠 DOM 顺序决定（本浮层后插入才压得住），太脆弱——
   这里显式提到 70（toast 是 99、扫码层 99999），别跟通用浮层抢层级。 */
#rc-overlay{z-index:70;}
.rc-wrap{max-width:440px;display:flex;flex-direction:column;max-height:92vh;padding:10px 12px 12px;}
.rc-head{display:flex;align-items:center;margin-bottom:6px;}
.rc-title{font-size:14px;font-weight:700;color:#333;flex:1;}
.rc-x{border:0;background:#eee;width:28px;height:28px;border-radius:50%;
       font-size:15px;color:#555;cursor:pointer;line-height:1;padding:0;}
.rc-x:hover{background:#e0e0e0;}
.rc-scroll{flex:1 1 auto;min-height:0;overflow:auto;background:#e9ecef;
            border-radius:8px;padding:8px;}
#rc-canvas{width:100%;height:auto;display:block;background:#fff;
           box-shadow:0 1px 4px rgba(0,0,0,.25);}
.rc-actions{display:flex;gap:8px;margin-top:8px;}
.rc-actions .btn-primary{flex:1;margin-top:0;padding:0;font-size:13px;height:40px;}
.rc-tip{font-size:10px;color:#999;text-align:center;margin-top:5px;}
#rc-print-img{display:none;}
@media print{
  #rc-print-img{display:block !important;width:var(--rc-mm,80mm);margin:0;}
  .rc-wrap{max-width:var(--rc-mm,80mm) !important;padding:0 !important;max-height:none !important;}
  .rc-scroll{min-height:0 !important;max-height:none !important;overflow:visible !important;
             background:#fff !important;padding:0 !important;}
  .rc-head,.rc-actions,.rc-tip{display:none !important;}
  #rc-canvas{display:none !important;}
}`;
    document.head.appendChild(st);
    injected = true;
  }

  let current = null;

  /** 打开订单预览（d = 与 buildReceipt 相同的数据结构） */
  function open(d) {
    ensureDom();
    current = d;
    // 🔴 纸宽随模板变：@media print 里原来的 width:80mm / max-width:80mm 是硬编码，
    // 换 58mm 模板打印就会缩错。这里按当前模板动态覆写内联样式。
    applyPaperWidth(d);
    const cv = newCanvas(d, 2);                 // 预览用2x，缩放显示依然清晰
    const el = document.getElementById('rc-canvas');
    el.width = cv.width; el.height = cv.height;
    el.getContext('2d').drawImage(cv, 0, 0);
    document.getElementById('rc-overlay').classList.remove('hidden');

    const dl = document.getElementById('rc-dl');
    if (dl) dl.onclick = function () { download(d); };
    const pb = document.getElementById('rc-print');
    if (pb) pb.onclick = function () { printReceipt(d); };
    const cb = document.getElementById('rc-close');
    if (cb) cb.onclick = function () { document.getElementById('rc-overlay').classList.add('hidden'); };
  }

  /** 把打印时的纸宽写成 CSS 变量，内联样式优先级高于样式表里的 80mm */
  function applyPaperWidth(d) {
    const mm = ((d && d.tpl && d.tpl.paper && d.tpl.paper.width_mm) || FALLBACK_PAPER.width_mm);
    const tail = ((d && d.tpl && d.tpl.paper && d.tpl.paper.tail_mm));
    const root = document.documentElement;
    root.style.setProperty('--rc-mm', mm + 'mm');
    if (tail != null) root.style.setProperty('--rc-tail', tail + 'mm');
    const img = document.getElementById('rc-print-img');
    if (img) img.style.width = mm + 'mm';
    const wrap = document.querySelector('#rc-overlay .rc-wrap');
    if (wrap) wrap.style.maxWidth = Math.round(mm * 5.5) + 'px';
  }

  function fileName(d) {
    const no = String((d && d.order_no) || 'order').replace(/[^\w\u4e00-\u9fa5-]/g, '');
    return '订单_' + no + '.png';
  }

  /** 导出 PNG（发给客户用）。优先「分享」，不支持则直接下载 */
  function download(d) {
    try {
      const cv = newCanvas(d, 3);              // 3x ≈ 230dpi，发微信/打印都够
      cv.toBlob(function (blob) {
        if (!blob) { alert('生成图片失败'); return; }
        const f = new File([blob], fileName(d), { type: 'image/png' });
        if (navigator.canShare && navigator.canShare({ files: [f] })) {
          navigator.share({ files: [f], title: d.order_no || '订单' }).catch(function () { });
          return;
        }
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url; a.download = fileName(d);
        document.body.appendChild(a); a.click(); a.remove();
        setTimeout(function () { URL.revokeObjectURL(url); }, 4000);
      }, 'image/png');
    } catch (e) {
      alert('保存失败：' + (e && e.message ? e.message : e));
    }
  }

  /** 系统打印：把 canvas 转成 img 交给浏览器打印（版式 1:1，无缩放漂移） */
  function printReceipt(d) {
    const cv = newCanvas(d, 3);
    const img = document.getElementById('rc-print-img');
    img.src = cv.toDataURL('image/png');
    const go = function () { window.print(); };
    if (img.complete) go();
    else img.onload = go;
  }

  window.ERPReceipt = { open: open, download: download, draw: draw, newCanvas: newCanvas, ean13Bits: ean13Bits };
})();