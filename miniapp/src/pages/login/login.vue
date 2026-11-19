<route lang="json5">
{ style: { navigationBarTitleText: '登录' } }
</route>
<!-- 登录：微信小程序里用「手机号快捷登录」（微信验证手机号，一次点击完成）；开发环境可以直接输手机号 -->
<template>
  <view class="lg">
    <view class="logo"><image src="/static/logo.png" mode="aspectFit" /></view>
    <view class="t">邻里鲜</view>
    <view class="s">今天下单，明天到小区自提</view>

    <!-- #ifdef MP-WEIXIN -->
    <template v-if="cfg.wxLogin">
      <button v-if="agreed" class="btn pri block" open-type="getPhoneNumber" :loading="busy" :disabled="busy" @getphonenumber="onPhone">手机号快捷登录</button>
      <button v-else class="btn pri block" @tap="needAgree">手机号快捷登录</button>
      <view class="why">提货时团长要核对手机号，有问题也会打这个电话联系你</view>
    </template>
    <!-- #endif -->

    <view v-if="cfg.devLogin" class="dev">
      <view class="lbl">手机号登录（开发环境，上线前关掉）</view>
      <input v-model="phone" class="ipt" type="number" maxlength="11" placeholder="输入 11 位手机号" />
      <button class="btn pri block" :disabled="phone.length !== 11" @tap="devLogin">登录</button>
      <view class="demo">演示账号：居民 <text @tap="phone = '13800001029'">13800001029</text>　团长 <text @tap="phone = '13800002716'">13800002716</text></view>
    </view>

    <view class="agree" @tap="agreed = !agreed">
      <view class="ck" :class="{ on: agreed }"><wd-icon v-if="agreed" name="check" size="11px" color="#fff" /></view>
      <text>我已阅读并同意</text><text class="lk" @tap.stop="openDoc('user')">《用户协议》</text><text>和</text><text class="lk" @tap.stop="openDoc('privacy')">《隐私政策》</text>
    </view>
  </view>
</template>
<script lang="ts" setup>
import { api, post, token, pointStore, toast, fromPoint } from '@/gb/util'
const cfg = ref<any>({ devLogin: false, wxLogin: false })
const phone = ref('')
const agreed = ref(false)
const busy = ref(false)
onLoad(async () => { cfg.value = await api('/config', null, 'GET', { silent: true }).catch(() => ({ devLogin: true, wxLogin: false })) })

function done(r: any) {
  token.set(r.token)
  if (r.member.defaultPoint) pointStore.set(r.member.defaultPoint)
  toast('登录成功')
  setTimeout(() => { const pages = getCurrentPages(); pages.length > 1 ? uni.navigateBack() : uni.switchTab({ url: '/pages/index/index' }) }, 500)
}
const needAgree = () => toast('请先阅读并勾选下方的协议')
const openDoc = (t: string) => uni.navigateTo({ url: `/pages/agreement/agreement?type=${t}` })

async function devLogin() {
  if (!agreed.value) return needAgree()
  done(await post('/dev-login', { phone: phone.value, pt: fromPoint() }))
}

/** 点了「手机号快捷登录」：微信给手机号 code，再拿 wx.login 的 code 一起交给后端（老用户后端不会去换手机号） */
function onPhone(e: any) {
  const phoneCode = e?.detail?.code
  if (!phoneCode) {
    if (!/deny|cancel/.test(e?.detail?.errMsg || '')) toast('没拿到手机号，请重试')
    else toast('需要手机号才能下单和提货')
    return
  }
  busy.value = true
  uni.login({
    provider: 'weixin',
    success: async (r) => {
      try {
        done(await post('/login', { code: r.code, phoneCode, pt: fromPoint() }))
      } finally {
        busy.value = false
      }
    },
    fail: () => {
      busy.value = false
      toast('微信登录失败，请重试')
    },
  })
}
</script>
<style lang="scss" scoped>
.lg { padding: 60px 32px 30px; text-align: center; }
.logo image { width: 76px; height: 76px; }
.t { font-size: 26px; font-weight: 700; margin-top: 12px; }
.s { color: $text-2; margin: 6px 0 40px; }
.why { font-size: 12px; color: $text-3; margin-top: 10px; line-height: 1.6; }
.dev { text-align: left; margin-top: 24px; }
.lbl { font-size: 13px; color: $text-3; margin-bottom: 8px; }
.ipt { height: 48px; border-radius: 12px; background: #fff; padding: 0 14px; font-size: 16px; margin-bottom: 14px; }
.demo { font-size: 12px; color: $text-3; margin-top: 14px; line-height: 1.8; text { color: $primary; padding: 0 2px; } }
.agree { display: flex; align-items: center; justify-content: center; flex-wrap: wrap; gap: 2px; font-size: 12px; color: $text-3; margin-top: 36px; }
.ck { width: 15px; height: 15px; border-radius: 50%; border: 1px solid #c9d0cb; display: flex; align-items: center; justify-content: center; margin-right: 4px; box-sizing: border-box; }
.ck.on { background: $primary; border-color: $primary; }
.lk { color: $primary; }
</style>
