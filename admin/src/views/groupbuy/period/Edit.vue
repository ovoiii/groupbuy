<!-- 新建 / 编辑团期：时间、提货点范围、团品（价格 / 库存 / 限购），发布后到点自动开团 -->
<template>
  <div class="gb-page pe">
    <div class="gb-head">
      <div><h1>{{ title }}</h1><p>发布后到开团时间（{{ md(form.openTime) }}）自动在小程序上架，开团前随时可以改。</p></div>
      <div class="acts"><a-button type="text" @click="router.push('/gb/period')">返回团期列表</a-button></div>
    </div>

    <section class="gb-card">
      <div class="gb-card-hd"><h2 class="sec"><span class="n">1</span>时间与范围</h2></div>
      <div class="gb-card-bd">
        <a-form layout="vertical">
          <div class="fg">
            <a-form-item label="团期名称" required><a-input v-model:value="form.name" /></a-form-item>
            <a-form-item label="开团时间" required extra="一般接在上一期截单之后"><a-date-picker v-model:value="form.openTime" show-time format="YYYY-MM-DD HH:mm" style="width: 100%" /></a-form-item>
            <a-form-item label="截单时间" required extra="截单后不能再下单，开始汇总分拣"><a-date-picker v-model:value="form.cutoffTime" show-time format="YYYY-MM-DD HH:mm" style="width: 100%" /></a-form-item>
            <a-form-item label="开始提货" required extra="居民在小程序看到「明天 16:00 起可提」"><a-date-picker v-model:value="form.pickupTime" show-time format="YYYY-MM-DD HH:mm" style="width: 100%" /></a-form-item>
            <a-form-item label="提货截止" required extra="超过还没取的，团长联系或转售后"><a-date-picker v-model:value="form.pickupEndTime" show-time format="YYYY-MM-DD HH:mm" style="width: 100%" /></a-form-item>
            <a-form-item label="开团的提货点">
              <a-radio-group v-model:value="scopeMode" button-style="solid"><a-radio-button value="all">全部合作中（{{ okPoints.length }} 个）</a-radio-button><a-radio-button value="pick">指定提货点</a-radio-button></a-radio-group>
              <a-select v-if="scopeMode === 'pick'" v-model:value="form.pointScope" mode="multiple" placeholder="选提货点" style="margin-top: 8px; width: 100%" :options="okPoints.map((p) => ({ value: p.id, label: p.name }))" />
            </a-form-item>
          </div>
        </a-form>
      </div>
    </section>

    <section class="gb-card">
      <div class="gb-card-hd"><h2 class="sec"><span class="n">2</span>团品</h2><span class="note">毛利低于 25% 标红；库存按供应商当天能送的量填</span>
        <span style="margin-left: auto; display: flex; gap: 8px"><a-button @click="copyLast" :disabled="!lastId"><Icon icon="ant-design:reload-outlined" />照搬上一期</a-button><a-button type="primary" @click="openAdd"><Icon icon="ant-design:plus-outlined" />从商品库添加</a-button></span></div>
      <a-table class="gb-table" :data-source="items" :columns="cols" :pagination="false" row-key="goodsId" size="middle">
        <template #bodyCell="{ column, record: it, index }">
          <template v-if="column.key === 'name'"><GoodsCell :image="it.image" :name="it.name"><template #sub>{{ it.spec }} · 采购 {{ yuan(it.costPrice) }}</template></GoodsCell></template>
          <template v-else-if="column.key === 'price'"><a-input-number v-model:value="it.price" :min="0.01" :precision="2" addon-after="元" style="width: 130px" /></template>
          <template v-else-if="column.key === 'margin'"><div class="gb-r"><span :class="{ 'gb-danger': margin(it) < 0.25 }">{{ pct(margin(it)) }}</span><span class="sub">参考价 {{ yuan(it.origPrice) }}</span></div></template>
          <template v-else-if="column.key === 'stock'"><a-input-number v-model:value="it.stock" :min="0" :precision="0" addon-after="件" style="width: 120px" /></template>
          <template v-else-if="column.key === 'limit'"><a-input-number v-model:value="it.limitQty" :min="0" :precision="0" placeholder="不限" addon-after="件" style="width: 104px" /></template>
          <template v-else-if="column.key === 'last'"><div class="gb-r">{{ it.lastSold ?? '—' }}<span v-if="it.lastStock && it.lastSold / it.lastStock >= 0.9" class="sub gb-mark">上期快卖完</span></div></template>
          <template v-else-if="column.key === 'act'"><a-button type="text" danger @click="items.splice(index, 1)"><Icon icon="ant-design:delete-outlined" /></a-button></template>
        </template>
        <template #emptyText><div class="gb-empty"><b>还没有团品</b><span>从商品库添加，或照搬上一期</span></div></template>
      </a-table>
    </section>

    <div class="sum-bar">
      <span>团品 <b>{{ items.length }}</b> 个</span><span>全部卖完销售额 <b>{{ yuan(cap) }}</b></span>
      <span v-if="lowCount" class="gb-danger">{{ lowCount }} 个毛利低于 25%</span>
      <span class="grow"></span>
      <a-button :loading="saving" @click="save(false)">保存草稿</a-button>
      <a-button type="primary" :loading="saving" @click="save(true)">发布，到点自动开团</a-button>
    </div>

    <a-modal v-model:open="addOpen" title="从商品库添加" :width="560" ok-text="加入本期" @ok="doAdd">
      <a-input v-model:value="addQ" placeholder="搜商品名" allow-clear style="margin-bottom: 12px" />
      <div class="pick-list">
        <label v-for="g in addList" :key="g.goods.id" class="pick-item" :class="{ dis: inSet.has(g.goods.id) || g.goods.status === 'off' }">
          <a-checkbox :checked="addSel.includes(g.goods.id) || inSet.has(g.goods.id)" :disabled="inSet.has(g.goods.id) || g.goods.status === 'off'" @change="(e) => pick(g.goods.id, e.target.checked)" />
          <GoodsCell :image="g.goods.images?.split(',')[0]" :name="g.goods.name"><template #sub>{{ g.goods.spec }} · {{ g.goods.status === 'off' ? '已下架：' + (g.goods.offReason || '') : '建议价 ' + yuan(g.goods.suggestPrice) }}</template></GoodsCell>
          <span class="gb-tag">{{ inSet.has(g.goods.id) ? '已在本期' : g.goods.category }}</span>
        </label>
      </div>
    </a-modal>
  </div>
</template>

<script lang="ts" setup>
  import { computed, onMounted, reactive, ref } from 'vue';
  import { useRouter, useRoute } from 'vue-router';
  import dayjs from 'dayjs';
  import { message } from 'ant-design-vue';
  import { Icon } from '/@/components/Icon';
  import { gbApi, yuan, pct, md } from '../api';
  import GoodsCell from '../GoodsCell.vue';

  defineOptions({ name: 'GbPeriodEdit' });
  const router = useRouter();
  const route = useRoute();
  const form = reactive<any>({ id: null, name: '', openTime: null, cutoffTime: null, pickupTime: null, pickupEndTime: null, pointScope: [] });
  const scopeMode = ref('all');
  const items = ref<any[]>([]);
  const okPoints = ref<any[]>([]);
  const lastId = ref<string>('');
  const saving = ref(false);
  const title = computed(() => (form.id ? `编辑 ${form.name}` : `准备 ${form.name || '下一期团'}`));
  const margin = (it) => (it.price ? (it.price - it.costPrice) / it.price : 0);
  const cap = computed(() => items.value.reduce((s, it) => s + (it.price || 0) * (it.stock || 0), 0));
  const lowCount = computed(() => items.value.filter((it) => margin(it) < 0.25).length);

  const fromDetail = (g) => ({ goodsId: g.goodsId, name: g.name, spec: g.spec, image: g.image, costPrice: g.costPrice, origPrice: g.origPrice, price: g.price, stock: g.stock, limitQty: g.limitQty || null, lastSold: g.lastSold ?? g.sold, lastStock: g.lastStock ?? g.stock, tag: g.tag });

  onMounted(async () => {
    const meta = await gbApi.meta();
    okPoints.value = meta.points.filter((p) => p.status === 'ok');
    const next = await gbApi.periodNext();
    lastId.value = next.lastId;
    const id = route.query.id as string, copy = route.query.copy as string;
    if (id) {
      const d = await gbApi.periodDetail(id);
      Object.assign(form, { id: d.period.id, name: d.period.name, openTime: dayjs(d.period.openTime), cutoffTime: dayjs(d.period.cutoffTime), pickupTime: dayjs(d.period.pickupTime), pickupEndTime: dayjs(d.period.pickupEndTime) });
      if (d.period.pointScope) { scopeMode.value = 'pick'; form.pointScope = d.period.pointScope.split(','); }
      items.value = d.goods.map(fromDetail);
    } else {
      Object.assign(form, { name: next.name, openTime: dayjs(next.openTime), cutoffTime: dayjs(next.cutoffTime), pickupTime: dayjs(next.pickupTime), pickupEndTime: dayjs(next.pickupEndTime) });
      if (copy || next.lastId) await loadFrom(copy || next.lastId, false);
    }
  });

  async function loadFrom(pid: string, toast = true) {
    const d = await gbApi.periodDetail(pid);
    // 已下架的商品不带过来；上期快卖完的库存 +100
    items.value = d.goods.filter((g) => g.goodsStatus === 'on').map((g) => ({ ...fromDetail(g), lastSold: g.sold, lastStock: g.stock, stock: g.stock > 0 && g.left * 10 <= g.stock ? g.stock + 100 : g.stock }));
    if (toast) message.success(`已照搬 ${d.period.name} 的 ${items.value.length} 个团品`);
  }
  const copyLast = () => loadFrom(lastId.value);

  async function save(publish: boolean) {
    const f = (v) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : null);
    saving.value = true;
    try {
      await gbApi.periodSave({
        period: { id: form.id, name: form.name, openTime: f(form.openTime), cutoffTime: f(form.cutoffTime), pickupTime: f(form.pickupTime), pickupEndTime: f(form.pickupEndTime), pointScope: scopeMode.value === 'pick' ? form.pointScope : null },
        goods: items.value.map((it) => ({ goodsId: it.goodsId, price: it.price, stock: it.stock, limitQty: it.limitQty || 0, tag: it.tag })),
        publish,
      });
      router.push('/gb/period');
    } finally {
      saving.value = false;
    }
  }

  const addOpen = ref(false), addQ = ref(''), addSel = ref<string[]>([]), allGoods = ref<any[]>([]);
  const inSet = computed(() => new Set(items.value.map((g) => g.goodsId)));
  const addList = computed(() => allGoods.value.filter((g) => !addQ.value || g.goods.name.includes(addQ.value)));
  const pick = (id, on) => (addSel.value = on ? [...addSel.value, id] : addSel.value.filter((x) => x !== id));
  async function openAdd() {
    addSel.value = [];
    addOpen.value = true;
    allGoods.value = (await gbApi.goodsList({ pageSize: 200 })).records;
  }
  function doAdd() {
    for (const id of addSel.value) {
      const g = allGoods.value.find((x) => x.goods.id === id).goods;
      items.value.push({ goodsId: g.id, name: g.name, spec: g.spec, image: g.images?.split(',')[0], costPrice: g.costPrice, origPrice: g.origPrice, price: g.suggestPrice, stock: 100, limitQty: null });
    }
    addOpen.value = false;
  }

  const cols = [
    { title: '商品', key: 'name' },
    { title: '团购价', key: 'price', width: 150 },
    { title: '毛利', key: 'margin', align: 'right' as const, width: 110 },
    { title: '本期库存', key: 'stock', width: 140 },
    { title: '每人限购', key: 'limit', width: 124 },
    { title: '上期已售', key: 'last', align: 'right' as const, width: 100 },
    { title: '', key: 'act', width: 56 },
  ];
</script>

<style lang="less" scoped>
  .pe .gb-card + .gb-card { margin-top: 16px; }
  .sec { display: flex; align-items: center; gap: 10px; .n { width: 22px; height: 22px; border-radius: 50%; display: grid; place-items: center; background: var(--brand-primary-soft); color: var(--brand-primary); font-size: 12px; } }
  .fg { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0 20px; :deep(.ant-form-item:last-child) { grid-column: span 2; } }
  .sum-bar { position: sticky; bottom: 0; z-index: 5; display: flex; align-items: center; gap: 24px; flex-wrap: wrap; padding: 12px 24px; margin: 16px -24px -32px; background: var(--brand-surface); border-top: 1px solid var(--brand-border-subtle); font-size: 13px; color: var(--brand-text-2);
    b { color: var(--brand-text); } .grow { flex: 1; } }
  .pick-list { max-height: 420px; overflow-y: auto; display: flex; flex-direction: column; gap: 2px; }
  .pick-item { display: grid; grid-template-columns: 20px minmax(0, 1fr) auto; gap: 12px; align-items: center; padding: 8px; border-radius: 6px; cursor: pointer;
    &:hover { background: var(--brand-bg-page); } &.dis { opacity: 0.55; cursor: default; } }
</style>
