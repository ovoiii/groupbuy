<!-- 佣金结算：各团长的佣金比例、待结算、本月入账、可提现；改比例；看入账明细 -->
<template>
  <div class="gb-page">
    <div class="gb-head">
      <div><h1>佣金结算</h1><p>团长按自己提货点的订单拿佣金。顾客提货满 24 小时、没有进行中的售后，佣金自动进团长的可提现余额。</p></div>
      <div class="acts"><a-button @click="go('/gb/withdraw')"><Icon icon="ant-design:transaction-outlined" />提现审核<span v-if="d.waitCount" class="badge">{{ d.waitCount }}</span></a-button></div>
    </div>
    <div class="gb-kpis">
      <div class="gb-kpi"><span class="k"><Icon icon="ant-design:hourglass-outlined" :size="15" />待结算（预估）</span><span class="v">{{ yuan(d.estimate) }}</span><span class="d">已付款还没到入账时间的订单</span></div>
      <div class="gb-kpi"><span class="k"><Icon icon="ant-design:calendar-outlined" :size="15" />本月入账</span><span class="v">{{ yuan(d.month) }}</span><span class="d">累计入账 {{ yuan(d.totalIncome) }}</span></div>
      <div class="gb-kpi"><span class="k"><Icon icon="ant-design:wallet-outlined" :size="15" />团长可提现合计</span><span class="v">{{ yuan(d.balance) }}</span><span class="d">平台应付给团长、还没申请提现的钱</span></div>
      <div class="gb-kpi"><span class="k"><Icon icon="ant-design:transaction-outlined" :size="15" />待审核提现</span><span class="v">{{ d.waitCount ?? 0 }} 笔</span><span class="d">共 {{ yuan(d.waitAmount) }}</span></div>
    </div>
    <section class="gb-card">
      <a-table class="gb-table" :data-source="d.records || []" :columns="cols" :loading="loading" row-key="pointId" size="middle" :pagination="false">
        <template #bodyCell="{ column, record: r }">
          <template v-if="column.key === 'leader'"><div class="gb-cell"><b>{{ r.leaderName }}<span v-if="r.status === 'paused'" class="gb-tag" style="margin-left: 6px">已暂停</span></b><span class="sub">{{ r.pointName }}</span></div></template>
          <template v-else-if="column.key === 'rate'"><a class="rate" @click="editRate(r)">{{ pct(r.rate, 1) }}<Icon icon="ant-design:edit-outlined" :size="12" /></a></template>
          <template v-else-if="column.key === 'estimate'"><span class="gb-muted">{{ yuan(r.estimate) }}</span></template>
          <template v-else-if="column.key === 'month'">{{ yuan(r.month) }}</template>
          <template v-else-if="column.key === 'total'"><div class="gb-r">{{ yuan(r.totalIncome) }}<span class="sub">已提现 {{ yuan(r.totalWithdrawn) }}</span></div></template>
          <template v-else-if="column.key === 'balance'"><div class="gb-r"><b>{{ yuan(r.balance) }}</b><span v-if="Number(r.frozen) > 0" class="sub warn">审核中 {{ yuan(r.frozen) }}</span></div></template>
          <template v-else-if="column.key === 'act'"><a @click="detail(r)">入账明细</a></template>
        </template>
        <template #emptyText><div class="gb-empty"><b>还没有合作中的团长</b></div></template>
      </a-table>
    </section>
    <p class="gb-foot">
      佣金 = 计佣金额 × 比例。计佣金额按订单商品金额算（优惠券由平台出，不扣团长的）；退过款的按「实付里没退的比例」折算，全退的没有佣金。
      比例在居民付款时记到订单上，改比例只影响之后付款的订单。没提货的订单没有佣金。
    </p>

    <a-modal v-model:open="rateOpen" :title="cur ? '佣金比例 · ' + cur.leaderName : ''" ok-text="保存" @ok="saveRate">
      <p class="gb-muted">只影响之后付款的订单，已付款的按原比例结算。</p>
      <a-input-number v-model:value="rate" :min="0" :max="30" :step="0.5" :precision="1" addon-after="%" style="width: 160px" />
    </a-modal>

    <a-drawer v-model:open="listOpen" :title="cur ? cur.leaderName + ' · 佣金入账明细' : ''" :width="720">
      <template v-if="cur">
        <div class="sum"><div><span>可提现</span><b>{{ yuan(cur.balance) }}</b></div><div><span>审核中</span><b>{{ yuan(cur.frozen) }}</b></div><div><span>累计入账</span><b>{{ yuan(cur.totalIncome) }}</b></div><div><span>累计提现</span><b>{{ yuan(cur.totalWithdrawn) }}</b></div></div>
        <a-table class="gb-table" :data-source="list" :columns="lcols" :loading="listLoading" :row-key="(x) => x.c.id" size="small"
          :pagination="{ current: pageNo, pageSize: 20, total, showSizeChanger: false, onChange: loadList }">
          <template #bodyCell="{ column, record: x }">
            <template v-if="column.key === 'order'"><div class="gb-cell"><span>{{ x.c.orderNo }}</span><span class="sub">{{ x.periodName }} · {{ x.c.buyerName }}</span></div></template>
            <template v-else-if="column.key === 'base'"><div class="gb-r">{{ yuan(x.c.baseAmount) }}<span v-if="Number(x.c.refundAmount) > 0" class="sub">商品 {{ yuan(x.c.goodsAmount) }}，退过 {{ yuan(x.c.refundAmount) }}</span></div></template>
            <template v-else-if="column.key === 'rate'">{{ pct(x.c.rate, 1) }}</template>
            <template v-else-if="column.key === 'amt'"><b class="plus">+{{ yuan(x.c.amount) }}</b></template>
            <template v-else-if="column.key === 'time'">{{ md(x.c.settleTime) }}</template>
          </template>
          <template #emptyText><div class="gb-empty"><b>还没有入账记录</b><span>顾客提货满 24 小时后自动入账</span></div></template>
        </a-table>
      </template>
    </a-drawer>
  </div>
</template>

<script lang="ts" setup>
  import { onMounted, ref } from 'vue';
  import { useRouter } from 'vue-router';
  import { Icon } from '/@/components/Icon';
  import { gbApi, yuan, pct, md } from '../api';

  defineOptions({ name: 'GbCommission' });
  const router = useRouter();
  const go = (path: string) => router.push(path);
  const d = ref<any>({});
  const loading = ref(false);
  async function load() {
    loading.value = true;
    try {
      d.value = await gbApi.commissionSummary();
    } finally {
      loading.value = false;
    }
  }
  onMounted(load);

  const cur = ref<any>(null);
  const rateOpen = ref(false), rate = ref(10);
  function editRate(r) { cur.value = r; rate.value = Math.round(Number(r.rate) * 1000) / 10; rateOpen.value = true; }
  async function saveRate() {
    await gbApi.pointRate({ id: cur.value.pointId, rate: (Number(rate.value || 0) / 100).toFixed(4) });
    rateOpen.value = false; load();
  }

  const listOpen = ref(false), list = ref<any[]>([]), total = ref(0), pageNo = ref(1), listLoading = ref(false);
  async function loadList(p = 1) {
    pageNo.value = p; listLoading.value = true;
    try {
      const r = await gbApi.commissionList({ pointId: cur.value.pointId, pageNo: p, pageSize: 20 });
      list.value = r.records; total.value = r.total;
    } finally {
      listLoading.value = false;
    }
  }
  function detail(r) { cur.value = r; list.value = []; listOpen.value = true; loadList(1); }

  const cols = [
    { title: '团长 / 提货点', key: 'leader' },
    { title: '佣金比例', key: 'rate', width: 110 },
    { title: '待结算（预估）', key: 'estimate', align: 'right' as const, width: 140 },
    { title: '本月入账', key: 'month', align: 'right' as const, width: 130 },
    { title: '累计入账', key: 'total', align: 'right' as const, width: 150 },
    { title: '可提现', key: 'balance', align: 'right' as const, width: 150 },
    { title: '操作', key: 'act', width: 100 },
  ];
  const lcols = [
    { title: '订单', key: 'order' },
    { title: '计佣金额', key: 'base', align: 'right' as const, width: 180 },
    { title: '比例', key: 'rate', width: 70 },
    { title: '佣金', key: 'amt', align: 'right' as const, width: 100 },
    { title: '入账时间', key: 'time', width: 110 },
  ];
</script>

<style lang="less" scoped>
  .rate { display: inline-flex; align-items: center; gap: 4px; font-weight: 600; }
  .warn { color: var(--brand-mark) !important; }
  .plus { color: var(--brand-success); }
  .badge { margin-left: 6px; min-width: 18px; height: 18px; line-height: 18px; padding: 0 5px; border-radius: 9px; background: var(--brand-danger, #e12a24); color: #fff; font-size: 12px; display: inline-block; }
  .sum { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 16px;
    > div { padding: 12px 14px; border-radius: 8px; background: var(--brand-bg-page); display: flex; flex-direction: column; gap: 2px; }
    span { font-size: 12px; color: var(--brand-text-3); } b { font-size: 16px; } }
</style>
