// 团购后台接口（后端 GbAdminController，/gb/admin/**）
import { defHttp } from '/@/utils/http/axios';
import { getFileAccessHttpUrl } from '/@/utils/common/compUtils';

const quiet = { successMessageMode: 'none' } as const;
const get = (url: string, params?: any) => defHttp.get({ url: '/gb/admin' + url, params }, quiet);
// POST 成功时自动弹出后端返回的提示文字（如「已保存」），失败自动弹错误
const post = (url: string, data?: any) => defHttp.post({ url: '/gb/admin' + url, data });

export const gbApi = {
  meta: () => get('/meta'),
  dashboard: () => get('/dashboard'),
  goodsList: (p) => get('/goods/list', p),
  goodsSave: (d) => post('/goods/save', d),
  goodsStatus: (d) => post('/goods/status', d),
  periodList: () => get('/period/list'),
  periodDetail: (id?: string) => get('/period/detail', { id }),
  periodNext: () => get('/period/next'),
  periodSave: (d) => post('/period/save', d),
  periodGoodsUpdate: (d) => post('/period/goods/update', d),
  periodGoodsAdd: (d) => post('/period/goods/add', d),
  orderList: (p) => get('/order/list', p),
  orderDetail: (id: string) => get('/order/detail', { id }),
  orderCancel: (id: string) => post('/order/cancel', { id }),
  orderVerify: (id: string) => post('/order/verify', { id }),
  orderRemind: (id: string) => post('/order/remind', { id }),
  sorting: (periodId?: string) => get('/sorting', { periodId }),
  ship: (d) => post('/delivery/ship', d),
  sign: (d) => post('/delivery/sign', d),
  asList: (status?: string) => get('/aftersale/list', { status }),
  asApprove: (d) => post('/aftersale/approve', d),
  asReject: (d) => post('/aftersale/reject', d),
  pointList: (p) => get('/point/list', p),
  pointCheck: (id: string) => get('/point/check', { id }),
  pointSave: (d) => post('/point/save', d),
  pointAudit: (d) => post('/point/audit', d),
  pointStatus: (d) => post('/point/status', d),
  pointRate: (d) => post('/point/rate', d),
  commissionSummary: () => get('/commission/summary'),
  commissionList: (p) => get('/commission/list', p),
  withdrawList: (p) => get('/withdraw/list', p),
  withdrawApprove: (d) => post('/withdraw/approve', d),
  withdrawReject: (d) => post('/withdraw/reject', d),
  notifyList: (p) => get('/notify/list', p),
  couponList: (type?: string) => get('/coupon/list', { type }),
  couponSave: (d) => post('/coupon/save', d),
  couponStatus: (d) => post('/coupon/status', d),
};

// ---------- 展示工具 ----------
export const yuan = (v: any) => '¥' + Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
export const pct = (v: any, d = 0) => (v == null ? '—' : (Number(v) * 100).toFixed(d) + '%');
export const img = (u?: string) => (u ? getFileAccessHttpUrl(u) : '');
const pad = (n: number) => String(n).padStart(2, '0');
export const toDate = (s: any) => (s instanceof Date ? s : new Date(String(s).replace(/-/g, '/')));
/** 11/17 23:00 */
export const md = (s: any) => { if (!s) return '—'; const d = toDate(s); return `${d.getMonth() + 1}/${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`; };
export const hms = (sec: number) => { sec = Math.max(0, Math.floor(sec)); return [Math.floor(sec / 3600), Math.floor((sec % 3600) / 60), sec % 60].map(pad).join(':'); };
export const fmt = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:00`;

export const ORDER_STATUS: Record<string, [string, string]> = {
  unpaid: ['待付款', 'warn'], paid: ['待截单', 'pri'], shipping: ['配送中', 'pri'], arrived: ['待提货', 'ok'], done: ['已提货', ''], closed: ['已关闭', ''],
};
export const PERIOD_STATUS: Record<string, [string, string]> = {
  draft: ['草稿', ''], plan: ['待开团', ''], live: ['进行中', 'pri'], cutoff: ['分拣配送中', 'warn'], pickup: ['自提中', 'ok'], done: ['已结束', ''],
};
export const STORAGE = { normal: '常温', cold: '冷藏 0–4℃', frozen: '冷冻' };
