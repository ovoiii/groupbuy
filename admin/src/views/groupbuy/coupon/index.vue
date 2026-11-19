<!-- 优惠活动：满减券 / 折扣券 / 新人 1 元购，领取与核销统计，新建编辑、停止发放、追加数量 -->
<template>
  <div class="gb-page">
    <div class="gb-head">
      <div><h1>优惠活动</h1><p>满减券、折扣券和新人 1 元购。优惠金额由平台承担；一单只能用一张，结算时自动选最划算的。</p></div>
      <div class="acts"><a-button type="primary" @click="edit(null)"><Icon icon="ant-design:plus-outlined" />新建优惠</a-button></div>
    </div>
    <div class="gb-kpis c3">
      <div class="gb-kpi"><span class="k"><Icon icon="ant-design:tags-outlined" :size="15" />本期用券订单</span><span class="v">{{ d.couponOrders ?? 0 }}</span><span class="d">占本期订单 {{ d.liveOrders ? pct(d.couponOrders / d.liveOrders, 1) : '—' }}</span></div>
      <div class="gb-kpi"><span class="k"><Icon icon="ant-design:wallet-outlined" :size="15" />本期优惠金额</span><span class="v">{{ yuan(d.discount) }}</span><span class="d">占本期销售额 {{ d.gmv ? pct(d.discount / d.gmv, 1) : '—' }}</span></div>
      <div class="gb-kpi"><span class="k"><Icon icon="ant-design:gift-outlined" :size="15" />本期新人首单</span><span class="v">{{ d.newbieOrders ?? 0 }}</span><span class="d">用「新人 1 元购」下的首单</span></div>
    </div>
    <section class="gb-card">
      <div class="gb-toolbar"><a-segmented v-model:value="type" :options="opts" @change="load" /><span class="grow"></span><span class="note">核销率 = 已使用 ÷ 已领取</span></div>
      <a-table class="gb-table" :data-source="d.records || []" :columns="cols" row-key="id" size="middle" :pagination="false">
        <template #bodyCell="{ column, record: c }">
          <template v-if="column.key === 'name'"><div class="face"><span class="val" :class="{ out: c.status !== 'on' }">{{ faceVal(c) }}</span><div class="gb-cell"><b>{{ c.name }}</b><span class="sub">{{ TYPES[c.type] }} · {{ rule(c) }}</span></div></div></template>
          <template v-else-if="column.key === 'scope'">{{ c.type === 'newbie' ? goodsName(c.newbieGoodsId) : c.scopeCategory || '全部商品' }}</template>
          <template v-else-if="column.key === 'valid'">{{ c.validDays ? `领取后 ${c.validDays} 天` : `${md(c.validStart).slice(0, 5)} – ${md(c.validEnd).slice(0, 5)}` }}</template>
          <template v-else-if="column.key === 'got'"><div class="rate"><span><b>{{ c.received.toLocaleString() }}</b>{{ c.total ? ' / ' + c.total.toLocaleString() : ' 人领取（不限量）' }}</span><span v-if="c.total" class="gb-bar" :class="{ hot: c.received >= c.total }" style="width: 100%; margin: 0"><i :style="{ width: Math.min(100, (c.received / c.total) * 100) + '%' }"></i></span></div></template>
          <template v-else-if="column.key === 'used'"><template v-if="c.received"><b>{{ pct(c.used / c.received) }}</b> <span class="gb-muted" style="font-size: 12px">{{ c.used }} 张</span></template><template v-else>—</template></template>
          <template v-else-if="column.key === 'status'"><span class="gb-tag" :class="statusOf(c)[1]">{{ statusOf(c)[0] }}</span></template>
          <template v-else-if="column.key === 'act'">
            <div class="gb-acts"><a @click="edit(c)">编辑</a>
              <a v-if="c.total && c.received >= c.total" @click="add(c)">追加 1000 张</a>
              <a v-else-if="c.status === 'on'" class="muted" @click="stop(c)">停止发放</a>
              <a v-else class="muted" @click="gbApi.couponStatus({ id: c.id, status: 'on' }).then(load)">恢复发放</a></div>
          </template>
        </template>
      </a-table>
    </section>

    <a-drawer v-model:open="open" :title="form.id ? '编辑优惠' : '新建优惠'" :width="600" destroy-on-close>
      <a-form layout="vertical">
        <a-form-item label="类型"><a-radio-group v-model:value="form.type" button-style="solid" :disabled="!!form.id"><a-radio-button value="full">满减券</a-radio-button><a-radio-button value="discount">折扣券</a-radio-button><a-radio-button value="newbie">新人 1 元购</a-radio-button></a-radio-group></a-form-item>
        <a-form-item label="名称（居民能看到）" required><a-input v-model:value="form.name" :maxlength="20" /></a-form-item>
        <div class="fg2">
          <template v-if="form.type !== 'newbie'"><a-form-item label="使用门槛"><a-input-number v-model:value="form.threshold" :min="0" addon-after="元起用" style="width: 100%" /></a-form-item></template>
          <a-form-item v-if="form.type === 'full'" label="减免" required><a-input-number v-model:value="form.amount" :min="1" addon-after="元" style="width: 100%" /></a-form-item>
          <template v-if="form.type === 'discount'">
            <a-form-item label="折扣" required extra="0.9 = 9 折"><a-input-number v-model:value="form.rate" :min="0.1" :max="0.99" :step="0.05" style="width: 100%" /></a-form-item>
            <a-form-item label="最多减"><a-input-number v-model:value="form.maxDiscount" :min="0" addon-after="元" style="width: 100%" /></a-form-item>
            <a-form-item label="限类目"><a-select v-model:value="form.scopeCategory" allow-clear placeholder="全部商品" :options="cats.map((c) => ({ value: c, label: c }))" /></a-form-item>
          </template>
          <a-form-item v-if="form.type === 'newbie'" label="1 元购的商品" required><a-select v-model:value="form.newbieGoodsId" :options="(d.goods || []).map((g) => ({ value: g.id, label: g.name }))" /></a-form-item>
          <a-form-item label="发放总量" extra="0 = 不限量"><a-input-number v-model:value="form.total" :min="0" addon-after="张" style="width: 100%" /></a-form-item>
          <a-form-item label="有效期">
            <a-radio-group v-model:value="validMode"><a-radio value="days">领取后几天</a-radio><a-radio value="range">固定日期</a-radio></a-radio-group>
            <a-input-number v-if="validMode === 'days'" v-model:value="form.validDays" :min="1" addon-after="天" style="width: 100%; margin-top: 8px" />
            <a-range-picker v-else v-model:value="range" style="width: 100%; margin-top: 8px" />
          </a-form-item>
        </div>
        <a-form-item label="领取方式"><a-radio-group v-model:value="form.channel"><a-radio value="home">小程序首页领券条</a-radio><a-radio value="admin">只能后台发放</a-radio></a-radio-group></a-form-item>
        <a-form-item label="小程序里长这样"><div class="preview"><span class="val">{{ faceVal(form) }}</span><div class="gb-cell"><b>{{ form.name || '未命名' }}</b><span class="sub">{{ rule(form) }}</span></div><span class="get">领取</span></div></a-form-item>
      </a-form>
      <template #footer><div style="text-align: right"><a-button style="margin-right: 8px" @click="open = false">取消</a-button><a-button type="primary" @click="save">保存</a-button></div></template>
    </a-drawer>
  </div>
</template>

<script lang="ts" setup>
  import { computed, onMounted, reactive, ref } from 'vue';
  import dayjs from 'dayjs';
  import { Modal } from 'ant-design-vue';
  import { Segmented as ASegmented } from 'ant-design-vue';
  import { Icon } from '/@/components/Icon';
  import { gbApi, yuan, pct, md, toDate } from '../api';

  defineOptions({ name: 'GbCoupon' });
  const TYPES = { full: '满减券', discount: '折扣券', newbie: '新人专享' };
  const cats = ['水果', '蔬菜', '肉禽蛋', '水产', '乳品烘焙', '粮油', '日用'];
  const type = ref('');
  const d = ref<any>({});
  const opts = computed(() => [['', '全部'], ['full', '满减券'], ['discount', '折扣券'], ['newbie', '新人专享']].map(([v, l]) => ({ value: v, label: `${l} ${d.value.counts?.[v] ?? ''}` })));
  const goodsName = (id) => (d.value.goods || []).find((g) => g.id === id)?.name || '—';
  const num = (v) => String(Number(v || 0));
  const faceVal = (c) => (c.type === 'full' ? `¥${num(c.amount)}` : c.type === 'discount' ? `${num((c.rate || 0) * 10)} 折` : '1 元购');
  const rule = (c) => (c.type === 'full' ? `满 ${num(c.threshold)} 元可用` : c.type === 'discount' ? `${c.scopeCategory || '全部商品'} ${num((c.rate || 0) * 10)} 折${c.maxDiscount ? '，最多减 ' + num(c.maxDiscount) : ''}` : '首单 1 元买一件指定商品');
  const statusOf = (c) => {
    if (c.status !== 'on') return ['已停止', ''];
    if (c.total && c.received >= c.total) return ['已领完', ''];
    if (c.validStart && toDate(c.validStart) > new Date()) return ['未开始', 'pri'];
    if (c.validEnd && toDate(c.validEnd) < new Date()) return ['已过期', ''];
    return ['进行中', 'ok'];
  };
  async function load() { d.value = await gbApi.couponList(type.value); }
  onMounted(load);

  const stop = (c) => Modal.confirm({ title: `停止发放「${c.name}」？`, content: '已领的券到期前仍然可以用。', okText: '停止', onOk: () => gbApi.couponStatus({ id: c.id, status: 'stop' }).then(load) });
  const add = (c) => gbApi.couponStatus({ id: c.id, addTotal: 1000 }).then(load);

  const open = ref(false), validMode = ref('days'), range = ref<any>(null);
  const form = reactive<any>({});
  function edit(c) {
    Object.keys(form).forEach((k) => delete form[k]);
    Object.assign(form, c ? { ...c } : { type: 'full', name: '满 39 减 5', threshold: 39, amount: 5, total: 5000, validDays: 7, channel: 'home', perLimit: 1 });
    validMode.value = form.validDays ? 'days' : 'range';
    range.value = form.validStart ? [dayjs(form.validStart), dayjs(form.validEnd)] : null;
    open.value = true;
  }
  async function save() {
    const body = { ...form };
    if (validMode.value === 'days') { body.validStart = null; body.validEnd = null; }
    else { body.validDays = null; body.validStart = range.value?.[0]?.startOf('day').format('YYYY-MM-DD HH:mm:ss'); body.validEnd = range.value?.[1]?.endOf('day').format('YYYY-MM-DD HH:mm:ss'); }
    if (body.type === 'newbie') body.threshold = 0;
    await gbApi.couponSave(body);
    open.value = false; load();
  }

  const cols = [
    { title: '优惠', key: 'name' },
    { title: '适用范围', key: 'scope', width: 130 },
    { title: '有效期', key: 'valid', width: 150 },
    { title: '领取 / 发放', key: 'got', width: 190 },
    { title: '核销率', key: 'used', width: 120 },
    { title: '状态', key: 'status', width: 90 },
    { title: '操作', key: 'act', width: 160 },
  ];
</script>

<style lang="less" scoped>
  .face { display: flex; align-items: center; gap: 12px; }
  .val { min-width: 72px; height: 48px; padding: 0 8px; border-radius: 8px; background: var(--gb-price-soft); color: var(--gb-price); display: grid; place-items: center; font-weight: 700; font-size: 17px; flex: none;
    &.out { background: var(--brand-bg-sunken); color: var(--brand-text-3); } }
  .rate { display: flex; flex-direction: column; gap: 4px; span { font-size: 12px; color: var(--brand-text-3); b { color: var(--brand-text); } } }
  .fg2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
  .preview { display: flex; gap: 12px; align-items: center; padding: 12px; border-radius: 10px; background: var(--brand-bg-page);
    .get { margin-left: auto; height: 28px; padding: 0 14px; border-radius: 14px; background: var(--gb-price); color: #fff; font-size: 13px; display: grid; place-items: center; } }
</style>
