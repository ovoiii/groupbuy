<route lang="json5">
{ style: { navigationBarTitleText: '商品详情' } }
</route>
<!-- 商品详情：价格、已团 / 仅剩、提货时间轴、坏了包退 -->
<template>
  <view v-if="g">
    <SharedTip :point="shared" @close="shared = null" @switched="shared = null" />
    <swiper class="hero" :indicator-dots="images.length > 1" indicator-active-color="#078149">
      <swiper-item v-for="u in images" :key="u"><image :src="img(u)" mode="aspectFill" class="hi" /></swiper-item>
    </swiper>
    <view class="pricebar">
      <Price :value="g.price" :size="30" /><text class="orig">超市价 ¥{{ g.origPrice }}</text>
      <view class="r">
        <view>已团 <text class="b">{{ g.sold }}</text> 份</view>
        <view v-if="hot" class="b">仅剩 {{ g.left }} 份</view>
        <Cutoff v-if="g.period && g.period.status === 'live'" :time="g.period.cutoffTime" label="截单" />
      </view>
    </view>
    <view class="info">
      <view class="h1row">
        <view class="h1">{{ g.name }}</view>
        <view class="share" @tap="shareOpen = true"><wd-icon name="share" size="18px" color="#078149" /><text>分享</text></view>
      </view>
      <view class="sp">{{ g.spec }}</view>
      <view class="tags">
        <text v-if="g.tag" class="tag warn">{{ g.tag }}</text>
        <text v-if="g.limitQty" class="tag">每人限购 {{ g.limitQty }} 份{{ g.bought ? `，已买 ${g.bought}` : '' }}</text>
        <text class="tag ok">坏了包退</text>
      </view>
    </view>
    <view class="pick">
      <view class="ic"><wd-icon name="location" size="20px" color="#078149" /></view>
      <view>
        <view class="b">{{ dayTime(g.period.pickupTime) }} 起到小区自提</view>
        <view class="muted" style="font-size: 13px">{{ point ? point.name + ' · 团长 ' + point.leaderName : '先选一个提货点' }}</view>
      </view>
      <view class="flow">
        <view class="on"><text class="b">现在</text>下单</view>
        <view><text class="b">{{ dayTime(g.period.cutoffTime) }}</text>截单</view>
        <view><text class="b">次日上午</text>送到提货点</view>
        <view><text class="b">{{ hm(g.period.pickupTime) }} 起</text>凭码自提</view>
      </view>
    </view>
    <view class="detail">
      <view class="h2">商品信息</view>
      <view class="dl"><text class="dt">产地</text><text>{{ g.origin || '—' }}</text></view>
      <view class="dl"><text class="dt">储存</text><text>{{ g.storage === 'normal' ? '阴凉处常温保存' : '冷藏 0–4℃，提货后尽快放冰箱' }}</text></view>
      <view class="dl"><text class="dt">超市价</text><text>¥{{ g.origPrice }}（附近超市同规格售价，仅供参考）</text></view>
      <view v-if="g.detail" class="dl"><text class="dt">说明</text><text>{{ g.detail }}</text></view>
      <view class="guar"><wd-icon name="secured" size="16px" color="#078149" /><text>坏了包退：提货后 24 小时内，在「订单 → 申请售后」拍照提交，审核通过原路退款。</text></view>
    </view>
    <view v-if="shareOpen" class="mask" @tap="shareOpen = false">
      <view class="sheet" @tap.stop>
        <view class="sheet-t">分享给邻居</view>
        <view class="opts">
          <!-- #ifdef MP-WEIXIN -->
          <button class="opt" open-type="share" @tap="shareOpen = false"><view class="oc"><wd-icon name="chat" size="26px" color="#078149" /></view><text>发给好友 / 群</text></button>
          <!-- #endif -->
          <view class="opt" @tap="toPoster"><view class="oc"><wd-icon name="picture" size="26px" color="#078149" /></view><text>生成分享海报</text></view>
        </view>
        <view class="sheet-tip">邻居从你的分享进来，会自动选你的提货点</view>
        <view class="cancel" @tap="shareOpen = false">取消</view>
      </view>
    </view>
    <view class="actbar-space" />
    <view class="actbar">
      <view class="cart-ic" @tap="goCart"><wd-icon name="cart" size="24px" color="#535b56" /><text>购物车</text><text v-if="count" class="badge">{{ count }}</text></view>
      <view class="sum" />
      <template v-if="n">
        <Stepper :value="n" :max="maxQty" @change="add" @limit="toast(`每人限购 ${g.limitQty} 份`)" />
        <button class="btn cta" @tap="goCart">去结算</button>
      </template>
      <button v-else class="btn cta" style="width: 200px" :disabled="!canBuy" @tap="add(1)">{{ g.left <= 0 ? '已抢光' : !live ? '本期已截单' : maxQty <= 0 ? '已达限购' : '加入购物车' }}</button>
    </view>
  </view>
</template>
<script lang="ts" setup>
import { api, img, cart, pointStore, dayTime, toast, toDate, token, handleShareEntry, sharePointId, entryQuery } from '@/gb/util'
import SharedTip from '@/gb/SharedTip.vue'
import Price from '@/gb/Price.vue'
import Stepper from '@/gb/Stepper.vue'
import Cutoff from '@/gb/Cutoff.vue'
const g = ref<any>(null)
const n = ref(0)
const count = ref(0)
const point = pointStore.get()
const images = computed(() => (g.value?.images ? g.value.images.split(',') : [g.value?.image]))
const hot = computed(() => g.value && g.value.stock > 0 && g.value.left * 10 <= g.value.stock)
const live = computed(() => g.value?.period?.status === 'live')
const maxQty = computed(() => (g.value.limitQty ? Math.min(g.value.left, g.value.limitQty - g.value.bought) : g.value.left))
const canBuy = computed(() => g.value.left > 0 && live.value && maxQty.value > 0)
const hm = (t) => { const d = toDate(t); return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0') }
const shared = ref<any>(null)
const shareOpen = ref(false)
let gid = ''
// 从分享进来的新用户要先登录，登录完回到这页时在 onShow 里再加载
async function load() {
  if (!gid || !token.get()) return
  g.value = await api('/goods', { id: gid })
  n.value = cart.qty(gid)
  count.value = cart.count()
}
onLoad((raw: any) => {
  // 分享链接带 id、pt；扫海报小程序码带 scene
  const q = entryQuery(raw)
  gid = q.id
  handleShareEntry(q).then((p) => (shared.value = p))
  load().catch(() => {})
})
onShow(() => { if (!g.value) load().catch(() => {}) })
function toPoster() {
  shareOpen.value = false
  uni.navigateTo({ url: `/pages/poster/poster?type=goods&id=${gid}` })
}
onShareAppMessage(() => ({
  title: g.value ? `¥${g.value.price} ${g.value.name}（${g.value.spec}），明天到小区自提` : '邻里鲜社区团购',
  path: `/pages/goods/goods?id=${gid}&pt=${sharePointId()}`,
  imageUrl: g.value ? img(images.value[0]) : undefined,
}))
onShareTimeline(() => ({ title: g.value ? `¥${g.value.price} ${g.value.name}` : '邻里鲜社区团购', query: `id=${gid}&pt=${sharePointId()}` }))
function add(d) {
  const before = n.value
  n.value = cart.add(g.value.id, d)
  count.value = cart.count()
  if (before === 0 && d > 0) toast('已加入购物车')
}
const goCart = () => uni.switchTab({ url: '/pages/cart/cart' })
</script>
<style lang="scss" scoped>
.hero { width: 100%; height: 375px; background: $sunken; }
.hi { width: 100%; height: 375px; }
.pricebar { display: flex; align-items: center; gap: 8px; padding: 12px 16px; background: $price-soft; }
.pricebar .r { margin-left: auto; text-align: right; font-size: 12px; color: $text-2; line-height: 1.6; display: flex; flex-direction: column; align-items: flex-end; }
.pricebar .b { color: $mark; font-weight: 700; }
.info { padding: 14px 16px 16px; background: #fff; margin-bottom: 10px; }
.h1row { display: flex; align-items: flex-start; gap: 10px; }
.h1 { font-size: 19px; font-weight: 700; line-height: 1.4; flex: 1; min-width: 0; }
.share { flex: none; margin: 0; padding: 0 0 0 10px; background: none; border-left: 1px solid $line; line-height: 1.2; display: flex; flex-direction: column; align-items: center; gap: 2px; font-size: 12px; color: $primary; }
.share::after { border: none; }
.mask { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.45); z-index: 99; display: flex; align-items: flex-end; }
.sheet { width: 100%; background: #fff; border-radius: 16px 16px 0 0; padding: 18px 16px calc(12px + env(safe-area-inset-bottom)); box-sizing: border-box; }
.sheet-t { text-align: center; font-size: 15px; font-weight: 600; }
.opts { display: flex; justify-content: center; gap: 48px; padding: 22px 0 10px; }
.opt { margin: 0; padding: 0; background: none; line-height: 1.4; display: flex; flex-direction: column; align-items: center; gap: 8px; font-size: 13px; color: $text; }
.opt::after { border: none; }
.oc { width: 56px; height: 56px; border-radius: 50%; background: $primary-soft; display: flex; align-items: center; justify-content: center; }
.sheet-tip { text-align: center; font-size: 12px; color: $text-3; padding-bottom: 12px; }
.cancel { text-align: center; height: 46px; line-height: 46px; border-top: 1px solid $line; font-size: 15px; color: $text-2; }
.sp { color: $text-2; margin-top: 4px; font-size: 14px; }
.tags { display: flex; gap: 6px; margin-top: 10px; flex-wrap: wrap; }
.pick { margin: 0 12px 10px; padding: 14px; border-radius: 12px; background: #fff; display: flex; flex-wrap: wrap; gap: 4px 12px; align-items: center; }
.pick .ic { width: 40px; height: 40px; border-radius: 10px; background: $primary-soft; display: flex; align-items: center; justify-content: center; }
.pick .b { font-weight: 700; display: block; }
.flow { display: flex; width: 100%; margin-top: 12px; font-size: 12px; color: $text-3; }
.flow > view { flex: 1; text-align: center; padding-top: 14px; position: relative; }
.flow > view::before { content: ''; position: absolute; top: 3px; left: 50%; width: 8px; height: 8px; margin-left: -4px; border-radius: 50%; background: $primary-soft-2; }
.flow .on { color: #006134; }
.flow .on::before { background: $primary; }
.flow .b { color: $text; font-weight: 600; }
.detail { padding: 14px 16px; background: #fff; }
.h2 { font-size: 16px; font-weight: 700; margin-bottom: 10px; }
.dl { display: flex; gap: 12px; font-size: 14px; padding: 4px 0; }
.dt { color: $text-3; width: 52px; flex: none; }
.guar { margin-top: 14px; padding: 12px; border-radius: 10px; background: $bg; font-size: 13px; color: $text-2; line-height: 1.6; display: flex; gap: 8px; }
.cart-ic { width: 52px; height: 44px; display: flex; flex-direction: column; align-items: center; justify-content: center; font-size: 12px; color: $text-2; position: relative; }
.badge { position: absolute; top: -2px; right: 2px; min-width: 18px; height: 18px; line-height: 18px; text-align: center; padding: 0 5px; border-radius: 9px; background: $price; color: #fff; font-size: 12px; font-weight: 700; box-sizing: border-box; }
</style>
