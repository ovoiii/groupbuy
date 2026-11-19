<route lang="json5">
{ style: { navigationBarTitleText: '确认订单' } }
</route>
<!-- 确认订单：到哪自提、提货人、优惠券（默认最划算）、留言；下单后支付（模拟支付 / 微信支付） -->
<template>
  <view v-if="pv">
    <view class="ptc">
      <view class="tt">到这里自提</view>
      <view class="h"><wd-icon name="location" size="18px" color="#078149" />{{ point.name }}</view>
      <view class="addr">{{ point.address }}</view>
      <view style="margin-top: 10px"><Pickup :time="pv.period.pickupTime" /></view>
      <view class="who"><text class="muted">提货人</text>{{ me.nickname }}<text class="muted" style="margin-left: 8px">手机</text>{{ me.phone }}</view>
    </view>
    <view class="gb-card">
      <view class="thumbs"><image v-for="l in pv.lines.slice(0, 4)" :key="l.periodGoodsId" :src="img(l.image)" mode="aspectFill" /><text class="n">共 {{ qty }} 件</text></view>
      <view class="gb-row"><text class="k">商品金额</text><text class="v">¥{{ pv.goodsAmount }}</text></view>
      <view class="gb-row" @tap="couponOpen = true"><text class="k">优惠券</text>
        <view class="v" :class="{ red: pv.discount > 0 }">{{ pv.couponName ? `${pv.couponName}　−¥${pv.discount}` : usableCount ? '不使用' : '暂无可用' }}<wd-icon name="arrow-right" size="14px" color="#6b716d" /></view></view>
      <view class="gb-row"><text class="k">配送</text><text class="v">到提货点自提，免运费</text></view>
    </view>
    <view class="gb-card"><textarea v-model="remark" class="ta" maxlength="60" placeholder="给团长留言（选填），如：晚上 8 点后来取" /></view>
    <view class="gb-tip">截单前可以在「订单」里取消，钱原路退回</view>
    <view class="actbar-space" />
    <view class="actbar">
      <view class="sum">实付 <Price :value="pv.pay" :size="22" /><text v-if="pv.discount > 0" class="sub">已优惠 ¥{{ pv.discount }}</text></view>
      <button class="btn cta" style="width: 150px" :loading="paying" @tap="submit">{{ cfg.payMock ? '微信支付' : '提交订单' }}</button>
    </view>

    <view v-if="couponOpen" class="mask" @tap="couponOpen = false" />
    <view class="sheet" :class="{ on: couponOpen }">
      <view class="sh">选择优惠券</view>
      <view v-for="c in pv.coupons" :key="c.userCouponId" class="opt" :class="{ dis: !c.usable }" @tap="c.usable && pick(c.userCouponId)">
        <view class="m"><view class="b">{{ c.name }}</view><view class="muted" style="font-size: 12px">{{ c.usable ? `可减 ¥${c.discount}` : c.lack ? `还差 ¥${c.lack}` : '这一单用不了' }}</view></view>
        <view class="ck" :class="{ on: c.userCouponId === pv.userCouponId }"><wd-icon v-if="c.userCouponId === pv.userCouponId" name="check" size="14px" color="#fff" /></view>
      </view>
      <view class="opt" @tap="pick('none')"><view class="m"><view class="b">不使用优惠券</view></view><view class="ck" :class="{ on: !pv.userCouponId }"><wd-icon v-if="!pv.userCouponId" name="check" size="14px" color="#fff" /></view></view>
      <view class="muted" style="font-size: 12px; padding: 8px 4px 0">已自动选好最划算的一张；一单只能用一张</view>
    </view>
  </view>
</template>
<script lang="ts" setup>
import { api, post, img, cart, pointStore, toast, loadConfig, subscribe } from '@/gb/util'
import Price from '@/gb/Price.vue'
import Pickup from '@/gb/Pickup.vue'

const items = uni.getStorageSync('gb-checkout') || []
const point = pointStore.get()
const pv = ref<any>(null)
const me = ref<any>({})
const cfg = ref<any>({ payMock: true })
const remark = ref('')
const couponOpen = ref(false)
const paying = ref(false)
let chosen: string | undefined
const qty = computed(() => pv.value.lines.reduce((s, l) => s + l.qty, 0))
const usableCount = computed(() => pv.value.coupons.filter((c) => c.usable).length)

async function preview() { pv.value = await api('/order/preview', { items, userCouponId: chosen }, 'POST') }
onLoad(async () => {
  ;[me.value, cfg.value] = await Promise.all([api('/me'), loadConfig()])
  preview().catch(() => setTimeout(() => uni.navigateBack(), 1500))
})
function pick(id: string) { chosen = id; couponOpen.value = false; preview() }

async function submit() {
  await subscribe(['arrived', 'pickup'])
  paying.value = true
  try {
    const o = await post('/order/create', { pointId: point.id, items, userCouponId: chosen || pv.value.userCouponId || 'none', remark: remark.value })
    cart.clear(items.map((i) => i.periodGoodsId))
    if (!o.payMock) return toast('微信支付还没接入，请联系管理员')
    await pay(o.id)
  } finally {
    paying.value = false
  }
}
async function pay(id: string) {
  // 接入微信支付后：后端返回支付参数，这里调 uni.requestPayment，成功后再查订单
  await post('/order/pay', { id }).catch(() => {
    uni.redirectTo({ url: `/pages/order/order?id=${id}` })
    throw new Error('支付失败')
  })
  uni.redirectTo({ url: `/pages/order/order?id=${id}&paid=1` })
}
</script>
<style lang="scss" scoped>
.ptc { margin: 12px; padding: 16px 14px; border-radius: 12px; background: #fff; }
.tt { font-size: 12px; color: $text-3; }
.h { font-size: 18px; font-weight: 700; margin: 4px 0 2px; display: flex; align-items: center; gap: 6px; }
.addr { font-size: 13px; color: $text-2; }
.who { display: flex; gap: 8px; margin-top: 12px; padding-top: 12px; border-top: 1px solid $line; font-size: 14px; }
.thumbs { display: flex; gap: 8px; padding: 12px 14px; align-items: center; }
.thumbs image { width: 56px; height: 56px; border-radius: 8px; background: $sunken; }
.thumbs .n { margin-left: auto; font-size: 13px; color: $text-3; }
.v.red { color: $price; font-weight: 600; }
.ta { width: 100%; height: 64px; padding: 12px 14px; font-size: 16px; box-sizing: border-box; }
.mask { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.45); z-index: 30; }
.sheet { position: fixed; left: 0; right: 0; bottom: 0; z-index: 31; background: #fff; border-radius: 16px 16px 0 0; padding: 16px 16px calc(16px + env(safe-area-inset-bottom)); transform: translateY(100%); transition: transform 0.25s; }
.sheet.on { transform: none; }
.sh { font-size: 17px; font-weight: 700; text-align: center; margin-bottom: 10px; }
.opt { display: flex; align-items: center; gap: 10px; min-height: 56px; padding: 0 4px; border-bottom: 1px solid $line; }
.opt.dis { opacity: 0.45; }
.opt .m { flex: 1; }
.opt .b { font-weight: 600; }
</style>
