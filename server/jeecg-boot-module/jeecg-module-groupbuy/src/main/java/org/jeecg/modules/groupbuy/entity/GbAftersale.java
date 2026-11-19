package org.jeecg.modules.groupbuy.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

/** 售后（表 gb_aftersale） */
@Data
@TableName("gb_aftersale")
public class GbAftersale implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String asNo;
    private String orderId;
    private String memberId;
    private String pointId;
    /** 有问题的订单商品 id，逗号分隔 */
    private String itemIds;
    /** 质量问题 / 少件 / 破损 / 发错货 / 没来得及取 */
    private String type;
    private String reason;
    private String images;
    private BigDecimal applyAmount;
    private BigDecimal refundAmount;
    /** 责任方：供应商 / 团长 / 平台 / 物流 */
    private String duty;
    /** 回复买家 */
    private String reply;
    /** wait 待审核 / done 已退款 / rej 已驳回 */
    private String status;
    /** 超过自动同意 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date deadline;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date handleTime;
    private String handleBy;
    private String createBy;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
    private String updateBy;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
}
