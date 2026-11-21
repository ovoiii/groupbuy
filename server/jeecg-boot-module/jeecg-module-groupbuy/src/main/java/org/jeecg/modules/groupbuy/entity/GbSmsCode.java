package org.jeecg.modules.groupbuy.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/** 短信验证码（表 gb_sms_code），只存摘要 */
@Data
@TableName("gb_sms_code")
public class GbSmsCode implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String phone;
    private String codeHash;
    private String ip;
    /** sending 发送中 / sent 已发送 / failed 发送失败 / used 已使用 */
    private String status;
    /** 输错次数 */
    private Integer attempts;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date expireTime;
    private String note;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
