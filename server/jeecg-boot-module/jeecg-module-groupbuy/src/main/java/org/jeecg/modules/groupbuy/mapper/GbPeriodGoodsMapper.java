package org.jeecg.modules.groupbuy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.jeecg.modules.groupbuy.entity.GbPeriodGoods;

public interface GbPeriodGoodsMapper extends BaseMapper<GbPeriodGoods> {

    /** 占库存：只有在售且剩余够的时候才成功，返回 1 成功 / 0 失败（并发安全） */
    @Update("UPDATE gb_period_goods SET sold = sold + #{n} WHERE id = #{id} AND status = 'on' AND sold + #{n} <= stock")
    int occupy(@Param("id") String id, @Param("n") int n);

    /** 释放库存（取消 / 超时关闭） */
    @Update("UPDATE gb_period_goods SET sold = GREATEST(sold - #{n}, 0) WHERE id = #{id}")
    int release(@Param("id") String id, @Param("n") int n);
}
