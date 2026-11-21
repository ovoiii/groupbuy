package org.jeecg.modules.groupbuy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jeecg.modules.groupbuy.entity.GbWithdraw;

public interface GbWithdrawMapper extends BaseMapper<GbWithdraw> {

    /** 按商家转账单号锁住这笔提现（转账结果通知、主动查询、发起转账同时到时只处理一次） */
    @Select("SELECT * FROM gb_withdraw WHERE transfer_no = #{no} FOR UPDATE")
    GbWithdraw lockByTransferNo(@Param("no") String transferNo);
}
