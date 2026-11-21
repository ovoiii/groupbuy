package org.jeecg.modules.groupbuy.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;

/** 团长佣金账户，按提货点（表 gb_leader_account）。余额只能通过 GbLeaderAccountMapper 里的原子 SQL 改 */
@Data
@TableName("gb_leader_account")
public class GbLeaderAccount implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.INPUT)
    private String pointId;
    /** 可提现；售后扣款不够扣时为负数，之后入账的佣金先抵上 */
    private BigDecimal balance;
    /** 提现审核中 */
    private BigDecimal frozen;
    /** 累计入账 */
    private BigDecimal totalIncome;
    /** 累计已提现 */
    private BigDecimal totalWithdrawn;
    /** 累计售后扣款（撤销的不算） */
    private BigDecimal totalDeducted;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
}
