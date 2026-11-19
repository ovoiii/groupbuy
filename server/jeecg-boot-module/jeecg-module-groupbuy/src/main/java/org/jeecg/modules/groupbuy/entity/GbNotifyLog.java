package org.jeecg.modules.groupbuy.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/** 订阅消息发送记录（表 gb_notify_log） */
@Data
@TableName("gb_notify_log")
public class GbNotifyLog implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    /** arrived 到货 / pickup 取货提醒 / aftersale 售后结果 / ship 发车 / apply 入驻审核 / withdraw 提现结果 */
    private String scene;
    private String memberId;
    /** 订单 / 配送单 / 提货点 / 提现单 id */
    private String bizId;
    private String templateId;
    /** sent 已发送 / mock 只记录（没配模板或 AppID）/ skipped 没发 / failed 失败 */
    private String status;
    private String note;
    /** 消息内容 JSON */
    private String content;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
}
