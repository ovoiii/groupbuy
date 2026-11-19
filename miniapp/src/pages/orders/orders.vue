<route lang="json5">
{ style: { navigationBarTitleText: '我的订单', enablePullDownRefresh: true } }
</route>
<!-- 我的订单：待付款 / 待截单 / 待提货 / 售后；截单前可以取消 -->
<template>
  <view>
    <view class="tabs">
      <view v-for="t in TABS" :key="t[0]" class="tab" :class="{ on: t[0] === st }" @tap="switchTab(t[0])">{{ t[1] }}<text v-if="t[0] === 'arrived' && arrivedN" class="n">{{ arrivedN }}</text></view>
    </view>
    <view v-for="o in list" :key="o.id" class="oc">
      <view class="hd">{{ o.periodName }} · {{ md(o.createTime) }}<text class="st" :class="stCls(o)">{{ stText(o) }}</text></view>
      <view class="bd" @tap="open(o)">
        <template v-if="o.items.length === 1"><image :src="img(o.items[0].image)" mode="aspectFill" /><text class="one">{{ o.items[0].goodsName }}</text></template>
        <template v-else><image v-for="it in o.items.slice(0, 3)" :key="it.id" :src="img(it.image)" mode="aspectFill" /></template>
        <view class="r"><text class="b">¥{{ o.payAmount }}</text>共 {{ o.qty }} 件</view>
      </view>
      <view v-if="['paid', 'shipping', 'arrived'].includes(o.status)" class="mk"><Pickup :time="o.pickupTime" :point="o.status === 'arrived' ? o.pointName : ''" /></view>
      <view class="ft">
        <template v-if="o.status === 'unpaid'"><button class="btn sm line" @tap="cancel(o)">取消订单</button><button class="btn sm cta" @tap="pay(o)">去支付</button></template>
        <template v-else-if="o.status === 'paid'"><button class="btn sm line" @tap="cancel(o)">取消订单</button><button class="btn sm line" @tap="open(o)">查看</button></template>
        <button v-else-if="o.status === 'arrived'" class="btn sm pri" @tap="open(o)">出示提货码</button>
        <template v-else-if="o.status === 'done'"><button v-if="o.afterStatus === 'none'" class="btn sm line" @tap="aftersale(o)">申请售后</button><button class="btn sm line" @tap="again(o)">再买一次</button></template>
        <button v-else class="btn sm line" @tap="open(o)">查看详情</button>
      </view>
    </view>
    <view v-if="loaded && !list.length" class="empty"><view class="bx"><wd-icon name="file" size="40px" color="#078149" /></view><view class="h">没有这类订单</view><view class="p">今天下单，明天到小区自提</view><button class="btn pri" @tap="goHome">去逛逛</button></view>
    <view v-if="list.length" class="gb-tip">只显示最近 50 单</view>
  </view>
</template>
<script lang="ts" setup>
import { api, post, img, md, cart, token, toLogin, toast, subscribe } from '@/gb/util'
import Pickup from '@/gb/Pickup.vue'
const TABS = [['', '全部'], ['unpaid', '待付款'], ['paid', '待截单'], ['arrived', '待提货'], ['refund', '售后']]
const st = ref('')
const list = ref<any[]>([])
const loaded = ref(false)
const arrivedN = ref(0)
const AFTER = { applying: '售后处理中', refunded: '已退款', rejected: '售后已驳回' }
const stText = (o) => (o.afterStatus !== 'none' ? AFTER[o.afterStatus] : o.status === 'unpaid' ? '待付款，15 分钟内有效' : { paid: '已付款 · 等待截单', shipping: '已截单 · 配送中', arrived: '已到货 · 待提货', done: '已提货', closed: o.closeReason || '已关闭' }[o.status])
const stCls = (o) => (o.afterStatus === 'applying' ? 'bad' : o.status === 'unpaid' ? 'warn' : ['paid', 'shipping', 'arrived'].includes(o.status) ? 'go' : '')

async function load() {
  if (!token.get()) return toLogin()
  list.value = await api('/order/list', { status: st.value })
  if (!st.value) arrivedN.value = list.value.filter((o) => o.status === 'arrived').length
  loaded.value = true
}
onLoad((q: any) => { if (q?.t) st.value = q.t })
onShow(() => {
  const t = uni.getStorageSync('gb-orders-tab')
  if (t !== '' && t != null) { st.value = t; uni.removeStorageSync('gb-orders-tab') }
  load().catch(() => {})
})
onPullDownRefresh(() => load().finally(() => uni.stopPullDownRefresh()))
function switchTab(t) { st.value = t; load() }
const open = (o) => uni.navigateTo({ url: `/pages/order/order?id=${o.id}` })
const aftersale = (o) => uni.navigateTo({ url: `/pages/aftersale/aftersale?id=${o.id}` })
const goHome = () => uni.switchTab({ url: '/pages/index/index' })
function cancel(o) {
  uni.showModal({ title: '取消这个订单？', content: o.status === 'paid' ? `会原路退回 ¥${o.payAmount}` : '', success: async (r) => { if (r.confirm) { await post('/order/cancel', { id: o.id }); toast('已取消'); load() } } })
}
async function pay(o) {
  await subscribe(['arrived', 'pickup'])
  await post('/order/pay', { id: o.id })
  uni.navigateTo({ url: `/pages/order/order?id=${o.id}&paid=1` })
}
function again(o) {
  // 只能加本期还在卖的同款商品
  api('/home').then((h) => {
    let n = 0
    o.items.forEach((it) => { const g = h.goods.find((x) => x.goodsId === it.goodsId && x.left > 0); if (g) { cart.add(g.id, 1); n++ } })
    toast(n ? `已把 ${n} 样加入购物车` : '这些商品本期没有团')
  })
}
</script>
<style lang="scss" scoped>
.tabs { position: sticky; top: 0; z-index: 5; display: flex; background: #fff; border-bottom: 1px solid $line; }
.tab { flex: 1; height: 44px; line-height: 44px; text-align: center; font-size: 15px; color: $text-2; position: relative; }
.tab.on { color: $text; font-weight: 700; }
.tab.on::after { content: ''; position: absolute; left: 50%; bottom: 4px; width: 20px; height: 3px; margin-left: -10px; border-radius: 2px; background: $primary; }
.tab .n { display: inline-block; vertical-align: 6px; margin-left: 1px; min-width: 16px; height: 16px; line-height: 16px; border-radius: 8px; background: $price; color: #fff; font-size: 11px; padding: 0 4px; font-weight: 700; box-sizing: border-box; }
.oc { margin: 12px 12px 0; background: #fff; border-radius: 12px; }
.hd { display: flex; align-items: center; padding: 12px 14px 0; font-size: 13px; color: $text-3; }
.st { margin-left: auto; font-size: 14px; font-weight: 600; color: $text-2; }
.st.go { color: $primary; }
.st.warn { color: $mark; }
.st.bad { color: $price; }
.bd { display: flex; gap: 8px; padding: 12px 14px; align-items: center; }
.bd image { width: 64px; height: 64px; border-radius: 8px; background: $sunken; flex: none; }
.one { flex: 1; min-width: 0; font-size: 15px; font-weight: 600; }
.r { margin-left: auto; text-align: right; font-size: 13px; color: $text-3; white-space: nowrap; }
.r .b { display: block; color: $text; font-size: 16px; font-weight: 600; }
.mk { padding: 0 14px; }
.ft { display: flex; justify-content: flex-end; gap: 10px; padding: 12px 14px 14px; }
</style>
