<!-- 退款记录：取消订单、售后同意、关单后才到账都会产生一条。提交微信失败会自动重试，超过次数的可以手动重试 -->
<template>
  <div class="gb-page">
    <div class="gb-head"><div><h1>退款记录</h1><p>每笔退款原路退回微信。提交失败会自动重试（同一个退款单号微信只退一次）；「退款异常」要到微信支付商户平台手动处理。</p></div></div>
    <section class="gb-card">
      <div class="gb-toolbar">
        <a-segmented v-model:value="st" :options="opts" @change="load(1)" />
        <a-input-search v-model:value="kw" placeholder="订单号 / 退款单号" style="width: 220px" allow-clear @search="load(1)" />
        <span class="grow"></span>
        <span v-if="!real" class="gb-tag warn">现在是模拟支付，退款只做记录</span>
        <a-button size="small" @click="load(pageNo)">刷新</a-button>
      </div>
      <a-table class="gb-table" :data-source="rows" :columns="cols" :loading="loading" :row-key="(r) => r.r.id" size="middle"
        :pagination="{ current: pageNo, pageSize: 20, total, showSizeChanger: false, onChange: load, hideOnSinglePage: true }">
        <template #bodyCell="{ column, record: x }">
          <template v-if="column.key === 'no'"><div class="gb-cell"><b class="mono">{{ x.r.refundNo }}</b><span class="sub">{{ md(x.r.createTime) }} 发起</span></div></template>
          <template v-else-if="column.key === 'order'"><div class="gb-cell"><span class="mono">{{ x.r.orderNo }}</span><span class="sub">{{ x.buyerName }} {{ x.buyerPhone }}</span></div></template>
          <template v-else-if="column.key === 'amt'"><div class="gb-cell" style="align-items: flex-end"><b>{{ yuan(x.r.amount) }}</b><span class="sub">实付 {{ yuan(x.r.total) }}</span></div></template>
          <template v-else-if="column.key === 'reason'">{{ x.r.reason }}</template>
          <template v-else-if="column.key === 'status'">
            <span class="gb-tag" :class="ST[x.r.status]?.[1]">{{ ST[x.r.status]?.[0] || x.r.status }}</span>
            <span v-if="x.r.payChannel !== 'wx'" class="gb-muted" style="font-size: 12px; margin-left: 6px">模拟</span>
            <div v-if="x.r.lastError && x.r.status !== 'success'" class="gb-muted err">{{ x.r.lastError }}</div>
            <div v-else-if="x.r.status === 'success' && x.r.payChannel === 'wx'" class="gb-muted err">{{ md(x.r.successTime) }} 到账</div>
          </template>
          <template v-else-if="column.key === 'act'"><a v-if="x.r.status === 'failed'" @click="retry(x)">重试</a></template>
        </template>
        <template #emptyText><div class="gb-empty"><b>没有退款记录</b><span>取消已付款订单、同意售后时会产生</span></div></template>
      </a-table>
    </section>
  </div>
</template>

<script lang="ts" setup>
  import { computed, onMounted, ref } from 'vue';
  import { message } from 'ant-design-vue';
  import { Segmented as ASegmented } from 'ant-design-vue';
  import { gbApi, yuan, md } from '../api';

  defineOptions({ name: 'GbRefund' });
  const ST = { pending: ['待提交', 'warn'], processing: ['退款中', 'pri'], success: ['已退回', 'ok'], failed: ['提交失败', 'bad'], abnormal: ['退款异常', 'bad'], closed: ['已关闭', ''] };
  const st = ref(''), kw = ref('');
  const rows = ref<any[]>([]), counts = ref<any>({}), total = ref(0), pageNo = ref(1), real = ref(true);
  const loading = ref(false);
  const opts = computed(() => [['', '全部'], ['processing', '退款中'], ['failed', '提交失败'], ['abnormal', '退款异常'], ['success', '已退回']].map(([v, l]) => ({ value: v, label: `${l} ${counts.value[v] ?? ''}` })));

  async function load(p = 1) {
    pageNo.value = p; loading.value = true;
    try {
      const r = await gbApi.refundList({ status: st.value, keyword: kw.value, pageNo: p, pageSize: 20 });
      rows.value = r.records; counts.value = r.counts; total.value = r.total; real.value = r.real;
    } finally {
      loading.value = false;
    }
  }
  onMounted(() => load(1));
  async function retry(x) {
    await gbApi.refundRetry({ id: x.r.id });
    message.success('已重新提交，稍后刷新看结果');
    setTimeout(() => load(pageNo.value), 1500);
  }

  const cols = [
    { title: '退款单', key: 'no', width: 210 },
    { title: '订单', key: 'order', width: 200 },
    { title: '金额', key: 'amt', align: 'right' as const, width: 110 },
    { title: '原因', key: 'reason' },
    { title: '状态', key: 'status', width: 260 },
    { title: '操作', key: 'act', width: 60 },
  ];
</script>

<style lang="less" scoped>
  .mono { font-family: Consolas, monospace; }
  .err { font-size: 12px; margin-top: 2px; }
</style>
