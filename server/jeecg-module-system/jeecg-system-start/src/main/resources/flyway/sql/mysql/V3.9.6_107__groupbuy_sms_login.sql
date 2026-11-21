-- ============================================================
-- 短信验证码登录：每条验证码一行，频率限制（同号 60 秒一条、每天 10 条，同 IP 每小时 20 条）也按这张表算
-- 验证码只存 HMAC 摘要；输错 5 次作废；用过一次作废
-- ============================================================

CREATE TABLE IF NOT EXISTS gb_sms_code (
  id varchar(32) NOT NULL,
  phone varchar(16) NOT NULL,
  code_hash varchar(64) NOT NULL COMMENT '验证码的 HMAC-SHA256，不存明文',
  ip varchar(64) DEFAULT NULL,
  status varchar(8) NOT NULL COMMENT 'sending 发送中 / sent 已发送 / failed 发送失败 / used 已使用',
  attempts int NOT NULL DEFAULT 0 COMMENT '输错次数',
  expire_time datetime NOT NULL,
  note varchar(255) DEFAULT NULL COMMENT '短信服务商返回 / 失败原因',
  create_time datetime NOT NULL,
  PRIMARY KEY (id), KEY idx_gb_sms_phone (phone, create_time), KEY idx_gb_sms_ip (ip, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='短信验证码';
