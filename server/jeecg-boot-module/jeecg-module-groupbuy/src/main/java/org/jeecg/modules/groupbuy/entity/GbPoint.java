package org.jeecg.modules.groupbuy.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

/** 提货点 / 团长（表 gb_point） */
@Data
@TableName("gb_point")
public class GbPoint implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    /** 提货点名称 */
    private String name;
    private String address;
    private BigDecimal lng;
    private BigDecimal lat;
    private String leaderName;
    private String leaderPhone;
    /** 团长对应的小程序用户 */
    private String leaderMemberId;
    /** 覆盖户数 */
    private Integer households;
    /** 有冰柜才能卖冷藏/冷冻商品 */
    private Integer hasFridge;
    private String openHours;
    /** pending 待审核 / ok 合作中 / paused 已暂停 / rejected 已拒绝 */
    private String status;
    /** 暂停原因 / 审核意见 */
    private String statusNote;
    /** 入驻申请说明 */
    private String applyNote;
    /** 佣金比例（预留，本版不结算） */
    private BigDecimal commissionRate;
    /** 合作开始 */
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd")
    @DateTimeFormat(pattern = "yyyy-MM-dd")
    private Date since;
    private String createBy;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
    private String updateBy;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
}
