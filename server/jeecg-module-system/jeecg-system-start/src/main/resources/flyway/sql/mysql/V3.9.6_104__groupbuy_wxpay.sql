-- 团购：微信支付（小程序 JSAPI）、退款记录、手机号登录、分享来源

-- 订单：支付方式和微信支付单号
ALTER TABLE gb_order
  ADD COLUMN pay_channel varchar(8) DEFAULT NULL COMMENT 'wx 微信支付 / mock 模拟支付 / free 0 元单' AFTER pay_time,
  ADD COLUMN transaction_id varchar(32) DEFAULT NULL COMMENT '微信支付单号' AFTER pay_channel,
  ADD COLUMN prepay_time datetime DEFAULT NULL COMMENT '第一次向微信下单的时间；有值的待付款订单关闭前要先问一下微信付没付' AFTER transaction_id;
UPDATE gb_order SET pay_channel = 'mock' WHERE pay_time IS NOT NULL AND pay_channel IS NULL;

-- 用户：手机号唯一（只来自微信手机号验证或开发登录）；记录第一次从哪个提货点的分享进来
ALTER TABLE gb_member DROP INDEX idx_gb_member_phone, ADD UNIQUE KEY uk_gb_member_phone (phone),
  ADD COLUMN source_point_id varchar(32) DEFAULT NULL COMMENT '首次从哪个提货点的分享进来（拉新统计）' AFTER default_point_id;

-- 退款：每次退款一条。业务里先写一条 pending，事务提交后再调微信，失败自动重试（退款单号不变，微信不会重复退）
CREATE TABLE IF NOT EXISTS gb_refund (
  id varchar(32) NOT NULL,
  refund_no varchar(32) NOT NULL COMMENT '商户退款单号 out_refund_no',
  order_id varchar(32) NOT NULL,
  order_no varchar(20) NOT NULL,
  aftersale_id varchar(32) DEFAULT NULL,
  pay_channel varchar(8) DEFAULT NULL,
  amount decimal(10,2) NOT NULL COMMENT '退款金额',
  total decimal(10,2) NOT NULL COMMENT '原订单实付',
  reason varchar(80) DEFAULT NULL,
  status varchar(12) NOT NULL COMMENT 'pending 待提交 / processing 退款中 / success 已退回 / failed 提交失败 / abnormal 退款异常 / closed 已关闭',
  wx_refund_id varchar(32) DEFAULT NULL,
  attempts int NOT NULL DEFAULT 0 COMMENT '提交次数',
  last_error varchar(255) DEFAULT NULL,
  success_time datetime DEFAULT NULL,
  create_time datetime DEFAULT NULL,
  update_time datetime DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_gb_refund_no (refund_no), KEY idx_gb_refund_order (order_id), KEY idx_gb_refund_status (status, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款记录';

-- ---------- 后台菜单：订单履约 下加「退款记录」 ----------
INSERT INTO sys_permission (id, parent_id, name, url, component, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_route, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, del_flag, rule_flag, status, internal_or_external) VALUES
('gb0000000000000000000000000000c5', 'gb0000000000000000000000000000c0', '退款记录', '/gb/refund', 'groupbuy/refund/index', 'GbRefund', NULL, 1, NULL, '1', 5, 0, NULL, 1, 1, 0, 0, 0, NULL, 'admin', NOW(), 0, 0, '1', 0);
INSERT INTO sys_role_permission (id, role_id, permission_id, data_rule_ids, operate_date, operate_ip)
SELECT CONCAT('gbrp', SUBSTRING(p.id, 5)), r.id, p.id, NULL, NOW(), '127.0.0.1'
FROM sys_permission p JOIN sys_role r ON r.role_code = 'admin'
WHERE p.id = 'gb0000000000000000000000000000c5';
