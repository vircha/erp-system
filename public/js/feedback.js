// 意见反馈模块（2026-10-15）
// 独立文件，不侵入 app.js：对 window.ERPFeedback 暴露 openSubmit / openInbox / badge
//   ERPFeedback.openSubmit()  —— 任何登录用户提交反馈
//   ERPFeedback.openInbox()   —— 管理员查看全部 + 回复（普通用户看自己的）
//   ERPFeedback.refreshBadge()—— 拉未读数，画侧栏红点
(function () {
  'use strict';

  const TYPES = [
    { id: 'bug', label: 'Bug 反馈', icon: '🐞', hint: '功能报错、数据显示不对、打印异常等' },
    { id: 'feature', label: '功能需求', icon: '💡', hint: '希望新增或改进某个功能' },
    { id: 'account', label: '账户问题', icon: '👤', hint: '登录、密码、权限、角色等' },
    { id: 'other', label: '其他', icon: '💬', hint: '其他问题或建议，用文字说明' }
  ];
  const STATUS = { open: { label: '待处理', cls: 'fb-st-open' }, replied: { label: '已回复', cls: 'fb-st-replied' }, closed: { label: '已关闭', cls: 'fb-st-closed' } };
  const MAX_FILE = 8 * 1024 * 1024;

  const BASE = (function () {
    const m = document.querySelector('meta[name="base"]');
    if (m && m.content) return m.content;
    try { if (window.Capacitor && window.Capacitor.isNativePlatform && window.Capacitor.isNativePlatform()) return 'https://erp.example.com'; } catch (e) {}
    return '';
  })();
  // 🔴 2026-10-15：原来写成 `const $ = s => document.querySelector(s)`（只认 1 个参数），
  // 但本文件有 31 处按 `$('#fb-xxx', body)` 这种带 scope 的方式调用 —— 第二参被静默丢弃，
  // 节点查找反而落到整个 document 上。目前 id 恰好唯一所以没炸，但这是定时炸弹
  //（一旦页面出现第二个 #fb-body 就会静默操作到错误的节点）。
  // 改成支持 scope，与 print_template.js 保持同一套写法。
  // 📌 $$ 本文件下方第 120 行已有 function 版本，不要重复声明（会 SyntaxError）。
  const $ = (s, root) => (root || document).querySelector(s);
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }
  function toast(msg, ms) {
    const t = $('#toast');
    if (!t) return;
    t.textContent = msg; t.classList.remove('hidden');
    clearTimeout(toast._t);
    toast._t = setTimeout(() => t.classList.add('hidden'), ms || 1800);
  }
  async function req(method, path, body) {
    const tok = localStorage.getItem('erp_token');
    const opt = { method, headers: {} };
    if (tok) opt.headers['Authorization'] = 'Bearer ' + tok;
    if (body) { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
    const r = await fetch(BASE + path, opt);
    let d = {};
    try { d = await r.json(); } catch (e) {}
    if (!r.ok) throw new Error(d.error || ('请求失败 ' + r.status));
    return d;
  }
  function isAdmin() {
    try {
      const t = localStorage.getItem('erp_me');
      if (t) return !!(JSON.parse(t).isAdmin);
    } catch (e) {}
    return false;
  }
  function currentPage() {
    const el = $('.sidebar nav button.active .tl');
    return el ? el.textContent.trim() : '';
  }

  // ---------- 自建浮层（不依赖 app.js 的 showModal，避免耦合） ----------
  function ov() {
    let o = $('#fb-overlay');
    if (o) return o;
    o = document.createElement('div');
    o.id = 'fb-overlay';
    o.className = 'fb-overlay hidden';
    o.innerHTML = '<div class="fb-modal"><div class="fb-head"><span id="fb-title"></span><button id="fb-x" title="关闭">×</button></div><div id="fb-body" class="fb-body"></div></div>';
    document.body.appendChild(o);
    o.addEventListener('click', e => { if (e.target === o) close(); });
    $('#fb-x', o).onclick = close;
    return o;
  }
  function close() { ov().classList.add('hidden'); }
  function show(title, html, wide) {
    const o = ov();
    $('#fb-title', o).textContent = title;
    $('#fb-body', o).innerHTML = html;
    o.classList.toggle('fb-wide', !!wide);
    o.classList.remove('hidden');
    return $('#fb-body', o);
  }

  // ---------- 提交反馈 ----------
  let curType = 'bug';
  let picked = [];        // [{name, data(base64)}]
  async function openSubmit(preType) {
    if (preType && TYPES.some(t => t.id === preType)) curType = preType;
    const body = show('意见反馈', `
      <div class="fb-types">
        ${TYPES.map(t => `<button class="fb-type${t.id === curType ? ' on' : ''}" data-t="${t.id}">
          <b>${t.icon} ${esc(t.label)}</b><i>${esc(t.hint)}</i></button>`).join('')}
      </div>
      <div id="fb-hint" class="fb-hint">${esc((TYPES.find(t => t.id === curType) || {}).hint || '')}</div>
      <label class="fb-lab">详细描述</label>
      <textarea id="fb-content" rows="5" placeholder="请描述：什么时候操作的？期望是什么？实际出现了什么？&#10;报错信息可直接粘贴。"></textarea>
      <label class="fb-lab">联系方式（选填）</label>
      <input id="fb-contact" placeholder="手机号 / 微信，方便管理员回访" maxlength="60"/>
      <label class="fb-lab">附件（选填，最多 5 个，单个 ≤ 8MB）</label>
      <div class="fb-files"><input type="file" id="fb-file" multiple accept="image/*,.txt,.log,.pdf,.zip,.json,.csv,.mp4"/>
      <div id="fb-picked" class="fb-picked"></div></div>
      <div id="fb-err" class="fb-err"></div>
      <div class="fb-actions"><button class="btn-sm gray" id="fb-cancel">取消</button><button class="btn-primary" id="fb-send">提交反馈</button></div>
      <p class="fb-note">提交后仅平台管理员可查看与回复；附件仅管理员与您本人可见。</p>`);
    $$('.fb-type', body).forEach(b => b.onclick = () => {
      curType = b.dataset.t;
      $$('.fb-type', body).forEach(x => x.classList.toggle('on', x.dataset.t === curType));
      $('#fb-hint', body).textContent = (TYPES.find(t => t.id === curType) || {}).hint || '';
    });
    $('#fb-cancel', body).onclick = close;
    $('#fb-file', body).onchange = e => addFiles(e.target.files);
    $('#fb-send', body).onclick = send;
    picked = [];
    renderPicked(body);
    setTimeout(() => { const c = $('#fb-content', body); if (c && window.innerWidth > 900) c.focus(); }, 60);
  }
  function $$(s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); }

  function renderPicked(body) {
    const box = $('#fb-picked', body);
    if (!box) return;
    box.innerHTML = picked.map((f, i) => `<span class="fb-chip">${esc(f.name)}<i>${(f.size / 1024).toFixed(0)}KB</i><b data-i="${i}">×</b></span>`).join('');
    $$('.fb-chip b', box).forEach(b => b.onclick = () => { picked.splice(Number(b.dataset.i), 1); renderPicked(body); });
  }
  function addFiles(files) {
    const body = $('#fb-body');
    for (const f of Array.prototype.slice.call(files || [])) {
      if (picked.length >= 5) { toast('最多 5 个附件'); break; }
      if (f.size > MAX_FILE) { toast(f.name + ' 超过 8MB'); continue; }
      const fr = new FileReader();
      fr.onload = () => {
        picked.push({ name: f.name, size: f.size, data: String(fr.result).split(',')[1] || '' });
        renderPicked(body);
      };
      fr.readAsDataURL(f);
    }
    const inp = $('#fb-file', body); if (inp) inp.value = '';
  }

  async function send() {
    const body = $('#fb-body');
    const content = $('#fb-content', body).value.trim();
    const contact = $('#fb-contact', body).value.trim();
    const err = $('#fb-err', body);
    err.textContent = '';
    if (!content && !picked.length) { err.textContent = '请填写描述内容，或上传截图附件'; return; }
    const btn = $('#fb-send', body);
    btn.disabled = true; btn.textContent = '提交中…';
    try {
      await req('POST', '/api/feedbacks', {
        type: curType, content, contact, attachments: picked,
        page: currentPage(),
        app_version: localStorage.getItem('erp_apk_version') || 'web'
      });
      close();
      toast('反馈已提交，管理员会尽快处理', 2600);
      refreshBadge();
      if (isAdmin()) setTimeout(() => openInbox(), 400);
    } catch (e) {
      err.textContent = e.message;
      btn.disabled = false; btn.textContent = '提交反馈';
    }
  }

  // ---------- 收件箱（管理员看全部 + 回复 / 普通用户看自己的） ----------
  let fbFilter = { status: '', type: '' };
  async function openInbox() {
    const admin = isAdmin();
    const body = show(admin ? '反馈管理' : '我的反馈', '<div class="fb-loading">加载中…</div>', true);
    const render = async () => {
      let data;
      try {
        data = admin
          ? await req('GET', '/api/feedbacks' + qs(fbFilter))
          : { items: await req('GET', '/api/feedbacks/mine') };
      } catch (e) {
        body.innerHTML = '<div class="fb-empty">' + esc(e.message) + '</div>'; return;
      }
      const items = data.items || [];
      const c = data.counts || {};
      const box = $('#fb-list', body) || body;
      box.innerHTML = `
        ${admin ? `<div class="fb-filters">
          <div class="fb-chips">
            ${[['', '全部'], ['open', '待处理 ' + (c.open || 0)], ['replied', '已回复 ' + (c.replied || 0)], ['closed', '已关闭 ' + (c.closed || 0)]]
        .map(x => `<button class="fb-chipbtn${fbFilter.status === x[0] ? ' on' : ''}" data-s="${x[0]}">${esc(x[1])}</button>`).join('')}
          </div>
          <select id="fb-ftype" class="fb-sel">
            <option value="">全部类型</option>
            ${TYPES.map(t => `<option value="${t.id}"${fbFilter.type === t.id ? ' selected' : ''}>${esc(t.label)}</option>`).join('')}
          </select>
        </div>` : ''}
        <div id="fb-items">${items.length ? items.map(it => card(it, admin)).join('') : '<div class="fb-empty">暂无反馈记录</div>'}</div>`;
      // 过滤
      $$('.fb-chipbtn', box).forEach(b => b.onclick = () => { fbFilter.status = b.dataset.s; render(); });
      const ft = $('#fb-ftype', box);
      if (ft) ft.onchange = () => { fbFilter.type = ft.value; render(); };
      // 行内操作
      $$('.fb-toggle', box).forEach(b => b.onclick = () => {
        const d = b.nextElementSibling;
        const open = d.classList.toggle('hidden');
        b.textContent = open ? '展开 ▾' : '收起 ▴';
      });
      $$('.fb-reply', box).forEach(b => b.onclick = () => replyForm(b.dataset.id, admin, render));
      $$('.fb-close', box).forEach(b => b.onclick = async () => {
        try { await req('POST', '/api/feedbacks/' + b.dataset.id + '/status', { status: 'closed' }); toast('已关闭'); render(); refreshBadge(); }
        catch (e) { toast(e.message); }
      });
      $$('.fb-open', box).forEach(b => b.onclick = async () => {
        try { await req('POST', '/api/feedbacks/' + b.dataset.id + '/status', { status: 'open' }); toast('已重新打开'); render(); refreshBadge(); }
        catch (e) { toast(e.message); }
      });
      $$('.fb-del', box).forEach(b => b.onclick = async () => {
        if (!confirm('确定删除这条反馈？附件文件会保留但链接失效。')) return;
        try { await req('DELETE', '/api/feedbacks/' + b.dataset.id); toast('已删除'); render(); refreshBadge(); }
        catch (e) { toast(e.message); }
      });
      $$('.fb-dl', box).forEach(a => a.onclick = () => downloadAtt(a.dataset.fid, a.dataset.name));
    };
    if (!$('#fb-list', body)) body.innerHTML = '<div id="fb-list"></div>';
    render();
    refreshBadge();
  }
  function qs(f) {
    const a = [];
    if (f.status) a.push('status=' + encodeURIComponent(f.status));
    if (f.type) a.push('type=' + encodeURIComponent(f.type));
    return a.length ? '?' + a.join('&') : '';
  }
  function card(it, admin) {
    const t = TYPES.find(x => x.id === it.type) || TYPES[3];
    const st = STATUS[it.status] || STATUS.open;
    let atts = [];
    try { atts = JSON.parse(it.attachments || '[]'); } catch (e) {}
    return `<div class="fb-card">
      <div class="fb-card-top">
        <span class="fb-tag">${t.icon} ${esc(t.label)}</span>
        <span class="${st.cls}">${st.label}</span>
        <span class="fb-no">#${it.id}</span>
        <span class="fb-time">${esc(it.created_at || '')}</span>
      </div>
      ${it.title ? `<div class="fb-card-title">${esc(it.title)}</div>` : ''}
      <div class="fb-card-body">${esc(it.content || '').replace(/\n/g, '<br>')}</div>
      ${atts.length ? `<div class="fb-atts">${atts.map(a => `<button class="fb-chipbtn fb-dl" data-fid="${a.id || 0}" data-name="${esc(a.name)}">📎 ${esc(a.name)}${a.size ? '<i>' + Math.round(a.size / 1024) + 'KB</i>' : ''}</button>`).join('')}</div>` : ''}
      <div class="fb-meta">
        ${admin ? `<span>👤 ${esc(it.account_name || '')}（ID ${it.account_id}）</span>` : ''}
        ${it.page ? `<span>📍 ${esc(it.page)}</span>` : ''}
        ${it.app_version ? `<span>📱 ${esc(it.app_version)}</span>` : ''}
        ${it.contact ? `<span>📞 ${esc(it.contact)}</span>` : ''}
      </div>
      ${it.admin_note ? `<div class="fb-note-box">备注：${esc(it.admin_note)}</div>` : ''}
      ${(it.replies || []).length ? `<div class="fb-replies hidden">
        ${it.replies.map(r => `<div class="fb-reply-item"><b>${esc(r.account_name || '管理员')}</b><span>${esc(r.content)}</span><i>${esc(r.created_at || '')}</i></div>`).join('')}
      </div>` : ''}
      <div class="fb-ops">
        ${(it.replies || []).length ? `<button class="fb-chipbtn fb-toggle">展开 ▾</button>` : ''}
        ${admin ? `<button class="btn-sm fb-reply" data-id="${it.id}">回复</button>` : ''}
        ${admin && it.status !== 'closed' ? `<button class="btn-sm gray fb-close" data-id="${it.id}">关闭</button>` : ''}
        ${admin && it.status === 'closed' ? `<button class="btn-sm gray fb-open" data-id="${it.id}">重新打开</button>` : ''}
        ${admin ? `<button class="btn-sm danger fb-del" data-id="${it.id}">删除</button>` : ''}
      </div>
    </div>`;
  }
  // 附件下载：直接用 feedback_files.id（attachments JSON 里已存）
  async function downloadAtt(key, name) {
    const fid = Number(key);
    if (!fid) { toast('附件信息缺失'); return; }
    try {
      const hdr = { Authorization: 'Bearer ' + localStorage.getItem('erp_token') };
      const r = await fetch(BASE + '/api/feedback-files/' + fid, { headers: hdr });
      if (!r.ok) throw new Error('附件下载失败');
      const blob = await r.blob();
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = name || 'attachment';
      document.body.appendChild(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(a.href), 3000);
    } catch (e) { toast(e.message); }
  }

  function replyForm(id, admin, after) {
    const body = $('#fb-body');
    let box = $('#fb-replybox-' + id, body);
    if (!box) {
      box = document.createElement('div');
      box.id = 'fb-replybox-' + id;
      box.className = 'fb-replybox';
      const card = box.closest('.fb-card');
      card.insertBefore(box, card.querySelector('.fb-ops'));
      box.innerHTML = `<textarea rows="3" placeholder="回复内容…"></textarea>
        <div class="fb-err"></div>
        <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:8px">
          <button class="btn-sm gray">取消</button><button class="btn-primary">发送回复</button></div>`;
      const ta = $('textarea', box), err = $('.fb-err', box);
      const btns = $$('button', box);
      btns[0].onclick = () => box.remove();
      btns[1].onclick = async () => {
        const c = ta.value.trim();
        if (!c) { err.textContent = '回复内容不能为空'; return; }
        btns[1].disabled = true; btns[1].textContent = '发送中…';
        try { await req('POST', '/api/feedbacks/' + id + '/reply', { content: c }); toast('已回复'); box.remove(); if (after) after(); }
        catch (e) { err.textContent = e.message; btns[1].disabled = false; btns[1].textContent = '发送回复'; }
      };
    }
    const ta = $('textarea', box);
    ta.focus();
  }

  // ---------- 侧栏红点（未读反馈数） ----------
  async function refreshBadge() {
    const el = document.getElementById('fb-dot');
    if (!el) return;
    try {
      const tok = localStorage.getItem('erp_token');
      if (!tok) { el.style.display = 'none'; return; }
      const r = await fetch(BASE + '/api/feedback-unread', { headers: { Authorization: 'Bearer ' + tok } });
      if (!r.ok) { el.style.display = 'none'; return; }
      const d = await r.json();
      if (d.unread > 0) { el.textContent = d.unread > 99 ? '99+' : d.unread; el.style.display = ''; }
      else el.style.display = 'none';
    } catch (e) { el.style.display = 'none'; }
  }

  window.ERPFeedback = { openSubmit, openInbox, refreshBadge, TYPES };
})();