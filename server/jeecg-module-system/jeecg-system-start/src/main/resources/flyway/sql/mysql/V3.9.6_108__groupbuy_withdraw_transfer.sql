-- ============================================================
-- 团长提现自动打款：提到微信零钱的，审核通过后调微信支付「商家转账」打到团长微信，团长在小程序里点「确认收款」
-- 状态：wait 待审核 → paying 打款中（等团长确认收款 / 微信处理中）→ paid 已打款；转账失败或撤销回到 wait，可以重打或驳回
-- ============================================================

ALTER TABLE gb_withdraw
  MODIFY COLUMN status varchar(8) NOT NULL DEFAULT 'wait' COMMENT 'wait 待审核 / paying 打款中 / paid 已打款 / rej 已驳回',
  ADD COLUMN transfer_no varchar(32) DEFAULT NULL COMMENT '商家转账的商户单号（每次打款一个，重打换新号）' AFTER voucher,
  ADD COLUMN transfer_bill_no varchar(64) DEFAULT NULL COMMENT '微信转账单号' AFTER transfer_no,
  ADD COLUMN transfer_state varchar(20) DEFAULT NULL COMMENT '微信转账单状态 ACCEPTED / WAIT_USER_CONFIRM / TRANSFERING / SUCCESS / FAIL / CANCELING / CANCELLED' AFTER transfer_bill_no,
  ADD COLUMN package_info varchar(1024) DEFAULT NULL COMMENT '团长确认收款要用的参数' AFTER transfer_state,
  ADD COLUMN transfer_fail varchar(255) DEFAULT NULL COMMENT '上次自动打款失败的原因' AFTER package_info,
  ADD COLUMN transfer_attempts int NOT NULL DEFAULT 0 COMMENT '发起过几次自动打款' AFTER transfer_fail,
  ADD COLUMN transfer_time datetime DEFAULT NULL COMMENT '最近一次发起 / 同步转账的时间' AFTER transfer_attempts,
  ADD UNIQUE KEY uk_gb_wd_transfer (transfer_no);
