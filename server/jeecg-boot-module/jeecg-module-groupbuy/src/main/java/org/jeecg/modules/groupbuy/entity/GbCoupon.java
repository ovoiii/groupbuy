package org.jeecg.modules.groupbuy.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

/** 优惠（表 gb_coupon） */
@Data
@TableName("gb_coupon")
public class GbCoupon implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String name;
    /** full 满减 / discount 折扣 / newbie 新人 1 元购 */
    private String type;
    /** 满多少可用 */
    private BigDecimal threshold;
    /** 满减金额 */
    private BigDecimal amount;
    /** 折扣，如 0.90 */
    private BigDecimal rate;
    /** 折扣最多减 */
    private BigDecimal maxDiscount;
    /** 限类目，为空=全部 */
    private String scopeCategory;
    /** 新人 1 元购指定商品 */
    private String newbieGoodsId;
    /** 发放总量，0=不限 */
    private Integer total;
    private Integer received;
    private Integer used;
    private Integer perLimit;
    /** 领取后几天有效；与固定日期二选一 */
    private Integer validDays;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date validStart;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date validEnd;
    /** home 首页领券 / admin 只能后台发放 */
    private String channel;
    /** on 进行中 / stop 已停止 */
    private String status;
    private String createBy;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
    private String updateBy;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
}
