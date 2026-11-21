<!-- 提现审核：提到微信零钱的可以一键打款（微信支付商家转账，团长在小程序确认收款）；银行卡的线下打款后登记流水号；驳回时钱退回团长余额 -->
<template>
  <div class="gb-page">
    <div class="gb-head"><div><h1>提现审核</h1><p>提到微信零钱的可以「打款到微信零钱」，团长在小程序里确认收款后自动到账；银行卡的先线下转账，再登记流水号。驳回的钱退回团长的可提现余额。</p></div></div>
    <section class="gb-card">
      <div class="gb-toolbar"><a-segmented v-model:value="st" :options="opts" @change="load(1)" /><span class="grow"></span><span class="note">收款人必须是团长本人，姓名对不上的请驳回</span></div>
      <a-table class="gb-table" :data-source="rows" :columns="cols" :loading="loading" :row-key="(r) => r.w.id" size="middle"
        :pagination="{ current: pageNo, pageSize: 20, total, showSizeChanger: false, onChange: load, hideOnSinglePage: true }">
        <template #bodyCell="{ column, record: r }">
          <template v-if="column.key === 'no'"><div class="gb-cell"><b>{{ r.w.wdNo }}</b><span class="sub">{{ md(r.w.createTime) }} 申请</span></div></template>
          <template v-else-if="column.key === 'leader'"><div class="gb-cell"><span>{{ r.leaderName }}</span><span class="sub">{{ r.pointName }}</span></div></template>
          <template v-else-if="column.key === 'amt'"><b>{{ yuan(r.w.amount) }}</b></template>
          <template v-else-if="column.key === 'acc'"><div class="gb-cell"><span>{{ r.w.method === 'bank' ? r.w.bankName : '微信零钱' }} · {{ r.w.accountName }}</span><span v-if="r.w.method === 'bank'" class="sub">{{ maskCard(r.w.accountNo) }}</span></div></template>
          <template v-else-if="column.key === 'status'"><span class="gb-tag" :class="ST[r.w.status][1]">{{ ST[r.w.status][0] }}</span><div v-if="r.w.status === 'paying'" class="gb-muted" style="font-size: 12px; margin-top: 2px">{{ TS[r.w.transferState] || r.w.transferState }}</div>
            <div v-else-if="r.w.status === 'wait' && r.w.transferFail" class="warn-t" style="font-size: 12px; margin-top: 2px">自动打款没成功：{{ r.w.transferFail }}</div>
            <div v-else-if="r.w.status !== 'wait'" class="gb-muted" style="font-size: 12px; margin-top: 2px">{{ r.w.status === 'paid' ? '流水 ' + r.w.voucher : r.w.reply }}</div></template>
          <template v-else-if="column.key === 'act'"><a @click="open(r)">{{ ['wait', 'paying'].includes(r.w.status) ? '处理' : '查看' }}</a></template>
        </template>
        <template #emptyText><div class="gb-empty"><b>{{ st === 'wait' ? '没有待审核的提现' : '没有记录' }}</b><span>团长在小程序「团长工作台 → 我的佣金」申请</span></div></template>
      </a-table>
    </section>

    <a-drawer v-model:open="show" :title="cur ? '提现 ' + cur.w.wdNo : ''" :width="520">
      <template v-if="cur">
        <div class="amount">{{ yuan(cur.w.amount) }}<span class="gb-tag" :class="ST[cur.w.status][1]">{{ ST[cur.w.status][0] }}</span></div>
        <div class="gb-sec"><h3>收款账户</h3><dl class="gb-dl">
          <dt>方式</dt><dd>{{ cur.w.method === 'bank' ? '银行卡' : '微信零钱' }}</dd>
          <dt>收款人</dt><dd>{{ cur.w.accountName }}<span v-if="cur.w.accountName !== cur.leaderName" class="mismatch">和团长姓名「{{ cur.leaderName }}」不一致</span></dd>
          <template v-if="cur.w.method === 'bank'"><dt>开户行</dt><dd>{{ cur.w.bankName }}</dd><dt>卡号</dt><dd class="mono">{{ spaced(cur.w.accountNo) }}<a class="copy" @click="copy(cur.w.accountNo)">复制</a></dd></template>
          <template v-else><dt>微信</dt><dd>团长登录小程序的微信（手机号 {{ cur.leaderPhone }}）</dd></template>
        </dl></div>
        <div class="gb-sec"><h3>团长</h3><dl class="gb-dl">
          <dt>提货点</dt><dd>{{ cur.pointName }}</dd><dt>累计佣金</dt><dd>{{ yuan(cur.totalIncome) }}</dd><dt>累计已提现</dt><dd>{{ yuan(cur.totalWithdrawn) }}</dd>
        </dl></div>
        <div v-if="cur.w.status === 'wait'" class="gb-sec"><h3>处理</h3>
          <a-alert v-if="cur.w.transferFail" type="warning" show-icon :message="'上次自动打款没成功：' + cur.w.transferFail" style="margin-bottom: 12px" />
          <div v-if="cur.autoPay" class="auto"><b>可以一键打到团长微信零钱</b><span>微信支付「商家转账」，团长在小程序「我的佣金」点确认收款后到账，不用填流水号。</span></div>
          <div v-else-if="cur.w.method === 'wechat' && cur.autoPayWhy" class="gb-muted" style="font-size: 12px; margin-bottom: 12px">不能自动打款：{{ cur.autoPayWhy }}，请线下转账后登记。</div>
          <a-form layout="vertical">
            <a-form-item label="打款流水号" extra="网银或微信转账记录里的交易单号，对账用"><a-input v-model:value="voucher" placeholder="打完款再填" /></a-form-item>
            <a-form-item label="驳回原因（驳回时必填，团长能看到）"><a-input v-model:value="reply" placeholder="如：收款人姓名和团长不一致" /></a-form-item>
          </a-form></div>
        <div v-else-if="cur.w.status === 'paying'" class="gb-sec"><h3>自动打款</h3><dl class="gb-dl">
          <dt>状态</dt><dd><b>{{ TS[cur.w.transferState] || cur.w.transferState }}</b></dd>
          <template v-if="cur.w.transferFail"><dt>说明</dt><dd>{{ cur.w.transferFail }}</dd></template>
          <dt>商户单号</dt><dd class="mono">{{ cur.w.transferNo }}</dd>
          <template v-if="cur.w.transferBillNo"><dt>微信单号</dt><dd class="mono">{{ cur.w.transferBillNo }}</dd></template>
          <dt>发起人</dt><dd>{{ cur.w.handleBy }} · {{ md(cur.w.transferTime) }} 更新</dd></dl>
          <p class="gb-muted" style="font-size: 12px">团长确认收款后自动变成「已打款」；系统每 5 分钟去微信查一次，也可以点「刷新结果」。团长一直不确认的可以撤销后重打或线下打款。</p></div>
        <div v-else class="gb-sec"><h3>处理结果</h3><dl class="gb-dl">
          <template v-if="cur.w.status === 'paid'"><dt>流水号</dt><dd class="mono">{{ cur.w.voucher }}</dd></template>
          <template v-else><dt>驳回原因</dt><dd>{{ cur.w.reply }}</dd></template>
          <dt>处理人</dt><dd>{{ cur.w.handleBy }} · {{ md(cur.w.handleTime) }}</dd></dl></div>
      </template>
      <template #footer>
        <div v-if="cur?.w.status === 'wait'" style="display: flex; justify-content: flex-end; gap: 8px"><a-button danger @click="reject">驳回</a-button>
          <a-button :type="cur.autoPay ? 'default' : 'primary'" @click="approve">已线下打款，登记</a-button>
          <a-button v-if="cur.autoPay" type="primary" :loading="busy" @click="autoPay">打款到微信零钱</a-button></div>
        <div v-else-if="cur?.w.status === 'paying'" style="display: flex; justify-content: flex-end; gap: 8px">
          <a-button v-if="['ACCEPTED', 'PROCESSING', 'WAIT_USER_CONFIRM'].includes(cur.w.transferState)" danger :loading="busy" @click="cancelTransfer">撤销转账</a-button>
          <a-button type="primary" :loading="busy" @click="syncTransfer">刷新结果</a-button></div>
      </template>
    </a-drawer>
  </div>
</template>

<script lang="ts" setup>
  import { computed, onMounted, ref } from 'vue';
  import { Modal, message } from 'ant-design-vue';
  import { Segmented as ASegmented } from 'ant-design-vue';
  import { gbApi, yuan, md } from '../api';

  defineOptions({ name: 'GbWithdraw' });
  const ST = { wait: ['待审核', 'warn'], paying: ['打款中', 'pri'], paid: ['已打款', 'ok'], rej: ['已驳回', ''] };
  // 微信商家转账单状态
  const TS = { CREATING: '发起中', UNKNOWN: '结果未知，系统会自动去微信查', ACCEPTED: '微信已受理', PROCESSING: '微信处理中', WAIT_USER_CONFIRM: '等团长在小程序确认收款',
    TRANSFERING: '微信打款中', CANCELING: '撤销中', SUCCESS: '已到账', FAIL: '失败', CANCELLED: '已撤销' };
  const st = ref('wait');
  const rows = ref<any[]>([]), counts = ref<any>({}), total = ref(0), pageNo = ref(1);
  const loading = ref(false);
  const opts = computed(() => [['wait', '待审核'], ['paying', '打款中'], ['paid', '已打款'], ['rej', '已驳回'], ['', '全部']].map(([v, l]) => ({ value: v, label: `${l} ${counts.value[v] ?? ''}` })));
  const maskCard = (no?: string) => (!no || no.length < 8 ? no : '•••• ' + no.slice(-4));
  const spaced = (no?: string) => (no || '').replace(/(\d{4})(?=\d)/g, '$1 ');

  async function load(p = 1) {
    pageNo.value = p; loading.value = true;
    try {
      const r = await gbApi.withdrawList({ status: st.value, pageNo: p, pageSize: 20 });
      rows.value = r.records; counts.value = r.counts; total.value = r.total;
    } finally {
      loading.value = false;
    }
  }
  onMounted(() => load(1));

  const show = ref(false), cur = ref<any>(null), voucher = ref(''), reply = ref('');
  function open(r) { cur.value = r; voucher.value = ''; reply.value = ''; show.value = true; }
  function copy(t: string) {
    navigator.clipboard?.writeText(t).then(() => message.success('卡号已复制'), () => message.warning('复制失败，请手动选中'));
  }
  function approve() {
    if (!voucher.value.trim()) return message.warning('先打款，再填流水号');
    Modal.confirm({ title: `确认已给 ${cur.value.w.accountName} 打款 ${yuan(cur.value.w.amount)}？`, content: '登记后不能撤回。', okText: '确认',
      onOk: async () => { await gbApi.withdrawApprove({ id: cur.value.w.id, voucher: voucher.value }); show.value = false; load(pageNo.value); } });
  }
  const busy = ref(false);
  /** 接口返回最新的提现单：更新抽屉里的内容，列表也刷新 */
  async function act(fn: () => Promise<any>) {
    busy.value = true;
    try {
      const w = await fn();
      if (w && w.id) cur.value = { ...cur.value, w, autoPay: false };
    } finally {
      busy.value = false;
      load(pageNo.value);
    }
  }
  function autoPay() {
    Modal.confirm({ title: `打 ${yuan(cur.value.w.amount)} 到 ${cur.value.w.accountName} 的微信零钱？`, content: '从商户号余额里出，团长在小程序确认收款后到账。微信会核对收款人姓名和微信实名。', okText: '确认打款',
      onOk: () => act(() => gbApi.withdrawTransfer({ id: cur.value.w.id })) });
  }
  const syncTransfer = () => act(() => gbApi.withdrawTransferSync({ id: cur.value.w.id }));
  function cancelTransfer() {
    Modal.confirm({ title: '撤销这笔转账？', content: '撤销后提现回到「待审核」，可以重新打款、线下打款或驳回。', okText: '撤销', okType: 'danger',
      onOk: () => act(() => gbApi.withdrawTransferCancel({ id: cur.value.w.id })) });
  }
  async function reject() {
    if (!reply.value.trim()) return message.warning('驳回要写原因，团长能看到');
    await gbApi.withdrawReject({ id: cur.value.w.id, reply: reply.value });
    show.value = false; load(pageNo.value);
  }

  const cols = [
    { title: '提现单', key: 'no', width: 200 },
    { title: '团长', key: 'leader' },
    { title: '金额', key: 'amt', align: 'right' as const, width: 120 },
    { title: '收款账户', key: 'acc', width: 220 },
    { title: '状态', key: 'status', width: 200 },
    { title: '操作', key: 'act', width: 70 },
  ];
</script>

<style lang="less" scoped>
  .amount { font-size: 28px; font-weight: 700; display: flex; align-items: center; gap: 10px; margin-bottom: 16px; }
  .mismatch { margin-left: 8px; color: var(--brand-mark); font-size: 12px; }
  .mono { font-family: Consolas, monospace; letter-spacing: 0.5px; }
  .copy { margin-left: 8px; font-family: inherit; }
  .auto { display: flex; flex-direction: column; gap: 2px; padding: 10px 12px; border-radius: 8px; background: var(--brand-primary-soft, #e7f9ec); margin-bottom: 14px; font-size: 13px;
    span { color: var(--brand-text-2); font-size: 12px; } }
  .warn-t { color: var(--brand-mark); }
</style>
