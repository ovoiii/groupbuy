-- ============================================================
-- 订阅消息发送失败后自动重发
-- 网络出错、微信繁忙、频率超限这类临时失败：1、5、30 分钟后各重发一次，等待期间订阅次数先占着；
-- 都失败了记为「失败」并退回次数，后台可以手动重发。模板 / openid 不对这类重发也没用的，直接记失败。
-- ============================================================

ALTER TABLE gb_notify_log
  ADD COLUMN page varchar(128) DEFAULT NULL COMMENT '点开消息进的小程序页面' AFTER template_id,
  ADD COLUMN payload varchar(2000) DEFAULT NULL COMMENT '发给微信的 data（原样，重发用）' AFTER content,
  ADD COLUMN attempts int NOT NULL DEFAULT 0 COMMENT '已经调用微信的次数' AFTER payload,
  ADD COLUMN next_retry_time datetime DEFAULT NULL COMMENT '下次自动重发时间（status=retry 时）' AFTER attempts,
  ADD COLUMN update_time datetime DEFAULT NULL AFTER create_time,
  MODIFY COLUMN status varchar(8) NOT NULL COMMENT 'sent 已发送 / mock 只记录 / skipped 没发 / retry 等待重发 / failed 失败',
  ADD KEY idx_gb_notify_retry (status, next_retry_time);
