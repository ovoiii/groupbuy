<!-- 团长管理：提货点列表、入驻审核（自动检查）、暂停 / 恢复、新增编辑 -->
<template>
  <div class="gb-page">
    <div class="gb-head">
      <div><h1>团长管理</h1><p>团长负责收货、保管和把货交给居民。一个团长对应一个提货点。</p></div>
      <div class="acts"><a-button type="primary" @click="edit(null)"><Icon icon="ant-design:plus-outlined" />添加团长</a-button></div>
    </div>
    <section class="gb-card">
      <div class="gb-toolbar"><a-segmented v-model:value="st" :options="opts" @change="load" /><span class="grow"></span><a-input-search v-model:value="kw" placeholder="团长 / 提货点" allow-clear style="width: 220px" @search="load" /></div>
      <a-table class="gb-table" :data-source="rows" :columns="cols" :loading="loading" :row-key="(r) => r.point.id" size="middle" :pagination="false">
        <template #bodyCell="{ column, record: r }">
          <template v-if="column.key === 'leader'"><div class="who"><span class="av">{{ r.point.leaderName[0] }}</span><div class="gb-cell"><b>{{ r.point.leaderName }}</b><span class="sub">{{ r.leaderPhoneMasked }}</span></div></div></template>
          <template v-else-if="column.key === 'point'"><div class="gb-cell"><span>{{ r.point.name }}{{ r.point.hasFridge ? '' : '（无冰柜）' }}</span><span class="sub">{{ r.point.address }}</span></div></template>
          <template v-else-if="column.key === 'hh'">{{ r.point.households ? r.point.households.toLocaleString() : '—' }}</template>
          <template v-else-if="column.key === 'orders'">{{ r.point.status === 'ok' ? r.orders : '—' }}</template>
          <template v-else-if="column.key === 'gmv'">{{ r.point.status === 'ok' ? yuan(r.gmv) : '—' }}</template>
          <template v-else-if="column.key === 'rate'">{{ pct(r.lastPickupRate) }}</template>
          <template v-else-if="column.key === 'status'"><span class="gb-tag" :class="ST[r.point.status][1]">{{ ST[r.point.status][0] }}</span><div v-if="r.point.statusNote && r.point.status !== 'ok'" class="gb-muted" style="font-size: 12px; margin-top: 2px">{{ r.point.statusNote }}</div></template>
          <template v-else-if="column.key === 'act'">
            <div class="gb-acts">
              <a v-if="r.point.status === 'pending'" @click="audit(r.point)">审核</a>
              <template v-else><a @click="edit(r.point)">编辑</a>
                <a v-if="r.point.status === 'ok'" class="muted" @click="pause(r.point)">暂停</a><a v-else-if="r.point.status === 'paused'" class="muted" @click="setStatus(r.point, 'ok')">恢复</a></template>
            </div>
          </template>
        </template>
        <template #emptyText><div class="gb-empty"><b>没有符合条件的团长</b></div></template>
      </a-table>
    </section>
    <p class="gb-foot">上期提货率 = 最近一期结束或自提中的团期里，已提货 ÷ 已到货。</p>

    <a-drawer v-model:open="auditOpen" :title="cur ? '团长入驻审核 · ' + cur.leaderName : ''" :width="560">
      <template v-if="cur">
        <div class="gb-sec"><h3>申请信息</h3><dl class="gb-dl"><dt>申请人</dt><dd>{{ cur.leaderName }} · {{ cur.leaderPhone }}</dd><dt>提货点</dt><dd>{{ cur.name }}</dd><dt>地址</dt><dd>{{ cur.address }}</dd>
          <dt>冰柜</dt><dd>{{ cur.hasFridge ? '有' : '没有' }}</dd><dt>说明</dt><dd>{{ cur.applyNote || '—' }}</dd><dt>提交时间</dt><dd>{{ md(cur.createTime) }}</dd></dl></div>
        <div class="gb-sec"><h3>自动检查</h3><ul class="checks"><li v-for="c in checks" :key="c.text" :class="c.ok ? 'ok' : 'warn'"><Icon :icon="c.ok ? 'ant-design:check-circle-outlined' : 'ant-design:warning-outlined'" />{{ c.text }}</li></ul></div>
        <a-form layout="vertical"><a-form-item label="审核意见（会发给申请人）"><a-textarea v-model:value="note" :rows="3" /></a-form-item></a-form>
      </template>
      <template #footer><div style="display: flex; justify-content: flex-end; gap: 8px"><a-button danger @click="doAudit(false)">拒绝</a-button><a-button type="primary" @click="doAudit(true)">通过，下期开团</a-button></div></template>
    </a-drawer>

    <a-drawer v-model:open="editOpen" :title="form.id ? '编辑提货点' : '添加团长'" :width="560" destroy-on-close>
      <a-form layout="vertical">
        <a-form-item label="提货点名称" required><a-input v-model:value="form.name" placeholder="如：幸福里 3 号楼便利店" /></a-form-item>
        <a-form-item label="地址" required><a-input v-model:value="form.address" /></a-form-item>
        <div class="fg2">
          <a-form-item label="团长姓名" required><a-input v-model:value="form.leaderName" /></a-form-item>
          <a-form-item label="团长手机号" required extra="团长用这个手机号登录小程序就能进团长工作台"><a-input v-model:value="form.leaderPhone" :maxlength="11" /></a-form-item>
          <a-form-item label="覆盖户数"><a-input-number v-model:value="form.households" :min="0" style="width: 100%" /></a-form-item>
          <a-form-item label="营业时间"><a-input v-model:value="form.openHours" placeholder="08:00-21:00" /></a-form-item>
          <a-form-item label="经度"><a-input-number v-model:value="form.lng" :precision="6" style="width: 100%" /></a-form-item>
          <a-form-item label="纬度"><a-input-number v-model:value="form.lat" :precision="6" style="width: 100%" /></a-form-item>
        </div>
        <a-form-item label="冰柜" extra="没有冰柜的提货点，居民不能下冷藏 / 冷冻商品"><a-switch v-model:checked="fridge" checked-children="有" un-checked-children="没有" /></a-form-item>
      </a-form>
      <template #footer><div style="text-align: right"><a-button style="margin-right: 8px" @click="editOpen = false">取消</a-button><a-button type="primary" @click="save">保存</a-button></div></template>
    </a-drawer>

    <a-modal v-model:open="pauseOpen" title="暂停提货点" ok-text="暂停" :ok-button-props="{ danger: true }" @ok="doPause">
      <p class="gb-muted">下一期起居民不能再选这个提货点，已下单的照常送达。</p>
      <a-input v-model:value="note" placeholder="暂停原因，如：店面装修，12 月恢复" />
    </a-modal>
  </div>
</template>

<script lang="ts" setup>
  import { computed, onMounted, reactive, ref } from 'vue';
  import { Segmented as ASegmented } from 'ant-design-vue';
  import { Icon } from '/@/components/Icon';
  import { gbApi, yuan, pct, md } from '../api';

  defineOptions({ name: 'GbPoint' });
  const ST = { ok: ['合作中', 'ok'], pending: ['待审核', 'warn'], paused: ['已暂停', ''], rejected: ['已拒绝', 'bad'] };
  const st = ref(''), kw = ref('');
  const rows = ref<any[]>([]), counts = ref<any>({});
  const loading = ref(false);
  const opts = computed(() => [['', '全部'], ['ok', '合作中'], ['pending', '待审核'], ['paused', '已暂停']].map(([v, l]) => ({ value: v, label: `${l} ${counts.value[v] ?? ''}` })));
  async function load() {
    loading.value = true;
    try {
      const r = await gbApi.pointList({ status: st.value, keyword: kw.value });
      rows.value = r.records; counts.value = r.counts;
    } finally {
      loading.value = false;
    }
  }
  onMounted(load);

  const cur = ref<any>(null), checks = ref<any[]>([]), note = ref(''), auditOpen = ref(false), pauseOpen = ref(false);
  async function audit(p) {
    cur.value = p;
    note.value = '欢迎加入！运营会联系你确认场地和冰柜，开通后从下一期团开始接单。';
    checks.value = (await gbApi.pointCheck(p.id)).checks;
    auditOpen.value = true;
  }
  async function doAudit(pass: boolean) {
    await gbApi.pointAudit({ id: cur.value.id, pass, note: note.value });
    auditOpen.value = false; load();
  }
  const pause = (p) => { cur.value = p; note.value = ''; pauseOpen.value = true; };
  async function doPause() { await setStatus(cur.value, 'paused', note.value); pauseOpen.value = false; }
  async function setStatus(p, status, n?: string) { await gbApi.pointStatus({ id: p.id, status, note: n }); load(); }

  const editOpen = ref(false);
  const form = reactive<any>({});
  const fridge = computed({ get: () => form.hasFridge === 1, set: (v) => (form.hasFridge = v ? 1 : 0) });
  function edit(p) {
    Object.keys(form).forEach((k) => delete form[k]);
    Object.assign(form, p ? { ...p } : { hasFridge: 1, openHours: '08:00-21:00', households: 0 });
    editOpen.value = true;
  }
  async function save() { await gbApi.pointSave({ ...form }); editOpen.value = false; load(); }

  const cols = [
    { title: '团长', key: 'leader', width: 150 },
    { title: '提货点', key: 'point' },
    { title: '覆盖户数', key: 'hh', align: 'right' as const, width: 90 },
    { title: '本期订单', key: 'orders', align: 'right' as const, width: 90 },
    { title: '本期销售额', key: 'gmv', align: 'right' as const, width: 120 },
    { title: '上期提货率', key: 'rate', align: 'right' as const, width: 100 },
    { title: '状态', key: 'status', width: 150 },
    { title: '操作', key: 'act', width: 110 },
  ];
</script>

<style lang="less" scoped>
  .who { display: flex; align-items: center; gap: 10px; }
  .av { width: 32px; height: 32px; border-radius: 50%; display: grid; place-items: center; background: var(--brand-primary-soft-2); color: var(--brand-primary); font-weight: 700; font-size: 13px; flex: none; }
  .checks { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 8px; li { display: flex; align-items: center; gap: 8px; } .ok { color: var(--brand-success); } .warn { color: var(--brand-mark); } }
  .fg2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
</style>
