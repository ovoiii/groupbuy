package org.jeecg.modules.groupbuy.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

/** 团购商品库（表 gb_goods） */
@Data
@TableName("gb_goods")
public class GbGoods implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    /** 商品名 */
    private String name;
    /** 规格，如 500g / 盒 */
    private String spec;
    /** 类目：水果/蔬菜/肉禽蛋/水产/乳品烘焙/粮油/日用 */
    private String category;
    /** 采购价 */
    private BigDecimal costPrice;
    /** 建议团购价 */
    private BigDecimal suggestPrice;
    /** 参考价（划线价） */
    private BigDecimal origPrice;
    private String supplier;
    /** 产地 */
    private String origin;
    /** normal 常温 / cold 冷藏 / frozen 冷冻 */
    private String storage;
    /** 图片，逗号分隔，第一张为封面 */
    private String images;
    /** 详情 */
    private String detail;
    /** on 可上团 / off 已下架 */
    private String status;
    private String offReason;
    private String createBy;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createTime;
    private String updateBy;
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updateTime;
}
