<!-- 从邻居的分享进来、但自己已经选了别的提货点时提示：可以一键切到分享人的提货点 -->
<template>
  <view v-if="point" class="sh">
    <wd-icon name="location" size="16px" color="#a06500" />
    <text class="t">分享给你的邻居在「{{ point.name }}」提货</text>
    <text class="sw" @tap="sw">切到这里</text>
    <view class="x" @tap="emit('close')"><wd-icon name="close" size="12px" color="#6b716d" /></view>
  </view>
</template>
<script lang="ts" setup>
import { post, pointStore, toast } from '@/gb/util'
const props = defineProps<{ point: any }>()
const emit = defineEmits<{ close: []; switched: [p: any] }>()
async function sw() {
  const p = props.point
  await post('/me/update', { pointId: p.id })
  pointStore.set(p)
  toast('已切换到 ' + p.name)
  emit('switched', p)
}
</script>
<style lang="scss" scoped>
.sh { margin: 10px 12px 0; padding: 10px 12px; border-radius: 10px; background: $mark-soft; display: flex; align-items: center; gap: 6px; font-size: 13px; }
.t { flex: 1; min-width: 0; color: $text-2; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sw { color: $mark; font-weight: 600; flex: none; }
.x { padding: 2px 0 2px 6px; flex: none; display: flex; }
</style>
