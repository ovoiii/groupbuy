-- ============================================================
-- 团长佣金结算和提现
-- 佣金 = 商品金额 × (实付 - 已退) / 实付 × 佣金比例；提货满 24 小时且无进行中售后时自动入账
-- ============================================================

ALTER TABLE gb_order ADD COLUMN commission_rate decimal(5,4) DEFAULT NULL COMMENT '付款时记下的团长佣金比例；为空按提货点当前比例' AFTER coupon_name;

ALTER TABLE gb_point MODIFY COLUMN commission_rate decimal(5,4) NOT NULL DEFAULT 0.1000 COMMENT '团长佣金比例，0.10 = 10%';
UPDATE gb_point SET commission_rate = 0.1000 WHERE commission_rate = 0;

CREATE TABLE IF NOT EXISTS gb_commission (
  id varchar(32) NOT NULL,
  order_id varchar(32) NOT NULL,
  order_no varchar(20) NOT NULL,
  period_id varchar(32) NOT NULL,
  point_id varchar(32) NOT NULL,
  buyer_name varchar(32) DEFAULT NULL,
  goods_amount decimal(10,2) NOT NULL COMMENT '订单商品金额',
  refund_amount decimal(10,2) NOT NULL DEFAULT 0 COMMENT '结算时已退款',
  base_amount decimal(10,2) NOT NULL COMMENT '计佣金额（扣掉退款部分）',
  rate decimal(5,4) NOT NULL,
  amount decimal(10,2) NOT NULL COMMENT '佣金',
  pickup_at datetime DEFAULT NULL,
  settle_time datetime NOT NULL COMMENT '入账时间',
  create_time datetime DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_gb_comm_order (order_id), KEY idx_gb_comm_point (point_id, settle_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='团长佣金（已入账，一单一条）';

CREATE TABLE IF NOT EXISTS gb_leader_account (
  point_id varchar(32) NOT NULL,
  balance decimal(10,2) NOT NULL DEFAULT 0 COMMENT '可提现',
  frozen decimal(10,2) NOT NULL DEFAULT 0 COMMENT '提现审核中',
  total_income decimal(10,2) NOT NULL DEFAULT 0 COMMENT '累计入账',
  total_withdrawn decimal(10,2) NOT NULL DEFAULT 0 COMMENT '累计已提现',
  update_time datetime DEFAULT NULL,
  PRIMARY KEY (point_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='团长佣金账户（按提货点）';

CREATE TABLE IF NOT EXISTS gb_withdraw (
  id varchar(32) NOT NULL,
  wd_no varchar(20) NOT NULL,
  point_id varchar(32) NOT NULL,
  member_id varchar(32) DEFAULT NULL COMMENT '提交申请的小程序用户',
  amount decimal(10,2) NOT NULL,
  method varchar(8) NOT NULL COMMENT 'wechat 微信零钱 / bank 银行卡',
  account_name varchar(32) NOT NULL COMMENT '收款人实名',
  account_no varchar(32) DEFAULT NULL COMMENT '银行卡号（微信零钱不填）',
  bank_name varchar(32) DEFAULT NULL,
  status varchar(8) NOT NULL DEFAULT 'wait' COMMENT 'wait 待审核 / paid 已打款 / rej 已驳回',
  voucher varchar(64) DEFAULT NULL COMMENT '打款流水号',
  reply varchar(128) DEFAULT NULL COMMENT '驳回原因',
  handle_time datetime DEFAULT NULL,
  handle_by varchar(32) DEFAULT NULL,
  create_by varchar(32) DEFAULT NULL, create_time datetime DEFAULT NULL,
  update_by varchar(32) DEFAULT NULL, update_time datetime DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_gb_wd_no (wd_no), KEY idx_gb_wd_point (point_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='团长提现';

-- ---------- 后台菜单：团长与提货点 下加两页 ----------
INSERT INTO sys_permission (id, parent_id, name, url, component, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_route, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, del_flag, rule_flag, status, internal_or_external) VALUES
('gb0000000000000000000000000000d2', 'gb0000000000000000000000000000d0', '佣金结算', '/gb/commission', 'groupbuy/commission/index', 'GbCommission', NULL, 1, NULL, '1', 2, 0, NULL, 1, 1, 0, 0, 0, NULL, 'admin', NOW(), 0, 0, '1', 0),
('gb0000000000000000000000000000d3', 'gb0000000000000000000000000000d0', '提现审核', '/gb/withdraw', 'groupbuy/withdraw/index', 'GbWithdraw', NULL, 1, NULL, '1', 3, 0, NULL, 1, 1, 0, 0, 0, NULL, 'admin', NOW(), 0, 0, '1', 0);

INSERT INTO sys_role_permission (id, role_id, permission_id, data_rule_ids, operate_date, operate_ip)
SELECT CONCAT('gbrp', SUBSTRING(p.id, 5)), r.id, p.id, NULL, NOW(), '127.0.0.1'
FROM sys_permission p JOIN sys_role r ON r.role_code = 'admin'
WHERE p.id IN ('gb0000000000000000000000000000d2', 'gb0000000000000000000000000000d3');
