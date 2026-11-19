<route lang="json5">
{ type: 'home', style: { navigationStyle: 'custom', navigationBarTitleText: '邻里鲜' } }
</route>
<!-- 首页：提货点、本期团时间、领券条、分类和团品列表（直接加减数量） -->
<template>
  <view class="pg">
    <view class="hd" :style="{ paddingTop: statusH + 'px' }">
      <view class="pt-sel" @tap="goPoints">
        <wd-icon name="location" size="18px" color="#078149" />
        <text class="nm">{{ point ? point.name : '选择提货点' }}</text>
        <wd-icon name="arrow-down" size="14px" color="#6b716d" />
      </view>
      <view v-if="point" class="pt-sub">团长 {{ point.leaderName }}{{ point.km != null ? ' · 距你 ' + kmText(point.km) : '' }}</view>
      <view class="search" @tap="focusSearch = true">
        <wd-icon name="search" size="16px" color="#6b716d" />
        <input v-model="kw" class="si" :focus="focusSearch" placeholder="搜草莓、鸡蛋、牛奶" confirm-type="search" @blur="focusSearch = false" />
      </view>
    </view>

    <view v-if="period" class="period">
      <view class="l1"><text class="t">今天下单，明天到小区自提</text><Cutoff :time="period.cutoffTime" /></view>
      <view><Pickup :time="period.pickupTime" /></view>
    </view>
    <view v-else-if="loaded" class="period">
      <view class="t">本期团已截单</view>
      <view class="muted" style="font-size: 13px; margin-top: 4px">{{ next ? `下一期 ${dayTime(next.openTime)} 开团` : '下一期开团时间还没定，晚点再来看看' }}</view>
    </view>

    <scroll-view v-if="coupons.length" class="coupons" scroll-x>
      <view v-for="c in coupons" :key="c.id" class="cp">
        <view class="v" :class="{ txt: c.type !== 'full' }">{{ faceVal(c) }}</view>
        <view class="n"><view class="b">{{ c.name }}</view><view class="s">{{ c.desc }}</view></view>
        <view class="get" :class="{ done: c.received }" @tap="getCoupon(c)">{{ c.received ? (c.type === 'newbie' ? '去用' : '已领') : '领取' }}</view>
      </view>
    </scroll-view>

    <scroll-view class="cats" scroll-x>
      <view v-for="c in cats" :key="c" class="cat" :class="{ on: c === cat }" @tap="cat = c"><text>{{ c }}</text></view>
    </scroll-view>

    <view class="goods">
      <view v-for="g in list" :key="g.id" class="g" :class="{ out: g.left <= 0 }" @tap="open(g)">
        <view class="pic"><image :src="img(g.image)" mode="aspectFill" /><text v-if="g.tag" class="tg" :class="{ new: g.tag.includes('新人') }">{{ g.tag }}</text></view>
        <view class="ft">
          <view>
            <view class="nm">{{ g.name }}</view>
            <view class="sp">{{ g.spec }}</view>
            <view class="sold" :class="{ hot: hot(g) }"><view class="bar"><view class="i" :style="{ width: (g.stock ? (g.sold / g.stock) * 100 : 0) + '%' }" /></view>{{ g.left <= 0 ? '已抢光' : hot(g) ? `仅剩 ${g.left} 份` : `已团 ${g.sold} 份` }}</view>
          </view>
          <view class="bt">
            <view><Price :value="g.price" :size="20" /><text class="orig">¥{{ g.origPrice }}</text><view v-if="g.limitQty" class="lim">每人限 {{ g.limitQty }} 份{{ g.bought ? `，已买 ${g.bought}` : '' }}</view></view>
            <Stepper v-if="g.left > 0 && period" :value="qty[g.id] || 0" :max="maxOf(g)" @change="(d) => add(g, d)" @limit="toast(g.limitQty ? `每人限购 ${g.limitQty} 份${g.bought ? '，你已买了 ' + g.bought + ' 份' : ''}` : '库存不够了')" />
          </view>
        </view>
      </view>
      <view v-if="loaded && period && !list.length" class="gb-tip" style="padding: 40px 0">{{ kw ? `没找到「${kw}」` : '这个分类本期没有团品，明天再来看看' }}</view>
    </view>
    <view class="gb-tip">商品图为示意图</view>
  </view>
</template>

<script lang="ts" setup>
import { api, post, img, cart, pointStore, token, toLogin, dayTime, toast, syncServerTime } from '@/gb/util'
import Price from '@/gb/Price.vue'
import Stepper from '@/gb/Stepper.vue'
import Pickup from '@/gb/Pickup.vue'
import Cutoff from '@/gb/Cutoff.vue'

const statusH = uni.getSystemInfoSync().statusBarHeight || 20
const point = ref<any>(null)
const period = ref<any>(null)
const next = ref<any>(null)
const goods = ref<any[]>([])
const coupons = ref<any[]>([])
const cats = ref<string[]>(['全部'])
const cat = ref('全部')
const kw = ref('')
const focusSearch = ref(false)
const loaded = ref(false)
const qty = ref<Record<string, number>>({})

const hot = (g) => g.stock > 0 && g.left > 0 && g.left * 10 <= g.stock
const maxOf = (g) => (g.limitQty ? Math.min(g.left, g.limitQty - (g.bought || 0)) : g.left)
const kmText = (km) => (km < 1 ? Math.round(km * 1000) + 'm' : km.toFixed(1) + 'km')
const faceVal = (c) => (c.type === 'full' ? '¥' + Number(c.amount) : c.type === 'discount' ? Number(c.rate) * 10 + '折' : '1元购')
const list = computed(() => goods.value.filter((g) => (cat.value === '全部' || g.category === cat.value) && (!kw.value || g.name.includes(kw.value))))

async function load() {
  if (!token.get()) return toLogin()
  const [h, me] = await Promise.all([api('/home'), api('/me')])
  if (h.period) syncServerTime(h.period.serverTime)
  period.value = h.period
  next.value = h.next
  goods.value = h.goods
  coupons.value = h.coupons
  cats.value = ['全部', ...h.categories.filter((c) => h.goods.some((g) => g.category === c))]
  point.value = me.defaultPoint || pointStore.get()
  if (point.value) pointStore.set(point.value)
  else uni.navigateTo({ url: '/pages/points/points' })
  // 购物车里已经不在本期的团品清掉
  const ids = new Set(h.goods.map((g) => g.id))
  const c = cart.get()
  Object.keys(c).forEach((k) => !ids.has(k) && delete c[k])
  cart.set(c)
  qty.value = c
  loaded.value = true
}
onShow(() => { load().catch(() => {}); qty.value = cart.get(); cart.badge() })
onPullDownRefresh(() => load().finally(() => uni.stopPullDownRefresh()))

function add(g, d) {
  cart.add(g.id, d)
  qty.value = cart.get()
}
const open = (g) => uni.navigateTo({ url: `/pages/goods/goods?id=${g.id}` })
const goPoints = () => uni.navigateTo({ url: '/pages/points/points' })
async function getCoupon(c) {
  if (c.received) {
    if (c.type === 'newbie') { const g = goods.value.find((x) => x.goodsId === c.newbieGoodsId); g && open(g) }
    return
  }
  await post('/coupon/receive', { couponId: c.id })
  c.received = true
  toast('领取成功，下单时自动使用')
}
</script>

<style lang="scss" scoped>
.pg { padding-bottom: 20px; }
.hd { background: $primary-soft; padding-left: 16px; padding-right: 16px; padding-bottom: 14px; }
.pt-sel { display: flex; align-items: center; gap: 4px; min-height: 44px; max-width: calc(100% - 100px); }
.pt-sel .nm { font-size: 17px; font-weight: 700; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.pt-sub { font-size: 13px; color: $text-2; margin-top: -4px; }
.search { margin-top: 12px; height: 40px; border-radius: 20px; background: #fff; display: flex; align-items: center; gap: 8px; padding: 0 14px; }
.si { flex: 1; font-size: 15px; }
.period { margin: -2px 12px 12px; padding: 12px 14px; border-radius: 12px; background: #fff; display: flex; flex-direction: column; gap: 8px; }
.period .l1 { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.period .t { font-size: 15px; font-weight: 700; }
.coupons { white-space: nowrap; padding: 0 12px; margin-bottom: 12px; box-sizing: border-box; width: 100%; }
.cp { display: inline-flex; align-items: center; gap: 10px; height: 64px; padding-right: 12px; border-radius: 10px; background: $price-soft; min-width: 230px; margin-right: 10px; vertical-align: top; }
.cp .v { width: 64px; height: 64px; border-right: 1px dashed rgba(225, 42, 36, 0.35); display: flex; align-items: center; justify-content: center; color: $price; font-weight: 700; font-size: 22px; box-sizing: border-box; flex: none; }
.cp .v.txt { font-size: 15px; }
.cp .n { flex: 1; min-width: 0; white-space: normal; }
.cp .n .b { font-size: 14px; font-weight: 600; }
.cp .n .s { font-size: 12px; color: $text-2; }
.cp .get { height: 28px; line-height: 28px; padding: 0 12px; border-radius: 14px; background: $price; color: #fff; font-size: 13px; font-weight: 600; flex: none; }
.cp .get.done { background: none; color: $price; border: 1px solid $price; line-height: 26px; }
.cats { white-space: nowrap; padding: 0 8px 4px; box-sizing: border-box; width: 100%; }
.cat { display: inline-flex; height: 44px; align-items: center; padding: 0 3px; }
.cat text { height: 32px; line-height: 32px; padding: 0 14px; border-radius: 16px; font-size: 14px; color: $text-2; background: #fff; }
.cat.on text { background: $primary; color: #fff; font-weight: 600; }
.goods { display: flex; flex-direction: column; gap: 10px; padding: 6px 12px 0; }
.g { display: flex; gap: 12px; padding: 12px; border-radius: 12px; background: #fff; }
.g.out { opacity: 0.55; }
.pic { position: relative; width: 112px; height: 112px; flex: none; border-radius: 10px; overflow: hidden; background: $sunken; }
.pic image { width: 112px; height: 112px; }
.tg { position: absolute; left: 0; top: 0; height: 22px; line-height: 22px; padding: 0 8px; border-radius: 10px 0 10px 0; background: $price; color: #fff; font-size: 12px; font-weight: 600; }
.tg.new { background: $primary; }
.ft { flex: 1; min-width: 0; display: flex; flex-direction: column; justify-content: space-between; }
.nm { font-size: 16px; font-weight: 600; line-height: 1.35; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.sp { font-size: 13px; color: $text-3; margin-top: 2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.sold { margin-top: 6px; display: flex; align-items: center; gap: 6px; font-size: 12px; color: $text-3; }
.sold .bar { width: 56px; height: 4px; border-radius: 2px; background: $sunken; overflow: hidden; }
.sold .i { height: 100%; background: $primary; }
.sold.hot { color: $mark; font-weight: 600; }
.sold.hot .i { background: $mark; }
.bt { display: flex; align-items: flex-end; justify-content: space-between; margin-top: 8px; }
.lim { font-size: 12px; color: $text-3; }
</style>
