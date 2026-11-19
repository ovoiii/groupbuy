<route lang="json5">
{ style: { navigationBarTitleText: '我的优惠券' } }
</route>
<template>
  <view>
    <view v-for="c in list" :key="c.id" class="cp" :class="{ off: c.status !== 'unused' }">
      <view class="v">{{ c.type === 'full' ? '¥' + Number(c.amount) : c.type === 'discount' ? Number(c.rate) * 10 + '折' : '1元购' }}</view>
      <view class="m"><view class="b">{{ c.name }}</view><view class="s">{{ c.desc }}</view><view class="s">{{ c.status === 'unused' ? md(c.expireTime) + ' 到期' : { used: '已使用', locked: '下单中，未付款', expired: '已过期' }[c.status] }}</view></view>
      <view v-if="c.status === 'unused'" class="use" @tap="goHome">去用</view>
    </view>
    <view v-if="loaded && !list.length" class="empty"><view class="bx"><wd-icon name="discount" size="40px" color="#078149" /></view><view class="h">还没有优惠券</view><view class="p">首页顶部可以领券</view><button class="btn pri" @tap="goHome">去领券</button></view>
    <view class="gb-tip">结算时自动用最划算的一张，一单只能用一张</view>
  </view>
</template>
<script lang="ts" setup>
import { api, md } from '@/gb/util'
const list = ref<any[]>([])
const loaded = ref(false)
onShow(async () => { list.value = await api('/coupon/mine'); loaded.value = true })
const goHome = () => uni.switchTab({ url: '/pages/index/index' })
</script>
<style lang="scss" scoped>
.cp { margin: 12px 12px 0; display: flex; align-items: center; gap: 12px; padding-right: 14px; border-radius: 12px; background: #fff; min-height: 84px; }
.cp.off { opacity: 0.5; }
.v { width: 88px; align-self: stretch; display: flex; align-items: center; justify-content: center; background: $price-soft; color: $price; font-size: 22px; font-weight: 700; border-radius: 12px 0 0 12px; flex: none; }
.m { flex: 1; min-width: 0; padding: 12px 0; }
.b { font-size: 15px; font-weight: 700; }
.s { font-size: 12px; color: $text-3; margin-top: 2px; }
.use { height: 30px; line-height: 30px; padding: 0 14px; border-radius: 15px; border: 1px solid $price; color: $price; font-size: 13px; }
</style>
