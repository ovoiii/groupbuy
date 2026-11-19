<route lang="json5">
{ style: { navigationBarTitleText: '登录' } }
</route>
<!-- 登录：微信一键登录（配置了 AppID 时）；开发阶段用手机号登录 -->
<template>
  <view class="lg">
    <view class="logo"><image src="/static/logo.png" mode="aspectFit" /></view>
    <view class="t">邻里鲜</view>
    <view class="s">今天下单，明天到小区自提</view>
    <button v-if="cfg.wxLogin" class="btn pri block" @tap="wxLogin">微信一键登录</button>
    <template v-if="cfg.devLogin">
      <view class="dev">
        <view class="lbl">手机号登录（开发环境）</view>
        <input v-model="phone" class="ipt" type="number" maxlength="11" placeholder="输入 11 位手机号" />
        <button class="btn pri block" :disabled="phone.length !== 11" @tap="devLogin">登录</button>
        <view class="demo">演示账号：居民 <text @tap="phone = '13800001029'">13800001029</text>　团长 <text @tap="phone = '13800002716'">13800002716</text></view>
      </view>
    </template>
    <view class="agree">登录即表示同意《用户协议》和《隐私政策》</view>
  </view>
</template>
<script lang="ts" setup>
import { api, post, token, pointStore, toast } from '@/gb/util'
const cfg = ref<any>({ devLogin: true, wxLogin: false })
const phone = ref('')
onLoad(async () => { cfg.value = await api('/config', null, 'GET', { silent: true }).catch(() => cfg.value) })
function done(r: any) {
  token.set(r.token)
  if (r.member.defaultPoint) pointStore.set(r.member.defaultPoint)
  toast('登录成功')
  setTimeout(() => { const pages = getCurrentPages(); pages.length > 1 ? uni.navigateBack() : uni.switchTab({ url: '/pages/index/index' }) }, 500)
}
async function devLogin() { done(await post('/dev-login', { phone: phone.value })) }
function wxLogin() {
  uni.login({ provider: 'weixin', success: async (r) => done(await post('/login', { code: r.code })), fail: () => toast('微信登录失败，请重试') })
}
</script>
<style lang="scss" scoped>
.lg { padding: 60px 32px 30px; text-align: center; }
.logo image { width: 76px; height: 76px; }
.t { font-size: 26px; font-weight: 700; margin-top: 12px; }
.s { color: $text-2; margin: 6px 0 40px; }
.dev { text-align: left; margin-top: 24px; }
.lbl { font-size: 13px; color: $text-3; margin-bottom: 8px; }
.ipt { height: 48px; border-radius: 12px; background: #fff; padding: 0 14px; font-size: 16px; margin-bottom: 14px; }
.demo { font-size: 12px; color: $text-3; margin-top: 14px; line-height: 1.8; text { color: $primary; padding: 0 2px; } }
.agree { font-size: 12px; color: $text-3; margin-top: 40px; }
</style>
