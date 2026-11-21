package org.jeecg.modules.groupbuy.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.groupbuy.config.GbProperties;
import org.jeecg.modules.groupbuy.entity.*;
import org.jeecg.modules.groupbuy.mapper.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.jeecg.modules.groupbuy.service.GbCoreService.fail;
import static org.jeecg.modules.groupbuy.service.GbCoreService.money;

/**
 * 团长佣金结算和提现。
 * <ul>
 *   <li>佣金 = 计佣金额 × 比例；计佣金额 = 商品金额 × (实付 − 已退) ÷ 实付。优惠券由平台出，不扣团长的；退过款按比例扣。</li>
 *   <li>比例在付款时记到订单上（gb_order.commission_rate），老订单没有记的按提货点当前比例。</li>
 *   <li>顾客提货满「售后期」小时、且没有进行中的售后，定时任务自动入账到可提现余额。没提货的订单没有佣金。</li>
 *   <li>提现：最少 withdraw-min 元，同时只能一笔在审核；审核通过 = 运营线下打完款后登记流水号。</li>
 *   <li>售后扣款：售后责任方是团长时，退款的全部或一部分从可提现扣；不够扣记成负数，之后的佣金先抵上。扣错了可以撤销。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GbCommissionService {
    private final GbProperties props;
    private final GbCommissionMapper commMapper;
    private final GbLeaderAccountMapper accMapper;
    private final GbWithdrawMapper wdMapper;
    private final GbLeaderDeductMapper dedMapper;
    private final GbPointMapper pointMapper;
    private final TransactionTemplate tx;
    private final GbNotifyService notify;

    public static final BigDecimal MAX_RATE = new BigDecimal("0.30");

    // ======================================================== 计算

    /** 订单适用的比例 */
    public BigDecimal rateOf(GbOrder o, Map<String, GbPoint> points) {
        if (o.getCommissionRate() != null) return o.getCommissionRate();
        GbPoint p = points.get(o.getPointId());
        return p == null || p.getCommissionRate() == null ? BigDecimal.ZERO : p.getCommissionRate();
    }

    /** 计佣金额：商品金额按实际没退的比例折算 */
    public static BigDecimal baseOf(GbOrder o) {
        BigDecimal pay = o.getPayAmount() == null ? BigDecimal.ZERO : o.getPayAmount();
        BigDecimal refund = o.getRefundAmount() == null ? BigDecimal.ZERO : o.getRefundAmount();
        if (pay.signum() <= 0 || refund.compareTo(pay) >= 0) return BigDecimal.ZERO.setScale(2);
        if (refund.signum() == 0) return money(o.getGoodsAmount());
        return o.getGoodsAmount().multiply(pay.subtract(refund)).divide(pay, 2, RoundingMode.DOWN);
    }

    public static BigDecimal commissionOf(BigDecimal base, BigDecimal rate) {
        return base.multiply(rate).setScale(2, RoundingMode.DOWN);
    }

    private Map<String, GbPoint> pointMap(Collection<String> ids) {
        if (ids.isEmpty()) return Map.of();
        return pointMapper.selectBatchIds(ids).stream().collect(Collectors.toMap(GbPoint::getId, Function.identity()));
    }

    /** 待结算（预估）：已付款还没入账的订单，按当前没退的部分估算；pointId 为空 = 全部，返回 pointId → 金额 */
    public Map<String, BigDecimal> estimate(String pointId) {
        List<GbOrder> os = commMapper.unsettled(pointId);
        Map<String, GbPoint> pts = pointMap(os.stream().map(GbOrder::getPointId).collect(Collectors.toSet()));
        Map<String, BigDecimal> out = new HashMap<>();
        for (GbOrder o : os) out.merge(o.getPointId(), commissionOf(baseOf(o), rateOf(o, pts)), BigDecimal::add);
        return out;
    }

    // ======================================================== 结算（定时任务调用）

    /** 把到期的订单入账，返回入账笔数。每单一个事务；同一单重复入账会被唯一索引挡住 */
    public int settleDue() {
        long windowMs = props.getAftersaleWindowHours() * 3600_000L;
        Date now = new Date();
        int n = 0;
        for (int round = 0; round < 20; round++) {
            List<GbOrder> due = commMapper.settleable(new Date(now.getTime() - windowMs), 200);
            if (due.isEmpty()) break;
            Map<String, GbPoint> pts = pointMap(due.stream().map(GbOrder::getPointId).collect(Collectors.toSet()));
            for (GbOrder o : due) {
                try {
                    Boolean ok = tx.execute(s -> settleOne(o, rateOf(o, pts), new Date(Math.min(now.getTime(), o.getPickupAt().getTime() + windowMs))));
                    if (Boolean.TRUE.equals(ok)) n++;
                } catch (DuplicateKeyException e) {
                    // 别的线程已经结算了
                }
            }
            if (due.size() < 200) break;
        }
        if (n > 0) log.info("[团购] 团长佣金入账 {} 笔", n);
        return n;
    }

    private boolean settleOne(GbOrder o, BigDecimal rate, Date settleTime) {
        BigDecimal base = baseOf(o);
        GbCommission c = new GbCommission();
        c.setOrderId(o.getId());
        c.setOrderNo(o.getOrderNo());
        c.setPeriodId(o.getPeriodId());
        c.setPointId(o.getPointId());
        c.setBuyerName(o.getBuyerName());
        c.setGoodsAmount(money(o.getGoodsAmount()));
        c.setRefundAmount(money(o.getRefundAmount()));
        c.setBaseAmount(base);
        c.setRate(rate);
        c.setAmount(commissionOf(base, rate));
        c.setPickupAt(o.getPickupAt());
        c.setSettleTime(settleTime);
        c.setCreateTime(new Date());
        commMapper.insert(c);
        if (c.getAmount().signum() > 0) {
            accMapper.ensure(o.getPointId());
            accMapper.income(o.getPointId(), c.getAmount());
        }
        return true;
    }

    // ======================================================== 账户

    public GbLeaderAccount account(String pointId) {
        accMapper.ensure(pointId);
        return accMapper.selectById(pointId);
    }

    public GbWithdraw pendingWithdraw(String pointId) {
        return wdMapper.selectOne(new LambdaQueryWrapper<GbWithdraw>().eq(GbWithdraw::getPointId, pointId).in(GbWithdraw::getStatus, "wait", "paying").last("limit 1"));
    }

    // ======================================================== 售后扣款

    /** 团长责任售后扣款。在售后审核的事务里调用；同一个售后只能扣一次（唯一索引） */
    @Transactional(rollbackFor = Exception.class)
    public GbLeaderDeduct deduct(GbAftersale a, GbOrder o, BigDecimal refund, BigDecimal amount, String reason, String by) {
        amount = money(amount);
        if (amount.signum() <= 0) return null;
        if (amount.compareTo(refund) > 0) throw fail("团长承担的钱不能超过退款金额 " + refund + " 元");
        GbLeaderDeduct d = new GbLeaderDeduct();
        d.setPointId(o.getPointId());
        d.setAftersaleId(a.getId());
        d.setAsNo(a.getAsNo());
        d.setOrderId(o.getId());
        d.setOrderNo(o.getOrderNo());
        d.setBuyerName(o.getBuyerName());
        d.setAsType(a.getType());
        d.setRefundAmount(refund);
        d.setAmount(amount);
        d.setReason(reason == null || reason.isBlank() ? a.getType() + "，责任在提货点" : reason.trim());
        d.setStatus("done");
        d.setCreateBy(by);
        d.setCreateTime(new Date());
        try {
            dedMapper.insert(d);
        } catch (DuplicateKeyException e) {
            throw fail("这个售后已经扣过团长的钱了");
        }
        accMapper.ensure(o.getPointId());
        accMapper.deduct(o.getPointId(), amount);
        log.info("[团购] 售后 {} 从提货点 {} 佣金扣 {}", a.getAsNo(), o.getPointId(), amount);
        return d;
    }

    /** 撤销扣款，钱退回团长可提现 */
    @Transactional(rollbackFor = Exception.class)
    public void revokeDeduct(String id, String reason, String by) {
        if (reason == null || reason.isBlank()) throw fail("写一下撤销原因，团长能看到");
        GbLeaderDeduct d = dedMapper.selectById(id);
        if (d == null) throw fail("扣款记录不存在");
        int n = dedMapper.update(null, new LambdaUpdateWrapper<GbLeaderDeduct>().eq(GbLeaderDeduct::getId, id).eq(GbLeaderDeduct::getStatus, "done")
                .set(GbLeaderDeduct::getStatus, "revoked").set(GbLeaderDeduct::getRevokeReason, reason.trim())
                .set(GbLeaderDeduct::getRevokeBy, by).set(GbLeaderDeduct::getRevokeTime, new Date()));
        if (n == 0) throw fail("已经撤销过了");
        if (accMapper.undeduct(d.getPointId(), d.getAmount()) == 0) throw fail("账户金额对不上，请联系技术处理");
    }

    /** 售后 id → 扣款记录（含已撤销） */
    public Map<String, GbLeaderDeduct> deductsOf(Collection<String> aftersaleIds) {
        if (aftersaleIds.isEmpty()) return Map.of();
        return dedMapper.selectList(new LambdaQueryWrapper<GbLeaderDeduct>().in(GbLeaderDeduct::getAftersaleId, aftersaleIds))
                .stream().collect(Collectors.toMap(GbLeaderDeduct::getAftersaleId, Function.identity()));
    }

    // ======================================================== 提现

    @Transactional(rollbackFor = Exception.class)
    public GbWithdraw apply(GbPoint pt, String memberId, BigDecimal amount, String method, String accountName, String accountNo, String bankName) {
        if (amount == null) throw fail("填一下提现金额");
        amount = money(amount);
        if (amount.compareTo(BigDecimal.valueOf(props.getWithdrawMin())) < 0) throw fail("单笔最少提现 " + props.getWithdrawMin() + " 元");
        if (!List.of("wechat", "bank").contains(method)) throw fail("选一下提到哪里");
        if (accountName == null || accountName.isBlank() || accountName.trim().length() > 16) throw fail("填一下收款人真实姓名");
        if ("bank".equals(method)) {
            String no = accountNo == null ? "" : accountNo.replaceAll("\\s", "");
            if (!no.matches("\\d{12,19}")) throw fail("银行卡号是 12 到 19 位数字");
            if (bankName == null || bankName.isBlank()) throw fail("填一下开户银行");
            accountNo = no;
        } else {
            accountNo = null;
            bankName = null;
        }
        accMapper.ensure(pt.getId());
        // 锁住账户行，防止同时提交两笔
        accMapper.selectOne(new LambdaQueryWrapper<GbLeaderAccount>().eq(GbLeaderAccount::getPointId, pt.getId()).last("FOR UPDATE"));
        if (pendingWithdraw(pt.getId()) != null) throw fail("还有一笔提现在处理，到账后才能再提");
        if (accMapper.freeze(pt.getId(), amount) == 0) throw fail("可提现余额不够");
        GbWithdraw w = new GbWithdraw();
        w.setWdNo(newNo());
        w.setPointId(pt.getId());
        w.setMemberId(memberId);
        w.setAmount(amount);
        w.setMethod(method);
        w.setAccountName(accountName.trim());
        w.setAccountNo(accountNo);
        w.setBankName(bankName == null ? null : bankName.trim());
        w.setStatus("wait");
        wdMapper.insert(w);
        log.info("[团购] {} 申请提现 {}", pt.getName(), amount);
        return w;
    }

    /** 运营线下打完款后登记。接入微信「商家转账到零钱」后改成在这里调转账接口 */
    @Transactional(rollbackFor = Exception.class)
    public void approve(String id, String voucher, String by) {
        if (voucher == null || voucher.isBlank()) throw fail("填一下打款流水号，方便对账");
        GbWithdraw w = wdMapper.selectById(id);
        if (w == null) throw fail("提现单不存在");
        int n = wdMapper.update(null, new LambdaUpdateWrapper<GbWithdraw>().eq(GbWithdraw::getId, id).eq(GbWithdraw::getStatus, "wait")
                .set(GbWithdraw::getStatus, "paid").set(GbWithdraw::getVoucher, voucher.trim()).set(GbWithdraw::getHandleTime, new Date()).set(GbWithdraw::getHandleBy, by));
        if (n == 0) throw fail("已经处理过了");
        if (accMapper.paidOut(w.getPointId(), w.getAmount()) == 0) throw fail("账户金额对不上，请联系技术处理");
        notify.withdraw(wdMapper.selectById(id), true);
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(String id, String reply, String by) {
        if (reply == null || reply.isBlank()) throw fail("驳回要写原因，团长能看到");
        GbWithdraw w = wdMapper.selectById(id);
        if (w == null) throw fail("提现单不存在");
        int n = wdMapper.update(null, new LambdaUpdateWrapper<GbWithdraw>().eq(GbWithdraw::getId, id).eq(GbWithdraw::getStatus, "wait")
                .set(GbWithdraw::getStatus, "rej").set(GbWithdraw::getReply, reply.trim()).set(GbWithdraw::getHandleTime, new Date()).set(GbWithdraw::getHandleBy, by));
        if (n == 0) throw fail("已经处理过了");
        if (accMapper.unfreeze(w.getPointId(), w.getAmount()) == 0) throw fail("账户金额对不上，请联系技术处理");
        notify.withdraw(wdMapper.selectById(id), false);
    }

    private String newNo() {
        String day = new SimpleDateFormat("yyMMdd").format(new Date());
        for (int i = 0; i < 20; i++) {
            String no = "TX" + day + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
            if (wdMapper.selectCount(new LambdaQueryWrapper<GbWithdraw>().eq(GbWithdraw::getWdNo, no)) == 0) return no;
        }
        throw fail("系统繁忙，请重试");
    }

    /** 卡号只露后 4 位 */
    public static String maskCard(String no) {
        if (no == null || no.length() < 8) return no;
        return "•••• " + no.substring(no.length() - 4);
    }
}
