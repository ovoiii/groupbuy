package org.jeecg.modules.groupbuy.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;

/** 团长佣金，已入账，一单一条（表 gb_commission） */
@Data
@TableName("gb_commission")
public class GbCommission implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String orderId;
    private String orderNo;
    private String periodId;
    private String pointId;
    private String buyerName;
    /** 订单商品金额 */
    private BigDecimal goodsAmount;
    /** 结算时已退款 */
    private BigDecimal refundAmount;
    /** 计佣金额（扣掉退款部分） */
    private BigDecimal baseAmount;
    private BigDecimal rate;
    /** 佣金 */
    private BigDecimal amount;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date pickupAt;
    /** 入账时间 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date settleTime;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
