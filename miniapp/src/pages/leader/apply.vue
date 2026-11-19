<route lang="json5">
{ style: { navigationBarTitleText: '申请当团长' } }
</route>
<!-- 团长入驻申请：介绍 → 填提货点信息 → 待审核 / 未通过可修改重提 / 已通过进工作台 -->
<template>
  <view v-if="ready">
    <!-- 审核中 -->
    <view v-if="state === 'pending'" class="res">
      <view class="ic warn"><wd-icon name="time" size="34px" color="#a06500" /></view>
      <view class="h">申请已提交，正在审核</view>
      <view class="p">运营会在 1–2 个工作日内打电话给你（{{ a.leaderPhone }}），确认场地和冰柜。审核通过后用这个手机号登录，就能进团长工作台。</view>
      <view class="gb-card sum">
        <view class="gb-row"><text class="k">提货点</text><view class="v">{{ a.name }}</view></view>
        <view class="gb-row"><text class="k">地址</text><view class="v">{{ a.address }}</view></view>
        <view class="gb-row"><text class="k">提交时间</text><view class="v">{{ md(a.createTime) }}</view></view>
      </view>
    </view>

    <!-- 已通过 -->
    <view v-else-if="state === 'ok'" class="res">
      <view class="ic ok"><wd-icon name="check-circle-filled" size="38px" color="#078149" /></view>
      <view class="h">你已经是团长了</view>
      <view class="p">{{ a ? a.statusNote || '欢迎加入！下一期团起，居民就能选你的提货点下单。' : '' }}</view>
      <view style="padding: 8px 16px"><button class="btn pri block" @tap="goLeader">进入团长工作台</button></view>
    </view>

    <!-- 填写（首次 / 被拒后重填） -->
    <view v-else>
      <view v-if="state === 'rejected'" class="rej">
        <wd-icon name="error-circle" size="18px" color="#e12a24" />
        <view style="flex: 1; min-width: 0"><view class="b">上次申请没有通过</view><view class="s">{{ a.statusNote || '运营没有写原因，可以打客服电话问一下。' }}改好后可以重新提交。</view></view>
      </view>
      <view v-else class="hero">
        <view class="t">在家门口开个提货点</view>
        <view class="d">邻居在小程序下单，货每天送到你这里，你负责收货、保管、交给邻居。</view>
        <view class="pts">
          <view><text class="b">10%</text><text>按销售额拿佣金</text></view>
          <view><text class="b">0</text><text>不用囤货压钱</text></view>
          <view><text class="b">每天</text><text>送货到店</text></view>
        </view>
      </view>

      <view class="gb-card">
        <view class="gb-card-hd">提货点</view>
        <view class="f"><text class="k">名称</text><input v-model="f.name" placeholder="如：幸福里 3 号楼便利店" maxlength="32" /></view>
        <view class="f loc" @tap="pickLocation">
          <text class="k">位置</text>
          <text class="v" :class="{ ph: !f.lat }">{{ f.lat ? locText || '已标记位置' : '在地图上标一下' }}</text>
          <wd-icon name="location" size="18px" :color="f.lat ? '#078149' : '#6b716d'" />
        </view>
        <view class="f"><text class="k">详细地址</text><input v-model="f.address" placeholder="小区 + 楼栋 + 门牌号" maxlength="100" /></view>
        <view class="f"><text class="k">覆盖户数</text><input v-model="f.households" type="number" placeholder="小区大概多少户，不清楚可不填" maxlength="6" /></view>
        <view class="f"><text class="k">营业时间</text>
          <picker mode="time" :value="openFrom" @change="(e) => (openFrom = e.detail.value)"><text class="tm">{{ openFrom }}</text></picker>
          <text class="muted" style="margin: 0 8px">至</text>
          <picker mode="time" :value="openTo" @change="(e) => (openTo = e.detail.value)"><text class="tm">{{ openTo }}</text></picker>
        </view>
        <view class="f sw">
          <view style="flex: 1; min-width: 0"><view>有冰柜</view><view class="muted" style="font-size: 12px">有冰柜才能卖牛奶、鲜肉、水产等冷藏商品</view></view>
          <switch :checked="f.hasFridge" color="#078149" @change="(e) => (f.hasFridge = e.detail.value)" />
        </view>
      </view>

      <view class="gb-card">
        <view class="gb-card-hd">团长</view>
        <view class="f"><text class="k">真实姓名</text><input v-model="f.leaderName" placeholder="和身份证一致，提现时要核对" maxlength="16" /></view>
        <view class="f"><text class="k">手机号</text><input v-model="f.leaderPhone" type="number" placeholder="运营会打这个电话" maxlength="11" /></view>
        <view class="note"><textarea v-model="f.applyNote" placeholder="补充说明（选填）：比如开的什么店、平时几点在、有没有做过团购" maxlength="200" auto-height /></view>
      </view>

      <view class="agree" @tap="agreed = !agreed">
        <view class="ck" :class="{ on: agreed }"><wd-icon v-if="agreed" name="check" size="12px" color="#fff" /></view>
        <text>我会按时收货、妥善保管，把货交给下单的邻居本人</text>
      </view>
      <view style="padding: 4px 12px 0"><button class="btn pri block" :disabled="busy" @tap="submit">{{ state === 'rejected' ? '重新提交' : '提交申请' }}</button></view>
      <view class="gb-tip">提交后运营会电话联系你，审核大约 1–2 个工作日。<br />同一个小区 1.5 公里内一般只开一个提货点。</view>
    </view>
  </view>
</template>
<script lang="ts" setup>
import { api, post, md, toast, token, toLogin, subscribe } from '@/gb/util'
const ready = ref(false)
const a = ref<any>(null)
const isLeader = ref(false)
const state = computed(() => (isLeader.value ? 'ok' : a.value?.status === 'pending' ? 'pending' : a.value?.status === 'rejected' ? 'rejected' : 'new'))
const f = reactive<any>({ name: '', address: '', lat: null, lng: null, households: '', hasFridge: false, leaderName: '', leaderPhone: '', applyNote: '' })
const openFrom = ref('08:00')
const openTo = ref('21:00')
const locText = ref('')
const agreed = ref(false)
const busy = ref(false)

onShow(async () => {
  if (!token.get()) return toLogin()
  const r = await api('/leader/apply')
  isLeader.value = r.isLeader
  a.value = r.apply
  if (!ready.value) {
    if (r.apply && r.apply.status === 'rejected') {
      Object.keys(f).forEach((k) => r.apply[k] != null && (f[k] = r.apply[k]))
      f.households = r.apply.households ? String(r.apply.households) : ''
      const [x, y] = (r.apply.openHours || '08:00-21:00').split('-')
      openFrom.value = x
      openTo.value = y
      agreed.value = true
    } else {
      f.leaderPhone = r.phone || ''
    }
  }
  ready.value = true
})

function pickLocation() {
  uni.chooseLocation({
    latitude: f.lat || undefined,
    longitude: f.lng || undefined,
    success: (r) => {
      f.lat = r.latitude
      f.lng = r.longitude
      locText.value = r.name || r.address || ''
      if (!f.address && r.address) f.address = r.address + (r.name && !r.address.includes(r.name) ? r.name : '')
    },
    // 选点不可用（如浏览器预览没配地图 key）时退回当前定位
    fail: (e) => {
      if (/cancel/.test(e.errMsg || '')) return
      uni.getLocation({
        type: 'gcj02',
        success: (r) => { f.lat = r.latitude; f.lng = r.longitude; locText.value = '已用当前位置' },
        fail: () => toast('拿不到位置，可以不标，运营会和你确认'),
      })
    },
  })
}

function check(): string {
  if (!f.name.trim()) return '填一下提货点名称'
  if (f.address.trim().length < 5) return '详细地址写到楼栋门牌'
  if (!f.leaderName.trim()) return '填一下你的真实姓名'
  if (!/^1\d{10}$/.test(f.leaderPhone)) return '手机号是 11 位数字'
  if (openFrom.value >= openTo.value) return '营业结束时间要晚于开始时间'
  if (!agreed.value) return '先勾选下面的承诺'
  return ''
}
async function submit() {
  const err = check()
  if (err) return toast(err)
  await subscribe(['apply', 'ship'])
  busy.value = true
  try {
    a.value = await post('/leader/apply', { ...f, openHours: `${openFrom.value}-${openTo.value}` })
    uni.pageScrollTo({ scrollTop: 0, duration: 0 })
    toast('已提交')
  } finally {
    busy.value = false
  }
}
const goLeader = () => uni.redirectTo({ url: '/pages/leader/leader' })
</script>
<style lang="scss" scoped>
.hero { margin: 12px; padding: 18px 16px; border-radius: 12px; background: $dark; color: #fff; }
.hero .t { font-size: 20px; font-weight: 700; }
.hero .d { font-size: 13px; opacity: 0.8; margin-top: 6px; line-height: 1.6; }
.pts { display: flex; margin-top: 14px; padding-top: 12px; border-top: 1px solid rgba(255, 255, 255, 0.12); }
.pts > view { flex: 1; display: flex; flex-direction: column; font-size: 12px; opacity: 0.85; }
.pts .b { font-size: 18px; font-weight: 700; color: $mark-bright; }
.rej { margin: 12px; padding: 12px 14px; border-radius: 12px; background: $price-soft; display: flex; gap: 10px; }
.rej .b { font-weight: 700; color: $price; }
.rej .s { font-size: 13px; color: $text-2; margin-top: 2px; line-height: 1.6; }
.f { display: flex; align-items: center; min-height: 50px; padding: 0 14px; font-size: 15px; }
.gb-card-hd + .f { margin-top: 6px; }
.f + .f { border-top: 1px solid $line; }
.f .k { width: 76px; color: $text-2; flex: none; }
.f input { flex: 1; height: 50px; font-size: 15px; min-width: 0; }
.f.loc .v { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.f.loc .ph { color: #9aa19c; }
.f.sw { padding-top: 10px; padding-bottom: 10px; }
.tm { display: inline-block; padding: 4px 12px; border-radius: 6px; background: $bg; font-weight: 600; }
.note { padding: 10px 14px 14px; border-top: 1px solid $line; }
.note textarea { width: 100%; min-height: 64px; font-size: 14px; }
.agree { display: flex; align-items: center; gap: 8px; padding: 4px 16px 12px; font-size: 13px; color: $text-2; }
.ck { width: 18px; height: 18px; border-radius: 50%; border: 1px solid #c9d0cb; display: flex; align-items: center; justify-content: center; flex: none; box-sizing: border-box; }
.ck.on { background: $primary; border-color: $primary; }
.res { padding: 32px 0 0; text-align: center; }
.res .ic { width: 72px; height: 72px; border-radius: 50%; margin: 0 auto; display: flex; align-items: center; justify-content: center; }
.res .ic.warn { background: $mark-soft; }
.res .ic.ok { background: $primary-soft; }
.res .h { font-size: 19px; font-weight: 700; margin-top: 14px; }
.res .p { font-size: 14px; color: $text-2; line-height: 1.7; padding: 8px 28px 20px; }
.sum { text-align: left; }
.sum .v { max-width: 230px; text-align: right; }
</style>
