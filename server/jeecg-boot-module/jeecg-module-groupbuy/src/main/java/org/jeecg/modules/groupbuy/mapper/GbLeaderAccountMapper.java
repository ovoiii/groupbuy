package org.jeecg.modules.groupbuy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.jeecg.modules.groupbuy.entity.GbLeaderAccount;

import java.math.BigDecimal;

/** 余额一律用这里的原子 SQL 改，不要 updateById（并发下会覆盖） */
public interface GbLeaderAccountMapper extends BaseMapper<GbLeaderAccount> {

    @Insert("INSERT IGNORE INTO gb_leader_account (point_id, balance, frozen, total_income, total_withdrawn, update_time) VALUES (#{p}, 0, 0, 0, 0, NOW())")
    int ensure(@Param("p") String pointId);

    /** 佣金入账 */
    @Update("UPDATE gb_leader_account SET balance = balance + #{a}, total_income = total_income + #{a}, update_time = NOW() WHERE point_id = #{p}")
    int income(@Param("p") String pointId, @Param("a") BigDecimal amount);

    /** 申请提现：余额转到审核中，余额不够返回 0 */
    @Update("UPDATE gb_leader_account SET balance = balance - #{a}, frozen = frozen + #{a}, update_time = NOW() WHERE point_id = #{p} AND balance >= #{a}")
    int freeze(@Param("p") String pointId, @Param("a") BigDecimal amount);

    /** 打款完成 */
    @Update("UPDATE gb_leader_account SET frozen = frozen - #{a}, total_withdrawn = total_withdrawn + #{a}, update_time = NOW() WHERE point_id = #{p} AND frozen >= #{a}")
    int paidOut(@Param("p") String pointId, @Param("a") BigDecimal amount);

    /** 驳回：退回余额 */
    @Update("UPDATE gb_leader_account SET frozen = frozen - #{a}, balance = balance + #{a}, update_time = NOW() WHERE point_id = #{p} AND frozen >= #{a}")
    int unfreeze(@Param("p") String pointId, @Param("a") BigDecimal amount);
}
