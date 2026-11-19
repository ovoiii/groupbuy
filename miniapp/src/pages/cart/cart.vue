<route lang="json5">
{ style: { navigationBarTitleText: '购物车' } }
</route>
<!-- 购物车：提货点和截单时间在最上面；勾选、加减；实时算满减还差多少（以后端预览为准） -->
<template>
  <view>
    <view v-if="!lines.length && loaded" class="empty">
      <view class="bx"><wd-icon name="cart" size="44px" color="#078149" /></view>
      <view class="h">购物车是空的</view>
      <view class="p">{{ period ? `${dayTime(period.cutoffTime)} 截单，${dayTime(period.pickupTime)} 到小区自提` : '本期团已截单，明天再来' }}</view>
      <button class="btn pri" @tap="goHome">去挑今日团品</button>
    </view>
    <template v-else-if="lines.length">
      <view class="pt">
        <view class="l1"><wd-icon name="location" size="18px" color="#078149" /><text class="nm">{{ point ? point.name : '还没选提货点' }}</text><view class="chg" @tap="goPoints">更换<wd-icon name="arrow-right" size="12px" /></view></view>
        <view v-if="period" class="row"><Pickup :time="period.pickupTime" /><Cutoff :time="period.cutoffTime" label="截单前可改" /></view>
      </view>
      <view class="gb-card">
        <view v-for="l in lines" :key="l.id" class="item" :class="{ off: l.gone }">
          <view class="ck" :class="{ on: !off[l.id] && !l.gone }" @tap="toggle(l)"><wd-icon v-if="!off[l.id] && !l.gone" name="check" size="14px" color="#fff" /></view>
          <image :src="img(l.image)" mode="aspectFill" class="pic" @tap="openGoods(l)" />
          <view class="m">
            <view class="nm">{{ l.name }}</view>
            <view class="sp">{{ l.spec }}</view>
            <view v-if="l.gone" class="warn">已下架或已抢光</view>
            <view v-else-if="l.limitQty && l.qty + (l.bought || 0) > l.limitQty" class="warn" style="color: #e12a24">每人限购 {{ l.limitQty }} 份，你已买 {{ l.bought }} 份，请减少数量</view>
            <view v-else-if="l.left <= 20" class="warn">仅剩 {{ l.left }} 份，尽快下单</view>
            <view class="bt"><Price :value="l.price" :size="17" /><Stepper :value="l.qty" :max="l.limitQty ? Math.min(l.left, l.limitQty - (l.bought || 0)) : l.left" @change="(d) => change(l, d)" @limit="toast(l.limitQty ? `每人限购 ${l.limitQty} 份${l.bought ? '，你已买了 ' + l.bought + ' 份' : ''}` : '库存不够了')" /></view>
          </view>
        </view>
        <view v-if="pv" class="cps"><wd-icon name="discount" size="16px" color="#e12a24" />
          <text v-if="pv.couponName">已用「{{ pv.couponName }}」</text>
          <text v-else-if="lack">再买 ¥{{ lack.lack }} 可用「{{ lack.name }}」</text>
          <text v-else class="muted">没有可用的优惠券</text>
          <text v-if="pv.discount > 0" class="d">−¥{{ pv.discount }}</text>
          <text v-else-if="lack" class="go" @tap="goHome">去凑单</text>
        </view>
      </view>
      <view class="gb-tip">截单前可以在「订单」里取消，钱原路退回</view>
      <view class="actbar-space" />
      <view class="actbar">
        <view class="all" @tap="toggleAll"><view class="ck" :class="{ on: allOn }"><wd-icon v-if="allOn" name="check" size="14px" color="#fff" /></view>全选</view>
        <view class="sum" style="text-align: right">合计 <Price :value="pv ? pv.pay : 0" :size="22" /><text class="sub">{{ pv && pv.discount > 0 ? `已优惠 ¥${pv.discount} · ` : '' }}共 {{ selQty }} 件</text></view>
        <button class="btn cta" :disabled="!selQty || !period" @tap="checkout">去结算</button>
      </view>
    </template>
  </view>
</template>
<script lang="ts" setup>
import { api, img, cart, pointStore, dayTime, toast, token, toLogin } from '@/gb/util'
import Price from '@/gb/Price.vue'
import Stepper from '@/gb/Stepper.vue'
import Pickup from '@/gb/Pickup.vue'
import Cutoff from '@/gb/Cutoff.vue'

const goods = ref<Record<string, any>>({})
const period = ref<any>(null)
const point = ref<any>(null)
const off = ref<Record<string, boolean>>({})
const pv = ref<any>(null)
const loaded = ref(false)
const qtys = ref(cart.get())

const lines = computed(() => Object.entries(qtys.value).map(([id, qty]) => {
  const g = goods.value[id]
  return g ? { ...g, qty, gone: g.left <= 0 } :{ id, qty, name: '已下架的团品', spec: '', price: 0, left: 0, gone: true }
}))
const sel = computed(() => lines.value.filter((l) => !off.value[l.id] && !l.gone))
const selQty = computed(() => sel.value.reduce((s, l) => s + l.qty, 0))
const allOn = computed(() => lines.value.length > 0 && !lines.value.some((l) => off.value[l.id]))
const lack = computed(() => (pv.value?.coupons || []).filter((c) => !c.usable && c.lack > 0).sort((a, b) => a.lack - b.lack)[0])

async function load() {
  if (!token.get()) return toLogin()
  const h = await api('/home')
  period.value = h.period
  goods.value = Object.fromEntries(h.goods.map((g) => [g.id, g]))
  point.value = pointStore.get()
  const c = cart.get()
  Object.keys(c).forEach((k) => !goods.value[k] && delete c[k])
  cart.set(c)
  qtys.value = c
  loaded.value = true
  preview()
}
let timer: any
function preview() {
  clearTimeout(timer)
  timer = setTimeout(async () => {
    if (!sel.value.length || !period.value) return (pv.value = null)
    pv.value = await api('/order/preview', { items: sel.value.map((l) => ({ periodGoodsId: l.id, qty: l.qty })) }, 'POST', { silent: true }).catch(() => null)
  }, 250)
}
onShow(() => { load().catch(() => {}); cart.badge() })

function change(l, d) {
  if (l.qty === 1 && d < 0) {
    return uni.showModal({ title: '', content: `把「${l.name}」移出购物车？`, success: (r) => { if (r.confirm) { cart.add(l.id, -1); qtys.value = cart.get(); preview() } } })
  }
  cart.add(l.id, d)
  qtys.value = cart.get()
  preview()
}
function toggle(l) { off.value = { ...off.value, [l.id]: !off.value[l.id] }; preview() }
function toggleAll() {
  const on = allOn.value
  off.value = Object.fromEntries(lines.value.map((l) => [l.id, on]))
  preview()
}
function checkout() {
  if (!point.value) return goPoints()
  uni.setStorageSync('gb-checkout', sel.value.map((l) => ({ periodGoodsId: l.id, qty: l.qty })))
  uni.navigateTo({ url: '/pages/checkout/checkout' })
}
const goHome = () => uni.switchTab({ url: '/pages/index/index' })
const goPoints = () => uni.navigateTo({ url: '/pages/points/points' })
const openGoods = (l) => !l.gone && uni.navigateTo({ url: `/pages/goods/goods?id=${l.id}` })
</script>
<style lang="scss" scoped>
.pt { margin: 12px; padding: 12px 14px; border-radius: 12px; background: #fff; display: flex; flex-direction: column; gap: 8px; }
.l1 { display: flex; align-items: center; gap: 6px; }
.l1 .nm { font-weight: 600; }
.chg { margin-left: auto; font-size: 13px; color: $text-3; display: flex; align-items: center; min-height: 32px; }
.row { display: flex; gap: 8px; flex-wrap: wrap; }
.item { display: flex; gap: 10px; align-items: center; padding: 12px 14px; }
.item + .item { border-top: 1px solid $line; }
.item.off { opacity: 0.5; }
.pic { width: 80px; height: 80px; border-radius: 8px; flex: none; background: $sunken; }
.m { flex: 1; min-width: 0; }
.nm { font-size: 15px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.sp { font-size: 12px; color: $text-3; }
.warn { font-size: 12px; color: $mark; margin-top: 2px; }
.bt { display: flex; align-items: center; justify-content: space-between; margin-top: 8px; }
.cps { display: flex; align-items: center; gap: 8px; padding: 12px 14px; font-size: 14px; border-top: 1px solid $line; }
.cps .d { margin-left: auto; color: $price; font-weight: 700; }
.cps .go { margin-left: auto; font-size: 13px; color: $primary; }
.all { display: flex; align-items: center; gap: 8px; font-size: 14px; color: $text-2; min-height: 44px; }
</style>
