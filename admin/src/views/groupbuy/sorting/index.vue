<!-- 分拣配送：按提货点看装车 / 发车 / 签收；按商品汇总拣货量 -->
<template>
  <div class="gb-page">
    <div class="gb-head">
      <div><h1>分拣配送{{ d.period ? ' · ' + d.period.name : '' }}</h1>
        <p v-if="d.period">{{ md(d.period.cutoffTime) }} 截单，{{ md(d.period.pickupTime) }} 起居民到提货点自提，提货点签收前要送到。</p>
        <p v-else>截单后自动按提货点生成配送单。</p></div>
      <div class="acts"><a-select v-if="d.periods?.length" :value="d.period?.id" style="width: 220px" :options="d.periods.map((p) => ({ value: p.id, label: `${p.name}（${PERIOD_STATUS[p.status][0]}）` }))" @change="load" /></div>
    </div>

    <template v-if="d.period">
      <a-segmented v-model:value="view" :options="[{ value: 'pt', label: `按提货点配送${unsigned ? `（${unsigned} 个未签收）` : ''}` }, { value: 'goods', label: '按商品汇总拣货' }]" style="margin-bottom: 16px" />
      <div v-if="view === 'pt'" class="route">
        <div v-for="x in d.deliveries" :key="x.delivery.id" class="pt">
          <div class="pt-hd"><Icon icon="ant-design:environment-outlined" :size="18" class="pin" /><div style="min-width: 0"><b>{{ x.pointName }}</b><small>团长 {{ x.leaderName }} · {{ x.leaderPhone }}</small></div>
            <span class="gb-tag" :class="ST[x.delivery.status][1]">{{ ST[x.delivery.status][0] }}</span></div>
          <div class="pt-nums"><div><span>订单</span><b>{{ x.delivery.orderCount }}</b></div><div><span>商品件数</span><b>{{ x.delivery.itemCount.toLocaleString() }}</b></div><div><span>周转箱</span><b>{{ x.delivery.boxes ?? '—' }}</b></div></div>
          <ul class="pt-track">
            <li :class="trackCls(x.delivery, 0)">{{ x.delivery.status === 'sorting' ? '分拣中' : '分拣完成' }}<span class="t">{{ hm(x.delivery.sortTime) }}</span></li>
            <li :class="trackCls(x.delivery, 1)">司机发车<span class="t">{{ hm(x.delivery.shipTime) }}</span></li>
            <li :class="trackCls(x.delivery, 2)">团长签收<span class="t">{{ hm(x.delivery.signTime) }}</span></li>
          </ul>
          <div v-if="x.delivery.shortNote" class="gb-tag bad" style="align-self: flex-start"><Icon icon="ant-design:warning-outlined" :size="12" />签收登记：{{ x.delivery.shortNote }}</div>
          <div v-if="x.delivery.driver" class="gb-muted" style="font-size: 12px">司机 {{ x.delivery.driver }}</div>
          <div class="pt-ft">
            <a-button @click="printList(x)"><Icon icon="ant-design:printer-outlined" />提货清单</a-button>
            <a-button v-if="x.delivery.status === 'sorting'" type="primary" @click="openShip(x)"><Icon icon="ant-design:car-outlined" />装车发出</a-button>
            <a-button v-else-if="x.delivery.status === 'shipping'" @click="openSign(x)">代团长签收</a-button>
          </div>
        </div>
        <div v-if="!d.deliveries?.length" class="gb-card gb-empty" style="grid-column: 1 / -1"><b>这期没有配送单</b><span>截单时没有已付款订单</span></div>
      </div>
      <section v-else class="gb-card">
        <div class="gb-card-hd"><h2>拣货汇总</h2><span class="note">应拣 = 本期已付款订单的商品数量（截单前取消的已扣掉），按提货点拆开</span><a-button style="margin-left: auto" @click="printPick"><Icon icon="ant-design:printer-outlined" />打印拣货单</a-button></div>
        <a-table class="gb-table" :data-source="d.goods" :columns="gCols" :pagination="false" row-key="goodsId" size="middle">
          <template #bodyCell="{ column, record: g }">
            <template v-if="column.key === 'name'"><GoodsCell :image="g.image" :name="g.name" :spec="g.spec" small /></template>
            <template v-else-if="column.key === 'total'"><b>{{ g.total }}</b></template>
            <template v-else-if="String(column.key).startsWith('p:')">{{ g.byPoint[String(column.key).slice(2)] || 0 }}</template>
          </template>
        </a-table>
      </section>
    </template>
    <section v-else-if="!loading" class="gb-card gb-empty"><b>还没有截单的团期</b><span>本期截单后，这里会按提货点生成配送单</span></section>

    <a-modal v-model:open="shipOpen" title="装车发出" ok-text="确认发车" @ok="doShip">
      <a-form layout="vertical">
        <a-form-item label="周转箱数"><a-input-number v-model:value="ship.boxes" :min="1" style="width: 160px" /></a-form-item>
        <a-form-item label="司机（姓名 + 电话）"><a-input v-model:value="ship.driver" placeholder="如：张师傅 13900002207" /></a-form-item>
      </a-form>
    </a-modal>
    <a-modal v-model:open="signOpen" title="代团长签收" ok-text="签收" @ok="doSign">
      <p class="gb-muted">签收后，这个提货点这期的订单都会变成「待提货」，居民收到到货通知。一般由团长在小程序里签收。</p>
      <a-form layout="vertical"><a-form-item label="短少登记（没有就不填）"><a-input v-model:value="shortNote" placeholder="如：黑猪五花肉 少 1 盒" /></a-form-item></a-form>
    </a-modal>
  </div>
</template>

<script lang="ts" setup>
  import { computed, onMounted, reactive, ref } from 'vue';
  import { useRoute } from 'vue-router';
  import { Segmented as ASegmented } from 'ant-design-vue';
  import { Icon } from '/@/components/Icon';
  import { gbApi, md, toDate, PERIOD_STATUS } from '../api';
  import GoodsCell from '../GoodsCell.vue';

  defineOptions({ name: 'GbSorting' });
  const route = useRoute();
  const ST = { sorting: ['分拣中', 'warn'], shipping: ['配送中', 'pri'], signed: ['团长已签收', 'ok'] };
  const d = ref<any>({});
  const loading = ref(true);
  const view = ref('pt');
  const unsigned = computed(() => (d.value.deliveries || []).filter((x) => x.delivery.status !== 'signed').length);
  const hm = (t) => (t ? toDate(t).toTimeString().slice(0, 5) : '—');
  const trackCls = (dv, i) => { const lv = { sorting: 0, shipping: 1, signed: 3 }[dv.status]; return i < lv ? 'ok' : i === lv ? 'now' : ''; };
  const gCols = computed(() => [
    { title: '商品', key: 'name' },
    { title: '供应商', dataIndex: 'supplier', width: 140 },
    { title: '应拣', key: 'total', align: 'right' as const, width: 80 },
    ...(d.value.pointCols || []).map((p) => ({ title: p.name, key: 'p:' + p.id, align: 'right' as const, width: 120 })),
  ]);

  async function load(id?: string) {
    loading.value = true;
    try {
      d.value = await gbApi.sorting(id);
    } finally {
      loading.value = false;
    }
  }
  onMounted(() => load(route.query.periodId as string));

  const shipOpen = ref(false), signOpen = ref(false), shortNote = ref('');
  const ship = reactive<any>({ id: '', boxes: null, driver: '' });
  const openShip = (x) => { Object.assign(ship, { id: x.delivery.id, boxes: Math.max(1, Math.ceil(x.delivery.itemCount / 45)), driver: '' }); shipOpen.value = true; };
  const openSign = (x) => { ship.id = x.delivery.id; shortNote.value = ''; signOpen.value = true; };
  async function doShip() { await gbApi.ship(ship); shipOpen.value = false; load(d.value.period.id); }
  async function doSign() { await gbApi.sign({ id: ship.id, shortNote: shortNote.value }); signOpen.value = false; load(d.value.period.id); }

  // 打印：新窗口生成简单表格，浏览器打印
  function printHtml(title: string, head: string[], rows: any[][]) {
    const w = window.open('', '_blank');
    if (!w) return;
    w.document.write(`<!doctype html><meta charset="utf-8"><title>${title}</title><style>body{font:14px "Microsoft YaHei",sans-serif;padding:24px}table{border-collapse:collapse;width:100%}th,td{border:1px solid #999;padding:6px 8px;text-align:left}th{background:#eee}h1{font-size:18px}</style>
      <h1>${title}</h1><table><tr>${head.map((h) => `<th>${h}</th>`).join('')}</tr>${rows.map((r) => `<tr>${r.map((c) => `<td>${c}</td>`).join('')}</tr>`).join('')}</table><script>print()<\/script>`);
    w.document.close();
  }
  async function printList(x) {
    const r = await gbApi.orderList({ periodId: d.value.period.id, pointId: x.delivery.pointId, pageSize: 1000 });
    const rows = r.records.filter((o) => !['unpaid', 'closed'].includes(o.status)).sort((a, b) => a.phoneTail.localeCompare(b.phoneTail))
      .map((o) => [o.phoneTail, o.buyerName, o.items.map((i) => `${i.goodsName} ×${i.qty}`).join('、'), o.remark || '', '']);
    printHtml(`${d.value.period.name} · ${x.pointName} 提货清单（${rows.length} 单，按手机尾号排序）`, ['尾号', '顾客', '商品', '留言', '已取'], rows);
  }
  function printPick() {
    const cols = d.value.pointCols || [];
    printHtml(`${d.value.period.name} 拣货单`, ['商品', '规格', '应拣', ...cols.map((c) => c.name)], (d.value.goods || []).map((g) => [g.name, g.spec, g.total, ...cols.map((c) => g.byPoint[c.id] || 0)]));
  }
</script>

<style lang="less" scoped>
  .route { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 16px; }
  .pt { display: flex; flex-direction: column; gap: 12px; background: var(--brand-surface); border: 1px solid var(--brand-border-subtle); border-radius: 10px; padding: 16px; }
  .pt-hd { display: flex; align-items: flex-start; gap: 8px; .pin { color: var(--brand-primary); margin-top: 3px; }
    b { font-size: 15px; display: block; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; } small { color: var(--brand-text-3); font-size: 12px; display: block; } .gb-tag { margin-left: auto; flex: none; } }
  .pt-nums { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; padding: 10px 0; border-top: 1px solid var(--brand-border-subtle); border-bottom: 1px solid var(--brand-border-subtle);
    span { font-size: 12px; color: var(--brand-text-3); } b { display: block; font-size: 20px; } }
  .pt-track { list-style: none; margin: 0; padding: 0; font-size: 13px; color: var(--brand-text-3);
    li { display: flex; gap: 8px; align-items: center; height: 26px; &::before { content: ''; width: 8px; height: 8px; border-radius: 50%; background: var(--brand-border-subtle); flex: none; } }
    li.ok { color: var(--brand-text); &::before { background: var(--brand-primary); } }
    li.now { color: var(--brand-mark); font-weight: 600; &::before { background: var(--brand-mark); box-shadow: 0 0 0 3px var(--brand-mark-soft); } }
    .t { margin-left: auto; } }
  .pt-ft { display: flex; gap: 8px; margin-top: auto; .ant-btn { flex: 1; } }
</style>
