// 小程序 gb/scene.ts 的单测：node --test tests/unit（Node 22.6+ 能直接导入 .ts）
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { b36ToDec, decToB36, parseScene } from '../../miniapp/src/gb/scene.ts'

const ref36 = (dec) => BigInt(dec).toString(36)

test('19 位 id 和 36 进制互转，和 BigInt 结果一致', () => {
  const ids = ['2123232862147170305', '2123232862000369665', '9223372036854775807', '1', '0', '35', '36', '1000000000000000000']
  for (let i = 0; i < 200; i++) ids.push(String(BigInt(Math.floor(Math.random() * 2 ** 31)) * BigInt(Math.floor(Math.random() * 2 ** 31)) + BigInt(i)))
  for (const id of ids) {
    assert.equal(decToB36(id), ref36(id), id)
    assert.equal(b36ToDec(ref36(id)), id, id)
  }
})

test('商品码：g.<团品>.<提货点>', () => {
  const pg = '2123232862147170305', pt = '2123232862000369665'
  const s = `g.${ref36(pg)}.${ref36(pt)}`
  assert.ok(s.length <= 32)
  assert.deepEqual(parseScene(s), { type: 'goods', id: pg, pt })
  // onLoad 拿到的是 URL 编码过的
  assert.deepEqual(parseScene(encodeURIComponent(s)), { type: 'goods', id: pg, pt })
  assert.deepEqual(parseScene(`g.${ref36(pg)}`), { type: 'goods', id: pg })
})

test('首页码：i.<提货点> 或 i', () => {
  assert.deepEqual(parseScene('i.' + ref36('2123232862000369665')), { type: 'home', pt: '2123232862000369665' })
  assert.deepEqual(parseScene('i'), { type: 'home' })
})

test('不认识的 scene 返回 null', () => {
  for (const s of [undefined, '', 'x.1', 'g', 'g.!!', 'i.@@', '%E4%BD%A0']) assert.equal(parseScene(s), null, String(s))
  assert.equal(b36ToDec(''), null)
  assert.equal(b36ToDec('a-b'), null)
  assert.equal(decToB36('12a'), null)
})
