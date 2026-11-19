package org.jeecg.modules.groupbuy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jeecg.modules.groupbuy.entity.GbCommission;
import org.jeecg.modules.groupbuy.entity.GbOrder;

import java.util.Date;
import java.util.List;

public interface GbCommissionMapper extends BaseMapper<GbCommission> {

    /** 可以结算的订单：已提货、提货时间早于 before、没有进行中的售后、还没结算过 */
    @Select("SELECT o.* FROM gb_order o LEFT JOIN gb_commission c ON c.order_id = o.id "
            + "WHERE o.status = 'done' AND o.after_status <> 'applying' AND o.pickup_at <= #{before} AND c.id IS NULL "
            + "ORDER BY o.pickup_at LIMIT #{limit}")
    List<GbOrder> settleable(@Param("before") Date before, @Param("limit") int limit);

    /** 还没结算的有效订单（已付款到已提货），用于算「待结算」预估；pointId 为空 = 全部 */
    @Select("<script>SELECT o.* FROM gb_order o LEFT JOIN gb_commission c ON c.order_id = o.id "
            + "WHERE o.status IN ('paid','shipping','arrived','done') AND c.id IS NULL "
            + "<if test='pointId != null'> AND o.point_id = #{pointId}</if></script>")
    List<GbOrder> unsettled(@Param("pointId") String pointId);
}
