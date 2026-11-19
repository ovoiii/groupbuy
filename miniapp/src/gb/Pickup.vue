<!-- 功能记号 · 提货牌：「明天 16:00 起可提 · 提货点」 -->
<template>
  <view class="mk-pickup" style="overflow: hidden"><wd-icon name="location" size="13px" color="#006134" /><text>{{ dayTime(time) }} 起可提</text><text v-if="point" class="pt"> · {{ point }}</text></view>
</template>
<script lang="ts" setup>
import { dayTime } from './util'
defineProps<{ time: any; point?: string }>()
</script>
