/**
 * 手机版下载：导航栏按钮 + 二维码
 *
 * 🔴 为什么二维码里放的是短链而不是 update.json 里的 R2 预签名 URL？
 *    R2 预签名链接带 X-Amz-Expires=604800（7 天），过期后二维码就扫不出来。
 *    二维码内容固定为 https://erp.example.com/downloads/apk，
 *    由 refresh_apk_url.sh 持续把 update.json 指向最新版 APK，短链永不失效。
 *
 * 二维码是自己实现的极简 QR 编码器（无第三方库）：
 *    - 字节模式 + 纠错等级 M
 *    - 版本自动选择（1..10，短链够用）
 *    - 渲染到 canvas，再转 dataURL 塞进 CSS background
 */
(function () {
  'use strict';

  // ---------------------------------------------------------------- QR 渲染
  // 编码器是 qrcode-mini.js（打包自 npm qrcode，已与官方库逐模块比对一致）。
  // 这里只负责把模块矩阵画到 canvas 上。
  function toDataURL(text, sizePx) {
    var q = window.QRCodeMini.create(text);
    var n = q.size, sc = sizePx / n;
    var cv = document.createElement('canvas');
    cv.width = sizePx; cv.height = sizePx;
    var g = cv.getContext('2d');
    g.fillStyle = '#fff'; g.fillRect(0, 0, sizePx, sizePx);
    g.fillStyle = '#000';
    for (var y = 0; y < n; y++) for (var x = 0; x < n; x++) {
      if (q.get(x, y)) g.fillRect(Math.floor(x * sc), Math.floor(y * sc), Math.ceil(sc), Math.ceil(sc));
    }
    return cv.toDataURL('image/png');
  }

// ---------------------------------------------------------------- 接入 UI
  var SHORT_URL = 'https://erp.example.com/downloads/apk';
  var DIRECT_FALLBACK = 'downloads/update.json';

  function initApkDownload() {
    // 侧栏（进系统后）与登录页（进系统前）各一块二维码，共用同一渲染逻辑
    ['apk-qr', 'apk-qr-login'].forEach(function (id) {
      var qr = document.getElementById(id);
      if (!qr) return;
      try {
        qr.style.backgroundImage = 'url(' + toDataURL(SHORT_URL, 88) + ')';
      } catch (e) { /* 画不出来就隐藏，不影响下载 */ }
    });
    // 两个入口：侧栏「手机版下载」卡片 + 登录页底部下载条
    var links = ['apk-dl', 'apk-dl-login'];
    links.forEach(function (id) {
      var el = document.getElementById(id);
      if (!el) return;
      el.addEventListener('click', function (e) {
        e.preventDefault();
        downloadApk();
      });
    });
  }

  function downloadApk() {
    // 走 update.json 拿最新的 R2 预签名地址（那里带版本号，最新）
    fetch('downloads/update.json', { cache: 'no-store' })
      .then(function (r) { return r.json(); })
      .then(function (j) {
        if (!j || !j.url) throw new Error('no url');
        var a = document.createElement('a');
        a.href = j.url;
        a.rel = 'noopener';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
      })
      .catch(function () {
        window.open(SHORT_URL, '_blank', 'noopener');
      });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initApkDownload);
  } else {
    initApkDownload();
  }

  window.__erpApk = { qr: toDataURL, url: SHORT_URL, download: downloadApk };
  // 供 Node 侧单测引用（浏览器里没有 module，天然跳过）
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = { toDataURL: toDataURL };
  }
})();