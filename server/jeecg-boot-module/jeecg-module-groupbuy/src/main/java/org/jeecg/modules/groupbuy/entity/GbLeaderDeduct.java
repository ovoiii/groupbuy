package org.jeecg.modules.groupbuy.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;

/** 团长售后扣款，一个售后最多一条（表 gb_leader_deduct） */
@Data
@TableName("gb_leader_deduct")
public class GbLeaderDeduct implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String pointId;
    private String aftersaleId;
    private String asNo;
    private String orderId;
    private String orderNo;
    private String buyerName;
    /** 售后类型 */
    private String asType;
    /** 退给买家的钱 */
    private BigDecimal refundAmount;
    /** 从团长佣金扣的钱 */
    private BigDecimal amount;
    /** 扣款说明，团长能看到 */
    private String reason;
    /** done 已扣 / revoked 已撤销 */
    private String status;
    private String revokeReason;
    private String revokeBy;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date revokeTime;
    private String createBy;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
