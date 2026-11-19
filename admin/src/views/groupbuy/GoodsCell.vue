<!-- 商品单元格：图 + 名称 + 规格 -->
<template>
  <div class="gb-goods" :class="{ sm: small }">
    <img v-if="image" :src="img(image)" alt="" />
    <span v-else class="ph"></span>
    <div style="min-width: 0">
      <div class="nm">{{ name }}<slot name="tag" /></div>
      <div class="sub"><slot name="sub">{{ spec }}</slot></div>
    </div>
  </div>
</template>
<script lang="ts" setup>
  import { img } from './api';
  defineProps<{ image?: string; name: string; spec?: string; small?: boolean }>();
</script>
