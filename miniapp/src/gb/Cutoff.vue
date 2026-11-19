<!-- 记号色 · 截单倒计时（每秒刷新） -->
<template>
  <view class="mk-cutoff"><wd-icon name="time" size="13px" color="#a06500" /><text>{{ label }} {{ text }}</text></view>
</template>
<script lang="ts" setup>
import { hms, leftSec } from './util'
const props = withDefaults(defineProps<{ time: any; label?: string }>(), { label: '距截单' })
const text = ref(hms(leftSec(props.time)))
let t: any
onMounted(() => (t = setInterval(() => (text.value = hms(leftSec(props.time))), 1000)))
onUnmounted(() => clearInterval(t))
</script>
