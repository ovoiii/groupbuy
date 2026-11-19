-- 团购：微信订阅消息（到货、取货提醒、售后结果、发车、入驻审核、提现结果）

-- 用户在小程序里点「允许」一次，这个模板就多 1 次发送次数（微信一次性订阅的规则），发一条扣一次
CREATE TABLE IF NOT EXISTS gb_notify_sub (
  member_id varchar(32) NOT NULL,
  template_id varchar(64) NOT NULL,
  quota int NOT NULL DEFAULT 0 COMMENT '剩余可发次数',
  update_time datetime DEFAULT NULL,
  PRIMARY KEY (member_id, template_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订阅消息剩余次数';

CREATE TABLE IF NOT EXISTS gb_notify_log (
  id varchar(32) NOT NULL,
  scene varchar(16) NOT NULL COMMENT 'arrived / pickup / aftersale / ship / apply / withdraw',
  member_id varchar(32) DEFAULT NULL,
  biz_id varchar(32) DEFAULT NULL COMMENT '订单 / 配送单 / 提货点 / 提现单 id',
  template_id varchar(64) DEFAULT NULL,
  status varchar(8) NOT NULL COMMENT 'sent 已发送 / mock 只记录 / skipped 没发 / failed 失败',
  note varchar(255) DEFAULT NULL COMMENT '没发或失败的原因',
  content varchar(1000) DEFAULT NULL COMMENT '消息内容（JSON）',
  create_time datetime DEFAULT NULL,
  PRIMARY KEY (id), KEY idx_gb_notify_time (create_time), KEY idx_gb_notify_member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订阅消息发送记录';

-- ---------- 后台菜单：订单履约 下加「消息通知」 ----------
INSERT INTO sys_permission (id, parent_id, name, url, component, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_route, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, del_flag, rule_flag, status, internal_or_external) VALUES
('gb0000000000000000000000000000c4', 'gb0000000000000000000000000000c0', '消息通知', '/gb/notify', 'groupbuy/notify/index', 'GbNotify', NULL, 1, NULL, '1', 4, 0, NULL, 1, 1, 0, 0, 0, NULL, 'admin', NOW(), 0, 0, '1', 0);
INSERT INTO sys_role_permission (id, role_id, permission_id, data_rule_ids, operate_date, operate_ip)
SELECT CONCAT('gbrp', SUBSTRING(p.id, 5)), r.id, p.id, NULL, NOW(), '127.0.0.1'
FROM sys_permission p JOIN sys_role r ON r.role_code = 'admin'
WHERE p.id = 'gb0000000000000000000000000000c4';
