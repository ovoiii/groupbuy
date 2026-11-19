package org.jeecg.modules.groupbuy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.jeecg.modules.groupbuy.entity.GbNotifyLog;

/** 发送记录 + 订阅次数（gb_notify_sub 只有这几条原子 SQL，不单独建实体） */
public interface GbNotifyLogMapper extends BaseMapper<GbNotifyLog> {

    /** 用户点了「允许」：这个模板多 1 次 */
    @Insert("INSERT INTO gb_notify_sub (member_id, template_id, quota, update_time) VALUES (#{m}, #{t}, 1, NOW()) ON DUPLICATE KEY UPDATE quota = quota + 1, update_time = NOW()")
    int addQuota(@Param("m") String memberId, @Param("t") String templateId);

    /** 发之前扣 1 次，没有次数返回 0 */
    @Update("UPDATE gb_notify_sub SET quota = quota - 1, update_time = NOW() WHERE member_id = #{m} AND template_id = #{t} AND quota > 0")
    int useQuota(@Param("m") String memberId, @Param("t") String templateId);

    /** 微信那边出错（不是用户的原因）时把次数还回去 */
    @Update("UPDATE gb_notify_sub SET quota = quota + 1, update_time = NOW() WHERE member_id = #{m} AND template_id = #{t}")
    int returnQuota(@Param("m") String memberId, @Param("t") String templateId);

    @Select("SELECT COALESCE(SUM(quota), 0) FROM gb_notify_sub WHERE member_id = #{m} AND template_id = #{t}")
    int quota(@Param("m") String memberId, @Param("t") String templateId);
}
