<route lang="json5">
{ style: { navigationBarTitleText: '团长工作台', navigationBarBackgroundColor: '#13221A', navigationBarTextStyle: 'white', enablePullDownRefresh: true } }
</route>
<!-- 团长工作台：今天已取 / 待取进度、扫码核销、我的佣金、到货签收和短少登记、按手机尾号找订单、超时提醒、本期预售 -->
<template>
  <view v-if="d">
    <view class="top">
      <view class="pt"><wd-icon name="location" size="15px" color="#b3c0b8" />{{ d.point.name }} · 团长 {{ d.point.leaderName }}</view>
      <template v-if="cur">
        <view class="h2">{{ cur.period.name }} · {{ dayTime(cur.period.pickupTime) }} 起提货</view>
        <view class="big"><text class="b">{{ cur.picked }}</text><text>/ {{ cur.total }} 单已取</text></view>
        <view class="prog"><view class="i" :style="{ width: (cur.total ? (cur.picked / cur.total) * 100 : 0) + '%' }" /></view>
        <view class="sub"><text>还有 {{ cur.waiting }} 单待取</text><text>{{ dayTime(cur.period.pickupEndTime) }} 提货截止</text></view>
      </template>
      <view v-else class="h2">现在没有要提货的团期</view>
    </view>
    <view class="scan">
      <view class="a1" @tap="scan"><wd-icon name="scan" size="28px" color="#fff" />扫码核销</view>
      <view class="a2" @tap="go('/pages/leader/verify')"><wd-icon name="edit" size="26px" color="#1d231f" />输入提货码</view>
    </view>

    <view v-if="d.income" class="gb-card inc" @tap="go('/pages/leader/income')">
      <view class="ic"><wd-icon name="wallet" size="20px" color="#a06500" /></view>
      <view style="flex: 1; min-width: 0"><view class="b">我的佣金</view><view class="s">可提现 <text class="v">¥{{ Number(d.income.balance).toFixed(2) }}</text> · 待结算 ¥{{ Number(d.income.estimate).toFixed(2) }}</view></view>
      <view class="go">提现<wd-icon name="arrow-right" size="12px" /></view>
    </view>

    <view class="gb-card shr">
      <view class="ic"><wd-icon name="share" size="20px" color="#078149" /></view>
      <view style="flex: 1; min-width: 0">
        <view class="b">分享本期团到小区群</view>
        <view class="s">邻居点进来自动选你的提货点{{ d.newcomers ? ` · 已带来 ${d.newcomers} 位新邻居` : '' }}</view>
      </view>
      <view class="shr-btns">
        <button class="btn sm pri" open-type="share">发到群</button>
        <button class="btn sm line" @tap="go('/pages/poster/poster?type=home')">生成海报</button>
      </view>
    </view>

    <view v-for="b in d.batches" :key="b.delivery.id" class="gb-card">
      <view class="recv">
        <view class="ic"><wd-icon name="bags" size="20px" color="#078149" /></view>
        <view style="flex: 1; min-width: 0">
          <view class="b">{{ b.period.name }} · {{ DS[b.delivery.status] }}</view>
          <view class="s">{{ b.delivery.status === 'signed' ? `${md(b.delivery.signTime)} 签收 ${b.delivery.boxes || '-'} 箱，${b.delivery.itemCount} 件` : b.delivery.status === 'shipping' ? `${md(b.delivery.shipTime)} 发车，司机 ${b.delivery.driver || ''}` : '仓库分拣中，发车后会通知你' }}</view>
        </view>
        <button v-if="b.delivery.status === 'shipping'" class="btn sm pri" @tap="openSign(b)">到货签收</button>
      </view>
      <view v-if="b.delivery.shortNote" class="short"><wd-icon name="warning" size="14px" color="#e12a24" /> 签收登记：{{ b.delivery.shortNote }}，平台会给顾客处理</view>
    </view>

    <view class="gb-card">
      <view class="gb-card-hd">待提货<text class="more">{{ orders.length }} 单</text></view>
      <view class="srch"><wd-icon name="search" size="16px" color="#6b716d" /><input v-model="kw" type="number" maxlength="6" placeholder="手机尾号 4 位 / 提货码" @input="search" /></view>
      <view v-for="o in orders" :key="o.id" class="po">
        <text class="tail">{{ o.phoneTail }}</text>
        <view style="flex: 1; min-width: 0"><view class="b">{{ o.buyerName }}<text v-if="o.late" class="late">超 24 小时未取</text><text v-if="o.afterStatus === 'applying'" class="late">售后中</text></view><view class="s">{{ o.items.map((i) => i.goodsName + ' ×' + i.qty).join('、') }}</view></view>
        <button v-if="o.afterStatus === 'applying'" class="btn sm line dis">售后中</button>
        <button v-else-if="o.late" class="btn sm line" :class="{ dis: o.reminded }" @tap="remind(o)">{{ o.reminded ? '已提醒' : '提醒' }}</button>
        <button v-else class="btn sm pri" @tap="give(o)">交货</button>
      </view>
      <view v-if="!orders.length" class="gb-tip" style="padding: 24px">{{ kw ? '没有找到这个尾号的待提货订单' : '没有待提货的订单' }}</view>
    </view>

    <view v-if="d.presale" class="gb-card">
      <view class="gb-card-hd">本期预售 · {{ d.presale.period.name }}<view class="more"><Cutoff :time="d.presale.period.cutoffTime" label="截单" /></view></view>
      <view class="presale"><view><text class="b">{{ d.presale.orders }}</text><text>本点订单</text></view><view><text class="b">{{ d.presale.qty }}</text><text>商品件数</text></view><view><text class="b">¥{{ Math.round(d.presale.gmv) }}</text><text>销售额</text></view></view>
    </view>

    <view v-if="signOpen" class="mask" @tap="signOpen = false" />
    <view class="sheet" :class="{ on: signOpen }">
      <view class="sh">到货签收</view>
      <view class="muted" style="font-size: 13px; margin-bottom: 12px">点清箱数和商品。签收后顾客会收到「已到货」通知，可以来取了。</view>
      <view class="lbl">短少 / 破损登记（没有就不填）</view>
      <input v-model="shortNote" class="ipt" placeholder="如：黑猪五花肉 少 1 盒" />
      <button class="btn pri block" @tap="doSign">确认签收</button>
    </view>
  </view>
</template>
<script lang="ts" setup>
import { api, post, dayTime, md, toast, subscribe } from '@/gb/util'
import Cutoff from '@/gb/Cutoff.vue'
const DS = { sorting: '仓库分拣中', shipping: '配送中，马上到', signed: '已签收' }
const d = ref<any>(null)
const orders = ref<any[]>([])
const kw = ref('')
const signOpen = ref(false)
const shortNote = ref('')
let signing: any = null
const cur = computed(() => d.value?.batches.find((b) => b.delivery.status === 'signed' && b.waiting > 0) || d.value?.batches[0])

async function load() {
  d.value = await api('/leader/dashboard')
  await search()
}
let t: any
function search() {
  clearTimeout(t)
  return new Promise<void>((resolve) => { t = setTimeout(async () => { orders.value = await api('/leader/orders', { keyword: kw.value }); resolve() }, 200) })
}
onShow(() => load().catch(() => setTimeout(() => uni.navigateBack(), 1200)))
// 团长分享：落地到首页，带上自己的提货点
function shareTitle() {
  const p = d.value?.point
  const c = d.value?.presale?.period?.cutoffTime
  return `${p ? p.name + '团购' : '邻里鲜团购'}开团啦${c ? '，' + dayTime(c) + ' 截单' : ''}，明天到店自提`
}
onShareAppMessage(() => ({ title: shareTitle(), path: `/pages/index/index?pt=${d.value?.point?.id || ''}` }))
onPullDownRefresh(() => load().finally(() => uni.stopPullDownRefresh()))
const go = (url: string) => uni.navigateTo({ url })
function scan() {
  uni.scanCode({
    onlyFromCamera: false,
    success: (r) => { const code = String(r.result).replace(/^GB:/, ''); go(`/pages/leader/verify?code=${code}`) },
    fail: () => {},
  })
}
const give = (o) => go(`/pages/leader/verify?orderId=${o.id}`)
async function remind(o) {
  if (o.reminded) return
  await post('/leader/remind', { orderId: o.id })
  o.reminded = true
  toast('已给顾客发取货提醒')
}
function openSign(b) { signing = b; shortNote.value = ''; signOpen.value = true }
async function doSign() {
  await subscribe(['ship']) // 订阅下一次发车通知
  await post('/leader/sign', { deliveryId: signing.delivery.id, shortNote: shortNote.value })
  signOpen.value = false
  toast('已签收')
  load()
}
</script>
<style lang="scss" scoped>
.top { background: $dark; color: #fff; padding: 8px 16px 20px; }
.pt { display: flex; align-items: center; gap: 6px; font-size: 13px; opacity: 0.8; }
.h2 { font-size: 15px; font-weight: 600; margin-top: 14px; opacity: 0.85; }
.big { display: flex; align-items: baseline; gap: 6px; margin-top: 4px; font-size: 15px; }
.big .b { font-size: 40px; font-weight: 700; line-height: 1.1; }
.prog { height: 6px; border-radius: 3px; background: rgba(255, 255, 255, 0.15); margin-top: 12px; overflow: hidden; }
.prog .i { height: 100%; background: $mark-bright; border-radius: 3px; }
.sub { display: flex; justify-content: space-between; font-size: 12px; opacity: 0.75; margin-top: 6px; }
.scan { margin: 14px 12px 12px; display: flex; gap: 10px; }
.scan .a1, .scan .a2 { flex: 1; height: 76px; border-radius: 12px; display: flex; align-items: center; justify-content: center; gap: 10px; font-size: 17px; font-weight: 700; }
.a1 { background: $primary; color: #fff; }
.a2 { background: #fff; color: $text; }
.inc { display: flex; gap: 12px; align-items: center; padding: 14px; }
.inc .ic { width: 40px; height: 40px; border-radius: 10px; background: $mark-soft; display: flex; align-items: center; justify-content: center; flex: none; }
.inc .b { font-size: 15px; font-weight: 600; }
.inc .s { font-size: 13px; color: $text-2; }
.inc .v { color: $mark; font-weight: 700; }
.inc .go { display: flex; align-items: center; gap: 2px; font-size: 14px; color: $text-3; flex: none; }
.shr { display: flex; gap: 12px; align-items: center; padding: 14px; }
.shr .ic { width: 40px; height: 40px; border-radius: 10px; background: $primary-soft; display: flex; align-items: center; justify-content: center; flex: none; }
.shr .b { font-size: 15px; font-weight: 600; }
.shr .s { font-size: 13px; color: $text-2; }
.shr-btns { display: flex; flex-direction: column; gap: 6px; flex: none; }
.shr-btns .btn { margin: 0; }
.recv { display: flex; gap: 12px; align-items: center; padding: 14px; }
.recv .ic { width: 40px; height: 40px; border-radius: 10px; background: $primary-soft; display: flex; align-items: center; justify-content: center; flex: none; }
.recv .b { font-size: 15px; font-weight: 600; }
.recv .s { font-size: 13px; color: $text-2; }
.short { margin: -4px 14px 14px; padding: 8px 10px; font-size: 13px; color: $price; background: $price-soft; border-radius: 8px; }
.srch { display: flex; align-items: center; gap: 8px; margin: 12px 14px 4px; height: 40px; border-radius: 20px; background: $bg; padding: 0 14px; }
.srch input { flex: 1; font-size: 16px; }
.po { display: flex; gap: 10px; align-items: center; padding: 12px 14px; }
.po + .po { border-top: 1px solid $line; }
.tail { width: 52px; height: 44px; line-height: 44px; text-align: center; border-radius: 8px; background: $bg; font: 700 17px Consolas, monospace; flex: none; }
.po .b { font-size: 15px; font-weight: 600; }
.po .s { font-size: 13px; color: $text-2; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.late { color: $mark; font-size: 12px; font-weight: 600; margin-left: 6px; }
.presale { display: flex; text-align: center; padding: 14px 0; }
.presale > view { flex: 1; display: flex; flex-direction: column; font-size: 12px; color: $text-3; }
.presale .b { font-size: 18px; font-weight: 700; color: $text; }
.mask { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.45); z-index: 30; }
.sheet { position: fixed; left: 0; right: 0; bottom: 0; z-index: 31; background: #fff; border-radius: 16px 16px 0 0; padding: 18px 16px calc(16px + env(safe-area-inset-bottom)); transform: translateY(100%); transition: transform 0.25s; }
.sheet.on { transform: none; }
.sh { font-size: 17px; font-weight: 700; text-align: center; margin-bottom: 10px; }
.lbl { font-size: 13px; color: $text-3; margin-bottom: 6px; }
.ipt { height: 48px; border-radius: 12px; background: $bg; padding: 0 14px; font-size: 16px; margin-bottom: 14px; }
</style>
