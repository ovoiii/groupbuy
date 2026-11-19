/* 邻里鲜小程序 · 接口、登录、购物车、时间工具（后端 /gb/app/**，见 GbAppController） */

// H5 开发走 vite 代理 /api，小程序直连后端（开发者工具里勾「不校验合法域名」）
const BASE = (() => {
  // #ifdef H5
  if (JSON.parse(String(__VITE_APP_PROXY__))) return import.meta.env.VITE_APP_PROXY_PREFIX
  // #endif
  return import.meta.env.VITE_SERVER_BASEURL
})()

const TOKEN = 'gb-token'
const CART = 'gb-cart'
const POINT = 'gb-point'

export const token = {
  get: () => uni.getStorageSync(TOKEN) as string,
  set: (t: string) => uni.setStorageSync(TOKEN, t),
  clear: () => uni.removeStorageSync(TOKEN),
}

let loggingIn = false
export function toLogin() {
  if (loggingIn) return
  loggingIn = true
  uni.navigateTo({ url: '/pages/login/login', complete: () => setTimeout(() => (loggingIn = false), 800) })
}

/** 请求：成功返回 result；失败弹出后端的提示并抛错 */
export function api<T = any>(url: string, data?: any, method: 'GET' | 'POST' = 'GET', opts: { silent?: boolean } = {}): Promise<T> {
  return new Promise((resolve, reject) => {
    uni.request({
      url: BASE + '/gb/app' + url,
      method,
      data,
      header: { 'X-Gb-Token': token.get() || '', 'Content-Type': 'application/json' },
      success: (res: any) => {
        const d = res.data || {}
        if (d.code === 401) { token.clear(); toLogin(); return reject(new Error('请先登录')) }
        if (d.success) return resolve(d.result as T)
        if (!opts.silent) uni.showToast({ title: d.message || '出错了，请重试', icon: 'none', duration: 2500 })
        reject(new Error(d.message))
      },
      fail: (e) => {
        if (!opts.silent) uni.showToast({ title: '网络不太好，请重试', icon: 'none' })
        reject(e)
      },
    })
  })
}
export const post = <T = any>(url: string, data?: any) => api<T>(url, data, 'POST')

/** 上传一张本地图片，返回后端相对路径 */
export function upload(filePath: string): Promise<string> {
  return new Promise((resolve, reject) => {
    uni.uploadFile({
      url: BASE + '/gb/app/upload',
      filePath,
      name: 'file',
      header: { 'X-Gb-Token': token.get() || '' },
      success: (res) => {
        const d = typeof res.data === 'string' ? JSON.parse(res.data) : res.data
        if (d.success) return resolve(d.result.path)
        uni.showToast({ title: d.message || '上传失败', icon: 'none' })
        reject(new Error(d.message))
      },
      fail: (e) => { uni.showToast({ title: '图片上传失败', icon: 'none' }); reject(e) },
    })
  })
}

/** 后端图片地址（商品图存在后台上传目录） */
export const img = (u?: string) => (!u ? '/static/placeholder.png' : /^https?:/.test(u) ? u : `${BASE}/sys/common/static/${u}`)

// ---------------- 购物车（本地存储，按团品 id 记数量） ----------------
export type Cart = Record<string, number>
export const cart = {
  get: (): Cart => uni.getStorageSync(CART) || {},
  set(c: Cart) { uni.setStorageSync(CART, c); cart.badge() },
  qty: (id: string) => cart.get()[id] || 0,
  add(id: string, d: number) {
    const c = cart.get()
    c[id] = Math.max(0, (c[id] || 0) + d)
    if (!c[id]) delete c[id]
    cart.set(c)
    return c[id] || 0
  },
  count: () => Object.values(cart.get()).reduce((s, n) => s + n, 0),
  clear(ids?: string[]) { if (!ids) return cart.set({}); const c = cart.get(); ids.forEach((i) => delete c[i]); cart.set(c) },
  badge() {
    const n = cart.count()
    try { n ? uni.setTabBarBadge({ index: 1, text: String(n) }) : uni.removeTabBarBadge({ index: 1 }) } catch (e) {}
  },
}

// ---------------- 提货点（本地记住，登录后以服务器的默认提货点为准） ----------------
export const pointStore = {
  get: () => uni.getStorageSync(POINT) || null,
  set: (p: any) => uni.setStorageSync(POINT, p),
}

// ---------------- 时间 ----------------
let offset = 0
export function syncServerTime(serverTime?: any) {
  if (serverTime) offset = toDate(serverTime).getTime() - Date.now()
}
export const now = () => Date.now() + offset
const pad = (n: number) => String(n).padStart(2, '0')
export const toDate = (s: any) => (s instanceof Date ? s : new Date(String(s).replace(/-/g, '/')))
export const hms = (sec: number) => { sec = Math.max(0, Math.floor(sec)); return [Math.floor(sec / 3600), Math.floor((sec % 3600) / 60), sec % 60].map(pad).join(':') }
export const leftSec = (t: any) => (t ? Math.max(0, (toDate(t).getTime() - now()) / 1000) : 0)
/** 今天 / 明天 16:00、11/21 16:00 */
export function dayTime(t: any) {
  if (!t) return ''
  const d = toDate(t), n = new Date(now())
  const day0 = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime()
  const diff = Math.round((day0(d) - day0(n)) / 86400000)
  const w = diff === 0 ? '今天' : diff === 1 ? '明天' : diff === -1 ? '昨天' : `${d.getMonth() + 1}/${d.getDate()}`
  return `${w} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
export const md = (t: any) => { if (!t) return ''; const d = toDate(t); return `${d.getMonth() + 1}/${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}` }

export const money = (v: any) => Number(v || 0).toFixed(2)
export const toast = (title: string) => uni.showToast({ title, icon: 'none', duration: 2000 })
export const STATUS: Record<string, string> = { unpaid: '待付款', paid: '已付款 · 等待截单', shipping: '已截单 · 配送中', arrived: '已到货 · 待提货', done: '已提货', closed: '已关闭' }
