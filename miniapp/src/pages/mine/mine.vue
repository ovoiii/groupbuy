<route lang="json5">
{ style: { navigationStyle: 'custom', navigationBarTitleText: '我的' } }
</route>
<!-- 我的：订单入口、优惠券、提货点；团长账号多一个「团长工作台」 -->
<template>
  <view v-if="me">
    <view class="me" :style="{ paddingTop: statusH + 12 + 'px' }">
      <view class="av">{{ (me.nickname || '我')[0] }}</view>
      <view style="min-width: 0"><view class="nm">{{ me.nickname }}</view><view class="ph">{{ me.phone || '未绑定手机号' }}{{ me.defaultPoint ? ' · ' + me.defaultPoint.name : '' }}</view></view>
    </view>
    <view class="stats">
      <view class="s" @tap="go('/pages/coupons/coupons')"><text class="b">{{ me.couponCount }}</text><text>优惠券</text></view>
      <view class="s" @tap="goOrders('')"><text class="b">{{ me.orderCounts.all }}</text><text>全部订单</text></view>
      <view class="s"><text class="b">¥{{ me.saved }}</text><text>优惠省下</text></view>
    </view>
    <view class="gb-card">
      <view class="gb-card-hd">我的订单<view class="more" @tap="goOrders('')">全部<wd-icon name="arrow-right" size="12px" /></view></view>
      <view class="ords">
        <view v-for="t in OT" :key="t[0]" class="o" @tap="goOrders(t[0])"><wd-icon :name="t[2]" size="26px" color="#1d231f" /><text>{{ t[1] }}</text><text v-if="me.orderCounts[t[0]]" class="n">{{ me.orderCounts[t[0]] }}</text></view>
      </view>
    </view>
    <view v-if="me.leaderPoint" class="leader" @tap="go('/pages/leader/leader')">
      <view class="ic"><wd-icon name="shop" size="22px" color="#ffc24d" /></view>
      <view style="min-width: 0; flex: 1"><view class="b">团长工作台</view><view class="s">{{ me.leaderPoint.name }} · 核销提货、到货签收、佣金提现</view></view>
      <view class="go">进入<wd-icon name="arrow-right" size="12px" color="#ffc24d" /></view>
    </view>
    <view class="gb-card">
      <view class="gb-row" @tap="go('/pages/coupons/coupons')"><wd-icon name="discount" size="18px" color="#535b56" /><text>我的优惠券</text><view class="v muted">{{ me.couponCount }} 张可用<wd-icon name="arrow-right" size="14px" /></view></view>
      <view class="gb-row" @tap="go('/pages/points/points')"><wd-icon name="location" size="18px" color="#535b56" /><text>我的提货点</text><view class="v muted">{{ me.defaultPoint ? me.defaultPoint.name : '去选择' }}<wd-icon name="arrow-right" size="14px" /></view></view>
      <view v-if="!me.leaderPoint" class="gb-row" @tap="go('/pages/leader/apply')"><wd-icon name="shop" size="18px" color="#535b56" /><text>申请当团长</text><view class="v" :class="APPLY[me.applyStatus]?.[1] || 'muted'">{{ APPLY[me.applyStatus]?.[0] || '在家门口开个提货点' }}<wd-icon name="arrow-right" size="14px" color="#6b716d" /></view></view>
      <view class="gb-row" @tap="logout"><wd-icon name="logout" size="18px" color="#535b56" /><text>退出登录</text></view>
    </view>
    <view class="gb-tip">客服电话 400-000-0000（示例） · 09:00–21:00<br /><text class="lk" @tap="go('/pages/agreement/agreement?type=user')">用户协议</text> · <text class="lk" @tap="go('/pages/agreement/agreement?type=privacy')">隐私政策</text></view>
  </view>
</template>
<script lang="ts" setup>
import { api, token, toLogin, rememberMe } from '@/gb/util'
const statusH = uni.getSystemInfoSync().statusBarHeight || 20
const OT = [['unpaid', '待付款', 'wallet'], ['paid', '待截单', 'time'], ['arrived', '待提货', 'bags'], ['refund', '售后', 'rollback']]
const me = ref<any>(null)
onShow(async () => {
  if (!token.get()) return toLogin()
  me.value = await api('/me').catch(() => null)
  if (me.value) rememberMe(me.value)
})
const go = (url: string) => uni.navigateTo({ url })
function goOrders(t: string) {
  uni.setStorageSync('gb-orders-tab', t)
  uni.switchTab({ url: '/pages/orders/orders' })
}
const APPLY = { pending: ['审核中', 'st-wait'], rejected: ['未通过，可修改重提', 'st-rej'] }
function logout() {
  token.clear()
  toLogin()
}
</script>
<style lang="scss" scoped>
.me { background: $primary-soft; padding: 12px 16px 56px; display: flex; align-items: center; gap: 12px; }
.av { width: 56px; height: 56px; border-radius: 50%; background: #fff; color: $primary; display: flex; align-items: center; justify-content: center; font-size: 22px; font-weight: 700; flex: none; }
.nm { font-size: 19px; font-weight: 700; }
.ph { font-size: 13px; color: $text-2; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.stats { margin: -42px 12px 12px; display: flex; background: #fff; border-radius: 12px; padding: 14px 0; }
.s { flex: 1; display: flex; flex-direction: column; align-items: center; font-size: 12px; color: $text-3; }
.s + .s { border-left: 1px solid $line; }
.s .b { font-size: 20px; font-weight: 700; color: $text; }
.ords { display: flex; padding: 6px 0 12px; }
.o { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 6px; font-size: 13px; color: $text-2; padding: 8px 0; position: relative; }
.o .n { position: absolute; top: 2px; left: 50%; margin-left: 8px; min-width: 18px; height: 18px; line-height: 18px; text-align: center; border-radius: 9px; background: $price; color: #fff; font-size: 12px; font-weight: 700; padding: 0 4px; box-sizing: border-box; }
.leader { margin: 0 12px 12px; padding: 14px; border-radius: 12px; background: $dark; color: #fff; display: flex; align-items: center; gap: 12px; }
.leader .ic { width: 44px; height: 44px; border-radius: 12px; background: rgba(255, 255, 255, 0.12); display: flex; align-items: center; justify-content: center; flex: none; }
.leader .b { font-weight: 700; }
.leader .s { display: block; font-size: 13px; opacity: 0.75; color: #fff; align-items: flex-start; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.leader .go { display: flex; align-items: center; gap: 2px; font-size: 14px; color: $mark-bright; font-weight: 600; flex: none; }
.lk { color: $text-2; text-decoration: underline; }
.st-wait { color: $mark; font-size: 14px; }
.st-rej { color: $price; font-size: 14px; }
</style>
