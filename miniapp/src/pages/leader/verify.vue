<route lang="json5">
{ style: { navigationBarTitleText: '核销提货' } }
</route>
<!-- 核销：扫码 / 输入提货码 / 从列表点「交货」进来；逐件点货，全部点完才能确认交货 -->
<template>
  <view>
    <template v-if="!o && !done">
      <view class="cam" @tap="scan"><view class="fr"><view /><view /><view /><view /></view><text>点这里打开相机扫顾客的提货码</text></view>
      <view class="gb-card">
        <view class="gb-card-hd">或者输入 6 位提货码</view>
        <view class="code-in"><input v-model="code" type="number" maxlength="6" class="ipt" placeholder="••••••" :focus="!!manual" @input="onCode" /></view>
        <view class="muted" style="font-size: 12px; padding: 0 14px 14px; text-align: center">顾客没带手机：回工作台按手机尾号找订单</view>
      </view>
    </template>
    <template v-else-if="o && !done">
      <view class="gb-card" style="margin-top: 12px">
        <view class="who"><text class="tail">{{ o.phoneTail }}</text><view style="flex: 1"><view class="b">{{ o.buyerName }}</view><view class="s">提货码 {{ o.pickupCode }} · {{ o.periodName }}</view></view><text class="tag" :class="o.status === 'arrived' ? 'ok' : 'warn'">{{ STATUS[o.status] }}</text></view>
        <view v-if="o.status !== 'arrived'" class="warnbox">{{ o.status === 'done' ? `这单 ${md(o.pickupAt)} 已经提过货了，不要重复交货` : '货还没到这个提货点，不能交货' }}</view>
        <view v-else-if="o.afterStatus === 'applying'" class="warnbox">这单正在售后，先别交货，等平台处理</view>
        <template v-else>
          <view class="gb-card-hd">逐件点货<text class="more">点一下表示已拿给顾客</text></view>
          <view v-for="it in o.items" :key="it.id" class="it" @tap="toggle(it.id)">
            <view class="ck" :class="{ on: got.includes(it.id) }"><wd-icon v-if="got.includes(it.id)" name="check" size="14px" color="#fff" /></view>
            <image :src="img(it.image)" mode="aspectFill" />
            <view style="flex: 1; min-width: 0"><view class="b">{{ it.goodsName }}</view><view class="s">{{ it.spec }}</view></view>
            <text class="n">× {{ it.qty }}</text>
          </view>
          <view v-if="o.remark" class="remark">顾客留言：{{ o.remark }}</view>
        </template>
      </view>
      <view class="gb-tip">少了或坏了：先别交货，让顾客在小程序申请售后</view>
      <view class="actbar-space" />
      <view class="actbar">
        <view class="sum">已点 <text style="font-weight: 700">{{ got.length }}</text> / {{ o.items.length }} 种<text class="sub">{{ allGot ? '核对无误可以交货' : '全部点完才能确认' }}</text></view>
        <button class="btn pri" style="width: 150px" :disabled="!allGot || o.status !== 'arrived' || o.afterStatus === 'applying'" @tap="confirm">确认交货</button>
      </view>
    </template>
    <view v-else class="done">
      <view class="ic"><wd-icon name="check" size="38px" color="#fff" /></view>
      <view class="h">交货完成</view>
      <view class="p">{{ o.buyerName }} · {{ o.qty }} 件<br />顾客会收到「已提货」通知</view>
      <button class="btn pri block" @tap="next">继续核销下一单</button>
      <button class="btn line block" style="margin-top: 10px" @tap="back">回工作台</button>
    </view>
  </view>
</template>
<script lang="ts" setup>
import { api, post, img, md, STATUS } from '@/gb/util'
const o = ref<any>(null)
const code = ref('')
const got = ref<string[]>([])
const done = ref(false)
const manual = ref(false)
const allGot = computed(() => o.value && got.value.length === o.value.items.length)
async function query(p: any) {
  o.value = await api('/leader/verify/query', p).catch(() => null)
  got.value = []
}
onLoad((q: any) => {
  if (q.code) query({ code: q.code })
  else if (q.orderId) query({ orderId: q.orderId })
  else manual.value = true
})
function onCode() { if (code.value.length === 6) query({ code: code.value }) }
function scan() {
  uni.scanCode({ success: (r) => query({ code: String(r.result).replace(/^GB:/, '') }), fail: () => {} })
}
const toggle = (id) => (got.value = got.value.includes(id) ? got.value.filter((x) => x !== id) : [...got.value, id])
async function confirm() {
  await post('/leader/verify', { orderId: o.value.id })
  done.value = true
}
function next() { o.value = null; done.value = false; code.value = ''; got.value = [] }
const back = () => uni.navigateBack()
</script>
<style lang="scss" scoped>
.cam { margin: 12px; height: 230px; border-radius: 14px; background: #1a221d; position: relative; display: flex; align-items: flex-end; justify-content: center; padding-bottom: 14px; box-sizing: border-box; color: rgba(255, 255, 255, 0.8); font-size: 14px; }
.fr { position: absolute; width: 160px; height: 160px; top: 22px; left: 50%; margin-left: -80px; }
.fr view { position: absolute; width: 26px; height: 26px; border: 3px solid $mark-bright; }
.fr view:nth-child(1) { left: 0; top: 0; border-right: 0; border-bottom: 0; }
.fr view:nth-child(2) { right: 0; top: 0; border-left: 0; border-bottom: 0; }
.fr view:nth-child(3) { left: 0; bottom: 0; border-right: 0; border-top: 0; }
.fr view:nth-child(4) { right: 0; bottom: 0; border-left: 0; border-top: 0; }
.code-in { padding: 14px; }
.ipt { height: 56px; border-radius: 12px; background: $bg; text-align: center; font: 700 26px Consolas, monospace; letter-spacing: 8px; }
.who { display: flex; align-items: center; gap: 12px; padding: 14px; border-bottom: 1px solid $line; }
.tail { width: 56px; height: 48px; line-height: 48px; text-align: center; border-radius: 10px; background: $primary-soft; color: #006134; font: 700 18px Consolas, monospace; flex: none; }
.b { font-size: 16px; font-weight: 600; }
.s { font-size: 13px; color: $text-2; }
.warnbox { margin: 14px; padding: 12px; border-radius: 10px; background: $mark-soft; color: $mark; font-size: 14px; }
.it { display: flex; gap: 10px; align-items: center; padding: 10px 14px; }
.it image { width: 52px; height: 52px; border-radius: 8px; background: $sunken; flex: none; }
.it .n { font: 700 20px Consolas, monospace; }
.remark { margin: 4px 14px 14px; padding: 10px; border-radius: 8px; background: $bg; font-size: 13px; color: $text-2; }
.done { text-align: center; padding: 50px 24px; }
.done .ic { width: 72px; height: 72px; margin: 0 auto 14px; border-radius: 50%; background: $primary; display: flex; align-items: center; justify-content: center; }
.done .h { font-size: 20px; font-weight: 700; }
.done .p { color: $text-2; margin: 6px 0 24px; line-height: 1.7; }
</style>
