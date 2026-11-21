/*
 * 海报上小程序码带的 scene（最多 32 个字符），和后端 GbPosterService.goodsScene / homeScene 对应：
 *   商品：g.<团品 id 36 进制>.<提货点 id 36 进制>
 *   首页：i.<提货点 id 36 进制>
 * id 是 19 位数字，超过 JS Number 的精度，这里按字符串做进制转换（不用 BigInt，老机型也能跑）。
 * 纯函数，不依赖 uni，tests/unit 里用 node 直接测。
 */

export interface SceneInfo {
  type: 'goods' | 'home'
  id?: string
  pt?: string
}

const DIGITS = '0123456789abcdefghijklmnopqrstuvwxyz'

/** 36 进制字符串 → 10 进制字符串；不合法返回 null */
export function b36ToDec(s: string): string | null {
  if (!s || !/^[0-9a-z]+$/i.test(s)) return null
  let dec: number[] = [0] // 低位在前
  for (const ch of s.toLowerCase()) {
    let carry = DIGITS.indexOf(ch)
    for (let i = 0; i < dec.length; i++) {
      const v = dec[i] * 36 + carry
      dec[i] = v % 10
      carry = Math.floor(v / 10)
    }
    while (carry > 0) {
      dec.push(carry % 10)
      carry = Math.floor(carry / 10)
    }
  }
  while (dec.length > 1 && dec[dec.length - 1] === 0) dec.pop()
  return dec.reverse().join('')
}

/** 10 进制字符串 → 36 进制（生成测试数据和自检用） */
export function decToB36(s: string): string | null {
  if (!/^\d+$/.test(s)) return null
  let num = s.replace(/^0+(?=\d)/, '').split('').map(Number)
  if (num.length === 1 && num[0] === 0) return '0'
  let out = ''
  while (num.length && !(num.length === 1 && num[0] === 0)) {
    let rem = 0
    const q: number[] = []
    for (const d of num) {
      const v = rem * 10 + d
      const qd = Math.floor(v / 36)
      rem = v % 36
      if (q.length || qd) q.push(qd)
    }
    out = DIGITS[rem] + out
    num = q.length ? q : [0]
  }
  return out
}

/** 解析 scene（页面 onLoad 拿到的是 URL 编码过的，要先 decodeURIComponent）；不认识返回 null */
export function parseScene(raw?: string): SceneInfo | null {
  if (!raw) return null
  let s = raw
  try {
    s = decodeURIComponent(raw)
  } catch (e) {}
  const a = s.trim().split('.')
  if (a[0] === 'g' && a.length >= 2) {
    const id = b36ToDec(a[1])
    if (!id) return null
    const pt = a.length >= 3 ? b36ToDec(a[2]) : null
    return pt ? { type: 'goods', id, pt } : { type: 'goods', id }
  }
  if (a[0] === 'i') {
    const pt = a.length >= 2 ? b36ToDec(a[1]) : null
    if (a.length >= 2 && !pt) return null
    return pt ? { type: 'home', pt } : { type: 'home' }
  }
  return null
}
