/* 邻里鲜 · 后台主壳：侧栏 / 顶栏 / 页签。菜单结构与落代码时 sys_permission 里要建的菜单一一对应。
   页面写法：<script>window.__PAGE__={key:'/gb/orders',title:'订单管理'}</script> + <div id="page-body">…</div> */
(function () {
  const MENU = [
    { name: '工作台', icon: 'gauge', key: '/dashboard', href: 'index.html', children: [] },
    { name: '团购运营', icon: 'store', children: [
      { key: '/gb/period', name: '每日团期', href: 'period.html' },
      { key: '/gb/goods', name: '商品库', href: 'goods.html' },
    ] },
    { name: '订单履约', icon: 'truck', children: [
      { key: '/gb/orders', name: '订单管理', href: 'orders.html' },
      { key: '/gb/sorting', name: '分拣配送', href: 'sorting.html', count: 2 },
      { key: '/gb/aftersale', name: '售后退款', href: 'aftersale.html', count: 2 },
    ] },
    { name: '团长与提货点', icon: 'pin', children: [
      { key: '/gb/leaders', name: '团长管理', href: 'leaders.html', count: 1 },
    ] },
    { name: '营销中心', icon: 'ticket', children: [
      { key: '/gb/coupons', name: '优惠活动', href: 'marketing.html' },
    ] },
    { name: '系统管理', icon: 'settings', children: [
      { key: '/system/user', name: '用户管理', href: '_placeholder.html?k=用户管理' },
      { key: '/system/role', name: '角色管理', href: '_placeholder.html?k=角色管理' },
      { key: '/system/dict', name: '数据字典', href: '_placeholder.html?k=数据字典' },
    ] },
  ];
  const PAGE = window.__PAGE__ || {};
  const ico = window.ico;
  const findLeaf = (key) => { for (const g of MENU) { if (g.key === key) return { g, c: g }; for (const c of g.children) if (c.key === key) return { g, c }; } return null; };
  const hit = findLeaf(PAGE.key);
  const store = { get(k, d) { try { return JSON.parse(sessionStorage.getItem(k)) ?? d; } catch { return d; } }, set(k, v) { try { sessionStorage.setItem(k, JSON.stringify(v)); } catch {} } };
  if (store.get('gb-folded', false)) document.documentElement.classList.add('sider-folded');

  // Logo：小区屋顶 + 叶子（邻里 + 鲜）
  const LOGO = `<svg class="brand-logo" viewBox="0 0 28 28" aria-hidden="true">
    <rect x="1" y="1" width="26" height="26" rx="7" fill="var(--c-primary)"/>
    <path d="M6.5 13.2 14 7l7.5 6.2V21a1 1 0 0 1-1 1h-13a1 1 0 0 1-1-1v-7.8Z" fill="none" stroke="var(--c-on-primary)" stroke-width="2" stroke-linejoin="round"/>
    <path d="M14 20.5c-2.8-.4-4-2.6-3.4-5.4 2.6.1 4 1.9 3.4 5.4Zm0 0c2.8-.4 4-2.6 3.4-5.4-2.6.1-4 1.9-3.4 5.4Z" fill="var(--c-mark-bright)"/></svg>`;
  window.BRAND_LOGO = LOGO;

  const menuHtml = MENU.map((g) => {
    const active = hit && hit.g === g;
    if (!g.children.length) return `<div class="m-group"><a class="m-leaf${active ? ' active' : ''}" href="${g.href}" title="${g.name}">${ico(g.icon)}<span class="m-label">${g.name}</span></a></div>`;
    const open = active || g.name !== '系统管理';
    const subs = g.children.map((c) => `<a class="m-leaf${PAGE.key === c.key ? ' active' : ''}" href="${c.href}"><span class="m-label">${c.name}</span>${c.count ? `<span class="cnt-badge">${c.count}</span>` : ''}</a>`).join('');
    return `<div class="m-group${open ? ' open' : ''}${active ? ' has-active' : ''}">
      <button type="button" title="${g.name}" aria-expanded="${open}">${ico(g.icon)}<span class="m-label">${g.name}</span>${ico('chev-down', 'ico-sm chev')}</button>
      <div class="m-sub">${subs}</div></div>`;
  }).join('');

  let tabs = store.get('gb-tabs', ['/dashboard']);
  if (PAGE.key && !tabs.includes(PAGE.key)) tabs.push(PAGE.key);
  tabs = tabs.slice(-7); store.set('gb-tabs', tabs);
  const leafOf = (k) => findLeaf(k) || { c: { name: k, href: 'index.html' } };
  const tabsHtml = tabs.map((k) => `<a class="tab${k === PAGE.key ? ' active' : ''}" href="${leafOf(k).c.href}">${leafOf(k).c.name}${k === '/dashboard' ? '' : `<span class="x" data-close="${k}" title="关闭">${ico('x')}</span>`}</a>`).join('');

  const body = document.getElementById('page-body');
  const shell = document.createElement('div');
  shell.className = 'layout';
  shell.innerHTML = `
    <aside class="sider">
      <a class="brand" href="index.html">${LOGO}<span class="brand-name">邻里鲜 · 团购后台</span></a>
      <nav class="menu" aria-label="主菜单">${menuHtml}</nav>
      <div class="sider-foot">基于 JeecgBoot 3.9.6</div>
    </aside>
    <div class="main">
      <header class="header">
        <button class="icon-btn" id="fold" title="折叠菜单">${ico('fold')}</button>
        <div class="crumb">${hit && hit.g !== hit.c ? `${hit.g.name}${ico('chev-right', 'ico-sm')}<b>${hit.c.name}</b>` : `<b>${PAGE.title || ''}</b>`}</div>
        <div class="spacer"></div>
        <a class="hd-period" href="period.html" title="当前团期">${ico('store', 'ico-sm')}<b>${GB.PERIOD.name}</b> 进行中 ${GB.cutoffHtml('距截单')}</a>
        <button class="icon-btn" title="通知">${ico('bell')}<span class="dot"></span></button>
        <button class="me" type="button"><span class="avatar">运</span><span class="me-name">林运营<small>朝阳运营中心</small></span>${ico('chev-down', 'ico-sm')}</button>
      </header>
      <nav class="tabs" aria-label="已打开页面">${tabsHtml}</nav>
      <main class="content"></main>
    </div>`;
  body.parentNode.insertBefore(shell, body);
  shell.querySelector('.content').appendChild(body);
  document.title = (PAGE.title || '') + ' · 邻里鲜团购后台';

  shell.querySelectorAll('.m-group > button').forEach((b) => b.addEventListener('click', () => {
    if (document.documentElement.classList.contains('sider-folded')) { document.documentElement.classList.remove('sider-folded'); store.set('gb-folded', false); }
    const g = b.parentElement; g.classList.toggle('open'); b.setAttribute('aria-expanded', g.classList.contains('open'));
  }));
  shell.querySelector('#fold').addEventListener('click', () => store.set('gb-folded', document.documentElement.classList.toggle('sider-folded')));
  shell.querySelectorAll('[data-close]').forEach((x) => x.addEventListener('click', (e) => {
    e.preventDefault(); e.stopPropagation();
    const k = x.dataset.close; const left = tabs.filter((t) => t !== k); store.set('gb-tabs', left);
    if (k === PAGE.key) location.href = leafOf(left[left.length - 1]).c.href; else x.closest('.tab').remove();
  }));

  /* ---------- 通用小工具 ---------- */
  window.toast = (msg) => {
    let t = document.querySelector('.toast');
    if (!t) { t = document.createElement('div'); t.className = 'toast'; t.setAttribute('role', 'status'); document.body.appendChild(t); }
    t.innerHTML = ico('check-circle') + msg; t.classList.add('on');
    clearTimeout(t._h); t._h = setTimeout(() => t.classList.remove('on'), 2200);
  };
  // 抽屉：openDrawer(title, bodyHtml, footHtml) → 返回抽屉元素
  window.openDrawer = (title, bd, ft = '', width) => {
    let m = document.querySelector('.mask'), d = document.querySelector('.drawer');
    if (!m) { m = document.createElement('div'); m.className = 'mask'; document.body.appendChild(m); d = document.createElement('aside'); d.className = 'drawer'; document.body.appendChild(d); m.onclick = () => window.closeDrawer(); }
    d.style.width = width ? width + 'px' : '';
    d.innerHTML = `<div class="drawer-hd"><h2>${title}</h2><button class="icon-btn" data-close-drawer title="关闭">${ico('x')}</button></div><div class="drawer-bd">${bd}</div>${ft ? `<div class="drawer-ft">${ft}</div>` : ''}`;
    d.querySelectorAll('[data-close-drawer]').forEach((b) => (b.onclick = () => window.closeDrawer()));
    requestAnimationFrame(() => { m.classList.add('on'); d.classList.add('on'); });
    return d;
  };
  window.closeDrawer = () => { document.querySelector('.mask')?.classList.remove('on'); document.querySelector('.drawer')?.classList.remove('on'); };
  // 分段筛选：<div class="seg" data-seg="name"><button data-v="">…</button></div>
  window.bindSeg = (root, cb) => root.querySelectorAll('.seg').forEach((s) => s.addEventListener('click', (e) => {
    const b = e.target.closest('button'); if (!b) return;
    s.querySelectorAll('button').forEach((x) => x.classList.toggle('on', x === b)); cb(s.dataset.seg, b.dataset.v);
  }));
})();
