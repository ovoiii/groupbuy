<template>
  <div class="wb">
    <div class="wb-head">
      <div>
        <h1>{{ greeting }}，{{ userinfo.realname || userinfo.username }}</h1>
        <p>{{ today }} · <span class="wb-demo">本页数据为界面示例，接口接入见 data.ts</span></p>
      </div>
    </div>

    <!-- 关键指标：每个数字都带口径说明 -->
    <section class="wb-kpis">
      <div v-for="k in kpis" :key="k.key" class="wb-card wb-kpi">
        <div class="wb-kpi-top">
          <span class="wb-ico" :class="{ mark: k.mark }"><Icon :icon="k.icon" :size="16" /></span>
          {{ k.title }}
          <a-tooltip :title="k.caliber">
            <span class="wb-q"><Icon icon="ant-design:question-circle-outlined" :size="14" /></span>
          </a-tooltip>
        </div>
        <div class="wb-kpi-val">
          <span class="num">{{ k.value }}</span>
          <span v-if="k.tag && k.tag.type === 'todo'" class="mk-todo">{{ k.tag.text }}</span>
          <span v-else-if="k.tag" class="wb-tag danger">{{ k.tag.text }}</span>
        </div>
        <div class="wb-kpi-sub">{{ k.sub }}</div>
      </div>
    </section>

    <div class="wb-row">
      <div class="wb-col">
        <section class="wb-card">
          <div class="wb-card-hd">
            <h2>我的待办</h2>
            <a-segmented v-model:value="tab" :options="tabOptions" />
          </div>
          <table class="wb-todo">
            <thead>
              <tr><th>流程</th><th>当前节点</th><th>剩余时限</th><th class="r">操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="row in todoData[tab]" :key="row.name">
                <td>
                  <div class="t1">
                    <span class="flow-ico"><Icon :icon="row.icon" :size="18" /></span>
                    <div class="cell-2">
                      <span class="name">{{ row.name }}</span>
                      <span class="sub">{{ row.who }}</span>
                    </div>
                  </div>
                </td>
                <td>
                  <span class="node">
                    <span class="steps"><i v-for="(s, i) in row.steps" :key="i" :class="{ done: s === 1, cur: s === 2 }"></i></span>
                    {{ row.node }}
                  </span>
                </td>
                <td><span class="left" :class="row.level">{{ row.left }}</span></td>
                <td class="r">
                  <a v-for="(a, i) in row.actions" :key="a" class="op" :class="{ muted: i > 0 }" @click="onAction(a, row)">{{ a }}</a>
                </td>
              </tr>
            </tbody>
          </table>
        </section>

        <section class="wb-card">
          <div class="wb-card-hd">
            <h2>系统运行</h2>
            <span class="wb-tag ok">全部服务正常</span>
            <a class="more" @click="go('/monitor/server')">性能监控 <Icon icon="ant-design:right-outlined" :size="12" /></a>
          </div>
          <div class="wb-sys">
            <div v-for="s in sysStatus" :key="s.label">
              <span class="lbl"><Icon :icon="s.icon" :size="16" />{{ s.label }}</span>
              <span class="v num">{{ s.value }}<small>{{ s.unit }}</small></span>
              <div class="bar"><i :class="{ warn: s.warn }" :style="{ width: s.percent + '%' }"></i></div>
              <span class="meta">{{ s.meta }}</span>
            </div>
          </div>
        </section>
      </div>

      <div class="wb-col side">
        <section class="wb-card">
          <div class="wb-card-hd"><h2>快捷入口</h2></div>
          <div class="wb-quick">
            <a v-for="q in quickLinks" :key="q.path" @click="go(q.path)">
              <span class="qi"><Icon :icon="q.icon" :size="20" /></span>
              {{ q.title }}
            </a>
          </div>
        </section>

        <section class="wb-card">
          <div class="wb-card-hd">
            <h2>通知公告</h2>
            <span class="wb-tag pri">{{ unreadCount }} 条未读</span>
            <a class="more" @click="go('/monitor/mynews')">我的消息 <Icon icon="ant-design:right-outlined" :size="12" /></a>
          </div>
          <ul class="wb-notice">
            <li v-for="n in notices" :key="n.title">
              <span class="dot" :class="{ unread: n.unread }"></span>
              <div>
                <div class="tt">{{ n.title }}</div>
                <div class="meta">{{ n.meta }}</div>
              </div>
            </li>
          </ul>
        </section>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup name="dashboard-workbench">
  import { computed, ref } from 'vue';
  import { useRouter } from 'vue-router';
  import dayjs from 'dayjs';
  import { Icon } from '/@/components/Icon';
  import { useUserStore } from '/@/store/modules/user';
  import { useMessage } from '/@/hooks/web/useMessage';
  import { kpis, todoTabs, todoData, quickLinks, notices, sysStatus } from './data';
  import type { TodoItem } from './data';

  const router = useRouter();
  const userStore = useUserStore();
  const { createMessage } = useMessage();
  const userinfo = computed(() => userStore.getUserInfo || ({} as any));

  const hour = new Date().getHours();
  const greeting = hour < 6 ? '夜深了' : hour < 12 ? '上午好' : hour < 14 ? '中午好' : hour < 18 ? '下午好' : '晚上好';
  const today = dayjs().format('YYYY 年 M 月 D 日') + ' 星期' + '日一二三四五六'[dayjs().day()];

  const tab = ref('approve');
  const tabOptions = todoTabs.map((t) => ({ value: t.key, label: `${t.label} ${todoData[t.key].length}` }));
  const unreadCount = notices.filter((n) => n.unread).length;

  function go(path: string) {
    router.push(path);
  }
  function onAction(action: string, row: TodoItem) {
    // 模板里没有接流程引擎，这里只做提示；接入后改成打开审批抽屉
    createMessage.info(`${action}：${row.name}`);
  }
</script>

<style lang="less" scoped>
  .wb {
    padding: 20px 24px 32px;
    color: var(--brand-text);
  }
  .num {
    font-variant-numeric: tabular-nums;
    white-space: nowrap;

    small {
      margin-left: 4px;
      font-size: 12px;
      font-weight: 400;
      color: var(--brand-text-2);
    }
  }
  .wb-head {
    margin-bottom: 16px;

    h1 {
      margin: 0;
      font-size: 20px;
      font-weight: 700;
      line-height: 1.3;
    }
    p {
      margin: 4px 0 0;
      font-size: 13px;
      color: var(--brand-text-3);
    }
  }
  .wb-card {
    background: var(--brand-surface);
    border: 1px solid var(--brand-border-subtle);
    border-radius: 10px;
  }
  .wb-card-hd {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 12px 20px;
    border-bottom: 1px solid var(--brand-border-subtle);

    h2 {
      margin: 0;
      font-size: 16px;
      font-weight: 700;
    }
    .more {
      margin-left: auto;
      font-size: 13px;
    }
  }
  .wb-tag {
    display: inline-flex;
    align-items: center;
    height: 22px;
    padding: 0 8px;
    border-radius: 4px;
    font-size: 12px;
    white-space: nowrap;

    &.ok {
      background: var(--brand-success-soft);
      color: var(--brand-success);
    }
    &.danger {
      background: var(--brand-danger-soft);
      color: var(--brand-danger);
    }
    &.pri {
      background: var(--brand-primary-soft);
      color: var(--brand-primary);
    }
  }

  .wb-kpis {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 16px;
    margin-bottom: 16px;
  }
  .wb-kpi {
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 14px 20px 12px;
  }
  .wb-kpi-top {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 13px;
    color: var(--brand-text-2);
  }
  .wb-ico {
    display: grid;
    place-items: center;
    width: 30px;
    height: 30px;
    border-radius: 6px;
    background: var(--brand-primary-soft);
    color: var(--brand-primary);

    &.mark {
      background: var(--brand-mark-soft);
      color: var(--brand-mark);
    }
  }
  .wb-q {
    display: inline-grid;
    margin-left: auto;
    color: var(--brand-text-3);
    cursor: help;
  }
  .wb-kpi-val {
    display: flex;
    align-items: baseline;
    gap: 10px;

    .num {
      font-size: 28px;
      font-weight: 700;
      line-height: 1.15;
    }
  }
  .wb-kpi-sub {
    font-size: 12px;
    color: var(--brand-text-3);
  }

  .wb-row {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 360px;
    gap: 16px;
    align-items: start;
  }
  .wb-col {
    display: flex;
    flex-direction: column;
    gap: 16px;
    min-width: 0;
  }

  .wb-todo {
    width: 100%;
    border-collapse: collapse;

    th {
      height: 42px;
      padding: 0 16px;
      background: var(--brand-bg-page);
      color: var(--brand-text-2);
      font-size: 13px;
      font-weight: 600;
      text-align: left;
      white-space: nowrap;
    }
    td {
      padding: 11px 16px;
      border-top: 1px solid var(--brand-border-subtle);
    }
    tbody tr:hover {
      background: var(--brand-bg-page);
    }
    .r {
      text-align: right;
      white-space: nowrap;
    }
    .t1 {
      display: flex;
      align-items: center;
      gap: 12px;
      min-width: 0;
    }
    .flow-ico {
      display: grid;
      place-items: center;
      flex: none;
      width: 34px;
      height: 34px;
      border-radius: 6px;
      background: var(--brand-bg-page);
      color: var(--brand-text-2);
    }
    .cell-2 {
      display: flex;
      flex-direction: column;
      gap: 2px;
      min-width: 0;
    }
    .name {
      font-weight: 600;
    }
    .sub {
      font-size: 12px;
      color: var(--brand-text-3);
    }
    .node {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      font-size: 13px;
      white-space: nowrap;
    }
    .steps {
      display: inline-flex;
      gap: 3px;

      i {
        width: 14px;
        height: 4px;
        border-radius: 2px;
        background: var(--brand-border-subtle);

        &.done {
          background: var(--brand-primary);
        }
        &.cur {
          background: var(--brand-mark);
        }
      }
    }
    .left {
      font-size: 13px;
      white-space: nowrap;

      &.late {
        color: var(--brand-danger);
        font-weight: 600;
      }
      &.soon {
        color: var(--brand-mark);
        font-weight: 600;
      }
    }
    .op {
      margin-left: 14px;
      font-size: 13px;

      &.muted {
        color: var(--brand-text-2);
      }
    }
  }

  .wb-sys {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));

    > div {
      display: flex;
      flex-direction: column;
      gap: 8px;
      padding: 14px 20px;
      border-right: 1px solid var(--brand-border-subtle);

      &:last-child {
        border-right: 0;
      }
    }
    .lbl {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: 13px;
      color: var(--brand-text-2);
    }
    .v {
      font-size: 20px;
      font-weight: 700;
    }
    .bar {
      height: 6px;
      border-radius: 3px;
      background: var(--brand-bg-sunken);
      overflow: hidden;

      i {
        display: block;
        height: 100%;
        border-radius: 3px;
        background: var(--brand-primary);

        &.warn {
          background: var(--brand-warning);
        }
      }
    }
    .meta {
      font-size: 12px;
      color: var(--brand-text-3);
    }
  }

  .wb-quick {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 8px;
    padding: 12px 20px;

    a {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 8px;
      padding: 14px 4px 12px;
      border-radius: 6px;
      color: var(--brand-text);
      font-size: 13px;

      &:hover {
        background: var(--brand-bg-page);
      }
    }
    .qi {
      display: grid;
      place-items: center;
      width: 40px;
      height: 40px;
      border-radius: 9px;
      background: var(--brand-primary-soft);
      color: var(--brand-primary);
    }
  }

  .wb-notice {
    margin: 0;
    padding: 6px 20px 10px;
    list-style: none;

    li {
      display: flex;
      gap: 10px;
      padding: 10px 0;
      border-bottom: 1px solid var(--brand-border-subtle);

      &:last-child {
        border-bottom: 0;
      }
    }
    .dot {
      flex: none;
      width: 6px;
      height: 6px;
      margin-top: 9px;
      border-radius: 50%;

      &.unread {
        background: var(--brand-primary);
      }
    }
    .tt {
      overflow: hidden;
      white-space: nowrap;
      text-overflow: ellipsis;
      max-width: 270px;
    }
    .meta {
      margin-top: 2px;
      font-size: 12px;
      color: var(--brand-text-3);
    }
  }

  @media (max-width: 1200px) {
    .wb-kpis {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
    .wb-row {
      grid-template-columns: minmax(0, 1fr);
    }
    .wb-sys {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }
</style>
