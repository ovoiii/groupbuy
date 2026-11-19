package org.jeecg.modules.groupbuy.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;

/** 团长提现（表 gb_withdraw） */
@Data
@TableName("gb_withdraw")
public class GbWithdraw implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String wdNo;
    private String pointId;
    /** 提交申请的小程序用户 */
    private String memberId;
    private BigDecimal amount;
    /** wechat 微信零钱 / bank 银行卡 */
    private String method;
    /** 收款人实名 */
    private String accountName;
    /** 银行卡号（微信零钱不填） */
    private String accountNo;
    private String bankName;
    /** wait 待审核 / paid 已打款 / rej 已驳回 */
    private String status;
    /** 打款流水号 */
    private String voucher;
    /** 驳回原因 */
    private String reply;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date handleTime;
    private String handleBy;
    private String createBy;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
    private String updateBy;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
}
