<!-- 加减数量：0 时只显示一个橙色加号 -->
<template>
  <view class="stp" @tap.stop>
    <template v-if="value > 0">
      <view class="b minus" @tap.stop="emit('change', -1)"><wd-icon name="decrease" size="16px" color="#535b56" /></view>
      <text class="n">{{ value }}</text>
    </template>
    <view class="b plus" :class="{ dis: max && value >= max }" @tap.stop="!(max && value >= max) ? emit('change', 1) : emit('limit')"><wd-icon name="add" size="16px" color="#fff" /></view>
  </view>
</template>
<script lang="ts" setup>
defineProps<{ value: number; max?: number }>()
const emit = defineEmits(['change', 'limit'])
</script>
<style lang="scss" scoped>
.stp { display: flex; align-items: center; }
.b { width: 30px; height: 30px; border-radius: 50%; display: flex; align-items: center; justify-content: center; position: relative; }
.b::after { content: ''; position: absolute; left: -7px; right: -7px; top: -7px; bottom: -7px; }
.minus { background: #fff; border: 1px solid #c9d0cb; box-sizing: border-box; }
.plus { background: #f14d05; }
.plus.dis { background: #edf2ee; }
.n { min-width: 30px; text-align: center; font-size: 16px; font-weight: 600; }
</style>
