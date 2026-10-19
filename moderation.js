// 违规内容检测 + 管理员治理
// 目标：商品/客户名称等自由文本在「写入时」就检测到违规词，
//       先给创建者一个 30 分钟自助整改窗口；超期未改则自动上报并封禁账号。
const crypto = require('crypto');

// ---------- 违禁词库 ----------
// 分三类，便于按业务实际取舍：
//   high  = 明确违法/诈骗/色情引流，零容忍，直接判定
//   mid   = 强擦边（仿冒山寨、代刷、赌博相关），命中即警告
//   risky = 易被滥用词（账号交易、代开发票、跑分等），命中即警告
// 说明：词库保持可维护，全部走 normalize() 归一化后再匹配，
//      以覆盖「微信/薇 信」「V信」「①②③」等变体绕过。
const WORDS = [
  // --- 明确违法（high）---
  { w: ['毒品', '冰毒', '海洛因', '摇头丸', '大麻', '可卡因', '麻古'], lv: 'high', why: '涉毒' },
  { w: ['枪支', '手枪', '步枪', '弹药', '军火', '仿真枪'], lv: 'high', why: '涉枪' },
  { w: ['色情', '情色', '裸聊', '约炮', '成人视频', '福利姬', '涩涩'], lv: 'high', why: '色情' },
  { w: ['赌博', '博彩', '彩票投注', '私彩', '六合彩', '澳门赌场', '百家乐'], lv: 'high', why: '赌博' },
  { w: ['诈骗', '刷单返利', '杀猪盘', '洗钱', '跑分', '洗钱通道'], lv: 'high', why: '违法交易' },
  { w: ['办证', '办驾照代扣', '假证', '代开发票', '代开专票', '虚开'], lv: 'high', why: '虚开/造假' },
  { w: ['走私', '偷渡', '代购违禁'], lv: 'high', why: '走私' },
  { w: ['个人信息', '身份证正反面', '出售公民', '人脸数据'], lv: 'high', why: '贩卖个人信息' },
  { w: ['外挂', '游戏外挂出售', '破解版下载', '私服刷钻'], lv: 'high', why: '侵权/外挂' },

  // --- 强擦边（mid）---
  { w: ['山寨', '仿牌', '高仿', 'A货', '原单', '尾货代发'], lv: 'mid', why: '涉嫌仿冒' },
  { w: ['微信', '薇信', 'V信', 'vx', '威信', '加微'], lv: 'mid', why: '站外引流' },
  { w: ['QQ', '扣扣', '企鹅号'], lv: 'mid', why: '站外引流' },
  { w: ['私聊', '加好友', '上门服务', '一条龙服务'], lv: 'mid', why: '站外引流' },
  { w: ['包过', '包上岸', '内部指标', '特殊渠道'], lv: 'mid', why: '虚假承诺' },

  // --- 易滥用（risky）---
  { w: ['账号交易', '买卖号', '收号', '出售账号', '租号'], lv: 'risky', why: '账号交易' },
  { w: ['解封', '强制解封', '快速解冻', '刷流水'], lv: 'risky', why: '异常资金' },
  { w: ['internal', '内测名额', '内部价'], lv: 'risky', why: '内部违规' },
  { w: ['免费送', '0元购', '一元购'], lv: 'risky', why: '诱导引流' },
];

// 归一化：全角→半角、去空格与常见分隔符、大写→小写、统一同形字符。
// 让「微 信」「微-信」「微信」都归一到同一个 key，挡住简单变形绕过。
function normalize(s) {
  return String(s == null ? '' : s)
    .replace(/[！-～]/g, ch => String.fromCharCode(ch.charCodeAt(0) - 0xFEE0)) // 全角
    .replace(/　/g, ' ')
    .toLowerCase()
    .replace(/[\s\-_.·、,，|/\\+*#@~"'`^%$￥()（）\[\]【】<>《》]+/g, ''); // 各类分隔符一律抹掉
}

// 命中检测：返回 [{word, lv, why}]
function detect(text) {
  const norm = normalize(text);
  if (!norm) return [];
  const hits = [];
  const seen = new Set();
  for (const g of WORDS) {
    for (const w of g.w) {
      const nw = normalize(w);
      if (!nw || seen.has(nw)) continue;
      if (norm.includes(nw)) {
        seen.add(nw);
        hits.push({ word: w, lv: g.lv, why: g.why });
      }
    }
  }
  // high 级优先，其余按词表顺序
  hits.sort((a, b) => (a.lv === 'high' ? -1 : b.lv === 'high' ? 1 : 0));
  return hits;
}
// 便捷：只看最高级别
function levelOf(hits) {
  if (!hits || !hits.length) return null;
  return hits.some(h => h.lv === 'high') ? 'high' : hits[0].lv;
}

// 生成整改提示文案
function warnMessage(hits) {
  const words = hits.map(h => h.word).slice(0, 5).join('、');
  const why = hits[0].why;
  return `商品名称包含违规内容（${why}）：${words}`;
}

module.exports = { detect, levelOf, warnMessage, normalize };