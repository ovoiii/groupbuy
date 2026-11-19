<route lang="json5">
{ style: { navigationBarTitleText: '提现' } }
</route>
<!-- 团长提现：金额、提到微信零钱或银行卡、收款人实名 -->
<template>
  <view v-if="d">
    <view class="gb-card amt">
      <view class="lb">提现金额</view>
      <view class="ipt"><text class="y">¥</text><input v-model="amount" type="digit" placeholder="0.00" /></view>
      <view class="hint">可提现 ¥{{ Number(d.balance).toFixed(2) }}，<text class="all" @tap="amount = Number(d.balance).toFixed(2)">全部提现</text><text v-if="err" class="err"> · {{ err }}</text></view>
    </view>
    <view class="gb-card">
      <view class="gb-card-hd">提到</view>
      <view class="ms">
        <view class="m" :class="{ on: method === 'wechat' }" @tap="method = 'wechat'"><wd-icon name="chat" size="22px" :color="method === 'wechat' ? '#078149' : '#6b716d'" /><text>微信零钱</text></view>
        <view class="m" :class="{ on: method === 'bank' }" @tap="method = 'bank'"><wd-icon name="creditcard" size="22px" :color="method === 'bank' ? '#078149' : '#6b716d'" /><text>银行卡</text></view>
      </view>
      <view class="f"><text class="k">收款人</text><input v-model="accountName" placeholder="你的真实姓名" maxlength="16" /></view>
      <template v-if="method === 'bank'">
        <view class="f"><text class="k">卡号</text><input v-model="accountNo" type="number" placeholder="银行卡号" maxlength="23" /></view>
        <view class="f"><text class="k">开户行</text><input v-model="bankName" placeholder="如：工商银行" maxlength="20" /></view>
      </template>
      <view v-else class="gb-tip" style="text-align: left; padding: 4px 14px 14px">打到你现在登录小程序的这个微信，收款人姓名要和微信实名一致。</view>
    </view>
    <view style="padding: 4px 12px 0"><button class="btn pri block" :disabled="!ok || busy" @tap="submit">提交申请</button></view>
    <view class="gb-tip">单笔最少 {{ d.withdrawMin }} 元，平台审核后 1–3 个工作日到账。<br />收款人必须是团长本人，姓名对不上会被退回。</view>
  </view>
</template>
<script lang="ts" setup>
import { api, post, toast, subscribe } from '@/gb/util'
const d = ref<any>(null)
const amount = ref('')
const method = ref<'wechat' | 'bank'>('wechat')
const accountName = ref('')
const accountNo = ref('')
const bankName = ref('')
const busy = ref(false)
onLoad(async () => {
  d.value = await api('/leader/income')
  const a = d.value.lastAccount
  if (a) {
    method.value = a.method
    accountName.value = a.accountName || ''
    accountNo.value = a.accountNo || ''
    bankName.value = a.bankName || ''
  }
})
const n = computed(() => Number(amount.value))
const err = computed(() => {
  if (!amount.value) return ''
  if (!/^\d+(\.\d{1,2})?$/.test(amount.value)) return '金额最多两位小数'
  if (n.value < d.value.withdrawMin) return `最少 ${d.value.withdrawMin} 元`
  if (n.value > Number(d.value.balance)) return '超过可提现金额'
  return ''
})
const ok = computed(() => amount.value && !err.value && accountName.value.trim() && (method.value === 'wechat' || (accountNo.value.length >= 12 && bankName.value.trim())))
async function submit() {
  if (!ok.value || busy.value) return
  await subscribe(['withdraw'])
  busy.value = true
  try {
    await post('/leader/withdraw', { amount: amount.value, method: method.value, accountName: accountName.value, accountNo: accountNo.value, bankName: bankName.value })
    toast('已提交，1–3 个工作日到账')
    setTimeout(() => uni.navigateBack(), 1200)
  } finally {
    busy.value = false
  }
}
</script>
<style lang="scss" scoped>
.amt { margin-top: 12px; padding: 16px 14px; }
.lb { font-size: 14px; color: $text-2; }
.ipt { display: flex; align-items: center; gap: 6px; border-bottom: 1px solid $line; padding: 8px 0; }
.ipt .y { font-size: 28px; font-weight: 700; }
.ipt input { flex: 1; height: 52px; font-size: 34px; font-weight: 700; }
.hint { font-size: 13px; color: $text-3; margin-top: 10px; }
.all { color: $primary; font-weight: 600; }
.err { color: $price; }
.ms { display: flex; gap: 10px; padding: 12px 14px; }
.m { flex: 1; height: 52px; border-radius: 10px; border: 1px solid $line; display: flex; align-items: center; justify-content: center; gap: 8px; font-size: 15px; color: $text-2; }
.m.on { border-color: $primary; background: $primary-soft; color: #006134; font-weight: 600; }
.f { display: flex; align-items: center; min-height: 50px; padding: 0 14px; border-top: 1px solid $line; font-size: 15px; }
.f .k { width: 64px; color: $text-2; flex: none; }
.f input { flex: 1; height: 50px; font-size: 15px; }
</style>
