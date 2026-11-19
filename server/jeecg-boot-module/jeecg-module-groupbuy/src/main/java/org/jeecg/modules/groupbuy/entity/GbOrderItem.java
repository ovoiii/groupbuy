package org.jeecg.modules.groupbuy.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.io.Serializable;
import java.math.BigDecimal;
import lombok.Data;

/** 订单商品（表 gb_order_item） */
@Data
@TableName("gb_order_item")
public class GbOrderItem implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String orderId;
    private String periodGoodsId;
    private String goodsId;
    private String goodsName;
    private String spec;
    private String image;
    private BigDecimal price;
    private Integer qty;
    private BigDecimal amount;
    /** 新人 1 元购的那一件 */
    private Integer newbie;
}
