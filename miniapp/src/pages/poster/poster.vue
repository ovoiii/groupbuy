<route lang="json5">
{ style: { navigationBarTitleText: '分享海报', navigationBarBackgroundColor: '#13221A', navigationBarTextStyle: 'white', backgroundColor: '#13221A' } }
</route>
<!-- 分享海报：type=goods&id=团品 id（商品海报）或 type=home（本期团海报）。图由后端画好（带小程序码和提货点），这里下载下来显示、保存到相册 -->
<template>
  <view class="pp">
    <view v-if="err" class="state">
      <view>{{ err }}</view>
      <button class="btn sm line" style="margin-top: 14px" @tap="load">重试</button>
    </view>
    <view v-else-if="!src" class="state"><wd-loading color="#fff" /><view style="margin-top: 12px">正在生成海报…</view></view>
    <template v-else>
      <image :src="src" mode="widthFix" class="img" show-menu-by-longpress @tap="preview" />
      <view class="bar">
        <!-- #ifdef MP-WEIXIN -->
        <button class="btn pri block" :loading="saving" @tap="save">保存到相册</button>
        <view class="tip">保存后发到朋友圈或小区群，邻居长按识别小程序码就能下单，自动选你的提货点</view>
        <!-- #endif -->
        <!-- #ifndef MP-WEIXIN -->
        <view class="tip">长按图片保存到手机，发到朋友圈或小区群</view>
        <!-- #endif -->
      </view>
    </template>
  </view>
</template>
<script lang="ts" setup>
import { downloadPoster, toast } from '@/gb/util'
const src = ref('')
const err = ref('')
const saving = ref(false)
let type = 'goods'
let id = ''

async function load() {
  err.value = ''
  src.value = ''
  try {
    src.value = await downloadPoster(type, id)
  } catch (e: any) {
    err.value = e?.message || '海报没生成出来，请稍后再试'
  }
}
onLoad((q: any) => {
  type = q.type === 'home' ? 'home' : 'goods'
  id = q.id || ''
  uni.setNavigationBarTitle({ title: type === 'home' ? '本期团海报' : '商品海报' })
  load()
})

const preview = () => uni.previewImage({ urls: [src.value] })

function save() {
  saving.value = true
  uni.saveImageToPhotosAlbum({
    filePath: src.value,
    success: () => toast('已保存到相册'),
    fail: (e: any) => {
      if (/auth|deny|authorize/i.test(e?.errMsg || '')) {
        uni.showModal({
          title: '需要相册权限',
          content: '保存海报要用到相册，去设置里打开「添加到相册」',
          confirmText: '去设置',
          success: (r) => r.confirm && uni.openSetting({}),
        })
      } else if (!/cancel/.test(e?.errMsg || '')) {
        toast('没保存成功，可以长按图片保存')
      }
    },
    complete: () => (saving.value = false),
  })
}
</script>
<style lang="scss" scoped>
.pp { min-height: 100vh; background: $dark; padding: 16px 24px 40px; box-sizing: border-box; }
.img { width: 100%; border-radius: 12px; display: block; box-shadow: 0 8px 30px rgba(0, 0, 0, 0.35); }
.state { color: #cfd8d2; text-align: center; padding-top: 40vh; font-size: 14px; }
.bar { margin-top: 20px; }
.tip { color: #9fb0a6; font-size: 12px; text-align: center; margin-top: 12px; line-height: 1.7; }
</style>
