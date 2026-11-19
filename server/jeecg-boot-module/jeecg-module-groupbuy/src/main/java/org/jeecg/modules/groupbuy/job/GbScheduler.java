package org.jeecg.modules.groupbuy.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.groupbuy.entity.GbAftersale;
import org.jeecg.modules.groupbuy.entity.GbOrder;
import org.jeecg.modules.groupbuy.entity.GbPeriod;
import org.jeecg.modules.groupbuy.mapper.GbAftersaleMapper;
import org.jeecg.modules.groupbuy.mapper.GbOrderMapper;
import org.jeecg.modules.groupbuy.mapper.GbPeriodMapper;
import org.jeecg.modules.groupbuy.service.GbCommissionService;
import org.jeecg.modules.groupbuy.service.GbCoreService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 每 30 秒推进一次：开团、截单、开始自提、结束；关闭超时未付款；售后超时自动同意；团长佣金到期入账。
 * 单机部署直接用；多实例部署时需要加分布式锁（或改用 JeecgBoot 的 Quartz 集群任务）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GbScheduler {
    private final GbPeriodMapper periodMapper;
    private final GbOrderMapper orderMapper;
    private final GbAftersaleMapper asMapper;
    private final GbCoreService core;
    private final GbCommissionService commission;

    @Scheduled(initialDelay = 15_000, fixedDelay = 30_000)
    public void tick() {
        try {
            Date now = new Date();
            // 待开团 → 进行中
            periodMapper.update(null, new LambdaUpdateWrapper<GbPeriod>().eq(GbPeriod::getStatus, "plan").le(GbPeriod::getOpenTime, now).set(GbPeriod::getStatus, "live"));
            // 进行中 → 截单
            for (GbPeriod p : periodMapper.selectList(new LambdaQueryWrapper<GbPeriod>().eq(GbPeriod::getStatus, "live").le(GbPeriod::getCutoffTime, now))) core.cutoff(p);
            // 截单 → 自提中 → 结束
            periodMapper.update(null, new LambdaUpdateWrapper<GbPeriod>().eq(GbPeriod::getStatus, "cutoff").le(GbPeriod::getPickupTime, now).set(GbPeriod::getStatus, "pickup"));
            periodMapper.update(null, new LambdaUpdateWrapper<GbPeriod>().eq(GbPeriod::getStatus, "pickup").le(GbPeriod::getPickupEndTime, now).set(GbPeriod::getStatus, "done"));
            // 超时未付款
            for (GbOrder o : orderMapper.selectList(new LambdaQueryWrapper<GbOrder>().eq(GbOrder::getStatus, GbCoreService.S_UNPAID).lt(GbOrder::getPayExpireTime, now))) core.closeOrder(o, "超时未付款");
            // 售后超时自动同意
            for (GbAftersale a : asMapper.selectList(new LambdaQueryWrapper<GbAftersale>().eq(GbAftersale::getStatus, "wait").lt(GbAftersale::getDeadline, now)))
                core.approveAftersale(a.getId(), null, "平台", "超过处理时限，系统已自动同意退款。", "system");
        } catch (Exception e) {
            log.error("[团购] 定时任务出错", e);
        }
        try {
            // 提货满售后期的订单，团长佣金入账
            commission.settleDue();
        } catch (Exception e) {
            log.error("[团购] 佣金结算出错", e);
        }
    }
}
