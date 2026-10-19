// 网页版「开单模板导入」的服务端解析与匹配（零依赖）。
// 输入：上传的 xlsx 二进制 + 当前套账的客户/商品名单；
// 输出：{ stores: [...], lines: [...] }，供前端预览确认后逐单创建。
//
// 与原生 App 的 TableFile.kt / TableOcr.kt 保持同一套规则：
//   ① 方向自适应：列=门店、行=品项（老分配表）与 行=门店、列=产品（开单模板）都能吃；
//   ② 门店列头 / 品项名用同一套模糊匹配（归一化 + 最长公共子序列）。
const X = require('./xlsx_util');

// ---------- 文本归一化 ----------
function fullHalf(s) {
  let out = '';
  for (const c of String(s == null ? '' : s)) {
    const code = c.charCodeAt(0);
    if (code >= 0xFF10 && code <= 0xFF19) out += String.fromCharCode(code - 0xFEE0); // ０-９
    else if (c === '．') out += '.';
    else out += c;
  }
  return out;
}

function norm(s0) {
  let s = String(s0 == null ? '' : s0);
  s = s.replace(/[\uFF01-\uFF5E]/g, c => String.fromCharCode(c.charCodeAt(0) - 0xFEE0)).replace(/\u3000/g, '');
  s = s.toLowerCase();
  let out = '';
  for (const ch of s) {
    if ((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'z') || /[\u4e00-\u9fff]/.test(ch)) out += ch;
  }
  return out;
}

// 最长公共子序列长度
function lcs(a, b) {
  if (!a || !b) return 0;
  let best = 0;
  let prev = new Array(b.length + 1).fill(0);
  for (let i = 1; i <= a.length; i++) {
    const cur = new Array(b.length + 1).fill(0);
    const ca = a[i - 1];
    for (let j = 1; j <= b.length; j++) {
      cur[j] = (ca === b[j - 1]) ? prev[j - 1] + 1 : Math.max(prev[j], cur[j - 1]);
      if (cur[j] > best) best = cur[j];
    }
    prev = cur;
  }
  return best;
}

function sim(a, b) {
  if (!a || !b) return 0;
  if (a === b) return 1;
  return lcs(a, b) * 2 / (a.length + b.length);
}

// 中文数字映射（"3店" → "三店"，支持 1-12）
const CN = { 0: '零', 1: '一', 2: '二', 3: '三', 4: '四', 5: '五', 6: '六', 7: '七', 8: '八', 9: '九' };
function digitToCn(s) {
  const m = /^(\d+)(.*)$/.exec(String(s == null ? '' : s));
  if (!m) return s;
  const n = parseInt(m[1], 10);
  let cn = null;
  if (n >= 1 && n <= 9) cn = CN[m[1][0]];
  else if (n === 10) cn = '十';
  else if (n === 11) cn = '十一';
  else if (n === 12) cn = '十二';
  return cn ? cn + m[2] : s;
}

// 宽松取数：3 / 3.0 / 3件 / 1,234 / 全角数字
function numOf(s0) {
  let s = fullHalf(s0).replace(/[,，\s]/g, '');
  if (!s) return null;
  s = s.replace(/(件|箱|包|瓶|提|个|袋|支|杯|条|罐|盒|斤|kg|KG|g)$/, '');
  const m = /-?\d+(\.\d+)?/.exec(s);
  if (!m) return null;
  const digits = (s.match(/\d/g) || []).length;
  return (digits * 2 >= s.length) ? parseFloat(m[0]) : null;
}

function isTotal(s) {
  return s.includes('合计') || s.includes('总计') || s.includes('小计');
}

// 表头里不应被当作门店/产品的列（老分配表常见）
const EXCLUDE = ['合计', '小计', '总计', '单位', '品项', '提货', '序号', '备注', '金额', '单价', '规格', '数量', '条码'];

// ---------- 匹配 ----------
function bestMatch(q0, items, nameOf, threshold) {
  const q = norm(q0);
  if (!q) return null;
  let best = null, bestScore = 0;
  for (const it of items) {
    const n = nameOf(it);
    if (!n) continue;
    let s;
    if (n === q) s = 1;
    else if (n.includes(q) || q.includes(n)) s = 0.85 + 0.1 * (Math.min(n.length, q.length) / Math.max(n.length, q.length));
    else s = sim(q, n);
    if (s > bestScore) { bestScore = s; best = it; }
  }
  return (best && bestScore >= threshold) ? { item: best, score: bestScore } : null;
}

// 兼顾「对象数组({name})」与「字符串数组」两种入参
function nameOfItem(x) {
  return norm(typeof x === 'string' ? x : (x && x.name != null ? x.name : ''));
}

function matchStore(header, customers) {
  const h = norm(header);
  if (!h) return [];
  const hc = norm(digitToCn(header));
  const scored = [];
  for (const c of customers) {
    const n = nameOfItem(c);
    if (!n) continue;
    let s;
    if (n === h || n === hc) s = 1;
    else if (n.includes(h)) s = 0.9;
    else if (hc !== h && n.includes(hc)) s = 0.88;
    else s = sim(h, n);
    if (s >= 0.5) scored.push({ item: c, score: s });
  }
  scored.sort((a, b) => b.score - a.score);
  return scored;
}

// 按 x 轴「列中心」聚类，重建品项行 → 供老分配表用（列=门店）
function byStoreColumns(grid, hIdx, header) {
  const ncols = Math.max(...grid.map(r => r.length));
  const cols = [], names = [];
  for (let c = 1; c < ncols; c++) {
    const h = String(header[c] == null ? '' : header[c]).trim();
    if (!h) continue;
    if (EXCLUDE.some(k => h.includes(k))) continue;
    cols.push(c); names.push(h);
  }
  if (!cols.length) return { stores: [], rows: [] };

  const rows = [];
  for (let i = hIdx + 1; i < grid.length; i++) {
    const r = grid[i];
    const name = String(r[0] == null ? '' : r[0]).trim();
    if (!name || isTotal(name)) continue;
    const qtys = {};
    cols.forEach((c, ci) => {
      const q = numOf(r[c]);
      if (q != null && q > 0) qtys[ci] = (qtys[ci] || 0) + q;
    });
    if (Object.keys(qtys).length) rows.push({ name, qtys });
  }
  return { stores: names, rows };
}

// 行=门店、列=产品（开单模板）→ 转成统一结构
function transposeByStore(grid, hIdx, header) {
  const ncols = Math.max(...grid.map(r => r.length));
  const prodCols = [];
  for (let c = 1; c < ncols; c++) {
    const h = String(header[c] == null ? '' : header[c]).trim();
    // 用 EXCLUDE 全量排除，不只挡 isTotal：「数量」「金额」「单价」这些列
    // 不是产品，一旦当成产品列，行方向的门店数量会被读成产品数量。
    if (!h || isTotal(h) || EXCLUDE.some(k => h.includes(k))) continue;
    prodCols.push({ c, name: h });
  }
  const storeRows = [];
  for (let i = hIdx + 1; i < grid.length; i++) {
    const n = String(grid[i][0] == null ? '' : grid[i][0]).trim();
    if (!n || isTotal(n)) continue;
    storeRows.push({ i, name: n });
  }
  if (!prodCols.length || !storeRows.length) return { stores: [], rows: [] };

  const rows = [];
  for (const pc of prodCols) {
    const qtys = {};
    storeRows.forEach((sr, si) => {
      const q = numOf(grid[sr.i][pc.c]);
      if (q != null && q > 0) qtys[si] = (qtys[si] || 0) + q;
    });
    if (Object.keys(qtys).length) rows.push({ name: pc.name, qtys });
  }
  if (!rows.length) return { stores: [], rows: [] };
  return { stores: storeRows.map(s => s.name), rows };
}

// 网格 → 统一结构 { stores: [门店名], rows: [{name, qtys:{门店索引:数量}}] }
function gridToTable(grid, storeNames, productNames) {
  if (!grid || !grid.length) return { stores: [], rows: [] };
  const ncols = Math.max(...grid.map(r => r.length));

  // 表头行：前 8 行里「非数字单元格最多」的一行
  let hIdx = 0, best = -1;
  grid.slice(0, 8).forEach((r, i) => {
    let nonNum = 0;
    for (let c = 1; c < Math.min(r.length, ncols); c++) {
      const v = r[c];
      if (v != null && String(v).trim() !== '' && numOf(v) == null) nonNum++;
    }
    if (nonNum > best) { best = nonNum; hIdx = i; }
  });
  const header = grid[hIdx];
  const warnings = detectMisplacedPcs(grid, hIdx, header);

  const colNames = [], firstCol = [];
  for (let c = 1; c < ncols; c++) {
    const h = String(header[c] == null ? '' : header[c]).trim();
    // ⚠️ 汇总列（合计 / 合计（件数）/ 小计…）不能参与方向判定：
    //    matchStore('合计')走的是模糊相似度，可能勉强命中某个门店名（如「合计」vs「德馨」）
    //    把计数抬高 → transpose 判反 → 整张表按错的轴解析，
    //    结果「合计」列被当成一家门店，件数被重复计入订单（实测踩过）。
    if (!h || isTotal(h) || EXCLUDE.some(k => h.includes(k))) continue;
    colNames.push(h);
  }
  for (let i = hIdx + 1; i < grid.length; i++) {
    const n = String(grid[i][0] == null ? '' : grid[i][0]).trim();
    if (!n || isTotal(n) || EXCLUDE.some(k => n === k)) continue;
    firstCol.push(n);
  }
  const hits = (names, dict) => dict.length ? names.filter(h => matchStore(h, dict).length > 0).length : 0;
  const colStore = hits(colNames, storeNames);
  const rowStore = hits(firstCol, storeNames);
  const colProd = hits(colNames, productNames);
  const rowProd = hits(firstCol, productNames);

  let transpose;
  if (rowStore !== colStore) transpose = rowStore > colStore;
  else if (colProd !== rowProd) transpose = colProd > rowProd;
  else transpose = false;   // 再平局：按老习惯（列=门店）

  const dir = transpose ? transposeByStore(grid, hIdx, header) : byStoreColumns(grid, hIdx, header);
  dir.warnings = warnings;
  return dir;
}

// 「件数」类汇总列关键词（件数列不参与订单数量，只作校验参考）
const PCS_KEYS = ['件数', '箱数', '箱量', '件/箱'];

/**
 * 扫出「件数/合计」列里被填了数、但门店列全空的行 → 经理多半是把件数填进了件数列。
 * 返回提示信息数组（不阻断导入，只在预览页顶部警告）。
 * @param {Array} grid 二维数组
 * @param {number} hIdx 表头行下标
 * @param {Array<string>} header 表头行
 */
function detectMisplacedPcs(grid, hIdx, header) {
  const ncols = Math.max(...grid.map(r => r.length));
  const pcsCols = [];
  for (let c = 1; c < ncols; c++) {
    const h = String(header[c] == null ? '' : header[c]).trim();
    if (isTotal(h) || PCS_KEYS.some(k => h.includes(k))) pcsCols.push(c);
  }
  if (!pcsCols.length) return [];
  // 门店列（排除所有汇总列）
  const storeCols = [];
  for (let c = 1; c < ncols; c++) {
    const h = String(header[c] == null ? '' : header[c]).trim();
    if (!h || isTotal(h) || EXCLUDE.some(k => h.includes(k)) || PCS_KEYS.some(k => h.includes(k))) continue;
    storeCols.push(c);
  }
  if (!storeCols.length) return [];

  const bad = [];
  for (let i = hIdx + 1; i < grid.length; i++) {
    const r = grid[i];
    const name = String(r[0] == null ? '' : r[0]).trim();
    if (!name || isTotal(name)) continue;
    const storeHas = storeCols.some(c => { const q = numOf(r[c]); return q != null && q > 0; });
    if (storeHas) continue;
    const pcsHas = pcsCols.some(c => { const q = numOf(r[c]); return q != null && q > 0; });
    if (pcsHas) bad.push(name);
  }
  if (!bad.length) return [];
  return ['有 ' + bad.length + ' 行只填了「合计（件数）」而门店列为空：'
    + bad.slice(0, 5).join('、') + (bad.length > 5 ? ' 等' : '')
    + '。订单数量按门店列的 EA 导入，件数列不参与计算，请把单个数量填到门店列。'];
}

/**
 * 主入口：xlsx 二进制 → 匹配好的开单结构
 * @param {Buffer} buffer
 * @param {Array<{id,name}>} customers
 * @param {Array<{id,name,price,unit}>} products
 */
function parseOrderTable(buffer, customers, products) {
  return X.parseXlsx(buffer).then(grid => {
  const storeNames = customers.map(c => c.name);
  const productNames = products.map(p => p.name);
  const t = gridToTable(grid, storeNames, productNames);
  if (!t.stores.length || !t.rows.length) return { stores: [], lines: [], warnings: t.warnings || [], summary: { storeCount: 0, lineCount: 0, unmatchedStores: 0, unmatchedProducts: 0 } };

  const stores = t.stores.map((header, idx) => {
    const ms = matchStore(header, customers);
    const top = ms[0] || null;
    const ambiguous = ms.length > 1 && Math.abs(ms[0].score - ms[1].score) < 0.01;
    return {
      idx,
      header,
      customer_id: top ? top.item.id : null,
      customer_name: top ? top.item.name : '',
      score: top ? +top.score.toFixed(3) : 0,
      ambiguous,
    };
  });

  const lines = t.rows.map(r => {
    const bm = bestMatch(r.name, products, p => norm(p.name), 0.55);
    return {
      name: r.name,
      product_id: bm ? bm.item.id : null,
      product_name: bm ? bm.item.name : '',
      unit: bm ? (bm.item.unit || '') : '',
      base_price: bm ? (Number(bm.item.price) || 0) : 0,
      score: bm ? +bm.score.toFixed(3) : 0,
      ambiguous: false,
      qtys: r.qtys,             // { 门店索引: 数量 }
    };
  });

  return {
    stores,
    lines,
    warnings: t.warnings || [],
    summary: {
      storeCount: stores.length,
      lineCount: lines.length,
      unmatchedStores: stores.filter(s => !s.customer_id).length,
      unmatchedProducts: lines.filter(l => !l.product_id).length,
    },
  };
  });
}

module.exports = { parseOrderTable, norm, sim, bestMatch, matchStore, numOf, gridToTable, detectMisplacedPcs };
