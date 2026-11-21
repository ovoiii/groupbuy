-- ============================================================
-- 团长责任售后从佣金里扣款
-- 售后审核时责任方选「团长」，退款的全部或一部分从团长可提现余额里扣；
-- 余额不够扣时记成负数，之后入账的佣金先抵上（负数时提不了现）。扣错了可以在后台撤销，钱退回余额。
-- ============================================================

ALTER TABLE gb_leader_account ADD COLUMN total_deducted decimal(10,2) NOT NULL DEFAULT 0 COMMENT '累计售后扣款（撤销的不算）' AFTER total_withdrawn;
ALTER TABLE gb_leader_account MODIFY COLUMN balance decimal(10,2) NOT NULL DEFAULT 0 COMMENT '可提现；售后扣款不够扣时为负数';

CREATE TABLE IF NOT EXISTS gb_leader_deduct (
  id varchar(32) NOT NULL,
  point_id varchar(32) NOT NULL,
  aftersale_id varchar(32) NOT NULL,
  as_no varchar(20) NOT NULL,
  order_id varchar(32) NOT NULL,
  order_no varchar(20) NOT NULL,
  buyer_name varchar(32) DEFAULT NULL,
  as_type varchar(16) DEFAULT NULL COMMENT '售后类型：少件 / 破损 / 没来得及取…',
  refund_amount decimal(10,2) NOT NULL COMMENT '这次售后退给买家的钱',
  amount decimal(10,2) NOT NULL COMMENT '从团长佣金扣的钱',
  reason varchar(128) DEFAULT NULL COMMENT '扣款说明，团长能看到',
  status varchar(8) NOT NULL DEFAULT 'done' COMMENT 'done 已扣 / revoked 已撤销',
  revoke_reason varchar(128) DEFAULT NULL,
  revoke_by varchar(32) DEFAULT NULL,
  revoke_time datetime DEFAULT NULL,
  create_by varchar(32) DEFAULT NULL,
  create_time datetime DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_gb_ded_as (aftersale_id), KEY idx_gb_ded_point (point_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='团长售后扣款（一个售后最多一条）';
