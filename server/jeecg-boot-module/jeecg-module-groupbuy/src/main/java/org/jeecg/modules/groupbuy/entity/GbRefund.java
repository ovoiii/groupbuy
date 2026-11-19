package org.jeecg.modules.groupbuy.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;

/** 退款记录（表 gb_refund）：业务里先写 pending，事务提交后调微信退款，失败自动重试 */
@Data
@TableName("gb_refund")
public class GbRefund implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    /** 商户退款单号（重试时不变，微信不会重复退） */
    private String refundNo;
    private String orderId;
    private String orderNo;
    private String aftersaleId;
    private String payChannel;
    private BigDecimal amount;
    /** 原订单实付 */
    private BigDecimal total;
    private String reason;
    /** pending 待提交 / processing 退款中 / success 已退回 / failed 提交失败 / abnormal 退款异常 / closed 已关闭 */
    private String status;
    private String wxRefundId;
    private Integer attempts;
    private String lastError;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date successTime;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
}
