/* 邻里鲜小程序 · 接口、登录、购物车、时间工具（后端 /gb/app/**，见 GbAppController） */
import { parseScene } from './scene'

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
    // 不在 tab 页时（如结算页）微信和 H5 都会失败，给个 fail 回调免得变成未处理的错误
    const fail = () => {}
    try { n ? uni.setTabBarBadge({ index: 1, text: String(n), fail }) : uni.removeTabBarBadge({ index: 1, fail }) } catch (e) {}
  },
}

// ---------------- 提货点（本地记住，登录后以服务器的默认提货点为准） ----------------
export const pointStore = {
  get: () => uni.getStorageSync(POINT) || null,
  set: (p: any) => uni.setStorageSync(POINT, p),
}

// ---------------- 订阅消息 ----------------
// 场景 → 模板 ID，来自后端 /config；启动时拉一次存起来，弹框时不能再等网络
let tmpl: Record<string, string> = uni.getStorageSync('gb-tmpl') || {}
export function loadConfig() {
  return api('/config', undefined, 'GET', { silent: true }).then((c: any) => {
    tmpl = c.tmpl || {}
    uni.setStorageSync('gb-tmpl', tmpl)
    return c
  })
}
/**
 * 弹微信订阅框（一次最多 3 个模板），用户点了「允许」的报给后端，之后可以给他发一条。
 * 微信要求由点击直接触发：必须在点击事件里、任何 await 之前调用。没配模板或不是微信小程序时什么都不做。
 * arrived 到货 / pickup 取货提醒 / aftersale 售后结果 / ship 发车 / apply 入驻审核 / withdraw 提现结果
 */
export function subscribe(scenes: string[]): Promise<void> {
  return new Promise((resolve) => {
    // #ifdef MP-WEIXIN
    const ids = [...new Set(scenes.map((s) => tmpl[s]).filter(Boolean))].slice(0, 3)
    if (ids.length) {
      uni.requestSubscribeMessage({
        tmplIds: ids,
        success: (r: any) => {
          const ok = ids.filter((i) => r[i] === 'accept')
          if (ok.length && token.get()) api('/notify/subscribe', { ids: ok }, 'POST', { silent: true }).catch(() => {})
        },
        complete: () => resolve(),
      } as any)
      return
    }
    // #endif
    resolve()
  })
}

// ---------------- 付款 ----------------
const wait = (ms: number) => new Promise((r) => setTimeout(r, ms))
/**
 * 付款。模拟支付 / 0 元单服务器直接算成功；微信支付拿参数调起收银台，付完以服务器向微信查到的结果为准。
 * 返回 paid 付款成功 / cancel 没付（取消或失败，订单还在，可以到订单里继续付）/ checking 微信说成功但服务器还没确认
 */
export async function payOrder(id: string): Promise<'paid' | 'cancel' | 'checking'> {
  const r = await post('/order/pay', { id })
  if (r.paid) return 'paid'
  // #ifdef MP-WEIXIN
  const ok = await new Promise<boolean>((resolve) => {
    uni.requestPayment({
      provider: 'wxpay',
      ...r.payParams,
      success: () => resolve(true),
      fail: (e: any) => {
        if (!/cancel/.test(e?.errMsg || '')) toast('支付没有成功，可以在订单里重新付款')
        resolve(false)
      },
    } as any)
  })
  if (!ok) return 'cancel'
  uni.showLoading({ title: '确认付款结果', mask: true })
  try {
    for (let i = 0; i < 6; i++) {
      const s: any = await api('/order/pay/check', { id }, 'POST', { silent: true }).catch(() => null)
      if (s?.paid) return 'paid'
      await wait(1000)
    }
  } finally {
    uni.hideLoading()
  }
  return 'checking'
  // #endif
  // #ifndef MP-WEIXIN
  toast('请在微信小程序里付款')
  return 'cancel'
  // #endif
}

// ---------------- 分享 ----------------
const FROM_PT = 'gb-from-pt'
const LEADER_PT = 'gb-leader-pt'
/** 拿到 /me 后记一下自己是不是团长（分享时用，不用每个页面都去查） */
export function rememberMe(me: any) {
  uni.setStorageSync(LEADER_PT, me?.leaderPoint?.id || '')
}
/** 分享出去的链接带上提货点：团长带自己的点，居民带自己常用的点 */
export function sharePointId(): string {
  return (uni.getStorageSync(LEADER_PT) as string) || pointStore.get()?.id || ''
}
/**
 * 从分享链接进来（带 pt=提货点）：记下来源，注册时算作这个提货点拉来的新人；
 * 还没选过提货点的直接选上。返回分享来的提货点（已经选了别的点时，页面可以提示切换）
 */
export async function handleShareEntry(q: any): Promise<any | null> {
  const pt = q?.pt
  if (!pt) return null
  uni.setStorageSync(FROM_PT, pt)
  // 没登录：先记着，注册新账号时默认选这个提货点（接口都要登录，这里不去查）
  if (!token.get()) return null
  const cur = pointStore.get()
  if (cur?.id === pt) return null
  const list: any[] = await api('/points', undefined, 'GET', { silent: true }).catch(() => [])
  const p = list.find((x) => x.id === pt)
  if (!p) return null
  if (!cur) {
    pointStore.set(p)
    if (token.get()) api('/me/update', { pointId: p.id }, 'POST', { silent: true }).catch(() => {})
    return null
  }
  return p
}
/** 登录接口带上分享来源（只对新注册的账号生效） */
export const fromPoint = () => (uni.getStorageSync(FROM_PT) as string) || undefined

/** 扫海报上的小程序码进来时参数在 scene 里（见 gb/scene.ts），转成和分享链接一样的 {id, pt} */
export function entryQuery(q: any): any {
  const info = parseScene(q?.scene)
  if (!info) return q || {}
  return { ...q, id: info.id || q.id, pt: info.pt || q.pt }
}

// ---------------- 分享海报 ----------------
/** 让后端画海报（带小程序码和分享人的提货点），下载成临时文件；返回本地路径 */
export function downloadPoster(type: string, id = ''): Promise<string> {
  const pt = sharePointId()
  const url = `${BASE}/gb/app/poster?type=${type}&id=${encodeURIComponent(id)}&pt=${encodeURIComponent(pt)}`
  return new Promise((resolve, reject) => {
    uni.downloadFile({
      url,
      header: { 'X-Gb-Token': token.get() || '' },
      success: (r) => {
        if (r.statusCode !== 200) return reject(new Error(type === 'home' ? '现在没有正在进行的团，开团后再来' : '海报没生成出来，请稍后再试'))
        // 出错时后端回的是 JSON 不是图片：用 getImageInfo 判断
        uni.getImageInfo({
          src: r.tempFilePath,
          success: () => resolve(r.tempFilePath),
          fail: () => reject(new Error(token.get() ? '海报没生成出来，请稍后再试' : '请先登录')),
        })
      },
      fail: () => reject(new Error('网络不太好，请重试')),
    })
  })
}

// ---------------- 短信验证码 ----------------
export const sendSmsCode = (phone: string) => post<{ interval: number; devCode?: string }>('/sms/send', { phone })

// ---------------- 地图导航 ----------------
/** 打开微信内置地图（可以再跳高德 / 百度 / 腾讯地图导航）；没标位置或打不开时复制地址 */
export function openMap(p: { lat?: any; lng?: any; name?: string; address?: string }) {
  const copy = () => {
    if (!p.address) return toast('这个提货点还没标位置')
    uni.setClipboardData({ data: p.address, success: () => toast('地址已复制，可以粘贴到地图 App 里搜'), fail: () => toast(p.address!) })
  }
  const lat = Number(p.lat), lng = Number(p.lng)
  if (!lat || !lng) return copy()
  uni.openLocation({ latitude: lat, longitude: lng, name: p.name || '', address: p.address || '', scale: 17, fail: copy })
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
