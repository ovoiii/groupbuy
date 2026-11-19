/**
 * 工作台示例数据。
 * 模板里先用静态数据把版面撑起来；接入真实项目时，把这里换成接口返回即可（字段名保持不变，页面不用改）。
 */
export interface TodoItem {
  icon: string;
  name: string;
  who: string;
  /** 流程节点：1 已完成 2 当前 0 未到 */
  steps: number[];
  node: string;
  left: string;
  level?: 'late' | 'soon';
  actions: string[];
}

export const kpis = [
  {
    key: 'todo',
    title: '待我处理',
    icon: 'ant-design:inbox-outlined',
    value: 7,
    mark: true,
    tag: { text: '2 项今天到期', type: 'todo' },
    sub: '最早一项已停留 26 小时',
    caliber: '口径：当前分配给我、状态为「待审批 / 待知会」的流程任务数，不含已转办',
  },
  {
    key: 'mine',
    title: '我发起的',
    icon: 'ant-design:send-outlined',
    value: 12,
    tag: { text: '1 项被驳回', type: 'danger' },
    sub: '本周已办结 9 项',
    caliber: '口径：我发起且未结束的流程实例；被驳回的计入「需修改」',
  },
  {
    key: 'newUser',
    title: '本月新增用户',
    icon: 'ant-design:team-outlined',
    value: 26,
    sub: '较上月同期 +8（44%）',
    caliber: '口径：sys_user 本月 create_time 计数，不含已删除；对比上月同期',
  },
  {
    key: 'online',
    title: '在线用户',
    icon: 'ant-design:line-chart-outlined',
    value: 43,
    sub: '今日峰值 61（10:30）',
    caliber: '口径：Redis 中未过期的登录令牌数（按账号去重）',
  },
];

export const todoTabs = [
  { key: 'approve', label: '待我审批' },
  { key: 'cc', label: '待我知会' },
  { key: 'mine', label: '我发起的' },
];

export const todoData: Record<string, TodoItem[]> = {
  approve: [
    { icon: 'ant-design:coffee-outlined', name: '请假申请 · 年假 3 天', who: '张晓雯 · 市场部', steps: [1, 1, 2, 0], node: '部门经理审批', left: '已超时 2 小时', level: 'late', actions: ['审批', '转办'] },
    { icon: 'ant-design:environment-outlined', name: '出差申请 · 上海客户拜访', who: '刘建国 · 销售一部', steps: [1, 2, 0, 0], node: '部门经理审批', left: '今天 18:00 前', level: 'soon', actions: ['审批', '转办'] },
    { icon: 'ant-design:file-protect-outlined', name: '合同审批 · 年度运维服务合同', who: '王磊 · 采购部', steps: [1, 1, 1, 2], node: '分管领导会签', left: '今天 20:00 前', level: 'soon', actions: ['审批', '转办'] },
    { icon: 'ant-design:file-text-outlined', name: '公文发文 · 关于调整报销流程的通知', who: '陈思 · 综合办', steps: [1, 2, 0, 0], node: '核稿', left: '剩 2 天', actions: ['审批', '转办'] },
    { icon: 'ant-design:safety-certificate-outlined', name: '用印申请 · 投标文件盖章', who: '赵敏 · 商务部', steps: [1, 1, 2, 0], node: '法务审核', left: '剩 3 天', actions: ['审批', '转办'] },
  ],
  cc: [
    { icon: 'ant-design:notification-outlined', name: '会议纪要 · 第 46 周经营例会', who: '综合办 · 抄送全体经理', steps: [1, 1, 1, 1], node: '已办结', left: '无时限', actions: ['已阅', '查看'] },
    { icon: 'ant-design:flag-outlined', name: '督办事项 · 客户投诉回访', who: '周婷 · 客服中心', steps: [1, 1, 2, 0], node: '责任人处理中', left: '剩 5 天', actions: ['已阅', '查看'] },
  ],
  mine: [
    { icon: 'ant-design:calendar-outlined', name: '会议室预约 · 3 号会议室', who: '我 · 11-12 提交', steps: [1, 2, 0, 0], node: '行政审核', left: '剩 1 天', actions: ['催办', '撤回'] },
    { icon: 'ant-design:coffee-outlined', name: '加班申请 · 11 月 9 日', who: '我 · 11-10 提交', steps: [1, 1, 0, 0], node: '被驳回：缺加班事由', left: '需修改', level: 'late', actions: ['修改', '查看'] },
    { icon: 'ant-design:shopping-cart-outlined', name: '采购申请 · 显示器 4 台', who: '我 · 11-06 提交', steps: [1, 1, 1, 2], node: '财务复核', left: '剩 4 天', actions: ['催办', '查看'] },
  ],
};

/** 快捷入口：path 是真实路由 */
export const quickLinks = [
  { title: '用户管理', icon: 'ant-design:user-outlined', path: '/system/user' },
  { title: '角色管理', icon: 'ant-design:safety-outlined', path: '/system/role' },
  { title: '部门管理', icon: 'ant-design:apartment-outlined', path: '/system/depart' },
  { title: '数据字典', icon: 'ant-design:book-outlined', path: '/system/dict' },
  { title: '通知公告', icon: 'ant-design:notification-outlined', path: '/system/notice' },
  { title: '定时任务', icon: 'ant-design:clock-circle-outlined', path: '/monitor/quartz' },
];

export const notices = [
  { title: '关于 11 月 15 日 22:00–24:00 系统停机升级的通知', meta: '系统公告 · 运维组 · 今天 10:24', unread: true },
  { title: '2026 年度考勤与休假制度修订说明（第二版）', meta: '制度发布 · 人力资源部 · 昨天 16:02', unread: true },
  { title: '新版移动端已上线，支持流程审批与扫码登录', meta: '系统公告 · 研发中心 · 11-10', unread: false },
  { title: '第四季度安全培训报名开始，截止 11 月 20 日', meta: '通知 · 行政部 · 11-08', unread: false },
];

export const sysStatus = [
  { label: 'CPU 使用率', icon: 'ant-design:cloud-server-outlined', value: '23', unit: '%', percent: 23, meta: '8 核 · 近 5 分钟均值' },
  { label: 'JVM 内存', icon: 'ant-design:database-outlined', value: '1.02', unit: '/ 1.5 GB', percent: 68, warn: true, meta: '堆内存 68%，接近上限' },
  { label: 'Redis', icon: 'ant-design:cluster-outlined', value: '1,284', unit: '个键', percent: 12, meta: '内存 49 MB · 命中率 98.6%' },
  { label: '定时任务', icon: 'ant-design:clock-circle-outlined', value: '6', unit: '/ 6 运行中', percent: 100, meta: '最近 24 小时 0 次失败' },
];
