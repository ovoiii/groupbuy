<route lang="json5">
{ style: { navigationBarTitleText: '选择提货点' } }
</route>
<!-- 选择提货点：按距离排序（拿不到定位时按默认顺序） -->
<template>
  <view>
    <view class="gb-tip" style="text-align: left; padding: 12px 16px 8px">选一个离你近、方便取货的提货点，以后下单默认送到这里</view>
    <view class="gb-card">
      <view v-for="p in list" :key="p.id" class="pt" @tap="choose(p)">
        <wd-icon name="location" size="20px" :color="p.id === cur ? '#078149' : '#6b716d'" />
        <view class="m">
          <view class="n">{{ p.name }}<text v-if="p.id === cur" class="tag ok" style="margin-left: 6px">当前</text></view>
          <view class="a">{{ p.address }}</view>
          <view class="a">团长 {{ p.leaderName }} · {{ p.openHours }} 可取{{ p.hasFridge ? '' : ' · 没有冰柜，冷藏商品不能选' }}</view>
        </view>
        <text v-if="p.km != null" class="km">{{ p.km < 1 ? Math.round(p.km * 1000) + 'm' : p.km.toFixed(1) + 'km' }}</text>
      </view>
    </view>
    <view class="gb-tip">只显示正在开团的提货点。想在自己小区开一个？到「我的 → 申请当团长」</view>
  </view>
</template>
<script lang="ts" setup>
import { api, post, pointStore, toast } from '@/gb/util'
const list = ref<any[]>([])
const cur = ref(pointStore.get()?.id)
async function load(lat?: number, lng?: number) {
  list.value = await api('/points', lat ? { lat, lng } : {})
}
onLoad(() => {
  load()
  uni.getLocation({ type: 'gcj02', success: (r) => load(r.latitude, r.longitude), fail: () => {} })
})
async function choose(p) {
  await post('/me/update', { pointId: p.id })
  pointStore.set(p)
  cur.value = p.id
  toast('已切换到 ' + p.name)
  setTimeout(() => uni.navigateBack({ fail: () => uni.switchTab({ url: '/pages/index/index' }) }), 500)
}
</script>
<style lang="scss" scoped>
.pt { display: flex; align-items: flex-start; gap: 10px; padding: 14px; }
.pt + .pt { border-top: 1px solid $line; }
.m { flex: 1; min-width: 0; }
.n { font-size: 16px; font-weight: 600; }
.a { font-size: 13px; color: $text-2; margin-top: 2px; }
.km { font-size: 13px; color: $text-3; }
</style>
