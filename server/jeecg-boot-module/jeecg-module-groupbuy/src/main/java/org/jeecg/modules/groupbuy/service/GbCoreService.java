package org.jeecg.modules.groupbuy.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.exception.JeecgBootException;
import org.jeecg.modules.groupbuy.config.GbProperties;
import org.jeecg.modules.groupbuy.entity.*;
import org.jeecg.modules.groupbuy.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 团购核心业务：下单 / 支付 / 取消 / 截单 / 配送签收 / 核销 / 售后。
 * 所有改库存、改券、改订单状态的操作都在这里，保证规则只有一份。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GbCoreService {
    public static final String S_UNPAID = "unpaid", S_PAID = "paid", S_SHIPPING = "shipping", S_ARRIVED = "arrived", S_DONE = "done", S_CLOSED = "closed";

    private final GbProperties props;
    private final GbGoodsMapper goodsMapper;
    private final GbPeriodMapper periodMapper;
    private final GbPeriodGoodsMapper pgMapper;
    private final GbPointMapper pointMapper;
    private final GbMemberMapper memberMapper;
    private final GbOrderMapper orderMapper;
    private final GbOrderItemMapper itemMapper;
    private final GbCouponMapper couponMapper;
    private final GbUserCouponMapper ucMapper;
    private final GbAftersaleMapper asMapper;
    private final GbDeliveryMapper deliveryMapper;

    // ======================================================== 工具

    public static BigDecimal money(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    public static JeecgBootException fail(String msg) {
        return new JeecgBootException(msg);
    }

    /** 当前可下单的团期（进行中、未截单） */
    public GbPeriod livePeriod() {
        Date now = new Date();
        return periodMapper.selectOne(new LambdaQueryWrapper<GbPeriod>().eq(GbPeriod::getStatus, "live")
                .le(GbPeriod::getOpenTime, now).gt(GbPeriod::getCutoffTime, now).orderByDesc(GbPeriod::getOpenTime).last("limit 1"));
    }

    public boolean pointInScope(GbPeriod p, String pointId) {
        if (p.getPointScope() == null || p.getPointScope().isBlank()) return true;
        return Arrays.asList(p.getPointScope().split(",")).contains(pointId);
    }

    /** 某人在某团品上已买（未关闭订单）的数量，用于限购 */
    public int boughtQty(String memberId, String periodGoodsId) {
        List<GbOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<GbOrder>().eq(GbOrder::getMemberId, memberId).ne(GbOrder::getStatus, S_CLOSED).select(GbOrder::getId));
        if (orders.isEmpty()) return 0;
        return itemMapper.selectList(new LambdaQueryWrapper<GbOrderItem>().in(GbOrderItem::getOrderId, orders.stream().map(GbOrder::getId).toList())
                .eq(GbOrderItem::getPeriodGoodsId, periodGoodsId)).stream().mapToInt(GbOrderItem::getQty).sum();
    }

    // ======================================================== 结算计算（预览和下单共用）

    /** 一行购物车 */
    public record Line(GbPeriodGoods pg, GbGoods goods, int qty) {
        BigDecimal amount() { return money(pg.getPrice().multiply(BigDecimal.valueOf(qty))); }
    }

    /** 结算结果 */
    public record Calc(List<Line> lines, BigDecimal goodsAmount, BigDecimal discount, GbUserCoupon coupon, GbCoupon couponDef,
                       List<Map<String, Object>> couponOptions, String newbieLineId) {
        public BigDecimal pay() { return money(goodsAmount.subtract(discount)); }
    }

    /** 一张券对这一单能减多少；不能用返回 null */
    public BigDecimal discountOf(GbCoupon c, List<Line> lines, GbMember m) {
        switch (c.getType()) {
            case "full": {
                BigDecimal sum = scopeSum(c, lines);
                return sum.compareTo(c.getThreshold()) >= 0 ? money(c.getAmount()) : null;
            }
            case "discount": {
                BigDecimal sum = scopeSum(c, lines);
                if (sum.signum() == 0 || sum.compareTo(c.getThreshold()) < 0) return null;
                BigDecimal d = money(sum.multiply(BigDecimal.ONE.subtract(c.getRate())));
                return c.getMaxDiscount() != null && d.compareTo(c.getMaxDiscount()) > 0 ? money(c.getMaxDiscount()) : d;
            }
            case "newbie": {
                if (m.getFirstOrderDone() != null && m.getFirstOrderDone() == 1) return null;
                return lines.stream().filter(l -> l.goods().getId().equals(c.getNewbieGoodsId())).findFirst()
                        .map(l -> money(l.pg().getPrice().subtract(BigDecimal.ONE).max(BigDecimal.ZERO))).orElse(null);
            }
            default:
                return null;
        }
    }

    private BigDecimal scopeSum(GbCoupon c, List<Line> lines) {
        return lines.stream().filter(l -> c.getScopeCategory() == null || c.getScopeCategory().isBlank() || c.getScopeCategory().equals(l.goods().getCategory()))
                .map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 计算一单。userCouponId：null = 自动选最划算；"none" = 不用券；其他 = 指定。
     */
    public Calc calc(GbMember m, GbPeriod period, List<Map<String, Object>> items, String userCouponId) {
        if (items == null || items.isEmpty()) throw fail("购物车是空的");
        List<Line> lines = new ArrayList<>();
        for (Map<String, Object> it : items) {
            String pgId = String.valueOf(it.get("periodGoodsId"));
            int qty = Integer.parseInt(String.valueOf(it.get("qty")));
            if (qty <= 0) continue;
            GbPeriodGoods pg = pgMapper.selectById(pgId);
            if (pg == null || !pg.getPeriodId().equals(period.getId())) throw fail("有商品不在本期团里，请刷新购物车");
            GbGoods g = goodsMapper.selectById(pg.getGoodsId());
            lines.add(new Line(pg, g, qty));
        }
        if (lines.isEmpty()) throw fail("购物车是空的");
        BigDecimal goodsAmount = lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

        // 可用券
        Date now = new Date();
        List<GbUserCoupon> mine = ucMapper.selectList(new LambdaQueryWrapper<GbUserCoupon>().eq(GbUserCoupon::getMemberId, m.getId())
                .eq(GbUserCoupon::getStatus, "unused").gt(GbUserCoupon::getExpireTime, now));
        Map<String, GbCoupon> defs = mine.isEmpty() ? Map.of() : couponMapper.selectBatchIds(mine.stream().map(GbUserCoupon::getCouponId).distinct().toList())
                .stream().collect(Collectors.toMap(GbCoupon::getId, c -> c));
        List<Map<String, Object>> options = new ArrayList<>();
        GbUserCoupon best = null;
        BigDecimal bestD = BigDecimal.ZERO;
        for (GbUserCoupon uc : mine) {
            GbCoupon c = defs.get(uc.getCouponId());
            if (c == null) continue;
            BigDecimal d = discountOf(c, lines, m);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("userCouponId", uc.getId());
            o.put("name", c.getName());
            o.put("discount", d);
            o.put("usable", d != null);
            o.put("expireTime", uc.getExpireTime());
            if (d == null && "full".equals(c.getType())) o.put("lack", money(c.getThreshold().subtract(scopeSum(c, lines))));
            options.add(o);
            if (d != null && d.compareTo(bestD) > 0) { bestD = d; best = uc; }
        }
        GbUserCoupon chosen;
        if ("none".equals(userCouponId)) chosen = null;
        else if (userCouponId == null || userCouponId.isBlank()) chosen = best;
        else chosen = mine.stream().filter(u -> u.getId().equals(userCouponId)).findFirst().orElseThrow(() -> fail("这张券不能用"));
        BigDecimal discount = BigDecimal.ZERO;
        GbCoupon chosenDef = null;
        String newbieLine = null;
        if (chosen != null) {
            chosenDef = defs.get(chosen.getCouponId());
            BigDecimal d = discountOf(chosenDef, lines, m);
            if (d == null) throw fail("这张券还没达到使用条件");
            discount = d;
            if ("newbie".equals(chosenDef.getType())) {
                GbCoupon nd = chosenDef;
                newbieLine = lines.stream().filter(l -> l.goods().getId().equals(nd.getNewbieGoodsId())).findFirst().map(l -> l.pg().getId()).orElse(null);
            }
        }
        options.sort(Comparator.comparing((Map<String, Object> o) -> !(Boolean) o.get("usable")));
        return new Calc(lines, money(goodsAmount), discount, chosen, chosenDef, options, newbieLine);
    }

    // ======================================================== 下单 / 支付 / 取消

    @Transactional(rollbackFor = Exception.class)
    public GbOrder createOrder(GbMember m, String pointId, List<Map<String, Object>> items, String userCouponId, String remark, String buyerName, String buyerPhone) {
        GbPeriod period = livePeriod();
        if (period == null) throw fail("本期团已截单，明天再来");
        GbPoint point = pointMapper.selectById(pointId);
        if (point == null || !"ok".equals(point.getStatus())) throw fail("这个提货点暂停营业了，请换一个");
        if (!pointInScope(period, pointId)) throw fail("这个提货点本期没有开团");
        Calc calc = calc(m, period, items, userCouponId);

        for (Line l : calc.lines()) {
            if (!"normal".equals(l.goods().getStorage()) && (point.getHasFridge() == null || point.getHasFridge() == 0))
                throw fail("「" + l.goods().getName() + "」需要冷藏，这个提货点没有冰柜");
            int limit = l.pg().getLimitQty() == null ? 0 : l.pg().getLimitQty();
            if (limit > 0 && boughtQty(m.getId(), l.pg().getId()) + l.qty() > limit)
                throw fail("「" + l.goods().getName() + "」每人限购 " + limit + " 份");
            if (pgMapper.occupy(l.pg().getId(), l.qty()) == 0)
                throw fail("「" + l.goods().getName() + "」库存不够了");
        }

        GbOrder o = new GbOrder();
        o.setOrderNo(newOrderNo());
        o.setPeriodId(period.getId());
        o.setPointId(pointId);
        o.setMemberId(m.getId());
        o.setBuyerName(buyerName != null && !buyerName.isBlank() ? buyerName : m.getNickname());
        o.setBuyerPhone(buyerPhone != null && !buyerPhone.isBlank() ? buyerPhone : m.getPhone());
        o.setGoodsAmount(calc.goodsAmount());
        o.setDiscountAmount(calc.discount());
        o.setPayAmount(calc.pay());
        o.setRefundAmount(BigDecimal.ZERO);
        o.setStatus(S_UNPAID);
        o.setAfterStatus("none");
        o.setRemark(remark);
        o.setRemindCount(0);
        o.setPayExpireTime(new Date(System.currentTimeMillis() + props.getPayTimeoutMinutes() * 60_000L));
        if (calc.coupon() != null) {
            o.setUserCouponId(calc.coupon().getId());
            o.setCouponName(calc.couponDef().getName());
        }
        orderMapper.insert(o);
        for (Line l : calc.lines()) {
            GbOrderItem it = new GbOrderItem();
            it.setOrderId(o.getId());
            it.setPeriodGoodsId(l.pg().getId());
            it.setGoodsId(l.goods().getId());
            it.setGoodsName(l.goods().getName());
            it.setSpec(l.goods().getSpec());
            it.setImage(firstImage(l.goods().getImages()));
            it.setPrice(l.pg().getPrice());
            it.setQty(l.qty());
            it.setAmount(l.amount());
            it.setNewbie(l.pg().getId().equals(calc.newbieLineId()) ? 1 : 0);
            itemMapper.insert(it);
        }
        if (calc.coupon() != null) {
            int n = ucMapper.update(null, new LambdaUpdateWrapper<GbUserCoupon>().eq(GbUserCoupon::getId, calc.coupon().getId()).eq(GbUserCoupon::getStatus, "unused")
                    .set(GbUserCoupon::getStatus, "locked").set(GbUserCoupon::getOrderId, o.getId()));
            if (n == 0) throw fail("优惠券已被使用，请重新下单");
        }
        if (o.getPayAmount().signum() <= 0) throw fail("实付金额必须大于 0");
        return o;
    }

    @Transactional(rollbackFor = Exception.class)
    public GbOrder pay(String memberId, String orderId) {
        GbOrder o = mustOwn(memberId, orderId);
        if (!S_UNPAID.equals(o.getStatus())) throw fail("订单已经付过款或已关闭");
        if (o.getPayExpireTime().before(new Date())) { closeOrder(o, "超时未付款"); throw fail("订单已超时关闭，请重新下单"); }
        GbPeriod p = periodMapper.selectById(o.getPeriodId());
        if (!"live".equals(p.getStatus()) || !p.getCutoffTime().after(new Date())) throw fail("本期团已截单");
        if (!props.isPayMock()) throw fail("微信支付还没接入（需要商户号），请在配置里打开 groupbuy.pay-mock");
        markPaid(o);
        return orderMapper.selectById(orderId);
    }

    /** 支付成功：微信支付回调接入后也调这里 */
    public void markPaid(GbOrder o) {
        o.setStatus(S_PAID);
        o.setPayTime(new Date());
        o.setPickupCode(newPickupCode(o.getPeriodId()));
        orderMapper.updateById(o);
        if (o.getUserCouponId() != null) {
            ucMapper.update(null, new LambdaUpdateWrapper<GbUserCoupon>().eq(GbUserCoupon::getId, o.getUserCouponId()).set(GbUserCoupon::getStatus, "used").set(GbUserCoupon::getUseTime, new Date()));
            GbUserCoupon uc = ucMapper.selectById(o.getUserCouponId());
            if (uc != null) couponMapper.addUsed(uc.getCouponId(), 1);
        }
        memberMapper.update(null, new LambdaUpdateWrapper<GbMember>().eq(GbMember::getId, o.getMemberId()).set(GbMember::getFirstOrderDone, 1));
    }

    /** 居民自己取消：截单前都可以 */
    @Transactional(rollbackFor = Exception.class)
    public void cancelByMember(String memberId, String orderId) {
        GbOrder o = mustOwn(memberId, orderId);
        if (!S_UNPAID.equals(o.getStatus()) && !S_PAID.equals(o.getStatus())) throw fail("已截单，不能取消了；有问题请申请售后");
        closeOrder(o, S_PAID.equals(o.getStatus()) ? "买家取消（已退款）" : "买家取消");
    }

    /** 后台取消并退款 */
    @Transactional(rollbackFor = Exception.class)
    public void cancelByAdmin(String orderId) {
        GbOrder o = orderMapper.selectById(orderId);
        if (o == null) throw fail("订单不存在");
        if (S_DONE.equals(o.getStatus()) || S_CLOSED.equals(o.getStatus())) throw fail("已提货或已关闭的订单不能取消，请走售后");
        closeOrder(o, "平台取消（已退款）");
    }

    /** 关闭订单：放回库存、退回券、已付款的原路退款（模拟） */
    public void closeOrder(GbOrder o, String reason) {
        boolean paid = o.getPayTime() != null;
        int n = orderMapper.update(null, new LambdaUpdateWrapper<GbOrder>().eq(GbOrder::getId, o.getId()).ne(GbOrder::getStatus, S_CLOSED)
                .set(GbOrder::getStatus, S_CLOSED).set(GbOrder::getCloseReason, reason)
                .set(paid, GbOrder::getRefundAmount, o.getPayAmount()));
        if (n == 0) return;
        for (GbOrderItem it : items(o.getId())) pgMapper.release(it.getPeriodGoodsId(), it.getQty());
        if (o.getUserCouponId() != null) {
            GbUserCoupon uc = ucMapper.selectById(o.getUserCouponId());
            if (uc != null) {
                if ("used".equals(uc.getStatus())) couponMapper.addUsed(uc.getCouponId(), -1);
                ucMapper.update(null, new LambdaUpdateWrapper<GbUserCoupon>().eq(GbUserCoupon::getId, uc.getId())
                        .set(GbUserCoupon::getStatus, "unused").set(GbUserCoupon::getOrderId, null).set(GbUserCoupon::getUseTime, null));
            }
        }
        if (paid) log.info("[团购] 订单 {} 关闭并退款 {}（模拟退款，接入微信支付后在这里调退款接口）", o.getOrderNo(), o.getPayAmount());
    }

    public List<GbOrderItem> items(String orderId) {
        return itemMapper.selectList(new LambdaQueryWrapper<GbOrderItem>().eq(GbOrderItem::getOrderId, orderId));
    }

    private GbOrder mustOwn(String memberId, String orderId) {
        GbOrder o = orderMapper.selectById(orderId);
        if (o == null || !o.getMemberId().equals(memberId)) throw fail("订单不存在");
        return o;
    }

    public String newOrderNo() {
        String day = new SimpleDateFormat("yyMMdd").format(new Date());
        for (int i = 0; i < 20; i++) {
            String no = day + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
            if (orderMapper.selectCount(new LambdaQueryWrapper<GbOrder>().eq(GbOrder::getOrderNo, no)) == 0) return no;
        }
        throw fail("系统繁忙，请重试");
    }

    /** 6 位提货码，同一团期内未完成的订单之间不重复 */
    public String newPickupCode(String periodId) {
        for (int i = 0; i < 50; i++) {
            String c = String.format("%06d", ThreadLocalRandom.current().nextInt(100000, 1_000_000));
            if (orderMapper.selectCount(new LambdaQueryWrapper<GbOrder>().eq(GbOrder::getPeriodId, periodId).eq(GbOrder::getPickupCode, c)) == 0) return c;
        }
        throw fail("系统繁忙，请重试");
    }

    public static String firstImage(String images) {
        if (images == null || images.isBlank()) return null;
        return images.split(",")[0];
    }

    // ======================================================== 团期流转（定时任务调用）

    /** 截单：关闭待付款，已付款转配送中，按提货点生成配送单 */
    @Transactional(rollbackFor = Exception.class)
    public void cutoff(GbPeriod p) {
        int n = periodMapper.update(null, new LambdaUpdateWrapper<GbPeriod>().eq(GbPeriod::getId, p.getId()).eq(GbPeriod::getStatus, "live").set(GbPeriod::getStatus, "cutoff"));
        if (n == 0) return;
        for (GbOrder o : orderMapper.selectList(new LambdaQueryWrapper<GbOrder>().eq(GbOrder::getPeriodId, p.getId()).eq(GbOrder::getStatus, S_UNPAID)))
            closeOrder(o, "截单时未付款");
        List<GbOrder> paid = orderMapper.selectList(new LambdaQueryWrapper<GbOrder>().eq(GbOrder::getPeriodId, p.getId()).eq(GbOrder::getStatus, S_PAID));
        Map<String, List<GbOrder>> byPoint = paid.stream().collect(Collectors.groupingBy(GbOrder::getPointId));
        for (var e : byPoint.entrySet()) {
            int itemCount = e.getValue().stream().mapToInt(o -> items(o.getId()).stream().mapToInt(GbOrderItem::getQty).sum()).sum();
            GbDelivery d = new GbDelivery();
            d.setPeriodId(p.getId());
            d.setPointId(e.getKey());
            d.setOrderCount(e.getValue().size());
            d.setItemCount(itemCount);
            d.setStatus("sorting");
            deliveryMapper.insert(d);
        }
        orderMapper.update(null, new LambdaUpdateWrapper<GbOrder>().eq(GbOrder::getPeriodId, p.getId()).eq(GbOrder::getStatus, S_PAID).set(GbOrder::getStatus, S_SHIPPING));
        log.info("[团购] {} 截单：{} 单，{} 个提货点", p.getName(), paid.size(), byPoint.size());
    }

    // ======================================================== 配送

    @Transactional(rollbackFor = Exception.class)
    public void ship(String deliveryId, Integer boxes, String driver) {
        GbDelivery d = deliveryMapper.selectById(deliveryId);
        if (d == null) throw fail("配送单不存在");
        if (!"sorting".equals(d.getStatus())) throw fail("已经发车了");
        d.setStatus("shipping");
        d.setBoxes(boxes);
        d.setDriver(driver);
        if (d.getSortTime() == null) d.setSortTime(new Date());
        d.setShipTime(new Date());
        deliveryMapper.updateById(d);
    }

    /** 团长签收：这个提货点这期的订单全部变成待提货 */
    @Transactional(rollbackFor = Exception.class)
    public void sign(String deliveryId, String shortNote) {
        GbDelivery d = deliveryMapper.selectById(deliveryId);
        if (d == null) throw fail("配送单不存在");
        if ("signed".equals(d.getStatus())) throw fail("已经签收过了");
        if ("sorting".equals(d.getStatus())) d.setShipTime(new Date());
        d.setStatus("signed");
        d.setSignTime(new Date());
        d.setShortNote(shortNote == null || shortNote.isBlank() ? null : shortNote.trim());
        deliveryMapper.updateById(d);
        orderMapper.update(null, new LambdaUpdateWrapper<GbOrder>().eq(GbOrder::getPeriodId, d.getPeriodId()).eq(GbOrder::getPointId, d.getPointId())
                .eq(GbOrder::getStatus, S_SHIPPING).set(GbOrder::getStatus, S_ARRIVED));
        log.info("[团购] 配送单 {} 签收，发送到货通知（订阅消息接入后在这里发）", deliveryId);
    }

    // ======================================================== 核销

    /** 团长核销：只能核销自己提货点、已到货的订单 */
    @Transactional(rollbackFor = Exception.class)
    public void verify(String pointId, String orderId) {
        GbOrder o = orderMapper.selectById(orderId);
        if (o == null || (pointId != null && !o.getPointId().equals(pointId))) throw fail("不是本提货点的订单");
        if (S_DONE.equals(o.getStatus())) throw fail("这单已经提过货了");
        if (!S_ARRIVED.equals(o.getStatus())) throw fail("货还没到，不能核销");
        if ("applying".equals(o.getAfterStatus())) throw fail("这单正在售后，先别交货");
        orderMapper.update(null, new LambdaUpdateWrapper<GbOrder>().eq(GbOrder::getId, orderId).eq(GbOrder::getStatus, S_ARRIVED)
                .set(GbOrder::getStatus, S_DONE).set(GbOrder::getPickupAt, new Date()));
    }

    // ======================================================== 售后

    @Transactional(rollbackFor = Exception.class)
    public GbAftersale applyAftersale(String memberId, String orderId, List<String> itemIds, String type, String reason, String images) {
        GbOrder o = mustOwn(memberId, orderId);
        if (!S_ARRIVED.equals(o.getStatus()) && !S_DONE.equals(o.getStatus())) throw fail("货到提货点之后才能申请售后");
        if (S_DONE.equals(o.getStatus()) && o.getPickupAt() != null && o.getPickupAt().getTime() + props.getAftersaleWindowHours() * 3600_000L < System.currentTimeMillis())
            throw fail("提货超过 " + props.getAftersaleWindowHours() + " 小时，不能再申请售后了，请联系客服");
        if ("applying".equals(o.getAfterStatus())) throw fail("这单已经在售后中了");
        if ("refunded".equals(o.getAfterStatus())) throw fail("这单已经退过款了");
        if (itemIds == null || itemIds.isEmpty()) throw fail("先选有问题的商品");
        if (type == null || type.isBlank()) throw fail("选一下遇到了什么问题");
        List<GbOrderItem> all = items(orderId);
        List<GbOrderItem> sel = all.stream().filter(i -> itemIds.contains(i.getId())).toList();
        if (sel.isEmpty()) throw fail("商品不在这个订单里");
        GbAftersale a = new GbAftersale();
        a.setAsNo("SH" + newOrderNo().substring(2));
        a.setOrderId(orderId);
        a.setMemberId(memberId);
        a.setPointId(o.getPointId());
        a.setItemIds(String.join(",", sel.stream().map(GbOrderItem::getId).toList()));
        a.setType(type);
        a.setReason(reason);
        a.setImages(images);
        a.setApplyAmount(refundCap(o, sel));
        a.setStatus("wait");
        a.setDeadline(new Date(System.currentTimeMillis() + props.getAftersaleAutoHours() * 3600_000L));
        asMapper.insert(a);
        orderMapper.update(null, new LambdaUpdateWrapper<GbOrder>().eq(GbOrder::getId, orderId).set(GbOrder::getAfterStatus, "applying"));
        return a;
    }

    /** 可退金额：所选商品金额按实付比例折算（用了券的少退一点），不超过剩余可退 */
    public BigDecimal refundCap(GbOrder o, List<GbOrderItem> sel) {
        BigDecimal selAmt = sel.stream().map(GbOrderItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cap = o.getGoodsAmount().signum() == 0 ? BigDecimal.ZERO
                : selAmt.multiply(o.getPayAmount()).divide(o.getGoodsAmount(), 2, RoundingMode.DOWN);
        BigDecimal left = o.getPayAmount().subtract(o.getRefundAmount() == null ? BigDecimal.ZERO : o.getRefundAmount());
        return money(cap.min(left).max(BigDecimal.ZERO));
    }

    @Transactional(rollbackFor = Exception.class)
    public void approveAftersale(String id, BigDecimal amount, String duty, String reply, String by) {
        GbAftersale a = asMapper.selectById(id);
        if (a == null) throw fail("售后单不存在");
        if (!"wait".equals(a.getStatus())) throw fail("已经处理过了");
        GbOrder o = orderMapper.selectById(a.getOrderId());
        BigDecimal cap = a.getApplyAmount();
        BigDecimal amt = amount == null ? cap : money(amount);
        if (amt.signum() <= 0 || amt.compareTo(cap) > 0) throw fail("退款金额要在 0.01 到 " + cap + " 元之间");
        a.setRefundAmount(amt);
        a.setDuty(duty);
        a.setReply(reply);
        a.setStatus("done");
        a.setHandleTime(new Date());
        a.setHandleBy(by);
        asMapper.updateById(a);
        orderMapper.update(null, new LambdaUpdateWrapper<GbOrder>().eq(GbOrder::getId, o.getId())
                .set(GbOrder::getAfterStatus, "refunded").set(GbOrder::getRefundAmount, money(o.getRefundAmount().add(amt))));
        log.info("[团购] 售后 {} 退款 {}（模拟退款）", a.getAsNo(), amt);
    }

    @Transactional(rollbackFor = Exception.class)
    public void rejectAftersale(String id, String reply, String by) {
        if (reply == null || reply.isBlank()) throw fail("驳回要写清理由，买家能看到");
        GbAftersale a = asMapper.selectById(id);
        if (a == null) throw fail("售后单不存在");
        if (!"wait".equals(a.getStatus())) throw fail("已经处理过了");
        a.setStatus("rej");
        a.setReply(reply);
        a.setHandleTime(new Date());
        a.setHandleBy(by);
        asMapper.updateById(a);
        orderMapper.update(null, new LambdaUpdateWrapper<GbOrder>().eq(GbOrder::getId, a.getOrderId()).set(GbOrder::getAfterStatus, "rejected"));
    }

    // ======================================================== 券

    @Transactional(rollbackFor = Exception.class)
    public GbUserCoupon receiveCoupon(String memberId, String couponId) {
        GbCoupon c = couponMapper.selectById(couponId);
        if (c == null || !"on".equals(c.getStatus()) || !"home".equals(c.getChannel())) throw fail("这张券领不了了");
        Date now = new Date();
        if (c.getValidEnd() != null && c.getValidEnd().before(now)) throw fail("活动已结束");
        if (c.getValidStart() != null && c.getValidStart().after(now)) throw fail("活动还没开始");
        if ("newbie".equals(c.getType())) {
            GbMember m = memberMapper.selectById(memberId);
            if (m.getFirstOrderDone() != null && m.getFirstOrderDone() == 1) throw fail("新人专享，只有第一单可以用");
        }
        long have = ucMapper.selectCount(new LambdaQueryWrapper<GbUserCoupon>().eq(GbUserCoupon::getMemberId, memberId).eq(GbUserCoupon::getCouponId, couponId));
        if (have >= Math.max(1, c.getPerLimit())) throw fail("已经领过了");
        if (couponMapper.incReceived(couponId) == 0) throw fail("券已经被领完了");
        GbUserCoupon uc = new GbUserCoupon();
        uc.setCouponId(couponId);
        uc.setMemberId(memberId);
        uc.setStatus("unused");
        uc.setExpireTime(c.getValidDays() != null ? new Date(now.getTime() + c.getValidDays() * 86400_000L) : c.getValidEnd());
        ucMapper.insert(uc);
        return uc;
    }
}
