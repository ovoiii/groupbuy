package org.jeecg.modules.groupbuy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.jeecg.modules.groupbuy.entity.GbCoupon;

public interface GbCouponMapper extends BaseMapper<GbCoupon> {

    /** 领取计数 +1，发完了返回 0 */
    @Update("UPDATE gb_coupon SET received = received + 1 WHERE id = #{id} AND status = 'on' AND (total = 0 OR received < total)")
    int incReceived(@Param("id") String id);

    @Update("UPDATE gb_coupon SET used = used + #{d} WHERE id = #{id}")
    int addUsed(@Param("id") String id, @Param("d") int d);
}
