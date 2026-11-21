<!-- 消息通知：微信订阅消息的配置情况和发送记录。没配模板时只记录（mock），在这里能看到会发什么 -->
<template>
  <div class="gb-page">
    <div class="gb-head"><div><h1>消息通知</h1><p>到货、取货提醒、售后结果、发车、入驻审核、提现结果会给居民和团长发微信订阅消息。用户每点一次「允许」只能收一条，这是微信的规则。网络或微信那边临时出错的，系统会在 1、5、30 分钟后自动重发。</p></div></div>

    <section class="gb-card">
      <div class="gb-toolbar">
        <b>模板配置</b>
        <span class="gb-tag" :class="wxReady ? 'ok' : 'warn'">{{ wxReady ? '已配微信 AppID' : '没配微信 AppID，只记录不发送' }}</span>
        <span class="grow"></span>
        <span class="note">在 application.yml 的 groupbuy.notify 里配置，改完重启后端</span>
      </div>
      <div class="scenes">
        <div v-for="s in scenes" :key="s.scene" class="sc">
          <div class="t">{{ s.name }}<span class="gb-tag" :class="s.templateId && wxReady ? 'ok' : ''">{{ s.templateId ? (wxReady ? '会发送' : '已填模板') : '只记录' }}</span></div>
          <div class="gb-muted d">{{ WHO[s.scene] }}</div>
          <div v-if="s.templateId" class="mono d">{{ s.templateId }}</div>
        </div>
      </div>
    </section>

    <section class="gb-card">
      <div class="gb-toolbar">
        <a-segmented v-model:value="st" :options="opts" @change="load(1)" />
        <a-select v-model:value="scene" :options="sceneOpts" style="width: 150px" @change="load(1)" />
        <span class="grow"></span>
        <a-button size="small" @click="load(pageNo)">刷新</a-button>
      </div>
      <a-table class="gb-table" :data-source="rows" :columns="cols" :loading="loading" :row-key="(r) => r.l.id" size="middle"
        :pagination="{ current: pageNo, pageSize: 20, total, showSizeChanger: false, onChange: load, hideOnSinglePage: true }">
        <template #bodyCell="{ column, record: r }">
          <template v-if="column.key === 'time'">{{ md(r.l.createTime) }}</template>
          <template v-else-if="column.key === 'scene'">{{ r.sceneName }}</template>
          <template v-else-if="column.key === 'who'"><div class="gb-cell"><span>{{ r.nickname || '—' }}</span><span class="sub">{{ r.phone }}</span></div></template>
          <template v-else-if="column.key === 'content'"><div class="content">{{ text(r.l.content) }}</div></template>
          <template v-else-if="column.key === 'status'"><span class="gb-tag" :class="ST[r.l.status]?.[1]">{{ ST[r.l.status]?.[0] || r.l.status }}</span><div v-if="r.l.note" class="gb-muted" style="font-size: 12px; margin-top: 2px">{{ r.l.note }}</div>
            <div v-if="r.l.status === 'retry' && r.l.nextRetryTime" class="gb-muted" style="font-size: 12px">{{ md(r.l.nextRetryTime) }} 重发</div></template>
          <template v-else-if="column.key === 'act'"><a v-if="r.canResend" :class="{ dis: busy === r.l.id }" @click="resend(r)">{{ r.l.status === 'retry' ? '立即重发' : '重发' }}</a></template>
        </template>
        <template #emptyText><div class="gb-empty"><b>还没有消息</b><span>团长签收到货、处理售后、审核入驻和提现时会产生</span></div></template>
      </a-table>
    </section>
  </div>
</template>

<script lang="ts" setup>
  import { computed, onMounted, ref } from 'vue';
  import { Modal, Segmented as ASegmented } from 'ant-design-vue';
  import { gbApi, md } from '../api';

  defineOptions({ name: 'GbNotify' });
  const ST = { sent: ['已发送', 'ok'], mock: ['只记录', 'pri'], skipped: ['没发', ''], retry: ['等待重发', 'warn'], failed: ['失败', 'bad'] };
  const WHO = { arrived: '团长签收后发给这批订单的居民', pickup: '团长点「提醒取货」时发给居民', aftersale: '售后同意、驳回或平台取消退款时发给居民',
    ship: '配送单发车时发给团长', apply: '入驻申请审核后发给申请人', withdraw: '提现打款或驳回后发给团长' };
  const NAMES = { orderNo: '订单', point: '提货点', address: '地址', code: '提货码', goods: '商品', time: '时间', hours: '营业', tip: '提示', result: '结果',
    amount: '金额', reply: '说明', orders: '订单数', items: '件数', boxes: '箱数', driver: '司机', name: '姓名', wdNo: '提现单', method: '方式' };
  const st = ref(''), scene = ref('');
  const rows = ref<any[]>([]), counts = ref<any>({}), total = ref(0), pageNo = ref(1);
  const scenes = ref<any[]>([]), wxReady = ref(false);
  const loading = ref(false);
  const opts = computed(() => [['', '全部'], ['sent', '已发送'], ['mock', '只记录'], ['skipped', '没发'], ['retry', '等待重发'], ['failed', '失败']].map(([v, l]) => ({ value: v, label: `${l} ${counts.value[v] ?? ''}` })));
  const sceneOpts = computed(() => [{ value: '', label: '全部场景' }, ...scenes.value.map((s) => ({ value: s.scene, label: s.name }))]);

  /** 内容 JSON → 「提货点：xx · 提货码：xx」；配了模板字段的直接显示字段值 */
  function text(c?: string) {
    if (!c) return '';
    try {
      const o = JSON.parse(c);
      return Object.entries(o).map(([k, v]: any) => (v && typeof v === 'object' && 'value' in v ? v.value : `${NAMES[k] || k}：${v ?? ''}`)).join(' · ');
    } catch (e) {
      return c;
    }
  }

  async function load(p = 1) {
    pageNo.value = p; loading.value = true;
    try {
      const r = await gbApi.notifyList({ status: st.value, scene: scene.value, pageNo: p, pageSize: 20 });
      rows.value = r.records; counts.value = r.counts; total.value = r.total; scenes.value = r.scenes; wxReady.value = r.wxReady;
    } finally {
      loading.value = false;
    }
  }
  onMounted(() => load(1));

  const busy = ref('');
  function resend(r) {
    const go = async () => {
      busy.value = r.l.id;
      try {
        await gbApi.notifyResend({ id: r.l.id }); // 结果提示由请求封装弹出
      } finally {
        busy.value = '';
        load(pageNo.value);
      }
    };
    if (r.l.status === 'retry') return go();
    // 失败的要重新用掉用户一次订阅次数
    Modal.confirm({ title: '重发这条消息？', content: '会用掉用户一次订阅次数；用户没有次数了就发不了。', okText: '重发', onOk: go });
  }

  const cols = [
    { title: '时间', key: 'time', width: 110 },
    { title: '场景', key: 'scene', width: 110 },
    { title: '接收人', key: 'who', width: 140 },
    { title: '内容', key: 'content' },
    { title: '状态', key: 'status', width: 240 },
    { title: '', key: 'act', width: 80 },
  ];
</script>

<style lang="less" scoped>
  .scenes { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); border-radius: 0 0 10px 10px; overflow: hidden; }
  .sc { padding: 14px 20px; min-width: 0; box-shadow: inset -1px -1px 0 var(--brand-border-subtle); }
  .sc .t { font-weight: 600; display: flex; align-items: center; gap: 8px; }
  .sc .d { font-size: 12px; margin-top: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .mono { font-family: Consolas, monospace; color: var(--brand-text-2); }
  .dis { pointer-events: none; opacity: 0.5; }
  .content { font-size: 13px; color: var(--brand-text-2); line-height: 1.6; }
</style>
