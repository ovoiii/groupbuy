<!-- 商品库：采购价 / 建议价 / 毛利 / 近 7 期销量，新增编辑、上下架、批量加入下一期团 -->
<template>
  <div class="gb-page">
    <div class="gb-head">
      <div><h1>商品库</h1><p>所有能上团的商品。上到某一期时再定团购价和库存。</p></div>
      <div class="acts"><a-button type="primary" @click="edit(null)"><Icon icon="ant-design:plus-outlined" />新增商品</a-button></div>
    </div>
    <section class="gb-card">
      <div class="gb-toolbar">
        <a-input-search v-model:value="q.keyword" placeholder="搜商品名 / 供应商" allow-clear style="width: 260px" @search="load(1)" />
        <a-select v-model:value="q.category" style="width: 140px" :options="[{ value: '', label: '全部类目' }, ...cats.map((c) => ({ value: c, label: c }))]" @change="load(1)" />
        <a-segmented v-model:value="q.status" :options="statusOpts" @change="load(1)" />
      </div>
      <div v-if="sel.length" class="gb-bulk">已选 <b>{{ sel.length }}</b> 个<a-button type="primary" size="small" @click="toNext">加入下一期团</a-button><a-button size="small" @click="setStatus(sel, 'off')">批量下架</a-button><span class="grow"></span><a @click="sel = []">取消选择</a></div>
      <a-table class="gb-table" :data-source="rows" :columns="cols" :loading="loading" :row-key="(r) => r.goods.id" size="middle"
        :row-selection="{ selectedRowKeys: sel, onChange: (k) => (sel = k), getCheckboxProps: (r) => ({ disabled: r.goods.status === 'off' }) }"
        :pagination="{ current: page.current, pageSize: 20, total: page.total, showTotal: (t) => `共 ${t} 个商品`, onChange: load }">
        <template #bodyCell="{ column, record: r }">
          <template v-if="column.key === 'name'"><GoodsCell :image="r.goods.images?.split(',')[0]" :name="r.goods.name"><template #sub>{{ r.goods.spec }} · {{ STORAGE[r.goods.storage] }}</template></GoodsCell></template>
          <template v-else-if="column.key === 'supplier'"><div class="gb-cell"><span>{{ r.goods.supplier || '—' }}</span><span class="sub">{{ r.goods.origin || '—' }}</span></div></template>
          <template v-else-if="column.key === 'cost'">{{ yuan(r.goods.costPrice) }}</template>
          <template v-else-if="column.key === 'price'"><div class="gb-r">{{ yuan(r.goods.suggestPrice) }}<span class="sub"><s>{{ yuan(r.goods.origPrice) }}</s></span></div></template>
          <template v-else-if="column.key === 'margin'"><span :class="{ 'gb-danger': m(r.goods) < 0.25 }">{{ pct(m(r.goods), 1) }}</span></template>
          <template v-else-if="column.key === 'sold'"><span>{{ r.sold7 }}</span><span class="spark" aria-hidden="true"><i v-for="(v, i) in r.trend" :key="i" :style="{ height: Math.max(3, (v / Math.max(1, ...r.trend)) * 22) + 'px' }"></i></span></template>
          <template v-else-if="column.key === 'status'"><span v-if="r.goods.status === 'on'" class="gb-tag ok">可上团</span><a-tooltip v-else :title="r.goods.offReason"><span class="gb-tag bad">已下架</span></a-tooltip></template>
          <template v-else-if="column.key === 'act'">
            <div class="gb-acts"><a @click="edit(r.goods)">编辑</a>
              <a v-if="r.goods.status === 'on'" class="muted" @click="setStatus([r.goods.id], 'off')">下架</a><a v-else class="muted" @click="setStatus([r.goods.id], 'on')">上架</a></div>
          </template>
        </template>
      </a-table>
    </section>

    <a-drawer v-model:open="open" :title="form.id ? '编辑商品' : '新增商品'" :width="600" destroy-on-close>
      <a-form layout="vertical" :model="form">
        <a-form-item label="商品图" extra="第一张是列表封面，建议 800×800"><JImageUpload v-model:value="form.images" :fileMax="6" /></a-form-item>
        <a-form-item label="商品名称" required><a-input v-model:value="form.name" placeholder="如：丹东 99 草莓" :maxlength="64" /></a-form-item>
        <div class="fg2">
          <a-form-item label="规格" required><a-input v-model:value="form.spec" placeholder="如：500g / 盒" /></a-form-item>
          <a-form-item label="类目" required><a-select v-model:value="form.category" :options="cats.map((c) => ({ value: c, label: c }))" /></a-form-item>
          <a-form-item label="采购价" required><a-input-number v-model:value="form.costPrice" :min="0" :precision="2" addon-after="元" style="width: 100%" /></a-form-item>
          <a-form-item label="建议团购价" required extra="上团时可以改"><a-input-number v-model:value="form.suggestPrice" :min="0" :precision="2" addon-after="元" style="width: 100%" /></a-form-item>
          <a-form-item label="参考价（划线价）" extra="要和附近超市实际售价一致，不能虚标"><a-input-number v-model:value="form.origPrice" :min="0" :precision="2" addon-after="元" style="width: 100%" /></a-form-item>
          <a-form-item label="毛利率"><div class="mg" :class="{ 'gb-danger': m(form) < 0.25 }">{{ form.suggestPrice ? pct(m(form), 1) : '—' }}</div></a-form-item>
          <a-form-item label="供应商"><a-input v-model:value="form.supplier" /></a-form-item>
          <a-form-item label="产地"><a-input v-model:value="form.origin" /></a-form-item>
          <a-form-item label="储存方式" extra="冷藏冷冻商品，只有带冰柜的提货点能卖"><a-select v-model:value="form.storage" :options="Object.entries(STORAGE).map(([v, l]) => ({ value: v, label: l }))" /></a-form-item>
        </div>
        <a-form-item label="商品详情"><a-textarea v-model:value="form.detail" :rows="3" /></a-form-item>
      </a-form>
      <template #footer><div style="text-align: right"><a-button style="margin-right: 8px" @click="open = false">取消</a-button><a-button type="primary" :loading="saving" @click="save">保存</a-button></div></template>
    </a-drawer>
  </div>
</template>

<script lang="ts" setup>
  import { computed, onMounted, reactive, ref } from 'vue';
  import { useRouter } from 'vue-router';
  import { Modal, message } from 'ant-design-vue';
  import { Segmented as ASegmented } from 'ant-design-vue';
  import { Icon } from '/@/components/Icon';
  import JImageUpload from '/@/components/Form/src/jeecg/components/JImageUpload.vue';
  import { gbApi, yuan, pct, STORAGE } from '../api';
  import GoodsCell from '../GoodsCell.vue';

  defineOptions({ name: 'GbGoods' });
  const router = useRouter();
  const cats = ['水果', '蔬菜', '肉禽蛋', '水产', '乳品烘焙', '粮油', '日用'];
  const q = reactive({ keyword: '', category: '', status: '' });
  const rows = ref<any[]>([]);
  const counts = ref<any>({});
  const page = reactive({ current: 1, total: 0 });
  const loading = ref(false);
  const sel = ref<string[]>([]);
  const m = (g) => (g.suggestPrice ? (g.suggestPrice - (g.costPrice || 0)) / g.suggestPrice : 0);
  const statusOpts = computed(() => [
    { value: '', label: `全部 ${counts.value.all ?? ''}` },
    { value: 'on', label: `可上团 ${counts.value.on ?? ''}` },
    { value: 'off', label: `已下架 ${counts.value.off ?? ''}` },
  ]);

  async function load(p = page.current) {
    loading.value = true;
    try {
      const r = await gbApi.goodsList({ ...q, pageNo: p, pageSize: 20 });
      rows.value = r.records;
      counts.value = r.counts;
      page.current = r.current;
      page.total = r.total;
    } finally {
      loading.value = false;
    }
  }
  onMounted(() => load(1));

  function setStatus(ids: string[], status: string) {
    const run = async () => { await gbApi.goodsStatus({ ids, status, reason: '运营下架' }); sel.value = []; load(); };
    if (status === 'off') Modal.confirm({ title: `下架 ${ids.length} 个商品？`, content: '下架后不能再加到新团期里；已经在进行中团期里的不受影响。', okText: '下架', okType: 'danger', onOk: run });
    else run();
  }
  function toNext() {
    message.info('到「准备下一期团」里可以从商品库挑团品');
    router.push('/gb/period/edit');
  }

  const open = ref(false), saving = ref(false);
  const form = reactive<any>({});
  function edit(g) {
    Object.keys(form).forEach((k) => delete form[k]);
    Object.assign(form, g ? { ...g } : { storage: 'normal', category: '水果' });
    open.value = true;
  }
  async function save() {
    saving.value = true;
    try {
      await gbApi.goodsSave({ ...form });
      open.value = false;
      load();
    } finally {
      saving.value = false;
    }
  }

  const cols = [
    { title: '商品', key: 'name' },
    { title: '类目', dataIndex: ['goods', 'category'], width: 90 },
    { title: '供应商 / 产地', key: 'supplier', width: 150 },
    { title: '采购价', key: 'cost', align: 'right' as const, width: 90 },
    { title: '建议团购价', key: 'price', align: 'right' as const, width: 110 },
    { title: '毛利率', key: 'margin', align: 'right' as const, width: 80 },
    { title: '近 7 期销量', key: 'sold', align: 'right' as const, width: 150 },
    { title: '状态', key: 'status', width: 90 },
    { title: '操作', key: 'act', width: 110 },
  ];
</script>

<style lang="less" scoped>
  .spark { display: inline-flex; align-items: flex-end; gap: 2px; height: 22px; vertical-align: middle; margin-left: 8px;
    i { width: 5px; border-radius: 1px; background: var(--brand-primary-soft-2); } i:last-child { background: var(--brand-primary); } }
  .fg2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
  .mg { height: 32px; line-height: 32px; font-weight: 600; }
</style>
