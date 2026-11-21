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
import org.jeecg.modules.groupbuy.service.GbNotifyService;
import org.jeecg.modules.groupbuy.service.GbPayService;
import org.jeecg.modules.groupbuy.service.GbTransferService;
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
    private final GbPayService pay;
    private final GbNotifyService notify;
    private final GbTransferService transfer;

    @Scheduled(initialDelay = 15_000, fixedDelay = 30_000)
    public void tick() {
        try {
            Date now = new Date();
            // 待开团 → 进行中
            periodMapper.update(null, new LambdaUpdateWrapper<GbPeriod>().eq(GbPeriod::getStatus, "plan").le(GbPeriod::getOpenTime, now).set(GbPeriod::getStatus, "live"));
            // 进行中 → 截单。截单会关掉所有待付款订单，先把向微信下过单的问一遍：付了的算进这一期
            for (GbPeriod p : periodMapper.selectList(new LambdaQueryWrapper<GbPeriod>().eq(GbPeriod::getStatus, "live").le(GbPeriod::getCutoffTime, now))) {
                for (GbOrder o : orderMapper.selectList(new LambdaQueryWrapper<GbOrder>().eq(GbOrder::getPeriodId, p.getId()).eq(GbOrder::getStatus, GbCoreService.S_UNPAID).isNotNull(GbOrder::getPrepayTime)))
                    pay.sync(o);
                core.cutoff(p);
            }
            // 截单 → 自提中 → 结束
            periodMapper.update(null, new LambdaUpdateWrapper<GbPeriod>().eq(GbPeriod::getStatus, "cutoff").le(GbPeriod::getPickupTime, now).set(GbPeriod::getStatus, "pickup"));
            periodMapper.update(null, new LambdaUpdateWrapper<GbPeriod>().eq(GbPeriod::getStatus, "pickup").le(GbPeriod::getPickupEndTime, now).set(GbPeriod::getStatus, "done"));
        } catch (Exception e) {
            log.error("[团购] 定时任务出错", e);
        }
        // 超时未付款：向微信下过单的先问一下，正在付的等一会（最多等 10 分钟）
        for (GbOrder o : orderMapper.selectList(new LambdaQueryWrapper<GbOrder>().eq(GbOrder::getStatus, GbCoreService.S_UNPAID).lt(GbOrder::getPayExpireTime, new Date()))) {
            try {
                String st = pay.sync(o);
                if (GbPayService.PAID.equals(st)) continue;
                if (GbPayService.PENDING.equals(st) && System.currentTimeMillis() - o.getPayExpireTime().getTime() < 10 * 60_000L) continue;
                core.closeOrder(o, "超时未付款");
            } catch (Exception e) {
                log.error("[团购] 关闭超时订单 {} 出错", o.getOrderNo(), e);
            }
        }
        // 售后超时自动同意
        for (GbAftersale a : asMapper.selectList(new LambdaQueryWrapper<GbAftersale>().eq(GbAftersale::getStatus, "wait").lt(GbAftersale::getDeadline, new Date()))) {
            try {
                core.approveAftersale(a.getId(), null, "平台", "超过处理时限，系统已自动同意退款。", "system");
            } catch (Exception e) {
                log.error("[团购] 售后 {} 自动同意出错", a.getAsNo(), e);
            }
        }
        try {
            // 没提交成功的退款重试、退款中太久的去查
            pay.retryRefunds();
        } catch (Exception e) {
            log.error("[团购] 退款重试出错", e);
        }
        try {
            // 提现自动打款：打款中的去微信查结果
            transfer.syncPaying();
        } catch (Exception e) {
            log.error("[团购] 同步提现转账出错", e);
        }
        try {
            // 订阅消息临时失败的，到时间重发
            notify.retryDue();
        } catch (Exception e) {
            log.error("[团购] 订阅消息重发出错", e);
        }
        try {
            // 提货满售后期的订单，团长佣金入账
            commission.settleDue();
        } catch (Exception e) {
            log.error("[团购] 佣金结算出错", e);
        }
    }
}
