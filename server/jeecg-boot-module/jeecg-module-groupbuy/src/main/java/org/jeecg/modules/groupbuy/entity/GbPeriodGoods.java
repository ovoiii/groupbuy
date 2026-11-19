package org.jeecg.modules.groupbuy.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

/** 团期团品（表 gb_period_goods） */
@Data
@TableName("gb_period_goods")
public class GbPeriodGoods implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String periodId;
    private String goodsId;
    /** 团购价 */
    private BigDecimal price;
    /** 本期库存 */
    private Integer stock;
    /** 已售（已支付 + 待支付占用） */
    private Integer sold;
    /** 每人每期限购，0 不限 */
    private Integer limitQty;
    /** 角标：爆品 / 快抢光 … */
    private String tag;
    /** on / off */
    private String status;
    private Integer sortNo;
    private String createBy;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
    private String updateBy;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
}
