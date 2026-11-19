-- ============================================================
-- 邻里鲜 · 社区团购：业务表 + 后台菜单
-- 金额一律 decimal(10,2)；时间 datetime；状态用英文短码，含义见注释
-- ============================================================

CREATE TABLE IF NOT EXISTS gb_goods (
  id varchar(32) NOT NULL,
  name varchar(64) NOT NULL COMMENT '商品名',
  spec varchar(64) NOT NULL COMMENT '规格，如 500g / 盒',
  category varchar(16) NOT NULL COMMENT '类目：水果/蔬菜/肉禽蛋/水产/乳品烘焙/粮油/日用',
  cost_price decimal(10,2) NOT NULL COMMENT '采购价',
  suggest_price decimal(10,2) NOT NULL COMMENT '建议团购价',
  orig_price decimal(10,2) DEFAULT NULL COMMENT '参考价（划线价）',
  supplier varchar(64) DEFAULT NULL,
  origin varchar(32) DEFAULT NULL COMMENT '产地',
  storage varchar(8) NOT NULL DEFAULT 'normal' COMMENT 'normal 常温 / cold 冷藏 / frozen 冷冻',
  images varchar(1000) DEFAULT NULL COMMENT '图片，逗号分隔，第一张为封面',
  detail text COMMENT '详情',
  status varchar(8) NOT NULL DEFAULT 'on' COMMENT 'on 可上团 / off 已下架',
  off_reason varchar(64) DEFAULT NULL,
  create_by varchar(32) DEFAULT NULL, create_time datetime DEFAULT NULL,
  update_by varchar(32) DEFAULT NULL, update_time datetime DEFAULT NULL,
  PRIMARY KEY (id), KEY idx_gb_goods_cat (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='团购商品库';

CREATE TABLE IF NOT EXISTS gb_period (
  id varchar(32) NOT NULL,
  name varchar(32) NOT NULL COMMENT '如 11月17日团',
  open_time datetime NOT NULL COMMENT '开团',
  cutoff_time datetime NOT NULL COMMENT '截单',
  pickup_time datetime NOT NULL COMMENT '开始提货',
  pickup_end_time datetime NOT NULL COMMENT '提货截止',
  status varchar(8) NOT NULL DEFAULT 'draft' COMMENT 'draft 草稿 / plan 待开团 / live 进行中 / cutoff 已截单(配送) / pickup 自提中 / done 已结束',
  point_scope varchar(1000) DEFAULT NULL COMMENT '为空=全部合作中的提货点；否则逗号分隔提货点 id',
  create_by varchar(32) DEFAULT NULL, create_time datetime DEFAULT NULL,
  update_by varchar(32) DEFAULT NULL, update_time datetime DEFAULT NULL,
  PRIMARY KEY (id), KEY idx_gb_period_open (open_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='团期';

CREATE TABLE IF NOT EXISTS gb_period_goods (
  id varchar(32) NOT NULL,
  period_id varchar(32) NOT NULL,
  goods_id varchar(32) NOT NULL,
  price decimal(10,2) NOT NULL COMMENT '团购价',
  stock int NOT NULL COMMENT '本期库存',
  sold int NOT NULL DEFAULT 0 COMMENT '已售（已支付 + 待支付占用）',
  limit_qty int NOT NULL DEFAULT 0 COMMENT '每人每期限购，0 不限',
  tag varchar(16) DEFAULT NULL COMMENT '角标：爆品 / 快抢光 …',
  status varchar(8) NOT NULL DEFAULT 'on' COMMENT 'on / off',
  sort_no int NOT NULL DEFAULT 0,
  create_by varchar(32) DEFAULT NULL, create_time datetime DEFAULT NULL,
  update_by varchar(32) DEFAULT NULL, update_time datetime DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_gb_pg (period_id, goods_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='团期团品';

CREATE TABLE IF NOT EXISTS gb_point (
  id varchar(32) NOT NULL,
  name varchar(64) NOT NULL COMMENT '提货点名称',
  address varchar(128) NOT NULL,
  lng decimal(10,6) DEFAULT NULL, lat decimal(10,6) DEFAULT NULL,
  leader_name varchar(32) NOT NULL,
  leader_phone varchar(20) NOT NULL,
  leader_member_id varchar(32) DEFAULT NULL COMMENT '团长对应的小程序用户',
  households int NOT NULL DEFAULT 0 COMMENT '覆盖户数',
  has_fridge tinyint NOT NULL DEFAULT 0 COMMENT '有冰柜才能卖冷藏/冷冻商品',
  open_hours varchar(32) DEFAULT '08:00-21:00',
  status varchar(8) NOT NULL DEFAULT 'pending' COMMENT 'pending 待审核 / ok 合作中 / paused 已暂停 / rejected 已拒绝',
  status_note varchar(128) DEFAULT NULL COMMENT '暂停原因 / 审核意见',
  apply_note varchar(255) DEFAULT NULL COMMENT '入驻申请说明',
  commission_rate decimal(5,4) NOT NULL DEFAULT 0 COMMENT '佣金比例（预留，本版不结算）',
  since date DEFAULT NULL COMMENT '合作开始',
  create_by varchar(32) DEFAULT NULL, create_time datetime DEFAULT NULL,
  update_by varchar(32) DEFAULT NULL, update_time datetime DEFAULT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提货点 / 团长';

CREATE TABLE IF NOT EXISTS gb_member (
  id varchar(32) NOT NULL,
  openid varchar(64) DEFAULT NULL,
  nickname varchar(32) DEFAULT NULL,
  phone varchar(20) DEFAULT NULL,
  default_point_id varchar(32) DEFAULT NULL,
  first_order_done tinyint NOT NULL DEFAULT 0 COMMENT '是否已完成首单（新人专享用）',
  create_by varchar(32) DEFAULT NULL, create_time datetime DEFAULT NULL,
  update_by varchar(32) DEFAULT NULL, update_time datetime DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_gb_member_openid (openid), KEY idx_gb_member_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='小程序用户';

CREATE TABLE IF NOT EXISTS gb_order (
  id varchar(32) NOT NULL,
  order_no varchar(20) NOT NULL,
  period_id varchar(32) NOT NULL,
  point_id varchar(32) NOT NULL,
  member_id varchar(32) NOT NULL,
  buyer_name varchar(32) DEFAULT NULL,
  buyer_phone varchar(20) DEFAULT NULL,
  goods_amount decimal(10,2) NOT NULL,
  discount_amount decimal(10,2) NOT NULL DEFAULT 0,
  pay_amount decimal(10,2) NOT NULL,
  refund_amount decimal(10,2) NOT NULL DEFAULT 0,
  user_coupon_id varchar(32) DEFAULT NULL,
  coupon_name varchar(32) DEFAULT NULL,
  status varchar(8) NOT NULL COMMENT 'unpaid 待付款 / paid 待截单 / shipping 配送中 / arrived 待提货 / done 已提货 / closed 已关闭',
  after_status varchar(8) NOT NULL DEFAULT 'none' COMMENT 'none / applying 售后中 / refunded 已退款 / rejected 已驳回',
  pickup_code varchar(6) DEFAULT NULL,
  remark varchar(64) DEFAULT NULL COMMENT '给团长留言',
  close_reason varchar(32) DEFAULT NULL,
  pay_time datetime DEFAULT NULL,
  pay_expire_time datetime DEFAULT NULL,
  pickup_at datetime DEFAULT NULL,
  remind_count int NOT NULL DEFAULT 0,
  create_by varchar(32) DEFAULT NULL, create_time datetime DEFAULT NULL,
  update_by varchar(32) DEFAULT NULL, update_time datetime DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_gb_order_no (order_no),
  KEY idx_gb_order_member (member_id), KEY idx_gb_order_pp (period_id, point_id), KEY idx_gb_order_code (pickup_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单';

CREATE TABLE IF NOT EXISTS gb_order_item (
  id varchar(32) NOT NULL,
  order_id varchar(32) NOT NULL,
  period_goods_id varchar(32) NOT NULL,
  goods_id varchar(32) NOT NULL,
  goods_name varchar(64) NOT NULL,
  spec varchar(64) DEFAULT NULL,
  image varchar(255) DEFAULT NULL,
  price decimal(10,2) NOT NULL,
  qty int NOT NULL,
  amount decimal(10,2) NOT NULL,
  newbie tinyint NOT NULL DEFAULT 0 COMMENT '新人 1 元购的那一件',
  PRIMARY KEY (id), KEY idx_gb_item_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单商品';

CREATE TABLE IF NOT EXISTS gb_aftersale (
  id varchar(32) NOT NULL,
  as_no varchar(20) NOT NULL,
  order_id varchar(32) NOT NULL,
  member_id varchar(32) NOT NULL,
  point_id varchar(32) NOT NULL,
  item_ids varchar(500) NOT NULL COMMENT '有问题的订单商品 id，逗号分隔',
  type varchar(16) NOT NULL COMMENT '质量问题 / 少件 / 破损 / 发错货 / 没来得及取',
  reason varchar(255) DEFAULT NULL,
  images varchar(1000) DEFAULT NULL,
  apply_amount decimal(10,2) NOT NULL,
  refund_amount decimal(10,2) DEFAULT NULL,
  duty varchar(8) DEFAULT NULL COMMENT '责任方：供应商 / 团长 / 平台 / 物流',
  reply varchar(255) DEFAULT NULL COMMENT '回复买家',
  status varchar(8) NOT NULL DEFAULT 'wait' COMMENT 'wait 待审核 / done 已退款 / rej 已驳回',
  deadline datetime NOT NULL COMMENT '超过自动同意',
  handle_time datetime DEFAULT NULL,
  handle_by varchar(32) DEFAULT NULL,
  create_by varchar(32) DEFAULT NULL, create_time datetime DEFAULT NULL,
  update_by varchar(32) DEFAULT NULL, update_time datetime DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_gb_as_no (as_no), KEY idx_gb_as_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='售后';

CREATE TABLE IF NOT EXISTS gb_coupon (
  id varchar(32) NOT NULL,
  name varchar(32) NOT NULL,
  type varchar(8) NOT NULL COMMENT 'full 满减 / discount 折扣 / newbie 新人 1 元购',
  threshold decimal(10,2) NOT NULL DEFAULT 0 COMMENT '满多少可用',
  amount decimal(10,2) DEFAULT NULL COMMENT '满减金额',
  rate decimal(4,2) DEFAULT NULL COMMENT '折扣，如 0.90',
  max_discount decimal(10,2) DEFAULT NULL COMMENT '折扣最多减',
  scope_category varchar(16) DEFAULT NULL COMMENT '限类目，为空=全部',
  newbie_goods_id varchar(32) DEFAULT NULL COMMENT '新人 1 元购指定商品',
  total int NOT NULL DEFAULT 0 COMMENT '发放总量，0=不限',
  received int NOT NULL DEFAULT 0,
  used int NOT NULL DEFAULT 0,
  per_limit int NOT NULL DEFAULT 1,
  valid_days int DEFAULT NULL COMMENT '领取后几天有效；与固定日期二选一',
  valid_start datetime DEFAULT NULL, valid_end datetime DEFAULT NULL,
  channel varchar(8) NOT NULL DEFAULT 'home' COMMENT 'home 首页领券 / admin 只能后台发放',
  status varchar(8) NOT NULL DEFAULT 'on' COMMENT 'on 进行中 / stop 已停止',
  create_by varchar(32) DEFAULT NULL, create_time datetime DEFAULT NULL,
  update_by varchar(32) DEFAULT NULL, update_time datetime DEFAULT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='优惠';

CREATE TABLE IF NOT EXISTS gb_user_coupon (
  id varchar(32) NOT NULL,
  coupon_id varchar(32) NOT NULL,
  member_id varchar(32) NOT NULL,
  status varchar(8) NOT NULL DEFAULT 'unused' COMMENT 'unused / locked 下单未付 / used',
  expire_time datetime NOT NULL,
  order_id varchar(32) DEFAULT NULL,
  use_time datetime DEFAULT NULL,
  create_by varchar(32) DEFAULT NULL, create_time datetime DEFAULT NULL,
  update_by varchar(32) DEFAULT NULL, update_time datetime DEFAULT NULL,
  PRIMARY KEY (id), KEY idx_gb_uc_member (member_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户的券';

CREATE TABLE IF NOT EXISTS gb_delivery (
  id varchar(32) NOT NULL,
  period_id varchar(32) NOT NULL,
  point_id varchar(32) NOT NULL,
  order_count int NOT NULL DEFAULT 0,
  item_count int NOT NULL DEFAULT 0,
  boxes int DEFAULT NULL,
  status varchar(8) NOT NULL DEFAULT 'sorting' COMMENT 'sorting 分拣中 / shipping 配送中 / signed 已签收',
  driver varchar(32) DEFAULT NULL,
  sort_time datetime DEFAULT NULL, ship_time datetime DEFAULT NULL, sign_time datetime DEFAULT NULL,
  short_note varchar(255) DEFAULT NULL COMMENT '签收时登记的短少',
  create_by varchar(32) DEFAULT NULL, create_time datetime DEFAULT NULL,
  update_by varchar(32) DEFAULT NULL, update_time datetime DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_gb_delivery (period_id, point_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配送单（团期 × 提货点）';

-- ---------- 后台菜单 ----------
-- 旧的「主页」等示例菜单软删除（数据保留），团购菜单放在最前
UPDATE sys_permission SET del_flag = 1 WHERE (parent_id IS NULL OR parent_id = '') AND url IN ('/dashboard', '/airag', '/online', '/dataVisual', '/mytenant', '/about/index', '/standardPermission');

INSERT INTO sys_permission (id, parent_id, name, url, component, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_route, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, del_flag, rule_flag, status, internal_or_external) VALUES
('gb0000000000000000000000000000a0', '', '工作台', '/gb/dashboard', 'groupbuy/dashboard/index', 'GbDashboard', NULL, 1, NULL, '1', 0.10, 0, 'ant-design:dashboard-outlined', 1, 1, 0, 0, 0, NULL, 'admin', NOW(), 0, 0, '1', 0),
('gb0000000000000000000000000000b0', '', '团购运营', '/gb/op', 'layouts/RouteView', NULL, NULL, 0, NULL, '1', 0.20, 0, 'ant-design:shop-outlined', 1, 0, 0, 0, 0, NULL, 'admin', NOW(), 0, 0, '1', 0),
('gb0000000000000000000000000000b1', 'gb0000000000000000000000000000b0', '每日团期', '/gb/period', 'groupbuy/period/index', 'GbPeriod', NULL, 1, NULL, '1', 1, 0, NULL, 1, 1, 0, 0, 0, NULL, 'admin', NOW(), 0, 0, '1', 0),
('gb0000000000000000000000000000b2', 'gb0000000000000000000000000000b0', '编辑团期', '/gb/period/edit', 'groupbuy/period/Edit', 'GbPeriodEdit', NULL, 1, NULL, '1', 2, 0, NULL, 1, 1, 0, 1, 0, NULL, 'admin', NOW(), 0, 0, '1', 0),
('gb0000000000000000000000000000b3', 'gb0000000000000000000000000000b0', '商品库', '/gb/goods', 'groupbuy/goods/index', 'GbGoods', NULL, 1, NULL, '1', 3, 0, NULL, 1, 1, 0, 0, 0, NULL, 'admin', NOW(), 0, 0, '1', 0),
('gb0000000000000000000000000000c0', '', '订单履约', '/gb/fulfil', 'layouts/RouteView', NULL, NULL, 0, NULL, '1', 0.30, 0, 'ant-design:car-outlined', 1, 0, 0, 0, 0, NULL, 'admin', NOW(), 0, 0, '1', 0),
('gb0000000000000000000000000000c1', 'gb0000000000000000000000000000c0', '订单管理', '/gb/orders', 'groupbuy/order/index', 'GbOrder', NULL, 1, NULL, '1', 1, 0, NULL, 1, 1, 0, 0, 0, NULL, 'admin', NOW(), 0, 0, '1', 0),
('gb0000000000000000000000000000c2', 'gb0000000000000000000000000000c0', '分拣配送', '/gb/sorting', 'groupbuy/sorting/index', 'GbSorting', NULL, 1, NULL, '1', 2, 0, NULL, 1, 1, 0, 0, 0, NULL, 'admin', NOW(), 0, 0, '1', 0),
('gb0000000000000000000000000000c3', 'gb0000000000000000000000000000c0', '售后退款', '/gb/aftersale', 'groupbuy/aftersale/index', 'GbAftersale', NULL, 1, NULL, '1', 3, 0, NULL, 1, 1, 0, 0, 0, NULL, 'admin', NOW(), 0, 0, '1', 0),
('gb0000000000000000000000000000d0', '', '团长与提货点', '/gb/leader', 'layouts/RouteView', NULL, NULL, 0, NULL, '1', 0.40, 0, 'ant-design:environment-outlined', 1, 0, 0, 0, 0, NULL, 'admin', NOW(), 0, 0, '1', 0),
('gb0000000000000000000000000000d1', 'gb0000000000000000000000000000d0', '团长管理', '/gb/points', 'groupbuy/point/index', 'GbPoint', NULL, 1, NULL, '1', 1, 0, NULL, 1, 1, 0, 0, 0, NULL, 'admin', NOW(), 0, 0, '1', 0),
('gb0000000000000000000000000000e0', '', '营销中心', '/gb/mkt', 'layouts/RouteView', NULL, NULL, 0, NULL, '1', 0.50, 0, 'ant-design:gift-outlined', 1, 0, 0, 0, 0, NULL, 'admin', NOW(), 0, 0, '1', 0),
('gb0000000000000000000000000000e1', 'gb0000000000000000000000000000e0', '优惠活动', '/gb/coupons', 'groupbuy/coupon/index', 'GbCoupon', NULL, 1, NULL, '1', 1, 0, NULL, 1, 1, 0, 0, 0, NULL, 'admin', NOW(), 0, 0, '1', 0);

INSERT INTO sys_role_permission (id, role_id, permission_id, data_rule_ids, operate_date, operate_ip)
SELECT CONCAT('gbrp', SUBSTRING(p.id, 5)), r.id, p.id, NULL, NOW(), '127.0.0.1'
FROM sys_permission p JOIN sys_role r ON r.role_code = 'admin'
WHERE p.id LIKE 'gb00000000000000000000000000%';
