<!-- 订单管理：按状态 / 团期 / 提货点筛选，详情抽屉（进度、提货、金额），取消退款、代核销、提醒取货 -->
<template>
  <div class="gb-page">
    <div class="gb-head">
      <div><h1>订单管理</h1><p>居民在小程序下的所有订单。截单前居民可以自己取消，截单后只能走售后。手机号已脱敏。</p></div>
    </div>
    <section class="gb-card">
      <div class="gb-toolbar" style="border-bottom: 0; padding-bottom: 0"><a-segmented v-model:value="q.status" :options="statusOpts" @change="load(1)" /></div>
      <div class="gb-toolbar">
        <a-input-search v-model:value="q.keyword" placeholder="订单号 / 手机尾号 / 提货码" allow-clear style="width: 260px" @search="load(1)" />
        <a-select v-model:value="q.periodId" style="width: 160px" :options="[{ value: '', label: '全部团期' }, ...periods.map((p) => ({ value: p.id, label: p.name }))]" @change="load(1)" />
        <a-select v-model:value="q.pointId" style="width: 200px" :options="[{ value: '', label: '全部提货点' }, ...points.map((p) => ({ value: p.id, label: p.name }))]" @change="load(1)" />
        <span class="grow"></span><span class="note">实付 = 商品金额 − 优惠</span>
      </div>
      <a-table class="gb-table" :data-source="rows" :columns="cols" :loading="loading" row-key="id" size="middle"
        :pagination="{ current: page.current, pageSize: 20, total: page.total, showTotal: (t) => `共 ${t} 单`, onChange: load }">
        <template #bodyCell="{ column, record: o }">
          <template v-if="column.key === 'no'"><div class="gb-cell"><b>{{ o.orderNo }}</b><span class="sub">{{ md(o.createTime) }} 下单 · {{ o.periodName }}</span></div></template>
          <template v-else-if="column.key === 'buyer'"><div class="gb-cell"><span>{{ o.buyerName }}</span><span class="sub">{{ o.buyerPhone }}</span></div></template>
          <template v-else-if="column.key === 'goods'">
            <div class="thumbs"><img v-for="it in o.items.slice(0, 3)" :key="it.id" :src="img(it.image)" alt="" /><span class="more">{{ o.items.length > 1 ? `${o.items[0].goodsName} 等 ${o.qty} 件` : `${o.items[0]?.goodsName} × ${o.qty}` }}</span></div>
          </template>
          <template v-else-if="column.key === 'point'"><div class="gb-cell"><span>{{ o.pointName }}</span><span class="sub">团长 {{ o.leaderName }}</span></div></template>
          <template v-else-if="column.key === 'pay'"><div class="gb-r"><b>{{ yuan(o.payAmount) }}</b><span v-if="o.discountAmount > 0" class="sub">{{ o.couponName }} −{{ o.discountAmount }}</span><span v-if="o.refundAmount > 0" class="sub gb-danger">已退 {{ yuan(o.refundAmount) }}</span></div></template>
          <template v-else-if="column.key === 'status'">
            <span class="gb-tag" :class="ORDER_STATUS[o.status][1]">{{ ORDER_STATUS[o.status][0] }}</span>
            <div v-if="o.afterStatus !== 'none'" class="after" :class="o.afterStatus">{{ AFTER[o.afterStatus] }}</div>
            <div v-else-if="o.closeReason" class="after">{{ o.closeReason }}</div>
          </template>
          <template v-else-if="column.key === 'act'">
            <div class="gb-acts"><a @click="detail(o)">详情</a>
              <a v-if="o.afterStatus === 'applying'" @click="router.push('/gb/aftersale')">去审核</a>
              <a v-else-if="o.status === 'arrived'" class="muted" @click="remind(o)">提醒取货</a></div>
          </template>
        </template>
        <template #emptyText><div class="gb-empty"><b>没有符合条件的订单</b><span>试试切到「全部」或清空搜索</span></div></template>
      </a-table>
    </section>

    <a-drawer v-model:open="open" :title="cur ? `订单 ${cur.orderNo}` : ''" :width="580">
      <template v-if="cur">
        <div class="gb-sec">
          <span class="gb-tag" :class="ORDER_STATUS[cur.status][1]">{{ ORDER_STATUS[cur.status][0] }}</span>
          <span v-if="cur.closeReason" class="gb-muted" style="margin-left: 8px">{{ cur.closeReason }}</span>
          <div class="gb-steps" style="margin-top: 16px">
            <div v-for="(s, i) in steps" :key="s[0]" class="step" :class="{ done: i < reached, now: i === reached && cur.status !== 'closed' }"><b>{{ s[0] }}</b>{{ s[1] }}</div>
          </div>
        </div>
        <div class="gb-sec"><h3>提货</h3>
          <div class="code-box"><Icon icon="ant-design:qrcode-outlined" :size="24" /><div><div class="gb-muted" style="font-size: 12px">提货码（只有买家和团长能看到完整的）</div><div class="code">{{ cur.pickupCode || '付款后生成' }}</div></div>
            <span class="mk-pickup" style="margin-left: auto"><Icon icon="ant-design:environment-outlined" :size="13" /><b>{{ md(cur.pickupTime) }}</b> 起可提</span></div>
          <dl class="gb-dl" style="margin-top: 14px"><dt>提货点</dt><dd>{{ cur.pointName }}<br /><span class="gb-muted">{{ cur.pointAddress }}</span></dd>
            <dt>团长</dt><dd>{{ cur.leaderName }} · {{ cur.leaderPhone }}</dd><dt>买家</dt><dd>{{ cur.buyerName }} · {{ cur.buyerPhone }}</dd>
            <dt v-if="cur.remark">留言</dt><dd v-if="cur.remark">{{ cur.remark }}</dd>
            <dt v-if="cur.delivery">配送</dt><dd v-if="cur.delivery">{{ DELIVERY[cur.delivery.status] }}{{ cur.delivery.shortNote ? '，签收时登记：' + cur.delivery.shortNote : '' }}</dd></dl>
        </div>
        <div class="gb-sec"><h3>商品</h3>
          <div class="od-items">
            <div v-for="it in cur.items" :key="it.id" class="od-item"><GoodsCell :image="it.image" :name="it.goodsName" :spec="it.spec"><template #tag><span v-if="it.newbie" class="gb-tag pri" style="margin-left: 6px">新人 1 元</span></template></GoodsCell><span class="gb-muted">× {{ it.qty }}</span><span class="gb-r">{{ yuan(it.amount) }}</span></div>
            <div class="od-sum"><span>商品金额</span><b>{{ yuan(cur.goodsAmount) }}</b>
              <template v-if="cur.discountAmount > 0"><span>{{ cur.couponName }}</span><b class="gb-price">−{{ yuan(cur.discountAmount) }}</b></template>
              <span>实付（微信支付）</span><b style="font-size: 16px">{{ yuan(cur.payAmount) }}</b>
              <template v-if="cur.refundAmount > 0"><span>已退款</span><b class="gb-danger">{{ yuan(cur.refundAmount) }}</b></template></div>
          </div>
        </div>
        <div v-if="cur.aftersales?.length" class="gb-sec"><h3>售后</h3>
          <div v-for="a in cur.aftersales" :key="a.id" class="as-row"><span class="gb-tag" :class="{ warn: a.status === 'wait', ok: a.status === 'done' }">{{ { wait: '待审核', done: '已退款', rej: '已驳回' }[a.status] }}</span> {{ a.type }}：{{ a.reason }}<span v-if="a.reply" class="gb-muted">　回复：{{ a.reply }}</span></div>
        </div>
      </template>
      <template #footer>
        <div v-if="cur" style="display: flex; justify-content: flex-end; gap: 8px">
          <a-button v-if="['unpaid', 'paid', 'shipping', 'arrived'].includes(cur.status)" danger @click="cancel">取消订单{{ cur.payTime ? '并退款' : '' }}</a-button>
          <a-button v-if="cur.status === 'arrived'" @click="remind(cur)">提醒取货</a-button>
          <a-button v-if="cur.status === 'arrived'" type="primary" @click="verify">代团长核销</a-button>
          <a-button @click="open = false">关闭</a-button>
        </div>
      </template>
    </a-drawer>
  </div>
</template>

<script lang="ts" setup>
  import { computed, onMounted, reactive, ref } from 'vue';
  import { useRoute, useRouter } from 'vue-router';
  import { Modal } from 'ant-design-vue';
  import { Segmented as ASegmented } from 'ant-design-vue';
  import { Icon } from '/@/components/Icon';
  import { gbApi, yuan, md, img, ORDER_STATUS } from '../api';
  import GoodsCell from '../GoodsCell.vue';

  defineOptions({ name: 'GbOrder' });
  const route = useRoute();
  const router = useRouter();
  const AFTER = { applying: '售后中', refunded: '已退款', rejected: '售后已驳回' };
  const DELIVERY = { sorting: '分拣中', shipping: '配送中', signed: '团长已签收' };
  const q = reactive({ status: '', keyword: '', periodId: (route.query.periodId as string) || '', pointId: '' });
  const rows = ref<any[]>([]), counts = ref<any>({}), periods = ref<any[]>([]), points = ref<any[]>([]);
  const page = reactive({ current: 1, total: 0 });
  const loading = ref(false);
  const statusOpts = computed(() => [['', '全部'], ['unpaid', '待付款'], ['paid', '待截单'], ['shipping', '配送中'], ['arrived', '待提货'], ['done', '已提货'], ['refund', '售后中'], ['closed', '已关闭']]
    .map(([v, l]) => ({ value: v, label: `${l} ${counts.value[v] ?? ''}` })));

  async function load(p = page.current) {
    loading.value = true;
    try {
      const r = await gbApi.orderList({ ...q, pageNo: p, pageSize: 20 });
      rows.value = r.records; counts.value = r.counts; page.current = r.current; page.total = r.total;
    } finally {
      loading.value = false;
    }
  }
  onMounted(async () => {
    const meta = await gbApi.meta();
    periods.value = meta.periods; points.value = meta.points;
    load(1);
  });

  const open = ref(false), cur = ref<any>(null);
  async function detail(o) {
    cur.value = await gbApi.orderDetail(o.id);
    open.value = true;
  }
  const steps = computed(() => cur.value ? [['下单', md(cur.value.createTime)], ['截单', md(cur.value.cutoffTime)], ['到提货点', md(cur.value.pickupTime)], ['提货', cur.value.pickupAt ? md(cur.value.pickupAt) : '—']] : []);
  const reached = computed(() => ({ unpaid: 0, paid: 1, shipping: 2, arrived: 3, done: 4, closed: 0 })[cur.value?.status] ?? 0);
  const remind = async (o) => { await gbApi.orderRemind(o.id); };
  function cancel() {
    Modal.confirm({ title: '取消这个订单？', content: cur.value.payTime ? `会原路退款 ${yuan(cur.value.payAmount)}，并放回库存。` : '会放回库存。', okText: '取消订单', okType: 'danger',
      onOk: async () => { await gbApi.orderCancel(cur.value.id); open.value = false; load(); } });
  }
  async function verify() {
    await gbApi.orderVerify(cur.value.id);
    open.value = false;
    load();
  }

  const cols = [
    { title: '订单', key: 'no', width: 200 },
    { title: '买家', key: 'buyer', width: 120 },
    { title: '商品', key: 'goods' },
    { title: '提货点', key: 'point', width: 180 },
    { title: '实付', key: 'pay', align: 'right' as const, width: 130 },
    { title: '状态', key: 'status', width: 110 },
    { title: '操作', key: 'act', width: 130 },
  ];
</script>

<style lang="less" scoped>
  .thumbs { display: flex; align-items: center; gap: 4px; img { width: 32px; height: 32px; border-radius: 6px; object-fit: cover; } .more { font-size: 12px; color: var(--brand-text-3); margin-left: 4px; white-space: nowrap; } }
  .after { font-size: 12px; color: var(--brand-text-3); margin-top: 2px; &.applying { color: var(--brand-danger); } }
  .code-box { display: flex; align-items: center; gap: 12px; padding: 12px 14px; border-radius: 10px; background: var(--brand-primary-soft); color: var(--brand-primary);
    .code { font: 700 22px/1.2 Consolas, monospace; letter-spacing: 0.18em; } }
  .od-items { border: 1px solid var(--brand-border-subtle); border-radius: 10px; }
  .od-item { display: grid; grid-template-columns: minmax(0, 1fr) 56px 96px; gap: 12px; align-items: center; padding: 12px 14px; & + .od-item { border-top: 1px solid var(--brand-border-subtle); } }
  .od-sum { display: grid; grid-template-columns: 1fr auto; gap: 6px; padding: 12px 14px; border-top: 1px solid var(--brand-border-subtle); background: var(--brand-bg-page); font-size: 13px; color: var(--brand-text-2); b { color: var(--brand-text); text-align: right; } }
  .as-row { padding: 8px 0; font-size: 13px; }
</style>
