package org.jeecg.modules.groupbuy.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

/** 订单（表 gb_order） */
@Data
@TableName("gb_order")
public class GbOrder implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String orderNo;
    private String periodId;
    private String pointId;
    private String memberId;
    private String buyerName;
    private String buyerPhone;
    private BigDecimal goodsAmount;
    private BigDecimal discountAmount;
    private BigDecimal payAmount;
    private BigDecimal refundAmount;
    private String userCouponId;
    private String couponName;
    /** 付款时记下的团长佣金比例；为空按提货点当前比例 */
    private BigDecimal commissionRate;
    /** unpaid 待付款 / paid 待截单 / shipping 配送中 / arrived 待提货 / done 已提货 / closed 已关闭 */
    private String status;
    /** none / applying 售后中 / refunded 已退款 / rejected 已驳回 */
    private String afterStatus;
    private String pickupCode;
    /** 给团长留言 */
    private String remark;
    private String closeReason;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date payTime;
    /** wx 微信支付 / mock 模拟支付 / free 0 元单 */
    private String payChannel;
    /** 微信支付单号 */
    private String transactionId;
    /** 第一次向微信下单的时间：有值的待付款订单，关闭前要先问微信付没付 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date prepayTime;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date payExpireTime;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date pickupAt;
    private Integer remindCount;
    private String createBy;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
    private String updateBy;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
}
