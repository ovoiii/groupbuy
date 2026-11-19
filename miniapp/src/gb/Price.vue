<!-- 价格：¥ 和角分缩小一号 -->
<template>
  <text class="price" :style="{ fontSize: size + 'px' }"><text class="y">¥</text>{{ parts[0] }}<text class="j">.{{ parts[1] }}</text></text>
</template>
<script lang="ts" setup>
const props = withDefaults(defineProps<{ value: any; size?: number }>(), { size: 18 })
const parts = computed(() => Number(props.value || 0).toFixed(2).split('.'))
</script>
