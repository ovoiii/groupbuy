<!-- 团购工作台：本期团进度、关键指标、近 7 期销售、待处理、团品和提货点排行 -->
<template>
  <div class="gb-page gb-dash">
    <a-spin :spinning="loading">
      <section v-if="d.live" class="gb-card period-card">
        <div>
          <h1>本期团 · {{ d.live.name }} <span class="gb-tag pri">进行中</span></h1>
          <p class="gb-muted">{{ fmtPlan(d.live) }}</p>
        </div>
        <div class="acts-r">
          <a-button @click="go('/gb/period')">查看团品</a-button>
          <a-button type="primary" @click="go('/gb/period/edit')"><Icon icon="ant-design:plus-outlined" />准备下一期团</a-button>
        </div>
        <div class="gb-steps">
          <div class="step done"><b>开团</b>{{ md(d.live.openTime) }}</div>
          <div class="step now"><b>截单</b>{{ md(d.live.cutoffTime) }} · 还剩 <span class="gb-mark">{{ hms(leftSec(d.live.cutoffTime)) }}</span></div>
          <div class="step"><b>分拣配送</b>截单后次日上午</div>
          <div class="step"><b>到点自提</b>{{ md(d.live.pickupTime) }} – {{ md(d.live.pickupEndTime) }}</div>
        </div>
      </section>
      <section v-else-if="!loading" class="gb-card period-card">
        <div><h1>现在没有进行中的团</h1><p class="gb-muted">新建一期团，到开团时间自动在小程序上架。</p></div>
        <div class="acts-r"><a-button type="primary" @click="go('/gb/period/edit')">准备下一期团</a-button></div>
      </section>

      <div class="gb-kpis">
        <div v-for="k in kpis" :key="k.k" class="gb-kpi">
          <span class="k"><Icon :icon="k.icon" :size="15" />{{ k.k }}
            <a-tooltip :title="k.tip"><Icon icon="ant-design:question-circle-outlined" :size="13" class="gb-muted" /></a-tooltip></span>
          <span class="v" v-html="k.v"></span>
          <span class="d" v-html="k.d"></span>
        </div>
      </div>

      <div class="grid-2">
        <div class="stack">
          <section class="gb-card">
            <div class="gb-card-hd"><h2>近 7 期销售额</h2><span class="note">单位：万元；本期截至现在</span>
              <span class="legend"><i class="l1"></i>销售额<i class="l2"></i>本期（进行中）</span></div>
            <div class="chart"><svg :viewBox="`0 0 ${W} ${H}`" preserveAspectRatio="none" role="img" aria-label="近 7 期销售额" v-html="chartSvg"></svg></div>
          </section>
          <section class="gb-card">
            <div class="gb-card-hd"><h2>本期团品销售</h2><span class="note">售罄率 = 已售 ÷ 本期库存</span><a class="more" @click="go('/gb/period')">全部 {{ d.goodsCount || 0 }} 个团品</a></div>
            <a-table class="gb-table" :data-source="d.topGoods || []" :pagination="false" row-key="id" size="middle" :columns="goodsCols">
              <template #bodyCell="{ column, record, index }">
                <template v-if="column.key === 'name'">
                  <div class="rank-cell"><span class="rk" :class="{ top: index < 3 }">{{ index + 1 }}</span><GoodsCell :image="record.image" :name="record.name" :spec="record.spec" /></div>
                </template>
                <template v-else-if="column.key === 'sold'">
                  <div class="gb-r">{{ record.sold }} / {{ record.stock }}<span class="gb-bar" :class="{ hot: rate(record) >= 0.9 }"><i :style="{ width: rate(record) * 100 + '%' }"></i></span>
                    <span class="sub" :class="{ 'gb-mark': rate(record) >= 0.9 }">售罄率 {{ pct(rate(record)) }}{{ rate(record) >= 0.9 ? '，可补库存' : '' }}</span></div>
                </template>
                <template v-else-if="column.key === 'gmv'"><b>{{ yuan(record.price * record.sold) }}</b></template>
              </template>
            </a-table>
          </section>
        </div>
        <div class="stack">
          <section class="gb-card">
            <div class="gb-card-hd"><h2>待处理</h2><span v-if="todos.length" class="gb-tag warn">{{ todos.length }} 项</span></div>
            <ul class="todo-list">
              <li v-for="t in todos" :key="t.t"><a @click="go(t.to)">
                <span class="ic" :class="t.c"><Icon :icon="t.icon" :size="18" /></span>
                <span style="min-width: 0"><b>{{ t.t }}</b><small>{{ t.s }}</small></span>
                <span class="go-l">{{ t.go }}<Icon icon="ant-design:right-outlined" :size="12" /></span></a></li>
              <li v-if="!todos.length" class="gb-empty"><b>没有要处理的事</b></li>
            </ul>
          </section>
          <section class="gb-card">
            <div class="gb-card-hd"><h2>提货点本期排行</h2><a class="more" @click="go('/gb/points')">团长管理</a></div>
            <a-table class="gb-table" :data-source="d.points || []" :pagination="false" row-key="name" size="middle" :columns="pointCols">
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'name'"><div class="gb-cell"><b>{{ record.name }}</b><span class="sub">团长 {{ record.leaderName }} · 覆盖 {{ record.households }} 户</span></div></template>
                <template v-else-if="column.key === 'gmv'">{{ yuan(record.gmv) }}</template>
              </template>
            </a-table>
          </section>
        </div>
      </div>
    </a-spin>
  </div>
</template>

<script lang="ts" setup>
  import { computed, onMounted, ref } from 'vue';
  import { useRouter } from 'vue-router';
  import { Icon } from '/@/components/Icon';
  import { gbApi, yuan, pct, md, hms } from '../api';
  import { useClock, syncServerTime } from '../useClock';
  import GoodsCell from '../GoodsCell.vue';

  defineOptions({ name: 'GbDashboard' });
  const router = useRouter();
  const go = (p: string) => router.push(p);
  const { leftSec } = useClock();
  const loading = ref(true);
  const d = ref<any>({});
  const rate = (g) => (g.stock ? g.sold / g.stock : 0);
  const fmtPlan = (p) => `${md(p.cutoffTime)} 截单，次日上午分拣配送，${md(p.pickupTime)} 起居民到提货点自提。`;

  onMounted(async () => {
    try {
      const meta = await gbApi.meta();
      syncServerTime(meta.now);
      d.value = await gbApi.dashboard();
    } finally {
      loading.value = false;
    }
  });

  const change = (cur, prev, better = 'up') => {
    if (prev == null || !Number(prev)) return '';
    const r = (cur - prev) / prev;
    const up = r >= 0;
    return `<span class="${up === (better === 'up') ? 'up' : 'down'}">${up ? '↑' : '↓'} ${Math.abs(r * 100).toFixed(1)}%</span> `;
  };
  const kpis = computed(() => {
    const x = d.value;
    return [
      { k: '本期销售额', icon: 'ant-design:bar-chart-outlined', v: x.gmv != null ? yuan(x.gmv) : '—', d: x.prevGmv != null ? change(x.gmv, x.prevGmv) + '较上期同一时刻' : '本期还没有订单', tip: '口径：本期已支付订单的商品金额合计，未扣优惠券、未扣退款' },
      { k: '本期订单', icon: 'ant-design:file-text-outlined', v: (x.orders ?? 0).toLocaleString(), d: `下单人数 <b>${(x.buyers ?? 0).toLocaleString()}</b>`, tip: '口径：已支付订单数，不含待付款和已关闭' },
      { k: '客单价', icon: 'ant-design:wallet-outlined', v: x.avg != null ? yuan(x.avg) : '—', d: x.prevAvg != null ? change(x.avg, x.prevAvg) + '较上期' : '', tip: '口径：本期销售额 ÷ 本期订单' },
      { k: '开团提货点', icon: 'ant-design:environment-outlined', v: `${x.pointOk ?? 0}<small> / ${x.pointTotal ?? 0}</small>`, d: `${(x.pointPending || []).length} 个待审核，${x.pointPaused ?? 0} 个已暂停`, tip: '口径：合作中的提货点 / 全部提货点' },
    ];
  });

  const todos = computed(() => {
    const x = d.value, out: any[] = [];
    const as = x.aftersaleWait || [];
    if (as.length) out.push({ c: 'bad', icon: 'ant-design:rollback-outlined', t: `${as.length} 个售后待审核`, s: as.slice(0, 2).map((a) => `${a.type}：${a.reason || ''}`).join('；') + '，超时自动同意退款', to: '/gb/aftersale', go: '去审核' });
    const hot = x.hotGoods || [];
    if (hot.length) out.push({ c: 'warn', icon: 'ant-design:fire-outlined', t: `${hot.length} 个团品快卖完`, s: hot.map((g) => `${g.name}剩 ${g.left}`).join('、') + '，截单前可能售罄', to: '/gb/period', go: '补库存' });
    const un = x.undelivered || [];
    if (un.length) out.push({ c: 'pri', icon: 'ant-design:car-outlined', t: `${un.length} 个提货点未签收`, s: un.map((u) => `${u.pointName}${u.status === 'sorting' ? '分拣中' : '配送中'}`).join('、'), to: '/gb/sorting', go: '看配送' });
    const pp = x.pointPending || [];
    if (pp.length) out.push({ c: 'pri', icon: 'ant-design:user-add-outlined', t: `${pp.length} 个团长入驻申请`, s: pp.map((p) => `${p.leaderName} · ${p.name}`).join('、'), to: '/gb/points', go: '去审核' });
    const off = x.offGoods || [];
    if (off.length) out.push({ c: '', icon: 'ant-design:inbox-outlined', t: `${off.length} 个商品已下架`, s: off.map((g) => `${g.name}${g.offReason ? '（' + g.offReason + '）' : ''}`).join('、'), to: '/gb/goods', go: '去看看' });
    return out;
  });

  const W = 640, H = 240;
  const chartSvg = computed(() => {
    const s = d.value.series || [];
    if (!s.length) return '';
    const max = Math.max(20000, Math.ceil(Math.max(...s.map((x) => x.gmv)) / 20000) * 20000);
    const pad = { l: 44, r: 8, t: 24, b: 28 };
    const bw = (W - pad.l - pad.r) / s.length;
    const y = (v) => pad.t + (H - pad.t - pad.b) * (1 - v / max);
    let svg = '';
    for (let v = 0; v <= max; v += max / 4) svg += `<line x1="${pad.l}" x2="${W - pad.r}" y1="${y(v)}" y2="${y(v)}" stroke="var(--brand-border-subtle)"/><text x="${pad.l - 8}" y="${y(v) + 4}" text-anchor="end">${v ? (v / 10000).toFixed(1).replace('.0', '') + '万' : 0}</text>`;
    s.forEach((p, i) => {
      const x = pad.l + i * bw + bw * 0.26, w = bw * 0.48;
      svg += `<rect x="${x}" y="${y(p.gmv)}" width="${w}" height="${Math.max(0, y(0) - y(p.gmv))}" rx="4" fill="${p.live ? 'var(--brand-mark)' : 'var(--brand-primary)'}" opacity="${p.live ? 1 : 0.85}"><title>${p.name} ${yuan(p.gmv)}</title></rect>
        <text x="${x + w / 2}" y="${y(p.gmv) - 6}" text-anchor="middle" class="lbl">${(p.gmv / 10000).toFixed(2)}</text>
        <text x="${x + w / 2}" y="${H - 8}" text-anchor="middle">${p.name.replace('团', '')}${p.live ? ' 本期' : ''}</text>`;
    });
    return svg;
  });

  const goodsCols = [
    { title: '团品', key: 'name' },
    { title: '已售 / 库存', key: 'sold', align: 'right' as const, width: 200 },
    { title: '销售额', key: 'gmv', align: 'right' as const, width: 120 },
  ];
  const pointCols = [
    { title: '提货点', key: 'name' },
    { title: '订单', dataIndex: 'orders', align: 'right' as const, width: 70 },
    { title: '销售额', key: 'gmv', align: 'right' as const, width: 120 },
  ];
</script>

<style lang="less" scoped>
  .period-card { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 16px 32px; align-items: center; padding: 18px 20px 20px; margin-bottom: 16px;
    h1 { font-size: 20px; display: flex; align-items: center; gap: 10px; }
    p { margin: 6px 0 0; font-size: 13px; }
    .gb-steps { grid-column: 1 / -1; }
    .acts-r { display: flex; gap: 8px; }
  }
  .grid-2 { display: grid; grid-template-columns: minmax(0, 1.65fr) minmax(0, 1fr); gap: 16px; align-items: start; }
  .stack { display: flex; flex-direction: column; gap: 16px; .gb-card + .gb-card { margin-top: 0; } }
  .legend { display: flex; align-items: center; gap: 6px; margin-left: auto; font-size: 12px; color: var(--brand-text-2);
    i { display: inline-block; width: 10px; height: 10px; border-radius: 2px; margin-left: 10px; }
    .l1 { background: var(--brand-primary); } .l2 { background: var(--brand-mark); } }
  .chart { padding: 8px 20px 16px; svg { width: 100%; height: 240px; display: block; }
    :deep(text) { font-size: 12px; fill: var(--brand-text-3); } :deep(.lbl) { fill: var(--brand-text-2); font-weight: 600; } }
  .rank-cell { display: flex; align-items: center; gap: 10px; }
  .rk { width: 20px; height: 20px; border-radius: 4px; background: var(--brand-bg-sunken); color: var(--brand-text-2); font-size: 12px; font-weight: 700; display: grid; place-items: center; flex: none;
    &.top { background: var(--brand-primary-soft); color: var(--brand-primary); } }
  .todo-list { list-style: none; margin: 0; padding: 4px 0;
    a { display: grid; grid-template-columns: 36px minmax(0, 1fr) auto; align-items: center; gap: 12px; padding: 10px 20px; color: var(--brand-text); }
    a:hover { background: var(--brand-bg-page); }
    .ic { width: 36px; height: 36px; border-radius: 8px; display: grid; place-items: center; background: var(--brand-bg-sunken); color: var(--brand-text-2);
      &.warn { background: var(--brand-mark-soft); color: var(--brand-mark); } &.bad { background: var(--brand-danger-soft); color: var(--brand-danger); } &.pri { background: var(--brand-primary-soft); color: var(--brand-primary); } }
    b { font-weight: 600; display: block; }
    small { color: var(--brand-text-3); font-size: 12px; display: block; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .go-l { color: var(--brand-text-3); display: flex; align-items: center; gap: 4px; font-size: 13px; }
  }
  @media (max-width: 1280px) { .grid-2 { grid-template-columns: minmax(0, 1fr); } }
</style>
