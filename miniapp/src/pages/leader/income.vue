<route lang="json5">
{ style: { navigationBarTitleText: '我的佣金', navigationBarBackgroundColor: '#13221A', navigationBarTextStyle: 'white', enablePullDownRefresh: true } }
</route>
<!-- 团长佣金：可提现、待结算、累计；提现入口；佣金明细 / 提现记录 / 售后扣款 -->
<template>
  <view v-if="d">
    <view class="top">
      <view class="lb">可提现（元）</view>
      <view class="big">{{ y(d.balance) }}</view>
      <view v-if="Number(d.balance) < 0" class="owe">有 ¥{{ y(-d.balance) }} 售后扣款还没抵完，之后入账的佣金会先补上</view>
      <button class="btn wd" :class="{ dis: !canWithdraw }" @tap="goWithdraw">提现</button>
      <view class="row3">
        <view><text class="b">{{ y(d.estimate) }}</text><text>待结算</text></view>
        <view><text class="b">{{ y(d.totalIncome) }}</text><text>累计佣金</text></view>
        <view><text class="b">{{ y(d.totalWithdrawn) }}</text><text>已提现</text></view>
      </view>
    </view>
    <view v-if="d.pending" class="pend" :class="{ go: d.pending.needConfirm }">
      <wd-icon :name="d.pending.needConfirm ? 'wallet' : 'time'" size="18px" :color="d.pending.needConfirm ? '#078149' : '#a06500'" />
      <view style="flex: 1; min-width: 0">
        <template v-if="d.pending.needConfirm">
          <view class="b">平台已打款 ¥{{ y(d.pending.amount) }}，待你确认收款</view>
          <view class="s">点「确认收款」，钱马上进你的微信零钱</view>
        </template>
        <template v-else>
          <view class="b">¥{{ y(d.pending.amount) }} {{ d.pending.status === 'paying' ? '打款中' : '提现审核中' }}</view>
          <view class="s">{{ d.pending.account }} · {{ md(d.pending.createTime) }} 提交{{ d.pending.status === 'paying' ? '，微信处理中' : '，1–3 个工作日到账' }}</view>
        </template>
        <view v-if="d.pending.transferFail" class="s fail">上次打款没成功：{{ d.pending.transferFail }}，平台会重新处理</view>
      </view>
      <button v-if="d.pending.needConfirm" class="btn sm pri" :loading="confirming" @tap="confirmRecv">确认收款</button>
    </view>
    <view class="gb-card">
      <view class="tabs"><view :class="{ on: tab === 'in' }" @tap="switchTab('in')">佣金明细</view><view :class="{ on: tab === 'out' }" @tap="switchTab('out')">提现记录</view><view v-if="Number(d.totalDeducted) > 0 || tab === 'ded'" :class="{ on: tab === 'ded' }" @tap="switchTab('ded')">售后扣款</view></view>
      <template v-if="tab === 'in'">
        <view v-for="c in list" :key="c.id" class="it">
          <view style="flex: 1; min-width: 0"><view class="b">{{ c.buyerName || '顾客' }}的订单</view><view class="s">商品 ¥{{ y(c.goodsAmount) }}{{ c.refunded ? `，退款后按 ¥${y(c.baseAmount)}` : '' }} × {{ pctText(c.rate) }} · {{ md(c.settleTime) }} 入账</view></view>
          <text class="plus">+{{ y(c.amount) }}</text>
        </view>
      </template>
      <template v-else-if="tab === 'ded'">
        <view v-for="x in list" :key="x.id" class="it">
          <view style="flex: 1; min-width: 0"><view class="b">{{ x.buyerName || '顾客' }}的订单 · {{ x.asType }}</view><view class="s">{{ x.reason }} · 退款 ¥{{ y(x.refundAmount) }} · {{ md(x.createTime) }}</view>
            <view v-if="x.status === 'revoked'" class="s back">已撤销，钱已退回：{{ x.revokeReason }}</view></view>
          <text class="minus" :class="{ x: x.status === 'revoked' }">-{{ y(x.amount) }}</text>
        </view>
      </template>
      <template v-else>
        <view v-for="w in list" :key="w.id" class="it">
          <view style="flex: 1; min-width: 0"><view class="b">提现到{{ w.account }}</view><view class="s">{{ md(w.createTime) }} 申请<text v-if="w.status === 'rej'" class="rej"> · 已驳回：{{ w.reply }}</text></view></view>
          <view class="r"><text class="minus" :class="{ x: w.status === 'rej' }">-{{ y(w.amount) }}</text><text class="st" :class="w.status">{{ WS[w.status] }}</text></view>
        </view>
      </template>
      <view v-if="!list.length && !loading" class="gb-tip" style="padding: 28px 16px">{{ tab === 'in' ? '还没有入账的佣金，顾客提货满 ' + d.settleHours + ' 小时后自动入账' : tab === 'ded' ? '没有售后扣款' : '还没有提现过' }}</view>
      <view v-if="more" class="gb-tip" @tap="loadMore">加载更多</view>
    </view>
    <view class="gb-tip">
      佣金比例 {{ pctText(d.rate) }}，按顾客买的商品金额算，优惠券由平台出不扣你的。<br />
      顾客提货满 {{ d.settleHours }} 小时、没有售后，佣金自动进「可提现」。退过款的订单按没退的部分算。<br />
      货在提货点丢了、放坏了、给错人这类责任在提货点的售后，平台会从可提现里扣，扣款明细里能看到原因。
    </view>
  </view>
</template>
<script lang="ts" setup>
import { api, post, md, toast } from '@/gb/util'
const WS = { wait: '审核中', paying: '打款中', paid: '已到账', rej: '已退回' }
const d = ref<any>(null)
const tab = ref<'in' | 'out' | 'ded'>('in')
const list = ref<any[]>([])
const page = ref(1)
const more = ref(false)
const loading = ref(false)
const y = (v) => Number(v || 0).toFixed(2)
const pctText = (r) => `${Math.round(Number(r || 0) * 1000) / 10}%`
const canWithdraw = computed(() => d.value && !d.value.pending && Number(d.value.balance) >= d.value.withdrawMin)

async function loadList(p = 1) {
  loading.value = true
  try {
    const r = await api('/leader/income/list', { type: tab.value, pageNo: p })
    list.value = p === 1 ? r.records : list.value.concat(r.records)
    page.value = p
    more.value = list.value.length < r.total
  } finally {
    loading.value = false
  }
}
async function load() {
  d.value = await api('/leader/income')
  await loadList(1)
}
onShow(() => load().catch(() => {}))
onPullDownRefresh(() => load().finally(() => uni.stopPullDownRefresh()))
const loadMore = () => loadList(page.value + 1)

/** 商家转账要团长自己点确认收款（wx.requestMerchantTransfer），确认后去后端查一下结果 */
const confirming = ref(false)
async function confirmRecv() {
  const id = d.value.pending.id
  confirming.value = true
  try {
    const p: any = await api('/leader/withdraw/confirm', { id })
    // #ifdef MP-WEIXIN
    // @ts-ignore 微信的 API，uni 没有包
    const w: any = wx
    if (!w.canIUse || !w.canIUse('requestMerchantTransfer')) {
      toast('微信版本太低，升级微信后再确认收款')
      return
    }
    await new Promise<void>((resolve) => {
      w.requestMerchantTransfer({
        mchId: p.mchId,
        appId: p.appId,
        package: p.package,
        success: async () => {
          await post('/leader/withdraw/check', { id }).catch(() => {})
          toast('已确认收款，钱会进你的微信零钱')
          resolve()
        },
        fail: (e: any) => {
          if (!/cancel/.test(e?.errMsg || '')) toast('确认收款没成功，请重试')
          resolve()
        },
      })
    })
    // #endif
    // #ifndef MP-WEIXIN
    toast('请在微信小程序里确认收款')
    // #endif
    await load()
  } finally {
    confirming.value = false
  }
}
function switchTab(t) {
  if (tab.value === t) return
  tab.value = t
  list.value = []
  loadList(1)
}
function goWithdraw() {
  if (d.value.pending) return uni.showToast({ title: '还有一笔在审核，到账后才能再提', icon: 'none' })
  if (Number(d.value.balance) < d.value.withdrawMin) return uni.showToast({ title: `满 ${d.value.withdrawMin} 元才能提现`, icon: 'none' })
  uni.navigateTo({ url: '/pages/leader/withdraw' })
}
</script>
<style lang="scss" scoped>
.top { background: $dark; color: #fff; padding: 16px 16px 18px; position: relative; }
.lb { font-size: 13px; opacity: 0.75; }
.big { font-size: 40px; font-weight: 700; line-height: 1.2; margin-top: 4px; }
.owe { font-size: 12px; color: $mark-bright; margin-top: 2px; padding-right: 110px; }
.s.back { color: $primary; }
.btn.wd { position: absolute; right: 16px; top: 34px; height: 38px; padding: 0 22px; border-radius: 19px; background: $mark-bright; color: $dark; font-size: 15px; }
.row3 { display: flex; margin-top: 16px; padding-top: 14px; border-top: 1px solid rgba(255, 255, 255, 0.12); }
.row3 > view { flex: 1; display: flex; flex-direction: column; font-size: 12px; opacity: 0.85; }
.row3 .b { font-size: 17px; font-weight: 700; }
.pend { margin: 12px 12px 0; padding: 12px 14px; border-radius: 12px; background: $mark-soft; display: flex; gap: 10px; align-items: center; }
.pend .b { font-weight: 700; color: $mark; }
.pend .s { font-size: 12px; color: $text-2; }
.pend.go { background: $primary-soft; }
.pend.go .b { color: #006134; }
.pend .fail { color: $price; margin-top: 2px; }
.pend .btn { flex: none; margin: 0; }
.gb-card { margin-top: 12px; }
.tabs { display: flex; border-bottom: 1px solid $line; }
.tabs > view { flex: 1; text-align: center; height: 46px; line-height: 46px; font-size: 15px; color: $text-2; position: relative; }
.tabs .on { color: $text; font-weight: 700; }
.tabs .on::after { content: ''; position: absolute; left: 50%; bottom: 0; width: 24px; height: 3px; margin-left: -12px; border-radius: 2px; background: $primary; }
.it { display: flex; align-items: center; gap: 10px; padding: 12px 14px; }
.it + .it { border-top: 1px solid $line; }
.it .b { font-size: 15px; font-weight: 600; }
.it .s { font-size: 12px; color: $text-3; margin-top: 2px; }
.plus { font-size: 17px; font-weight: 700; color: $primary; flex: none; }
.r { display: flex; flex-direction: column; align-items: flex-end; flex: none; }
.minus { font-size: 17px; font-weight: 700; }
.minus.x { color: $text-3; text-decoration: line-through; }
.st { font-size: 12px; color: $text-3; }
.st.wait, .st.paying { color: $mark; }
.st.paid { color: $primary; }
.rej { color: $price; }
</style>
