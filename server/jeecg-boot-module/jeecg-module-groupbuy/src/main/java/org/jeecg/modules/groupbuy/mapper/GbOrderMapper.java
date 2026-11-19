package org.jeecg.modules.groupbuy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jeecg.modules.groupbuy.entity.GbOrder;

public interface GbOrderMapper extends BaseMapper<GbOrder> {

    /** 锁住订单行（必须在事务里调）：支付回调和主动查单可能同时到，只能有一个去改状态 */
    @Select("SELECT * FROM gb_order WHERE order_no = #{no} FOR UPDATE")
    GbOrder lockByNo(@Param("no") String orderNo);
}
