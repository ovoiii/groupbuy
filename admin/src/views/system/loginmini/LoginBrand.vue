<!-- 登录页品牌区：logo（图形 + 系统标题）与 pitch（左侧深色面板里的标语）。标题取自 .env 的 VITE_GLOB_APP_TITLE。 -->
<template>
  <div v-if="type === 'logo'" class="login-brand-logo">
    <svg viewBox="0 0 28 28" aria-hidden="true">
      <rect x="1" y="1" width="26" height="26" rx="7" fill="#078149" />
      <path d="M6.5 13.2 14 7l7.5 6.2V21a1 1 0 0 1-1 1h-13a1 1 0 0 1-1-1v-7.8Z" fill="none" stroke="#fff" stroke-width="2" stroke-linejoin="round" />
      <path d="M14 20.5c-2.8-.4-4-2.6-3.4-5.4 2.6.1 4 1.9 3.4 5.4Zm0 0c2.8-.4 4-2.6 3.4-5.4-2.6.1-4 1.9-3.4 5.4Z" fill="#ffc24d" />
    </svg>
    <span>{{ title }}</span>
  </div>
  <div v-else class="login-brand-pitch">
    <h1>今天开团，明天到小区，<br />一个后台管到底</h1>
    <p>每日团期自动开团截单，按提货点分拣配送，团长扫码核销，售后 24 小时闭环。</p>
    <ul>
      <li>每日团期</li>
      <li>分拣配送</li>
      <li>团长核销</li>
    </ul>
  </div>
</template>

<script lang="ts" setup>
  import { useGlobSetting } from '/@/hooks/setting';

  defineProps<{ type: 'logo' | 'pitch' }>();
  const { title } = useGlobSetting();
</script>

<style lang="less" scoped>
  .login-brand-logo {
    display: flex;
    align-items: center;
    gap: 10px;
    font-size: 18px;
    font-weight: 700;
    color: #1d231f;
    white-space: nowrap;

    svg {
      flex: none;
      width: 32px;
      height: 32px;
    }
  }
  .login-brand-pitch {
    color: #b5bac6;

    h1 {
      margin: 0;
      color: #fff;
      font-size: 32px;
      font-weight: 700;
      line-height: 1.3;
    }
    p {
      margin: 14px 0 0;
      font-size: 15px;
      line-height: 1.75;
    }
    ul {
      display: flex;
      flex-wrap: wrap;
      gap: 8px 20px;
      margin: 24px 0 0;
      padding: 0;
      list-style: none;
      font-size: 13px;
    }
    li::before {
      content: '';
      display: inline-block;
      width: 6px;
      height: 6px;
      margin-right: 8px;
      border-radius: 50%;
      background: #ffc24d;
      vertical-align: 2px;
    }
  }
</style>
<style lang="less">
  html[data-theme='dark'] .login-brand-logo {
    color: #fff;
  }
</style>
