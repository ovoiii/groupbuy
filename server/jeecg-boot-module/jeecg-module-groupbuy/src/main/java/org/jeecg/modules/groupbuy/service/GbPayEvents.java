package org.jeecg.modules.groupbuy.service;

/** 业务事务里发出、提交后由 GbPayService 处理的事件（避免核心业务直接依赖微信支付） */
public final class GbPayEvents {
    private GbPayEvents() {
    }

    /** 新写了一条待提交的退款 */
    public record RefundCreated(String refundId) {
    }

    /** 向微信下过单的待付款订单关闭了：去微信关单，防止之后还能付 */
    public record UnpaidClosed(String orderNo) {
    }
}
