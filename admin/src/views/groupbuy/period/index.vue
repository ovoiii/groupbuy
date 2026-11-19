<!-- 每日团期：本期团品（改库存 / 下架 / 加团品）+ 全部团期 -->
<template>
  <div class="gb-page">
    <div class="gb-head">
      <div><h1>每日团期</h1><p>每天一期：前一晚开团、当晚截单、次日到提货点自提。到点自动开团、截单，不用手动操作。</p></div>
      <div class="acts"><a-button type="primary" @click="router.push('/gb/period/edit')"><Icon icon="ant-design:plus-outlined" />准备下一期团</a-button></div>
    </div>

    <section class="gb-card" v-if="cur">
      <div class="cur-hd">
        <h2>{{ cur.period.name }}</h2><span class="gb-tag" :class="PERIOD_STATUS[cur.period.status][1]">{{ cur.statusText }}</span>
        <span class="meta">团品 <b>{{ onCount }}</b> 个在售</span><span class="meta">已售 <b>{{ cur.soldQty }}</b> 件</span><span class="meta">销售额 <b>{{ yuan(cur.gmv) }}</b></span>
        <span class="grow"></span>
        <span v-if="cur.period.status === 'live'" class="mk-cutoff"><Icon icon="ant-design:clock-circle-outlined" :size="13" />距截单 <b>{{ hms(leftSec(cur.period.cutoffTime)) }}</b></span>
        <span class="mk-pickup"><Icon icon="ant-design:environment-outlined" :size="13" /><b>{{ md(cur.period.pickupTime) }}</b> 起可提</span>
      </div>
      <div class="gb-toolbar">
        <a-segmented v-model:value="filter" :options="filterOpts" />
        <span class="grow"></span>
        <a-button v-if="['plan', 'live', 'draft'].includes(cur.period.status)" @click="openAdd"><Icon icon="ant-design:plus-outlined" />从商品库加团品</a-button>
      </div>
      <a-table class="gb-table" :data-source="rows" :columns="cols" :pagination="false" row-key="id" size="middle" :row-class-name="(r) => (r.status === 'off' ? 'off' : '')">
        <template #bodyCell="{ column, record: g }">
          <template v-if="column.key === 'name'">
            <GoodsCell :image="g.image" :name="g.name" :spec="g.spec"><template #tag><span v-if="g.tag" class="gb-tag warn" style="margin-left: 6px">{{ g.tag }}</span></template></GoodsCell>
          </template>
          <template v-else-if="column.key === 'price'"><div class="gb-r"><span class="gb-price">{{ yuan(g.price) }}</span><span class="sub"><s>{{ yuan(g.origPrice) }}</s></span></div></template>
          <template v-else-if="column.key === 'margin'"><div class="gb-r"><span :class="{ 'gb-danger': g.margin < 0.25 }">{{ pct(g.margin) }}</span><span class="sub">采购 {{ yuan(g.costPrice) }}</span></div></template>
          <template v-else-if="column.key === 'stock'">
            <div v-if="editing === g.id" class="stock-edit">库存 <a-input-number v-model:value="editStock" :min="g.sold" size="small" style="width: 84px" /><a @click="saveStock(g)">保存</a><a class="gb-muted" @click="editing = ''">取消</a></div>
            <div v-else-if="g.status === 'off'" class="gb-r">已下架</div>
            <div v-else class="gb-r">{{ g.sold }} / {{ g.stock }}<span class="gb-bar" :class="{ hot: hot(g) }"><i :style="{ width: (g.stock ? (g.sold / g.stock) * 100 : 0) + '%' }"></i></span>
              <span class="sub" :class="{ 'gb-mark': hot(g) }">{{ hot(g) ? `只剩 ${g.left}，可能截单前售罄` : `剩 ${g.left}` }}</span></div>
          </template>
          <template v-else-if="column.key === 'limit'">{{ g.limitQty ? g.limitQty + ' 件' : '不限' }}</template>
          <template v-else-if="column.key === 'supplier'"><div class="gb-cell"><span>{{ g.supplier }}</span><span class="sub">{{ g.origin || '—' }} · {{ STORAGE[g.storage] }}</span></div></template>
          <template v-else-if="column.key === 'act'">
            <div class="gb-acts" v-if="canEdit">
              <template v-if="g.status === 'on'"><a @click="editing = g.id; editStock = g.stock + (hot(g) ? 100 : 0)">改库存</a><a class="muted" @click="toggle(g, 'off')">下架</a></template>
              <a v-else @click="toggle(g, 'on')">重新上架</a>
            </div>
          </template>
        </template>
        <template #emptyText><div class="gb-empty"><b>没有符合条件的团品</b></div></template>
      </a-table>
    </section>
    <section v-else-if="!loading" class="gb-card"><div class="gb-empty"><b>现在没有进行中的团期</b><a-button type="primary" @click="router.push('/gb/period/edit')">准备下一期团</a-button></div></section>

    <section class="gb-card">
      <div class="gb-card-hd"><h2>全部团期</h2><span class="note">提货率 = 已提货订单 ÷ 已到货订单</span></div>
      <a-table class="gb-table" :data-source="periods" :columns="pCols" :pagination="false" :row-key="(r) => r.period.id" size="middle">
        <template #bodyCell="{ column, record: r }">
          <template v-if="column.key === 'name'"><a @click="load(r.period.id)"><b>{{ r.period.name }}</b></a></template>
          <template v-else-if="column.key === 'time'"><span class="tl"><b>开团</b>{{ md(r.period.openTime) }}　<b>截单</b>{{ md(r.period.cutoffTime) }}　<b>提货</b>{{ md(r.period.pickupTime) }}</span></template>
          <template v-else-if="column.key === 'orders'">{{ r.orders ? r.orders.toLocaleString() : '—' }}</template>
          <template v-else-if="column.key === 'gmv'">{{ r.orders ? yuan(r.gmv) : '—' }}</template>
          <template v-else-if="column.key === 'rate'"><div class="gb-r">{{ r.pickupRate == null ? '—' : pct(r.pickupRate) }}<span v-if="r.notPicked" class="sub">还有 {{ r.notPicked }} 单未提</span></div></template>
          <template v-else-if="column.key === 'status'"><span class="gb-tag" :class="PERIOD_STATUS[r.period.status][1]">{{ r.statusText }}</span></template>
          <template v-else-if="column.key === 'act'">
            <div class="gb-acts">
              <a v-if="['draft', 'plan'].includes(r.period.status)" @click="router.push({ path: '/gb/period/edit', query: { id: r.period.id } })">编辑团品</a>
              <a v-else-if="r.period.status === 'cutoff'" @click="router.push({ path: '/gb/sorting', query: { periodId: r.period.id } })">看分拣配送</a>
              <a v-else @click="router.push({ path: '/gb/orders', query: { periodId: r.period.id } })">看订单</a>
              <a class="muted" @click="router.push({ path: '/gb/period/edit', query: { copy: r.period.id } })">复制为新团期</a>
            </div>
          </template>
        </template>
      </a-table>
    </section>

    <a-modal v-model:open="addOpen" title="从商品库加团品" :width="560" ok-text="加入本期" @ok="doAdd">
      <a-input v-model:value="addQ" placeholder="搜商品名" allow-clear style="margin-bottom: 12px" />
      <div class="pick-list">
        <label v-for="g in addList" :key="g.goods.id" class="pick-item" :class="{ dis: inSet.has(g.goods.id) || g.goods.status === 'off' }">
          <a-checkbox :checked="addSel.includes(g.goods.id) || inSet.has(g.goods.id)" :disabled="inSet.has(g.goods.id) || g.goods.status === 'off'" @change="(e) => pick(g.goods.id, e.target.checked)" />
          <GoodsCell :image="g.goods.images?.split(',')[0]" :name="g.goods.name"><template #sub>{{ g.goods.spec }} · {{ g.goods.status === 'off' ? '已下架' : '建议价 ' + yuan(g.goods.suggestPrice) }}</template></GoodsCell>
          <span class="gb-tag">{{ inSet.has(g.goods.id) ? '已在本期' : g.goods.category }}</span>
        </label>
      </div>
    </a-modal>
  </div>
</template>

<script lang="ts" setup>
  import { computed, onMounted, ref } from 'vue';
  import { useRouter, useRoute } from 'vue-router';
  import { Modal } from 'ant-design-vue';
  import { Segmented as ASegmented } from 'ant-design-vue';
  import { Icon } from '/@/components/Icon';
  import { gbApi, yuan, pct, md, hms, PERIOD_STATUS, STORAGE } from '../api';
  import { useClock, syncServerTime } from '../useClock';
  import GoodsCell from '../GoodsCell.vue';

  defineOptions({ name: 'GbPeriod' });
  const router = useRouter();
  const route = useRoute();
  const { leftSec } = useClock();
  const loading = ref(true);
  const cur = ref<any>(null);
  const periods = ref<any[]>([]);
  const filter = ref('');
  const editing = ref('');
  const editStock = ref(0);
  const hot = (g) => g.status === 'on' && g.stock > 0 && g.left * 10 <= g.stock;
  const canEdit = computed(() => cur.value && ['draft', 'plan', 'live'].includes(cur.value.period.status));
  const onCount = computed(() => (cur.value?.goods || []).filter((g) => g.status === 'on').length);
  const filterOpts = computed(() => {
    const g = cur.value?.goods || [];
    return [
      { value: '', label: `全部 ${g.length}` },
      { value: 'hot', label: `快卖完 ${g.filter(hot).length}` },
      { value: 'limit', label: `限购 ${g.filter((x) => x.limitQty).length}` },
      { value: 'off', label: `已下架 ${g.filter((x) => x.status === 'off').length}` },
    ];
  });
  const rows = computed(() => (cur.value?.goods || []).filter((g) => !filter.value || (filter.value === 'hot' ? hot(g) : filter.value === 'limit' ? g.limitQty : g.status === 'off')));

  async function load(id?: string) {
    loading.value = true;
    try {
      const [meta, d, list] = await Promise.all([gbApi.meta(), gbApi.periodDetail(id), gbApi.periodList()]);
      syncServerTime(meta.now);
      cur.value = d;
      periods.value = list;
    } finally {
      loading.value = false;
    }
  }
  onMounted(() => load(route.query.id as string));

  async function saveStock(g) {
    await gbApi.periodGoodsUpdate({ id: g.id, stock: editStock.value });
    editing.value = '';
    load(cur.value.period.id);
  }
  function toggle(g, status) {
    if (status === 'off')
      Modal.confirm({ title: `下架「${g.name}」？`, content: '已下单的照常履约，居民在小程序里看不到这个团品了。', okText: '下架', okType: 'danger',
        onOk: async () => { await gbApi.periodGoodsUpdate({ id: g.id, status }); load(cur.value.period.id); } });
    else gbApi.periodGoodsUpdate({ id: g.id, status }).then(() => load(cur.value.period.id));
  }

  // 加团品
  const addOpen = ref(false), addQ = ref(''), addSel = ref<string[]>([]), allGoods = ref<any[]>([]);
  const inSet = computed(() => new Set((cur.value?.goods || []).map((g) => g.goodsId)));
  const addList = computed(() => allGoods.value.filter((g) => !addQ.value || g.goods.name.includes(addQ.value)));
  const pick = (id, on) => (addSel.value = on ? [...addSel.value, id] : addSel.value.filter((x) => x !== id));
  async function openAdd() {
    addSel.value = [];
    addOpen.value = true;
    allGoods.value = (await gbApi.goodsList({ pageSize: 200 })).records;
  }
  async function doAdd() {
    if (!addSel.value.length) return (addOpen.value = false);
    await gbApi.periodGoodsAdd({ periodId: cur.value.period.id, goodsIds: addSel.value });
    addOpen.value = false;
    load(cur.value.period.id);
  }

  const cols = [
    { title: '团品', key: 'name' },
    { title: '团购价', key: 'price', align: 'right' as const, width: 110 },
    { title: '毛利', key: 'margin', align: 'right' as const, width: 110 },
    { title: '已售 / 本期库存', key: 'stock', align: 'right' as const, width: 230 },
    { title: '每人限购', key: 'limit', align: 'right' as const, width: 90 },
    { title: '供应商', key: 'supplier', width: 160 },
    { title: '操作', key: 'act', width: 130 },
  ];
  const pCols = [
    { title: '团期', key: 'name', width: 120 },
    { title: '时间', key: 'time' },
    { title: '团品', dataIndex: 'goodsCount', align: 'right' as const, width: 70 },
    { title: '订单', key: 'orders', align: 'right' as const, width: 80 },
    { title: '销售额', key: 'gmv', align: 'right' as const, width: 120 },
    { title: '提货率', key: 'rate', align: 'right' as const, width: 120 },
    { title: '状态', key: 'status', width: 110 },
    { title: '操作', key: 'act', width: 190 },
  ];
</script>

<style lang="less" scoped>
  .cur-hd { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; padding: 16px 20px; border-bottom: 1px solid var(--brand-border-subtle);
    h2 { font-size: 16px; } .meta { font-size: 13px; color: var(--brand-text-2); b { color: var(--brand-text); } } .grow { flex: 1; } }
  .stock-edit { display: flex; align-items: center; gap: 8px; justify-content: flex-end; }
  .tl { font-size: 13px; color: var(--brand-text-2); white-space: nowrap; b { color: var(--brand-text); font-weight: 600; margin-right: 4px; } }
  .pick-list { max-height: 420px; overflow-y: auto; display: flex; flex-direction: column; gap: 2px; }
  .pick-item { display: grid; grid-template-columns: 20px minmax(0, 1fr) auto; gap: 12px; align-items: center; padding: 8px; border-radius: 6px; cursor: pointer;
    &:hover { background: var(--brand-bg-page); } &.dis { opacity: 0.55; cursor: default; } }
</style>
