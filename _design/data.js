/* 邻里鲜 · 原型共享示例数据（PC 后台与小程序共用同一份，保证两端数字对得上）。
   时间固定：2026-11-17 周二 14:47:30。本期团：11/16 23:00 开团 → 11/17 23:00 截单 → 11/18 16:00 起到提货点自提。
   全部是示例数据，不代表真实经营情况。 */
(function () {
  const D = {};
  D.NOW = { date: '11月17日', week: '周二', time: '14:47:30' };
  D.CUTOFF_LEFT = 8 * 3600 + 12 * 60 + 30; // 距 23:00 截单
  D.PERIOD = { id: 'T20261117', name: '11月17日团', open: '11/16 23:00', cutoff: '11/17 23:00', pickup: '11/18 16:00', pickupWord: '明天 16:00' };

  /* ---------- 商品示意图：几何图形，开发时换成实拍图 ---------- */
  const A = {
    strawberry: ['#FFEDEC', '<path d="M50 88C30 78 20 58 24 44c3-11 14-16 26-16s23 5 26 16c4 14-6 34-26 44Z" fill="#E5463F"/><path d="M50 30c-8-6-18-6-22-2 6 1 10 3 12 6-4-1-9 0-12 3 8 0 14 1 22 0 8 1 14 0 22 0-3-3-8-4-12-3 2-3 6-5 12-6-4-4-14-4-22 2Z" fill="#2E9B57"/><g fill="#FFD9A8"><circle cx="40" cy="50" r="1.8"/><circle cx="56" cy="48" r="1.8"/><circle cx="47" cy="60" r="1.8"/><circle cx="60" cy="62" r="1.8"/><circle cx="38" cy="64" r="1.8"/><circle cx="50" cy="72" r="1.8"/></g>'],
    egg: ['#FFF4E3', '<ellipse cx="38" cy="58" rx="15" ry="20" fill="#F4D3A6"/><ellipse cx="62" cy="54" rx="15" ry="20" fill="#EFC28A"/><ellipse cx="50" cy="70" rx="15" ry="19" fill="#F8E1BF"/><ellipse cx="45" cy="64" rx="4" ry="6" fill="#fff" opacity=".5"/>'],
    greens: ['#EAF7EC', '<path d="M50 90V40" stroke="#B9DDB0" stroke-width="8" stroke-linecap="round"/><path d="M50 72C30 70 20 54 24 32c16 2 26 14 26 40Z" fill="#3BAA5C"/><path d="M50 72c20-2 30-18 26-40-16 2-26 14-26 40Z" fill="#2E9150"/><path d="M50 60c-6-14-4-30 0-42 4 12 6 28 0 42Z" fill="#59C27A"/>'],
    milk: ['#EEF4FB', '<path d="M34 34h32v52a4 4 0 0 1-4 4H38a4 4 0 0 1-4-4V34Z" fill="#fff" stroke="#C9D7EA" stroke-width="2"/><path d="M34 34l6-14h20l6 14Z" fill="#DCE8F6"/><rect x="34" y="50" width="32" height="18" fill="#4D82C9"/><path d="M42 64c3-6 13-6 16 0" stroke="#fff" stroke-width="2.5" fill="none" stroke-linecap="round"/>'],
    apple: ['#FFEEEA', '<path d="M50 36c-6-4-24-6-26 14-2 22 14 36 22 34 2 0 3-1 4-1s2 1 4 1c8 2 24-12 22-34-2-20-20-18-26-14Z" fill="#D9473D"/><path d="M50 36c0-6 2-12 6-16" stroke="#7A4B2A" stroke-width="3" stroke-linecap="round" fill="none"/><path d="M54 28c6-8 14-8 18-6-4 6-10 8-18 6Z" fill="#3BAA5C"/><ellipse cx="38" cy="50" rx="4" ry="7" fill="#fff" opacity=".3"/>'],
    citrus: ['#FFF3E2', '<circle cx="50" cy="56" r="28" fill="#F59A23"/><circle cx="50" cy="56" r="28" fill="none" stroke="#E7861A" stroke-width="2" stroke-dasharray="1 5"/><path d="M50 28c4-8 12-10 18-8-4 6-10 9-18 8Z" fill="#3BAA5C"/><ellipse cx="40" cy="46" rx="5" ry="7" fill="#fff" opacity=".28"/>'],
    fish: ['#E9F3F8', '<path d="M18 56c10-16 30-22 48-14l14-10v48l-14-10c-18 8-38 2-48-14Z" fill="#6F9FBF"/><path d="M18 56c10 10 28 14 44 8" stroke="#A8C8DC" stroke-width="3" fill="none"/><circle cx="32" cy="52" r="3" fill="#1D2B36"/>'],
    pork: ['#FCEDEE', '<path d="M20 50c0-12 14-20 32-20s30 8 30 20-12 24-32 24S20 62 20 50Z" fill="#E98C8C"/><path d="M26 52c4 8 14 14 26 14s22-6 26-14" stroke="#FBE3E3" stroke-width="5" fill="none" stroke-linecap="round"/><path d="M32 46c6 4 26 4 34-2" stroke="#F7C6C6" stroke-width="3" fill="none" stroke-linecap="round"/>'],
    rice: ['#F6F2E7', '<path d="M30 36h40l4 46a6 6 0 0 1-6 6H32a6 6 0 0 1-6-6l4-46Z" fill="#E9DCB8"/><path d="M30 36c4-6 36-6 40 0" stroke="#C9B587" stroke-width="3" fill="none"/><rect x="36" y="52" width="28" height="18" rx="3" fill="#078149"/><path d="M44 62h12" stroke="#fff" stroke-width="2.5" stroke-linecap="round"/>'],
    tomato: ['#FFEDEA', '<circle cx="50" cy="58" r="26" fill="#E5463F"/><path d="M50 34 42 28m8 6 8-6m-8 6-2-10m2 10 10 2m-10-2-10 2" stroke="#2E9B57" stroke-width="4" stroke-linecap="round"/><ellipse cx="40" cy="50" rx="5" ry="7" fill="#fff" opacity=".3"/>'],
    banana: ['#FFF8DE', '<path d="M24 40c8 30 36 42 58 26 2-2 0-6-3-5-18 8-38 0-48-22-2-3-8-2-7 1Z" fill="#F2C335"/><path d="M28 44c10 20 28 26 46 20" stroke="#E0AC1E" stroke-width="2.5" fill="none"/><path d="M24 40l-3-6" stroke="#6B5A2A" stroke-width="4" stroke-linecap="round"/>'],
    mushroom: ['#F4EFEA', '<path d="M22 54c0-16 12-26 28-26s28 10 28 26Z" fill="#9C6B4E"/><path d="M40 54h20l-3 28a4 4 0 0 1-4 4h-6a4 4 0 0 1-4-4l-3-28Z" fill="#EADBC8"/><circle cx="38" cy="42" r="3" fill="#C49A7C"/><circle cx="58" cy="38" r="2.5" fill="#C49A7C"/>'],
    bread: ['#FBF1E4', '<path d="M22 50c0-12 12-18 28-18s28 6 28 18v24a6 6 0 0 1-6 6H28a6 6 0 0 1-6-6V50Z" fill="#D99A55"/><path d="M36 46l6 8m4-10 6 8m4-8 6 8" stroke="#F3D3A6" stroke-width="3" stroke-linecap="round"/>'],
    tissue: ['#EFF3F8', '<rect x="22" y="40" width="56" height="38" rx="6" fill="#fff" stroke="#C9D7EA" stroke-width="2"/><path d="M40 40c0-8 4-14 10-14s10 6 10 14" fill="#fff" stroke="#C9D7EA" stroke-width="2"/><rect x="22" y="56" width="56" height="8" fill="#9DC3E8"/>'],
  };
  D.art = (kind, size = 64, radius) => {
    const [bg, body] = A[kind] || A.greens;
    return `<span class="art" style="width:${size}px;height:${size}px;background:${bg}${radius != null ? `;border-radius:${radius}px` : ''}"><svg viewBox="0 0 100 100" aria-hidden="true">${body}</svg></span>`;
  };

  /* ---------- 商品 & 本期团品 ---------- */
  // price: 团购价；orig: 超市参考价；stock: 本期可售；sold: 已售；limit: 每人限购；cost: 采购价
  D.CATS = ['全部', '水果', '蔬菜', '肉禽蛋', '水产', '乳品烘焙', '粮油', '日用'];
  D.GOODS = [
    { id: 1001, name: '丹东 99 草莓', spec: '500g / 盒 · 约 20 颗', cat: '水果', art: 'strawberry', price: 29.9, orig: 45.8, cost: 21.5, stock: 400, sold: 386, limit: 2, tag: '爆品', supplier: '丹东鑫果园', origin: '辽宁丹东' },
    { id: 1002, name: '散养土鸡蛋', spec: '30 枚 / 箱 · 约 1.5kg', cat: '肉禽蛋', art: 'egg', price: 26.8, orig: 39.9, cost: 19.2, stock: 600, sold: 512, limit: 3, tag: '', supplier: '平谷蛋禽合作社', origin: '北京平谷' },
    { id: 1003, name: '本地小油菜', spec: '500g / 袋', cat: '蔬菜', art: 'greens', price: 3.5, orig: 6.0, cost: 2.1, stock: 800, sold: 437, limit: 0, tag: '', supplier: '顺义蔬菜基地', origin: '北京顺义' },
    { id: 1004, name: '鲜牛奶 950ml', spec: '950ml / 瓶 · 低温', cat: '乳品烘焙', art: 'milk', price: 12.9, orig: 16.5, cost: 9.6, stock: 500, sold: 298, limit: 0, tag: '', supplier: '三元乳业', origin: '北京' },
    { id: 1005, name: '烟台红富士苹果', spec: '5 斤 / 箱 · 果径 80mm+', cat: '水果', art: 'apple', price: 24.9, orig: 35.0, cost: 17.8, stock: 300, sold: 156, limit: 0, tag: '', supplier: '栖霞果品', origin: '山东栖霞' },
    { id: 1006, name: '赣南脐橙', spec: '5 斤 / 箱', cat: '水果', art: 'citrus', price: 19.9, orig: 29.9, cost: 13.5, stock: 300, sold: 291, limit: 2, tag: '快抢光', supplier: '信丰果业', origin: '江西赣州' },
    { id: 1007, name: '冰鲜鲈鱼', spec: '约 600g / 条 · 已去鳞去脏', cat: '水产', art: 'fish', price: 22.8, orig: 32.0, cost: 16.9, stock: 150, sold: 87, limit: 0, tag: '', supplier: '京深海鲜批发', origin: '广东' },
    { id: 1008, name: '黑猪五花肉', spec: '500g / 盒', cat: '肉禽蛋', art: 'pork', price: 25.9, orig: 36.0, cost: 19.8, stock: 200, sold: 143, limit: 0, tag: '', supplier: '千喜鹤', origin: '黑龙江' },
    { id: 1009, name: '五常大米', spec: '5kg / 袋 · 当季新米', cat: '粮油', art: 'rice', price: 49.9, orig: 69.0, cost: 38.0, stock: 120, sold: 41, limit: 0, tag: '', supplier: '五常米业', origin: '黑龙江五常' },
    { id: 1010, name: '普罗旺斯西红柿', spec: '1kg / 盒', cat: '蔬菜', art: 'tomato', price: 9.9, orig: 15.8, cost: 6.2, stock: 400, sold: 222, limit: 0, tag: '新人 1 元', supplier: '顺义蔬菜基地', origin: '北京顺义' },
    { id: 1011, name: '香蕉', spec: '1.5kg / 把', cat: '水果', art: 'banana', price: 8.8, orig: 12.9, cost: 5.9, stock: 300, sold: 118, limit: 0, tag: '', supplier: '新发地', origin: '云南' },
    { id: 1012, name: '鲜香菇', spec: '300g / 盒', cat: '蔬菜', art: 'mushroom', price: 6.9, orig: 9.9, cost: 4.4, stock: 300, sold: 64, limit: 0, tag: '', supplier: '顺义蔬菜基地', origin: '河北' },
    { id: 1013, name: '手撕吐司', spec: '400g / 袋', cat: '乳品烘焙', art: 'bread', price: 9.9, orig: 15.0, cost: 6.8, stock: 0, sold: 0, limit: 0, tag: '', supplier: '好利来中央工厂', origin: '北京', off: true },
    { id: 1014, name: '抽纸 3 层 × 24 包', spec: '100 抽 / 包', cat: '日用', art: 'tissue', price: 32.9, orig: 49.9, cost: 25.0, stock: 200, sold: 37, limit: 0, tag: '', supplier: '清风', origin: '' },
  ];
  D.goods = (id) => D.GOODS.find((g) => g.id === id);

  /* ---------- 团长 / 提货点 ---------- */
  D.LEADERS = [
    { id: 1, leader: '王桂芳', phone: '138****2716', point: '幸福里 3 号楼便利店', addr: '朝阳区幸福里小区 3 号楼底商', households: 1260, orders: 412, gmv: 16960.2, status: 'ok', since: '2025-03' },
    { id: 2, leader: '李建国', phone: '139****0821', point: '阳光花园南门驿站', addr: '朝阳区阳光花园南门菜鸟驿站', households: 2100, orders: 386, gmv: 15891.6, status: 'ok', since: '2025-01' },
    { id: 3, leader: '赵敏', phone: '186****5532', point: '翠竹苑物业服务中心', addr: '朝阳区翠竹苑 1 号楼 1 层', households: 860, orders: 205, gmv: 8439.5, status: 'ok', since: '2025-06' },
    { id: 4, leader: '陈晓燕', phone: '135****9902', point: '和平里水果店', addr: '东城区和平里七区 12 号楼底商', households: 1540, orders: 283, gmv: 11649.0, status: 'ok', since: '2025-04' },
    { id: 5, leader: '刘洋', phone: '158****4410', point: '望京 SOHO 快递柜旁', addr: '朝阳区望京街 10 号', households: 0, orders: 0, gmv: 0, status: 'pending', since: '' },
    { id: 6, leader: '孙丽', phone: '137****6638', point: '百子湾家园 7 号楼', addr: '朝阳区百子湾家园 7 号楼 102', households: 980, orders: 0, gmv: 0, status: 'paused', since: '2025-02' },
  ];
  D.leader = (id) => D.LEADERS.find((l) => l.id === id);

  /* ---------- 订单 ---------- */
  // status: unpaid 待付款 / paid 待成团(未截单) / sorting 已截单待分拣 / shipping 配送中 / arrived 待提货 / done 已提货 / refund 售后中 / closed 已关闭
  D.STATUS = {
    unpaid: ['待付款', 'warn'], paid: ['待截单', 'pri'], shipping: ['配送中', 'pri'], arrived: ['待提货', 'ok'],
    done: ['已提货', ''], refund: ['售后中', 'bad'], closed: ['已关闭', ''],
  };
  const o = (no, buyer, phone, leader, items, status, time, extra = {}) => {
    const amount = items.reduce((s, [id, n]) => s + D.goods(id).price * n, 0);
    const discount = extra.discount || 0;
    return { no, buyer, phone, leader, items, status, time, amount: +amount.toFixed(2), discount, pay: +(amount - discount).toFixed(2), code: extra.code || String(100000 + ((no * 7919) % 899999)).slice(-6), ...extra };
  };
  D.ORDERS = [
    o(25111702317, '张女士', '138****1029', 1, [[1001, 2], [1003, 2]], 'paid', '11/17 14:41', { coupon: '满 39 减 5', discount: 5 }),
    o(25111702288, '刘先生', '159****3302', 2, [[1002, 1], [1004, 2], [1009, 1]], 'paid', '11/17 14:22'),
    o(25111702260, '周阿姨', '135****7781', 1, [[1006, 2]], 'unpaid', '11/17 14:18'),
    o(25111702201, '吴女士', '186****6604', 3, [[1007, 1], [1008, 1], [1012, 2]], 'paid', '11/17 13:56', { coupon: '满 59 减 8', discount: 8 }),
    o(25111702177, '郑先生', '137****2290', 4, [[1010, 1]], 'paid', '11/17 13:40', { coupon: '新人 1 元购', discount: 8.9, newbie: true }),
    o(25111601925, '王女士', '139****5518', 2, [[1001, 1], [1005, 1]], 'arrived', '11/16 21:07'),
    o(25111601880, '冯先生', '188****0476', 1, [[1002, 2]], 'arrived', '11/16 20:33'),
    o(25111601702, '陈女士', '150****8812', 4, [[1004, 2], [1011, 1]], 'done', '11/16 18:15'),
    o(25111601655, '褚先生', '133****6025', 3, [[1008, 2]], 'refund', '11/16 17:48', { refund: { reason: '少件：五花肉只收到 1 盒', amount: 25.9, apply: '11/17 16:42', type: '少件' } }),
    o(25111601410, '卫女士', '177****3948', 2, [[1007, 1]], 'refund', '11/16 15:02', { refund: { reason: '鲈鱼不新鲜，有异味', amount: 22.8, apply: '11/17 17:05', type: '质量问题', photos: 2 } }),
    o(25111501208, '蒋先生', '136****7720', 1, [[1009, 1]], 'done', '11/15 19:20'),
    o(25111500987, '沈女士', '185****4431', 4, [[1006, 1], [1003, 3]], 'closed', '11/15 12:06', { closeReason: '超时未付款' }),
  ];

  /* ---------- 营销 ---------- */
  D.COUPONS = [
    { id: 1, name: '满 39 减 5', type: '满减券', rule: '满 39 元可用', value: 5, scope: '全部商品', total: 5000, got: 3216, used: 1874, valid: '领取后 7 天', status: 'on' },
    { id: 2, name: '满 59 减 8', type: '满减券', rule: '满 59 元可用', value: 8, scope: '全部商品', total: 3000, got: 1452, used: 690, valid: '11/10 – 11/30', status: 'on' },
    { id: 3, name: '水果 9 折', type: '折扣券', rule: '水果类 9 折，最多减 10 元', value: '9 折', scope: '水果', total: 2000, got: 2000, used: 1338, valid: '11/01 – 11/20', status: 'out' },
    { id: 4, name: '新人 1 元购', type: '新人专享', rule: '首单可 1 元买一件指定商品', value: '1 元购', scope: '西红柿 1kg', total: 0, got: 412, used: 287, valid: '注册后 3 天', status: 'on' },
    { id: 5, name: '双 12 预热 满 99 减 15', type: '满减券', rule: '满 99 元可用', value: 15, scope: '全部商品', total: 5000, got: 0, used: 0, valid: '12/01 – 12/12', status: 'plan' },
  ];

  D.money = (n, cls = '') => {
    const [y, j] = Number(n).toFixed(2).split('.');
    return `<span class="price ${cls}"><small>¥</small>${y}<span class="jiao">.${j}</span></span>`;
  };
  D.yuan = (n) => '¥' + Number(n).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  D.hms = (s) => [Math.floor(s / 3600), Math.floor((s % 3600) / 60), s % 60].map((x) => String(x).padStart(2, '0')).join(':');
  D.esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  D.pickup = (point, short) => `<span class="mk-pickup">${window.ico ? window.ico('pin') : ''}<b>${D.PERIOD.pickupWord}</b> 起可提${point ? `<span class="pt">· ${D.esc(point)}</span>` : ''}</span>`;
  // 截单倒计时：所有 .js-cutoff 每秒刷新
  D.cutoffHtml = (prefix = '距截单') => `<span class="mk-cutoff">${window.ico ? window.ico('timer') : ''}${prefix} <b class="js-cutoff">${D.hms(D.CUTOFF_LEFT)}</b></span>`;
  let left = D.CUTOFF_LEFT;
  const tick = () => document.querySelectorAll('.js-cutoff').forEach((e) => (e.textContent = D.hms(left)));
  setInterval(() => { left = Math.max(0, left - 1); tick(); }, 1000);
  document.readyState === 'loading' ? document.addEventListener('DOMContentLoaded', tick) : setTimeout(tick);

  window.GB = D;
})();
