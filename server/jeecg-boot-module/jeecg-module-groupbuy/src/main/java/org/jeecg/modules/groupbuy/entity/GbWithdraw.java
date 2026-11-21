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
    /** wait 待审核 / paying 打款中（商家转账，等团长确认收款或微信处理中）/ paid 已打款 / rej 已驳回 */
    private String status;
    /** 打款流水号 */
    private String voucher;
    /** 商家转账的商户单号（每次打款一个，重打换新号） */
    private String transferNo;
    /** 微信转账单号 */
    private String transferBillNo;
    /** 微信转账单状态：ACCEPTED / PROCESSING / WAIT_USER_CONFIRM / TRANSFERING / SUCCESS / FAIL / CANCELING / CANCELLED；UNKNOWN = 网络出错还不知道结果 */
    private String transferState;
    /** 团长确认收款要用的参数（wx.requestMerchantTransfer 的 package），不给后台看 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String packageInfo;
    /** 上次自动打款失败的原因 */
    private String transferFail;
    private Integer transferAttempts;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date transferTime;
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
