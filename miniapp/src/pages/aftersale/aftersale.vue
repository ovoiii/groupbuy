<route lang="json5">
{ style: { navigationBarTitleText: '申请售后' } }
</route>
<!-- 申请售后：选商品、选原因、拍照；退款金额按实付比例由后台算 -->
<template>
  <view v-if="o">
    <view class="gb-card" style="margin-top: 12px">
      <view class="gb-card-hd">哪件有问题</view>
      <view v-for="it in o.items" :key="it.id" class="it" @tap="toggle(it.id)">
        <view class="ck" :class="{ on: sel.includes(it.id) }"><wd-icon v-if="sel.includes(it.id)" name="check" size="14px" color="#fff" /></view>
        <image :src="img(it.image)" mode="aspectFill" />
        <view class="m"><view class="nm">{{ it.goodsName }}</view><view class="sp">{{ it.spec }} × {{ it.qty }}</view></view>
        <Price :value="it.amount" :size="15" />
      </view>
    </view>
    <view class="gb-card">
      <view class="gb-card-hd">遇到了什么问题</view>
      <view class="chips"><view v-for="r in REASONS" :key="r" class="chip" :class="{ on: r === type }" @tap="type = r"><text>{{ r }}</text></view></view>
      <textarea v-model="reason" class="ta" maxlength="200" placeholder="说说具体情况，比如：打开有 3 个蛋碎了" />
      <view class="ph">
        <view v-for="(u, i) in photos" :key="u" class="p"><image :src="u" mode="aspectFill" /><view class="x" @tap="photos.splice(i, 1)">×</view></view>
        <view v-if="photos.length < 4" class="add" @tap="choose"><wd-icon name="camera" size="22px" color="#6b716d" /><text>拍照</text></view>
      </view>
      <view class="muted" style="font-size: 12px; padding: 0 14px 14px">{{ type === '没来得及取' ? '团长会先帮你保管，提货期结束仍没取再退款' : type === '质量问题' || type === '破损' ? '质量问题和破损请拍清楚商品，至少 1 张' : '照片选填' }}</view>
    </view>
    <view class="gb-tip">审核通过原路退回微信；一般 2 小时内处理，最晚 24 小时，超时自动退款</view>
    <view class="actbar-space" />
    <view class="actbar"><view class="sum">退款金额以审核结果为准<text class="sub">用了优惠券的按实付比例退</text></view><button class="btn pri" :loading="busy" @tap="submit">提交</button></view>
  </view>
</template>
<script lang="ts" setup>
import { api, post, img, toast, upload } from '@/gb/util'
import Price from '@/gb/Price.vue'
const REASONS = ['质量问题', '少件', '破损', '发错货', '没来得及取']
const o = ref<any>(null)
const sel = ref<string[]>([])
const type = ref('质量问题')
const reason = ref('')
const photos = ref<string[]>([])
const busy = ref(false)
onLoad(async (q: any) => {
  o.value = await api('/order/detail', { id: q.id })
  sel.value = o.value.items.length === 1 ? [o.value.items[0].id] : []
})
const toggle = (id) => (sel.value = sel.value.includes(id) ? sel.value.filter((x) => x !== id) : [...sel.value, id])
function choose() {
  uni.chooseImage({ count: 4 - photos.value.length, sizeType: ['compressed'], success: (r) => (photos.value = [...photos.value, ...(r.tempFilePaths as string[])]) })
}
async function submit() {
  if (!sel.value.length) return toast('先选有问题的商品')
  if ((type.value === '质量问题' || type.value === '破损') && !photos.value.length) return toast('请拍一张照片')
  busy.value = true
  try {
    const paths: string[] = []
    for (const p of photos.value) paths.push(await upload(p))
    await post('/aftersale/apply', { orderId: o.value.id, itemIds: sel.value, type: type.value, reason: reason.value, images: paths.join(',') })
    toast('已提交，处理结果会通知你')
    setTimeout(() => uni.navigateBack(), 800)
  } finally {
    busy.value = false
  }
}
</script>
<style lang="scss" scoped>
.it { display: flex; gap: 10px; align-items: center; padding: 10px 14px; }
.it image { width: 56px; height: 56px; border-radius: 8px; background: $sunken; flex: none; }
.it .m { flex: 1; min-width: 0; }
.it .nm { font-size: 15px; font-weight: 600; }
.it .sp { font-size: 12px; color: $text-3; }
.chips { display: flex; flex-wrap: wrap; gap: 8px; padding: 12px 14px 4px; }
.chip { height: 44px; display: flex; align-items: center; }
.chip text { height: 34px; line-height: 34px; padding: 0 14px; border-radius: 17px; background: $bg; font-size: 14px; color: $text-2; }
.chip.on text { background: $primary-soft; color: #006134; font-weight: 600; box-shadow: inset 0 0 0 1px #a4deb7; }
.ta { width: 100%; height: 72px; padding: 4px 14px; font-size: 16px; box-sizing: border-box; }
.ph { display: flex; gap: 10px; padding: 4px 14px 12px; flex-wrap: wrap; }
.p, .add { width: 76px; height: 76px; border-radius: 8px; position: relative; }
.p image { width: 76px; height: 76px; border-radius: 8px; }
.x { position: absolute; top: -6px; right: -6px; width: 22px; height: 22px; line-height: 20px; text-align: center; border-radius: 50%; background: rgba(0, 0, 0, 0.55); color: #fff; font-size: 16px; }
.add { border: 1px dashed #8f9691; display: flex; flex-direction: column; align-items: center; justify-content: center; font-size: 12px; color: $text-3; box-sizing: border-box; }
</style>
