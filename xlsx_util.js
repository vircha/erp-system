// 零依赖 xlsx（OOXML）生成与解析：仅用 Node 内置 zlib / Buffer。
// 支持：生成带表头的 xlsx 模板；解析上传的 xlsx（兼容 inlineStr 与 sharedStrings，STORED/DEFLATE 压缩）。
const zlib = require('zlib');

// ---------- CRC32 ----------
function crc32(buf) {
  let table = crc32._t;
  if (!table) {
    table = crc32._t = [];
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
      table[n] = c >>> 0;
    }
  }
  let crc = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i++) crc = (crc >>> 8) ^ table[(crc ^ buf[i]) & 0xFF];
  return (crc ^ 0xFFFFFFFF) >>> 0;
}

// ---------- ZIP 写入（STORED，不压缩） ----------
function zip(files) {
  const chunks = [];
  const central = [];
  let offset = 0;
  for (const f of files) {
    const nameBuf = Buffer.from(f.name, 'utf8');
    const data = f.data;
    const crc = crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);      // version needed
    local.writeUInt16LE(0, 6);       // flags
    local.writeUInt16LE(0, 8);       // method = store
    local.writeUInt16LE(0, 10);      // mod time
    local.writeUInt16LE(0, 12);      // mod date
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    local.writeUInt16LE(0, 28);      // extra len
    chunks.push(local, nameBuf, data);

    const cd = Buffer.alloc(46);
    cd.writeUInt32LE(0x02014b50, 0);
    cd.writeUInt16LE(20, 4);         // version made by
    cd.writeUInt16LE(20, 6);         // version needed
    cd.writeUInt16LE(0, 8);          // flags
    cd.writeUInt16LE(0, 10);         // method
    cd.writeUInt16LE(0, 12);         // time
    cd.writeUInt16LE(0, 14);         // date
    cd.writeUInt32LE(crc, 16);
    cd.writeUInt32LE(data.length, 20);
    cd.writeUInt32LE(data.length, 24);
    cd.writeUInt16LE(nameBuf.length, 28);
    cd.writeUInt16LE(0, 30);         // extra
    cd.writeUInt16LE(0, 32);         // comment
    cd.writeUInt16LE(0, 34);         // disk
    cd.writeUInt16LE(0, 36);         // internal
    cd.writeUInt32LE(0, 38);         // external
    cd.writeUInt32LE(offset, 42);     // local header offset
    central.push(Buffer.concat([cd, nameBuf]));

    offset += local.length + nameBuf.length + data.length;
  }
  const centralBuf = Buffer.concat(central);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(0, 4);
  eocd.writeUInt16LE(0, 6);
  eocd.writeUInt16LE(files.length, 8);
  eocd.writeUInt16LE(files.length, 10);
  eocd.writeUInt32LE(centralBuf.length, 12);
  eocd.writeUInt32LE(offset, 16);
  eocd.writeUInt16LE(0, 20);
  return Buffer.concat([...chunks, centralBuf, eocd]);
}

// ---------- ZIP 读取（STORED / DEFLATE，带解压上限防炸弹） ----------
const MAX_INFLATE_ENTRY = 64 * 1024 * 1024;   // 单条目解压后上限 64MB
const MAX_UNZIP_TOTAL = 256 * 1024 * 1024;    // 整包解压后上限 256MB
const MAX_ZIP_ENTRIES = 4096;                 // 条目数上限，防伪造目录 DoS

// 有体积上限的 inflate（流式，避免 zip 炸弹把进程内存打爆导致服务崩溃）
function inflateBounded(data, maxBytes) {
  return new Promise((resolve, reject) => {
    const inf = zlib.createInflateRaw();
    const chunks = [];
    let total = 0;
    inf.on('data', c => {
      total += c.length;
      if (total > maxBytes) { inf.destroy(); reject(new Error('解压后体积超限（文件可能已损坏或含压缩炸弹）')); return; }
      chunks.push(c);
    });
    inf.on('end', () => resolve(Buffer.concat(chunks)));
    inf.on('error', e => reject(e));
    inf.end(data);
  });
}

// XML 实体反转义（与写入侧的 xmlEscape 配套）。
// Excel 真实导出对 & < > " 必做转义，不反转义会把「纯牛奶 & 脱脂」灌成字面量「纯牛奶 &amp; 脱脂」→ 数据错乱、门店名匹配不上。
function xmlUnescape(s) {
  return String(s == null ? '' : s)
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#x([0-9a-fA-F]+);/g, (m, h) => { const c = parseInt(h, 16); return (c >= 0 && c <= 0x10FFFF) ? String.fromCodePoint(c) : m; })
    .replace(/&#(\d+);/g, (m, d) => { const c = parseInt(d, 10); return (c >= 0 && c <= 0x10FFFF) ? String.fromCodePoint(c) : m; })
    .replace(/&amp;/g, '&');   // &amp; 最后处理，避免二次反转义
}

async function unzip(buf) {
  if (!Buffer.isBuffer(buf) || buf.length < 22) throw new Error('不是有效的 xlsx/zip 文件');
  let eocdIdx = -1;
  for (let i = buf.length - 22; i >= 0; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocdIdx = i; break; }
  }
  if (eocdIdx < 0) throw new Error('不是有效的 xlsx/zip 文件');
  const count = buf.readUInt16LE(eocdIdx + 10);
  if (count > MAX_ZIP_ENTRIES) throw new Error('zip 条目过多（可能为恶意文件）');
  let cdOffset = buf.readUInt32LE(eocdIdx + 16);
  const out = {};
  let totalOut = 0;
  for (let i = 0; i < count; i++) {
    if (buf.readUInt32LE(cdOffset) !== 0x02014b50) throw new Error('zip 目录损坏');
    const method = buf.readUInt16LE(cdOffset + 10);
    const compSize = buf.readUInt32LE(cdOffset + 20);
    const nameLen = buf.readUInt16LE(cdOffset + 28);
    const extraLen = buf.readUInt16LE(cdOffset + 30);
    const commentLen = buf.readUInt16LE(cdOffset + 32);
    const name = buf.toString('utf8', cdOffset + 46, cdOffset + 46 + nameLen);
    const localOffset = buf.readUInt32LE(cdOffset + 42);
    const lNameLen = buf.readUInt16LE(localOffset + 26);
    const lExtraLen = buf.readUInt16LE(localOffset + 28);
    const dataStart = localOffset + 30 + lNameLen + lExtraLen;
    let data = buf.subarray(dataStart, dataStart + compSize);
    if (method === 8) {
      data = await inflateBounded(data, MAX_INFLATE_ENTRY);
    } else if (method !== 0) {
      throw new Error('不支持的压缩方式');
    }
    if (data.length > MAX_INFLATE_ENTRY) throw new Error('单文件解压后过大');
    totalOut += data.length;
    if (totalOut > MAX_UNZIP_TOTAL) throw new Error('解压后总体积超限（可能为恶意文件）');
    out[name] = data;
    cdOffset += 46 + nameLen + extraLen + commentLen;
  }
  return out;
}

// ---------- 工具 ----------
function colLetter(idx) {
  let s = '';
  let n = idx + 1;
  while (n > 0) {
    const r = (n - 1) % 26;
    s = String.fromCharCode(65 + r) + s;
    n = Math.floor((n - 1) / 26);
  }
  return s;
}
function colToIdx(letters) {
  let n = 0;
  for (const ch of letters) n = n * 26 + (ch.charCodeAt(0) - 64);
  return n - 1;
}
function xmlEscape(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

// 显示宽度：中文/全角算 2 列，其余算 1 列（Excel 列宽单位≈一个数字宽度）
function dispWidth(s) {
  let w = 0;
  const str = String(s == null ? '' : s);
  for (const ch of str) w += (ch.codePointAt(0) > 0x2000) ? 2 : 1;
  return w;
}

// ---------- styles.xml ----------
// s=0 无边框（默认）｜1 普通带框 ｜2 表头（加粗+居中+底色）｜3 左列（自动换行+顶对齐）
// s=4 数字居中 ｜5 合计行（加粗+居中+底色）
// ⚠️ fonts / fills / borders / cellXfs 的出现顺序是 OOXML 规范强制的，
//    顺序写错 Excel 会直接报「文件已损坏」，别按字母序排。
function stylesXml() {
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="2"><font><sz val="11"/><name val="微软雅黑"/></font><font><b/><sz val="11"/><name val="微软雅黑"/></font></fonts>
<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFF0E4D8"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color rgb="FFBFB6AC"/></left><right style="thin"><color rgb="FFBFB6AC"/></right><top style="thin"><color rgb="FFBFB6AC"/></top><bottom style="thin"><color rgb="FFBFB6AC"/></bottom><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="6">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment horizontal="left" vertical="center"/></xf>
<xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment horizontal="left" vertical="top" wrapText="1"/></xf>
<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
<xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
</cellXfs>
<cellStyles count="1"><cellStyle name="常规" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>`;
}

// <cols>：colWidth 是**字符宽度**，A 列按最长商品名算（这样 Excel 打开就不是默认 8.43 的窄条）
function colsXml(widths) {
  if (!widths || !widths.length) return '';
  const cs = widths.map((w, i) =>
    `<col min="${i + 1}" max="${i + 1}" width="${w.toFixed(2)}" customWidth="1"/>`).join('');
  return `<cols>${cs}</cols>`;
}

// ---------- sheet XML 构建 ----------
// 单元格取值：number → 数字；{f:'SUM(A1:A3)'} → 公式（可带 v 缓存值）；null/undefined → 空；其余 → 文本
// opt = { widths:[数字], styleAt:(rowIdx, colIdx, value) => 样式号 }
// ⚠️ 空单元格在 bordered 模式下**必须输出 `<c ...></c>`**，否则框线在空白处断开。
//    但**绝不能写成自闭合 `<c .../>`** —— 解析端 parseXlsx 的正则
//    `<c\b([^>]*)>([\s\S]*?)<\/c>` 会把 `/>` 一起吞进属性组，再顺延到下一个 `</c>`，
//    导致导入时整行数据错位一列（这个坑会让开单模板导入静默出错）。
function sheetXml(rows, opt) {
  opt = opt || {};
  const styleAt = opt.styleAt;
  const bordered = !!opt.widths;          // 传了列宽就意味着要画框线
  let body = '';
  rows.forEach((row, r) => {
    const rn = r + 1;
    let cells = '';
    row.forEach((val, ci) => {
      const ref = colLetter(ci) + rn;
      const st = styleAt ? styleAt(r, ci, val) : null;
      const sa = (bordered && st != null) ? ` s="${st}"` : '';
      if (val == null || val === '') {
        if (bordered && st != null) cells += `<c r="${ref}"${sa}></c>`;
        return;                            // 无框线模式下照旧跳过，缩小体积
      }
      if (typeof val === 'object' && val.f) {
        const cached = (val.v != null) ? `<v>${val.v}</v>` : '';
        cells += `<c r="${ref}"${sa}><f>${xmlEscape(val.f)}</f>${cached}</c>`;
      } else if (typeof val === 'number' && !isNaN(val)) {
        cells += `<c r="${ref}"${sa}><v>${val}</v></c>`;
      } else {
        cells += `<c r="${ref}"${sa} t="inlineStr"><is><t xml:space="preserve">${xmlEscape(val)}</t></is></c>`;
      }
    });
    body += `<row r="${rn}"${r === 0 ? ' ht="26" customHeight="1"' : ''}>${cells}</row>`;
  });
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetFormatPr defaultRowHeight="19"/>${colsXml(opt.widths)}<sheetData>${body}</sheetData></worksheet>`;
}

// ---------- 生成完整 xlsx ----------
function buildXlsx(sheetRows, sheetName, opt) {
  const name = sheetName || '商品导入';
  const hasStyles = !!(opt && opt.widths);
  // ⚠️ 只要单元格用了 s="n"，就必须三处齐备：
  //    ① [Content_Types].xml 声明 styles.xml 的 Override
  //    ② xl/_rels/workbook.xml.rels 加 rId2 → styles.xml 的 Relationship
  //    ③ files 里真的把 xl/styles.xml 打进 zip
  //    缺任何一处，Excel 打开直接报「文件已损坏」，WPS 会静默丢样式。
  const contentTypes = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>${hasStyles ? '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>' : ''}<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>`;
  const rels = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>`;
  const workbook = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${xmlEscape(name)}" sheetId="1" r:id="rId1"/></sheets><calcPr fullCalcOnLoad="1"/></workbook>`;
  const wbRels = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>${hasStyles ? '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>' : ''}</Relationships>`;
  const files = [
    { name: '[Content_Types].xml', data: Buffer.from(contentTypes, 'utf8') },
    { name: '_rels/.rels', data: Buffer.from(rels, 'utf8') },
    { name: 'xl/workbook.xml', data: Buffer.from(workbook, 'utf8') },
    { name: 'xl/_rels/workbook.xml.rels', data: Buffer.from(wbRels, 'utf8') },
    { name: 'xl/worksheets/sheet1.xml', data: Buffer.from(sheetXml(sheetRows, opt), 'utf8') },
  ];
  if (hasStyles) files.push({ name: 'xl/styles.xml', data: Buffer.from(stylesXml(), 'utf8') });
  return zip(files);
}

// 商品导入模板
function buildTemplate() {
  // 含「每箱装量」列：件数的换算依据。填 0 = 单品不换算
  const header = ['名称', '条码', '规格', '单位', '单价', '库存数量', '低库存阈值', '每箱装量'];
  const example = ['纯牛奶 250ml*12', '6900000001', '250ml*12', '箱', 45, 50, 10, 12];
  return buildXlsx([header, example], '商品导入');
}

/**
 * 开单模板：行=门店名称，列=产品名称，只填数量、不带价格（价格按门店上次价格自动取）。
 * 数量单位 EA；每行末尾与最后一行均自动带 SUM 汇总公式（打开即算）。
 *
 * 列结构：A=产品名称 | B..N=各门店 | O=合计(EA) | P=合计（件数）
 *   「合计（件数）」= ROUND(EA ÷ pack_size, 2)，即保留 2 位小数的箱数。
 *   ⚠️ 2026-10-27 经理要求：不能进位取整（36÷24=1.5 要显示 1.5，不能显示 2）。
 *   pack_size=0（产品资料未维护装箱量）→ 该行件数留空，不写公式，
 *   避免算出 0 被当成「0 件」的真数字。
 *
 * 1.5.1 版式增强（经理 2026-10-09 要求）：
 *   ① 全表单元格加框线（原来没有 styles.xml，框线只在屏幕上、打印出来是空白）
 *   ② 合计列右边新增「合计（件数）」列，行/列两个方向都用公式自动算件数
 *   ③ 列宽自适应：A 列按最长商品名算（上限 46），其余列按表头算，避免折叠成 8.43 窄条
 *   ④ 表头加高 26 + 换行 + 居中，长门店名不会被压成两行叠字
 *
 * ⚠️ prods 兼容两种入参：旧版是字符串数组，新版是 {name, packSize} 对象数组。
 *    server.js 已改为传对象，但保留字符串分支以防其他地方仍在用旧签名。
 */
// 布局：行=产品、列=门店（转置，与旧分配表一致：门店在列、品项在行）。
// 导入端 gridToTable 方向自适应，列=门店（老格式）同样能解析。
function buildOrderTemplate(storeNames, productNames, prefill) {
  // 2026-10-17 新增：prefill = { '商品名': [各门店数量...] }
  //   用于「模板自动同步」——把经理手上那份已填好数量的旧表里的数量，
  //   照字段搬迁到按数据库现状重建的新表里（新商品行、新门店列自动补齐）。
  const PF = (prefill && typeof prefill === 'object') ? prefill : {};
  const stores = Array.isArray(storeNames) ? storeNames.filter(s => s != null && String(s).trim() !== '') : [];
  // 归一化：{name, packSize} 或裸字符串都接受
  const prods = (Array.isArray(productNames) ? productNames : [])
    .map(x => (x && typeof x === 'object')
      ? { name: String(x.name == null ? '' : x.name), packSize: Math.max(0, Math.round(Number(x.packSize)) || 0) }
      : { name: String(x == null ? '' : x), packSize: 0 })
    .filter(p => p.name.trim() !== '');
  const names = prods.map(p => p.name);

  const header = ['产品名称（数量单位：EA）'].concat(stores);
  if (stores.length > 0) { header.push('合计'); header.push('合计（件数）'); }
  const rows = [header];
  // 🔴 colLetter() 收的是 **0 基列索引**（colLetter(0)='A'）。
  // 门店占 B..(stores.length) 列 → 最后门店列索引 = stores.length；
  // 「合计」列索引 = stores.length + 1，「件数」列索引 = stores.length + 2。
  // （旧代码把这几个变量命名成 lastStoreCol/sumCol，名字与实际索引差一位，容易改错）
  const lastStoreCol = colLetter(stores.length);     // 最后一个门店列（0 基索引 = 门店数）
  const sumCol = colLetter(stores.length + 1);       // 「合计」所在列
  const pcsCol = colLetter(stores.length + 2);       // 「合计（件数）」所在列

  prods.forEach((p, i) => {
    const rn = i + 2;                                 // 数据从 Excel 第 2 行开始
    const row = [p.name];
    const pv = PF[p.name];
    for (let c = 0; c < stores.length; c++) {
      const q = (Array.isArray(pv) ? Number(pv[c]) : 0) || 0;
      row.push(q > 0 ? q : null);
    }
    if (stores.length > 0) {
      let sumEA = 0; for (let c = 0; c < stores.length; c++) { const q = row[c + 1]; if (typeof q === 'number') sumEA += q; }
      row.push({ f: `SUM(B${rn}:${lastStoreCol}${rn})`, v: sumEA });            // 合计（EA）
      // 件数 = EA / 每箱装量，保留 2 位小数（不再进位取整）。
      // 🔴 2026-10-27 经理要求：36÷24 要显示 1.5，不能进位成 2。
      // 未维护装箱量（0）→ 留空不写公式。
      row.push(p.packSize > 0
        ? { f: `IF(${sumCol}${rn}=0,0,ROUND(${sumCol}${rn}/${p.packSize},2))`, v: 0 }
        : null);
    }
    rows.push(row);
  });

  if (prods.length > 0 && stores.length > 0) {
    const lastDataRn = prods.length + 1;              // 最后一个数据行的 Excel 行号
    const totalRow = ['合计'];
    for (let c = 0; c < stores.length; c++) {
      const col = colLetter(c + 1);
      totalRow.push({ f: `SUM(${col}2:${col}${lastDataRn})`, v: 0 });      // 列合计（该门店各商品合计）
    }
    totalRow.push({ f: `SUM(${sumCol}2:${sumCol}${lastDataRn})`, v: 0 });   // 该列总计（不必绕行相加）
    // 件数总计 = **各行件数相加**。
    // ⚠️ 不能写成 ROUND(Σ(EA_i/箱_i), 2) —— 先加后舍入与逐行舍入不一致。
    //    例：10÷8=1.25、10÷8=1.25，逐行合计 2.50；
    //        但 1÷3 这类循环小数逐行舍入会累积误差，故一律逐行相加。
    const pcRows = prods.map((p, i) => (p.packSize > 0 ? i + 2 : null)).filter(Boolean);
    totalRow.push(pcRows.length
      ? { f: 'SUM(' + pcRows.map(rn => `${pcsCol}${rn}`).join(',') + ')', v: 0 }
      : null);
    rows.push(totalRow);
  }

  // ---- 列宽自适应 ----
  // A 列取最长商品名（中文按 2 列宽算），夹在 22~46：太窄会挤成两行，太宽一行放不下整张纸
  let maxName = dispWidth('产品名称（数量单位：EA）');
  names.forEach(p => { const w = dispWidth(p); if (w > maxName) maxName = w; });
  const wName = Math.min(46, Math.max(22, maxName + 2.5));
  // 门店列取最长店名，至少 11（放得下「万家便利店-1店」这种 9 个字符的表头）
  const wStore = Math.min(18, Math.max(11, Math.max(0, ...stores.map(dispWidth)) + 2));
  const widths = [wName]
    .concat(stores.map(() => wStore))
    .concat(stores.length > 0 ? [9, 15] : []);        // 合计 / 合计（件数）

  // ---- 样式分配 ----
  // 表头行 r=0 → s=2；合计行 → s=5；A 列 → s=3（顶部对齐+换行）；其余 → s=1 或 s=4（居中）
  const totalRowIdx = prods.length + 1;              // 0 基：合计行的下标
  const styleAt = (r, ci) => {
    if (r === 0) return 2;
    if (r === totalRowIdx) return 5;
    if (ci === 0) return 3;                          // 商品名列：换行 + 顶对齐
    return 4;                                        // 数字列：居中
  };
  return buildXlsx(rows, '开单模板EA', { widths, styleAt });
}

// ---------- 解析 xlsx -> 二维数组 ----------
const MAX_ROWS = 500000;   // 单表行数上限：超大表直接拒绝，避免请求长时间挂起/内存暴涨
function parseXlsx(buffer) {
  return unzip(buffer).then(parts => {
    const sheetName = Object.keys(parts).find(n => /xl\/worksheets\/sheet\d+\.xml$/.test(n)) || 'xl/worksheets/sheet1.xml';
    if (!parts[sheetName]) throw new Error('文件中未找到工作表');
    const sheetStr = parts[sheetName].toString('utf8');

    // 共享字符串表
    const shared = [];
    if (parts['xl/sharedStrings.xml']) {
      const sst = parts['xl/sharedStrings.xml'].toString('utf8');
      const siRe = /<si>([\s\S]*?)<\/si>/g;
      let m;
      while ((m = siRe.exec(sst))) {
        // 去掉拼音注释块 <rPh>…</rPh>，避免把东亚语言发音注记当正文灌入（Excel 真实导出常带）
        const siInner = m[1].replace(/<rPh\b[^>]*>[\s\S]*?<\/rPh>/g, '');
        let t = '';
        const tRe = /<t[^>]*>([\s\S]*?)<\/t>/g;
        let tm;
        while ((tm = tRe.exec(siInner))) t += xmlUnescape(tm[1]);
        shared.push(t);
      }
    }

    const rows = [];
    // 🔴 单元格必须兼容**自闭合**形态 <c r="B2" s="1"/>
    //    我们自己生成的模板写的是 <c r="B2" s="1"></c>，本地自测怎么都过；
    //    但经理用 Excel / WPS 打开填完再保存后，空单元格会被重写成自闭合，
    //    旧正则 <c\b([^>]*)>([\s\S]*?)<\/c> 会把结尾的 "/" 当成属性吃掉，
    //    再顺延到**下一个 </c>** 才收尾 —— 跨行吞掉整段数据，导致
    //    「模板没问题、导入结果全错位」。这是导入错乱的第一号根因。
    //    同理 <row> 也要兼容自闭合。
    const rowRe = /<row\b[^>]*?(?:\/>|>([\s\S]*?)<\/row>)/g;
    let rm;
    while ((rm = rowRe.exec(sheetStr))) {
      const rowInner = rm[1] == null ? '' : rm[1];
      const cells = {};
      let maxC = -1;
      // group1=属性（不含结尾 /），group2=内容。自闭合时 group2 为 undefined。
      const cRe = /<c\b([^>]*?)(?:\/>|>([\s\S]*?)<\/c>)/g;
      let cm;
      while ((cm = cRe.exec(rowInner))) {
        const attrs = cm[1] || '';
        const inner = cm[2] || '';
        const rM = attrs.match(/\br="([A-Z]+)(\d+)"/);
        if (!rM) continue;
        const colIdx = colToIdx(rM[1]);
        const tM = attrs.match(/\bt="([^"]+)"/);
        const t = tM ? tM[1] : '';
        let val = '';
        if (t === 'inlineStr') {
          const tm = inner.match(/<t[^>]*>([\s\S]*?)<\/t>/);
          val = tm ? xmlUnescape(tm[1]) : '';
        } else if (t === 's') {
          const vm = inner.match(/<v>([\s\S]*?)<\/v>/);
          const idx = vm ? parseInt(vm[1], 10) : -1;
          val = idx >= 0 ? (shared[idx] != null ? shared[idx] : '') : '';
        } else if (inner) {
          const vm = inner.match(/<v>([\s\S]*?)<\/v>/);
          if (vm) {
            const n = parseFloat(vm[1]);
            val = isNaN(n) ? vm[1] : n;
          }
        }
        cells[colIdx] = val;
        if (colIdx > maxC) maxC = colIdx;
      }
      const arr = [];
      for (let i = 0; i <= maxC; i++) arr.push(cells[i] != null ? cells[i] : '');
      rows.push(arr);
      if (rows.length > MAX_ROWS) throw new Error('表格行数过多（超过 ' + MAX_ROWS + ' 行），请拆分后导入');
    }
    return rows;
  });
}

module.exports = { buildXlsx, buildTemplate, buildOrderTemplate, parseXlsx, zip, unzip };
