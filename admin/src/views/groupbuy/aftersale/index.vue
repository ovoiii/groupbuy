<!-- 售后退款：24 小时内处理，审核定退款金额和责任方 -->
<template>
  <div class="gb-page">
    <div class="gb-head"><div><h1>售后退款</h1><p>居民在小程序申请，24 小时内要处理完；超时没处理的系统自动同意退款。</p></div></div>
    <section class="gb-card">
      <div class="gb-toolbar"><a-segmented v-model:value="st" :options="opts" @change="load" /><span class="grow"></span><span class="note">责任方决定这笔钱由谁承担，月底和供应商、团长对账用</span></div>
      <a-table class="gb-table" :data-source="rows" :columns="cols" :loading="loading" :row-key="(r) => r.aftersale.id" size="middle" :pagination="false">
        <template #bodyCell="{ column, record: r }">
          <template v-if="column.key === 'no'"><div class="gb-cell"><b>{{ r.aftersale.asNo }}</b><span class="sub">{{ md(r.aftersale.createTime) }} 申请 · {{ r.order.buyerName }}</span></div></template>
          <template v-else-if="column.key === 'goods'"><GoodsCell v-if="r.items[0]" :image="r.items[0].image" :name="r.items[0].goodsName + (r.items.length > 1 ? ` 等 ${r.items.length} 种` : ` × ${r.items[0].qty}`)" :spec="'订单 ' + r.order.orderNo" small /></template>
          <template v-else-if="column.key === 'reason'"><div class="gb-cell"><span><span class="gb-tag" :class="{ bad: r.aftersale.type === '质量问题' }">{{ r.aftersale.type }}</span></span><span class="sub ellipsis">{{ r.aftersale.reason }}</span></div></template>
          <template v-else-if="column.key === 'amt'"><b>{{ yuan(r.aftersale.refundAmount ?? r.aftersale.applyAmount) }}</b></template>
          <template v-else-if="column.key === 'point'">{{ r.order.pointName }}</template>
          <template v-else-if="column.key === 'left'">
            <span v-if="r.aftersale.status === 'wait'" class="mk-cutoff"><Icon icon="ant-design:clock-circle-outlined" :size="12" />剩 <b>{{ leftText(r.aftersale.deadline) }}</b></span>
            <div v-else class="gb-cell"><span>{{ r.aftersale.duty || '—' }}</span><span v-if="r.deduct" class="sub" :class="{ strike: r.deduct.status === 'revoked' }">团长承担 {{ yuan(r.deduct.amount) }}</span></div>
          </template>
          <template v-else-if="column.key === 'status'"><span class="gb-tag" :class="ST[r.aftersale.status][1]">{{ ST[r.aftersale.status][0] }}</span></template>
          <template v-else-if="column.key === 'act'"><a @click="audit(r)">{{ r.aftersale.status === 'wait' ? '审核' : '查看' }}</a></template>
        </template>
        <template #emptyText><div class="gb-empty"><b>{{ st === 'wait' ? '没有待审核的售后' : '没有记录' }}</b><span>新的申请会在侧栏「售后退款」提醒</span></div></template>
      </a-table>
    </section>

    <a-drawer v-model:open="open" :title="cur ? '售后 ' + cur.aftersale.asNo : ''" :width="560">
      <template v-if="cur">
        <div class="gb-sec"><h3>买家说</h3><div class="quote"><span class="gb-tag" :class="{ bad: cur.aftersale.type === '质量问题' }">{{ cur.aftersale.type }}</span>　{{ cur.aftersale.reason || '（没写原因）' }}</div>
          <div v-if="cur.aftersale.images" class="photos"><a-image v-for="u in cur.aftersale.images.split(',')" :key="u" :src="img(u)" :width="96" :height="96" /></div></div>
        <div class="gb-sec"><h3>订单</h3><dl class="gb-dl">
          <dt>商品</dt><dd><div v-for="it in cur.items" :key="it.id">{{ it.goodsName }} × {{ it.qty }} · {{ yuan(it.amount) }}</div></dd>
          <dt>订单号</dt><dd>{{ cur.order.orderNo }}</dd><dt>买家</dt><dd>{{ cur.order.buyerName }} · {{ cur.order.buyerPhone }}</dd>
          <dt>提货点</dt><dd>{{ cur.order.pointName }} · 团长 {{ cur.order.leaderName }}</dd>
          <dt>本单实付</dt><dd>{{ yuan(cur.order.payAmount) }}{{ cur.order.couponName ? `（用了${cur.order.couponName}）` : '' }}</dd></dl></div>
        <div v-if="cur.aftersale.status === 'wait'" class="gb-sec"><h3>处理</h3>
          <a-form layout="vertical">
            <a-form-item label="退款金额" :extra="`最多退 ${yuan(cur.aftersale.applyAmount)}（所选商品按实付比例折算）`"><a-input-number v-model:value="f.refundAmount" :min="0.01" :max="cur.aftersale.applyAmount" :precision="2" addon-after="元" style="width: 180px" /></a-form-item>
            <a-form-item label="责任方" :extra="cur.aftersale.type === '少件' ? '分拣少件一般记在平台' : '生鲜质量问题一般由供应商承担'"><a-radio-group v-model:value="f.duty" button-style="solid"><a-radio-button v-for="w in ['供应商', '团长', '平台', '物流']" :key="w" :value="w">{{ w }}</a-radio-button></a-radio-group></a-form-item>
            <template v-if="f.duty === '团长'">
              <a-form-item label="从团长佣金扣" :extra="`团长 ${cur.order.leaderName} 承担多少（0 = 只记责任不扣钱）。直接从可提现扣，不够扣记成负数，之后的佣金先抵上`">
                <a-input-number v-model:value="f.leaderCharge" :min="0" :max="f.refundAmount" :precision="2" addon-after="元" style="width: 180px" /></a-form-item>
              <a-form-item label="扣款说明（团长能看到）"><a-input v-model:value="f.chargeNote" :maxlength="60" /></a-form-item>
            </template>
            <a-form-item label="回复买家（会显示在小程序里）"><a-textarea v-model:value="f.reply" :rows="3" /></a-form-item>
          </a-form></div>
        <div v-else class="gb-sec"><h3>处理结果</h3><dl class="gb-dl"><dt>结果</dt><dd>{{ ST[cur.aftersale.status][0] }}</dd>
          <template v-if="cur.aftersale.status === 'done'"><dt>退款</dt><dd>{{ yuan(cur.aftersale.refundAmount) }} · 原路退回</dd><dt>责任方</dt><dd>{{ cur.aftersale.duty }}</dd></template>
          <template v-if="cur.deduct"><dt>团长承担</dt><dd>{{ yuan(cur.deduct.amount) }} · 已从佣金扣<span v-if="cur.deduct.status === 'revoked'" class="gb-muted">（已撤销：{{ cur.deduct.revokeReason }}）</span><div class="gb-muted">{{ cur.deduct.reason }}</div></dd></template>
          <dt>回复</dt><dd>{{ cur.aftersale.reply }}</dd><dt>处理人</dt><dd>{{ cur.aftersale.handleBy }} · {{ md(cur.aftersale.handleTime) }}</dd></dl></div>
      </template>
      <template #footer>
        <div v-if="cur?.aftersale.status === 'wait'" style="display: flex; justify-content: flex-end; gap: 8px"><a-button danger @click="reject">驳回</a-button><a-button type="primary" @click="approve">同意退款</a-button></div>
      </template>
    </a-drawer>
  </div>
</template>

<script lang="ts" setup>
  import { computed, onMounted, reactive, ref, watch } from 'vue';
  import { Modal, message } from 'ant-design-vue';
  import { Segmented as ASegmented } from 'ant-design-vue';
  import { Icon } from '/@/components/Icon';
  import { gbApi, yuan, md, img } from '../api';
  import { useClock, syncServerTime } from '../useClock';
  import GoodsCell from '../GoodsCell.vue';

  defineOptions({ name: 'GbAftersale' });
  const ST = { wait: ['待审核', 'warn'], done: ['已退款', 'ok'], rej: ['已驳回', ''] };
  const { leftSec } = useClock();
  const st = ref('wait');
  const rows = ref<any[]>([]), counts = ref<any>({});
  const loading = ref(false);
  const opts = computed(() => [['wait', '待审核'], ['done', '已退款'], ['rej', '已驳回'], ['', '全部']].map(([v, l]) => ({ value: v, label: `${l} ${counts.value[v] ?? ''}` })));
  const leftText = (t) => { const s = leftSec(t); return s <= 0 ? '已超时' : s >= 3600 ? `${Math.floor(s / 3600)} 小时` : `${Math.ceil(s / 60)} 分钟`; };

  async function load() {
    loading.value = true;
    try {
      const r = await gbApi.asList(st.value);
      rows.value = r.records; counts.value = r.counts;
    } finally {
      loading.value = false;
    }
  }
  onMounted(async () => { syncServerTime((await gbApi.meta()).now); load(); });

  const open = ref(false), cur = ref<any>(null);
  const f = reactive<any>({ refundAmount: 0, duty: '供应商', reply: '', leaderCharge: 0, chargeNote: '' });
  // 选了团长：默认团长承担全部退款；退款金额改小时跟着不超过它
  watch(() => f.duty, (d) => { if (d === '团长') Object.assign(f, { leaderCharge: f.refundAmount, chargeNote: f.chargeNote || `${cur.value?.aftersale.type || '售后'}，责任在提货点` }); });
  watch(() => f.refundAmount, (v) => { if (Number(f.leaderCharge) > Number(v)) f.leaderCharge = v; });
  function audit(r) {
    cur.value = r;
    const short = r.aftersale.type === '少件';
    Object.assign(f, { refundAmount: r.aftersale.applyAmount, duty: short ? '平台' : '供应商', leaderCharge: 0, chargeNote: '',
      reply: short ? '抱歉少给了您，已退款，钱会原路退回微信。' : '抱歉给您带来不好的体验，已退款，钱会原路退回微信。' });
    open.value = true;
  }
  async function approve() {
    const d: any = { id: cur.value.aftersale.id, refundAmount: String(f.refundAmount), duty: f.duty, reply: f.reply };
    if (f.duty === '团长') Object.assign(d, { leaderCharge: String(f.leaderCharge ?? 0), chargeNote: f.chargeNote });
    await gbApi.asApprove(d);
    open.value = false; load();
  }
  function reject() {
    if (!f.reply || f.reply.includes('已退款')) return message.warning('驳回要在「回复买家」里写清理由');
    Modal.confirm({ title: '驳回这个售后？', content: '买家会看到你的回复，可以再次申请。', okText: '驳回', okType: 'danger',
      onOk: async () => { await gbApi.asReject({ id: cur.value.aftersale.id, reply: f.reply }); open.value = false; load(); } });
  }

  const cols = [
    { title: '售后单', key: 'no', width: 200 },
    { title: '商品', key: 'goods' },
    { title: '原因', key: 'reason', width: 240 },
    { title: '退款金额', key: 'amt', align: 'right' as const, width: 100 },
    { title: '提货点', key: 'point', width: 170 },
    { title: '处理时限 / 责任方', key: 'left', width: 150 },
    { title: '状态', key: 'status', width: 90 },
    { title: '操作', key: 'act', width: 70 },
  ];
</script>

<style lang="less" scoped>
  .quote { padding: 12px 14px; border-radius: 10px; background: var(--brand-bg-page); line-height: 1.7; }
  .photos { display: flex; gap: 8px; margin-top: 10px; }
  .strike { text-decoration: line-through; }
  .ellipsis { max-width: 220px; overflow: hidden; text-overflow: ellipsis; }
</style>
