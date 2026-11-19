<route lang="json5">
{ style: { navigationBarTitleText: '订单详情' } }
</route>
<!-- 订单详情 / 提货码：到货后显示二维码 + 6 位数字；提货点地址、导航、联系团长；金额明细 -->
<template>
  <view v-if="o">
    <view class="top" :class="{ ok: o.status === 'arrived' }">
      <view class="h">{{ title }}</view>
      <view class="s">{{ o.periodName }} · 订单 {{ o.orderNo }}</view>
    </view>

    <view v-if="o.pickupCode && ['paid', 'shipping', 'arrived'].includes(o.status)" class="code">
      <image v-if="o.status === 'arrived'" :src="qr" class="qr" mode="aspectFit" />
      <view class="num" :class="{ dim: o.status !== 'arrived' }">{{ o.pickupCode }}</view>
      <view class="hint">{{ o.status === 'arrived' ? '给团长扫码，或报这 6 位数字' : '货到提货点后凭这个码取货，到货会发通知' }}</view>
      <view style="margin-top: 12px"><Pickup :time="o.pickupTime" /></view>
    </view>
    <view v-else-if="o.status === 'unpaid'" class="code">
      <view class="hint" style="margin-top: 0">请在 {{ md(o.payExpireTime) }} 前付款，超时自动取消</view>
      <button class="btn cta block" style="margin-top: 12px" @tap="pay">微信支付 ¥{{ o.payAmount }}</button>
    </view>

    <view class="gb-card">
      <view class="addr">
        <view style="flex: 1; min-width: 0"><view class="b">{{ o.pointName }}</view><view class="muted" style="font-size: 13px">{{ o.pointAddress }} · {{ o.openHours }}</view></view>
        <view class="circle" @tap="nav"><wd-icon name="location" size="20px" color="#078149" /></view>
        <view class="circle" @tap="call"><wd-icon name="phone" size="20px" color="#078149" /></view>
      </view>
    </view>

    <view class="gb-card">
      <view class="gb-card-hd">商品<text class="more">共 {{ o.qty }} 件</text></view>
      <view v-for="it in o.items" :key="it.id" class="it">
        <image :src="img(it.image)" mode="aspectFill" />
        <view class="m"><view class="nm">{{ it.goodsName }}<text v-if="it.newbie" class="tag ok" style="margin-left: 6px">新人 1 元</text></view><view class="sp">{{ it.spec }}</view></view>
        <view class="r">¥{{ it.amount }}<view class="muted">× {{ it.qty }}</view></view>
      </view>
      <view class="gb-row"><text class="k">商品金额</text><text class="v">¥{{ o.goodsAmount }}</text></view>
      <view v-if="o.discountAmount > 0" class="gb-row"><text class="k">{{ o.couponName }}</text><text class="v" style="color: #e12a24">−¥{{ o.discountAmount }}</text></view>
      <view class="gb-row"><text class="k">实付</text><text class="v"><text style="font-weight: 700">¥{{ o.payAmount }}</text></text></view>
      <view v-if="o.refundAmount > 0" class="gb-row"><text class="k">已退款</text><text class="v" style="color: #e12a24">¥{{ o.refundAmount }}</text></view>
      <view v-if="o.remark" class="gb-row"><text class="k">留言</text><text class="v">{{ o.remark }}</text></view>
    </view>

    <view v-if="o.aftersales && o.aftersales.length" class="gb-card">
      <view class="gb-card-hd">售后</view>
      <view v-for="a in o.aftersales" :key="a.id" class="as">
        <view><text class="tag" :class="{ warn: a.status === 'wait', ok: a.status === 'done' }">{{ { wait: '处理中', done: '已退款 ¥' + a.refundAmount, rej: '已驳回' }[a.status] }}</text>　{{ a.type }}</view>
        <view v-if="a.reply" class="muted" style="font-size: 13px; margin-top: 6px">客服回复：{{ a.reply }}</view>
        <view v-else-if="a.status === 'wait'" class="muted" style="font-size: 13px; margin-top: 6px">{{ md(a.deadline) }} 前处理完，超时自动退款</view>
      </view>
    </view>

    <view class="foot">
      <button v-if="['unpaid', 'paid'].includes(o.status)" class="btn sm line" @tap="cancel">取消订单</button>
      <button v-if="canAftersale" class="btn sm line" @tap="aftersale">申请售后</button>
    </view>
    <view class="gb-tip">提货后 24 小时内发现问题都可以申请售后</view>
  </view>
</template>
<script lang="ts" setup>
import qrcode from 'qrcode-generator'
import { api, post, img, md, toast, subscribe, openMap } from '@/gb/util'
import Pickup from '@/gb/Pickup.vue'

const o = ref<any>(null)
let id = ''
const title = computed(() => {
  const x = o.value
  if (x.afterStatus === 'applying') return '售后处理中'
  return { unpaid: '待付款', paid: '已付款，等待截单', shipping: '已截单，正在配送', arrived: '已到货，可以来取了', done: '已提货', closed: '订单已关闭' }[x.status]
})
const canAftersale = computed(() => ['arrived', 'done'].includes(o.value.status) && ['none', 'rejected'].includes(o.value.afterStatus))
const qr = computed(() => {
  const q = qrcode(0, 'M')
  q.addData(`GB:${o.value.pickupCode}`)
  q.make()
  return q.createDataURL(8, 2)
})
async function load() { o.value = await api('/order/detail', { id }) }
onLoad((q: any) => { id = q.id; if (q.paid) toast('支付成功') })
onShow(() => id && load())
async function pay() { await subscribe(['arrived', 'pickup']); await post('/order/pay', { id }); toast('支付成功'); load() }
function cancel() {
  uni.showModal({ title: '取消这个订单？', content: o.value.payTime ? `会原路退回 ¥${o.value.payAmount}` : '', success: async (r) => { if (r.confirm) { await post('/order/cancel', { id }); toast('已取消'); load() } } })
}
const aftersale = () => uni.navigateTo({ url: `/pages/aftersale/aftersale?id=${id}` })
const call = () => uni.makePhoneCall({ phoneNumber: o.value.leaderPhone, fail: () => {} })
const nav = () => openMap({ lat: o.value.pointLat, lng: o.value.pointLng, name: o.value.pointName, address: o.value.pointAddress })
</script>
<style lang="scss" scoped>
.top { background: $primary; color: #fff; padding: 16px 16px 64px; }
.top .h { font-size: 20px; font-weight: 700; }
.top .s { font-size: 13px; opacity: 0.85; margin-top: 4px; }
.code { margin: -52px 12px 12px; padding: 20px 16px 18px; border-radius: 14px; background: #fff; text-align: center; box-shadow: 0 6px 20px rgba(19, 34, 26, 0.08); }
.qr { width: 180px; height: 180px; display: block; margin: 4px auto 12px; }
.num { font: 700 32px/1 Consolas, monospace; letter-spacing: 6px; }
.num.dim { color: $text-3; font-size: 26px; }
.hint { font-size: 13px; color: $text-3; margin-top: 10px; }
.addr { display: flex; align-items: center; gap: 8px; padding: 14px; }
.addr .b { font-size: 16px; font-weight: 600; }
.circle { width: 44px; height: 44px; border-radius: 50%; background: $primary-soft; display: flex; align-items: center; justify-content: center; flex: none; }
.it { display: flex; gap: 10px; align-items: center; padding: 10px 14px; }
.it image { width: 56px; height: 56px; border-radius: 8px; background: $sunken; flex: none; }
.it .m { flex: 1; min-width: 0; }
.it .nm { font-size: 15px; font-weight: 600; }
.it .sp { font-size: 12px; color: $text-3; }
.it .r { text-align: right; font-size: 14px; }
.as { padding: 12px 14px; font-size: 14px; }
.foot { display: flex; justify-content: flex-end; gap: 10px; padding: 4px 12px 8px; }
</style>
