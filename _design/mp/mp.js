/* 邻里鲜小程序 · 外壳脚本：状态栏 / 导航栏 / tabbar / 购物车状态 / 提示。
   页面写法：<div class="app" data-title="购物车" data-tab="cart" data-back="index.html">…<main class="page">…</main></div>
   ?frame=0 去掉手机外框（总览页嵌入用）。 */
(function () {
  const q = new URLSearchParams(location.search);
  if (q.get('frame') !== '0') document.documentElement.classList.add('framed');
  const app = document.querySelector('.app');
  const ico = window.ico;

  // 购物车：sessionStorage，页面间共享
  const KEY = 'gb-cart';
  const DEF = { 1001: 1, 1002: 1, 1003: 2 };
  const read = () => { try { return JSON.parse(sessionStorage.getItem(KEY)) || { ...DEF }; } catch { return { ...DEF }; } };
  const Cart = {
    get: read,
    set(c) { try { sessionStorage.setItem(KEY, JSON.stringify(c)); } catch {} Cart.badge(); },
    qty: (id) => read()[id] || 0,
    add(id, d) { const c = read(); c[id] = Math.max(0, (c[id] || 0) + d); if (!c[id]) delete c[id]; Cart.set(c); return c[id] || 0; },
    count: () => Object.values(read()).reduce((s, n) => s + n, 0),
    badge() { document.querySelectorAll('.js-cart-n').forEach((b) => { const n = Cart.count(); b.textContent = n; b.hidden = !n; }); },
  };
  if (q.get('cart') === 'empty') Cart.set({});
  window.Cart = Cart;

  // 状态栏 + 导航栏
  const title = app.dataset.title;
  const top = document.createElement('div');
  top.innerHTML = `<div class="statusbar"><span class="num">14:47</span><span class="sb-r"><i></i><i></i><i></i></span></div>${app.dataset.nonav != null ? '' : `<div class="navbar">${app.dataset.back ? `<a class="back" href="${app.dataset.back}" aria-label="返回">${ico('chev-left')}</a>` : ''}<h1>${title || ''}</h1></div>`}`;
  app.prepend(...top.children);
  app.insertAdjacentHTML('beforeend', `<div class="capsule" aria-hidden="true"><span></span><i></i><span></span></div><div class="home-ind"></div><div class="mtoast" role="status"></div><div class="msheet-mask"></div><div class="msheet" role="dialog"></div>`);

  // tabbar
  const tab = app.dataset.tab;
  if (tab) {
    const T = [['index', '首页', 'home', 'index.html'], ['cart', '购物车', 'cart', 'cart.html'], ['orders', '订单', 'file', 'orders.html'], ['mine', '我的', 'user', 'mine.html']];
    app.querySelector('.home-ind').insertAdjacentHTML('beforebegin', `<nav class="tabbar">${T.map(([k, n, i, h]) => `<a href="${h}${q.get('frame') === '0' ? '?frame=0' : ''}" class="${tab === k ? 'on' : ''}">${ico(i)}${n}${k === 'cart' ? '<span class="badge js-cart-n"></span>' : ''}</a>`).join('')}</nav>`);
  }
  // 嵌入模式下站内链接保持 frame=0
  if (q.get('frame') === '0') document.addEventListener('click', (e) => { const a = e.target.closest('a[href$=".html"], a[href*=".html?"]'); if (a && !a.href.includes('frame=0')) a.href += (a.href.includes('?') ? '&' : '?') + 'frame=0'; }, true);
  Cart.badge();

  window.mtoast = (msg) => { const t = app.querySelector('.mtoast'); t.innerHTML = msg; t.classList.add('on'); clearTimeout(t._h); t._h = setTimeout(() => t.classList.remove('on'), 1800); };
  window.sheet = (html) => { const s = app.querySelector('.msheet'), m = app.querySelector('.msheet-mask'); s.innerHTML = html; s.classList.add('on'); m.classList.add('on'); m.onclick = window.closeSheet; return s; };
  window.closeSheet = () => { app.querySelector('.msheet').classList.remove('on'); app.querySelector('.msheet-mask').classList.remove('on'); };
  window.stepperHtml = (id, n, max) => n ? `<span class="stepper" data-id="${id}"><button class="minus" data-d="-1" aria-label="减一">${ico('minus')}</button><b>${n}</b><button class="plus" data-d="1" aria-label="加一" ${max && n >= max ? 'disabled' : ''}>${ico('plus')}</button></span>` : `<button class="add-btn" data-id="${id}" data-d="1" aria-label="加入购物车">${ico('plus')}</button>`;
})();
