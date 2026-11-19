package org.jeecg.modules.groupbuy.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.jeecg.modules.groupbuy.entity.*;
import org.jeecg.modules.groupbuy.mapper.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 把实体拼成前端要的样子（两端共用），只读 */
@Service
@RequiredArgsConstructor
public class GbViewService {
    private final GbGoodsMapper goodsMapper;
    private final GbPeriodMapper periodMapper;
    private final GbPointMapper pointMapper;
    private final GbOrderItemMapper itemMapper;
    private final GbAftersaleMapper asMapper;
    private final GbDeliveryMapper deliveryMapper;

    public static final Map<String, String> ORDER_STATUS = Map.of("unpaid", "待付款", "paid", "待截单", "shipping", "配送中", "arrived", "待提货", "done", "已提货", "closed", "已关闭");
    public static final Map<String, String> PERIOD_STATUS = Map.of("draft", "草稿", "plan", "待开团", "live", "进行中", "cutoff", "分拣配送中", "pickup", "自提中", "done", "已结束");

    public static String mask(String phone) {
        if (phone == null || phone.length() < 7) return phone;
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    public static String tail(String phone) {
        return phone == null || phone.length() < 4 ? "" : phone.substring(phone.length() - 4);
    }

    public Map<String, GbPoint> pointMap(Collection<String> ids) {
        if (ids.isEmpty()) return Map.of();
        return pointMapper.selectBatchIds(new HashSet<>(ids)).stream().collect(Collectors.toMap(GbPoint::getId, Function.identity()));
    }

    public Map<String, GbPeriod> periodMap(Collection<String> ids) {
        if (ids.isEmpty()) return Map.of();
        return periodMapper.selectBatchIds(new HashSet<>(ids)).stream().collect(Collectors.toMap(GbPeriod::getId, Function.identity()));
    }

    public Map<String, GbGoods> goodsMap(Collection<String> ids) {
        if (ids.isEmpty()) return Map.of();
        return goodsMapper.selectBatchIds(new HashSet<>(ids)).stream().collect(Collectors.toMap(GbGoods::getId, Function.identity()));
    }

    public Map<String, List<GbOrderItem>> itemsOf(Collection<String> orderIds) {
        if (orderIds.isEmpty()) return Map.of();
        return itemMapper.selectList(new LambdaQueryWrapper<GbOrderItem>().in(GbOrderItem::getOrderId, orderIds))
                .stream().collect(Collectors.groupingBy(GbOrderItem::getOrderId, LinkedHashMap::new, Collectors.toList()));
    }

    /** 订单列表 / 详情共用。full=true 时带配送进度与售后 */
    public List<Map<String, Object>> orders(List<GbOrder> list, boolean maskPhone, boolean showCode) {
        Map<String, GbPoint> pts = pointMap(list.stream().map(GbOrder::getPointId).toList());
        Map<String, GbPeriod> pds = periodMap(list.stream().map(GbOrder::getPeriodId).toList());
        Map<String, List<GbOrderItem>> its = itemsOf(list.stream().map(GbOrder::getId).toList());
        List<Map<String, Object>> out = new ArrayList<>();
        for (GbOrder o : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            GbPoint pt = pts.get(o.getPointId());
            GbPeriod pd = pds.get(o.getPeriodId());
            m.put("id", o.getId());
            m.put("orderNo", o.getOrderNo());
            m.put("status", o.getStatus());
            m.put("statusText", ORDER_STATUS.get(o.getStatus()));
            m.put("afterStatus", o.getAfterStatus());
            m.put("buyerName", o.getBuyerName());
            m.put("buyerPhone", maskPhone ? mask(o.getBuyerPhone()) : o.getBuyerPhone());
            m.put("phoneTail", tail(o.getBuyerPhone()));
            m.put("goodsAmount", o.getGoodsAmount());
            m.put("discountAmount", o.getDiscountAmount());
            m.put("payAmount", o.getPayAmount());
            m.put("refundAmount", o.getRefundAmount());
            m.put("couponName", o.getCouponName());
            m.put("remark", o.getRemark());
            m.put("closeReason", o.getCloseReason());
            m.put("pickupCode", showCode ? o.getPickupCode() : (o.getPickupCode() == null ? null : o.getPickupCode().substring(0, 2) + "••••"));
            m.put("createTime", o.getCreateTime());
            m.put("payTime", o.getPayTime());
            m.put("payExpireTime", o.getPayExpireTime());
            m.put("pickupAt", o.getPickupAt());
            m.put("remindCount", o.getRemindCount());
            if (pt != null) {
                m.put("pointId", pt.getId());
                m.put("pointName", pt.getName());
                m.put("pointAddress", pt.getAddress());
                m.put("leaderName", pt.getLeaderName());
                m.put("leaderPhone", maskPhone ? mask(pt.getLeaderPhone()) : pt.getLeaderPhone());
                m.put("openHours", pt.getOpenHours());
            }
            if (pd != null) {
                m.put("periodId", pd.getId());
                m.put("periodName", pd.getName());
                m.put("cutoffTime", pd.getCutoffTime());
                m.put("pickupTime", pd.getPickupTime());
                m.put("pickupEndTime", pd.getPickupEndTime());
            }
            List<GbOrderItem> items = its.getOrDefault(o.getId(), List.of());
            m.put("items", items);
            m.put("qty", items.stream().mapToInt(GbOrderItem::getQty).sum());
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> orderDetail(GbOrder o, boolean maskPhone, boolean showCode) {
        Map<String, Object> m = orders(List.of(o), maskPhone, showCode).get(0);
        GbDelivery d = deliveryMapper.selectOne(new LambdaQueryWrapper<GbDelivery>().eq(GbDelivery::getPeriodId, o.getPeriodId()).eq(GbDelivery::getPointId, o.getPointId()));
        m.put("delivery", d);
        m.put("aftersales", asMapper.selectList(new LambdaQueryWrapper<GbAftersale>().eq(GbAftersale::getOrderId, o.getId()).orderByDesc(GbAftersale::getCreateTime)));
        return m;
    }

    /** 团品行：商品信息 + 本期价格库存 */
    public List<Map<String, Object>> periodGoods(List<GbPeriodGoods> list) {
        Map<String, GbGoods> gm = goodsMap(list.stream().map(GbPeriodGoods::getGoodsId).toList());
        List<Map<String, Object>> out = new ArrayList<>();
        for (GbPeriodGoods pg : list) {
            GbGoods g = gm.get(pg.getGoodsId());
            if (g == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", pg.getId());
            m.put("periodId", pg.getPeriodId());
            m.put("goodsId", g.getId());
            m.put("name", g.getName());
            m.put("spec", g.getSpec());
            m.put("category", g.getCategory());
            m.put("storage", g.getStorage());
            m.put("image", GbCoreService.firstImage(g.getImages()));
            m.put("images", g.getImages());
            m.put("detail", g.getDetail());
            m.put("supplier", g.getSupplier());
            m.put("origin", g.getOrigin());
            m.put("costPrice", g.getCostPrice());
            m.put("origPrice", g.getOrigPrice());
            m.put("price", pg.getPrice());
            m.put("stock", pg.getStock());
            m.put("sold", pg.getSold());
            m.put("left", Math.max(0, pg.getStock() - pg.getSold()));
            m.put("limitQty", pg.getLimitQty());
            m.put("tag", pg.getTag());
            m.put("status", pg.getStatus());
            m.put("goodsStatus", g.getStatus());
            m.put("margin", pg.getPrice().signum() == 0 ? BigDecimal.ZERO : pg.getPrice().subtract(g.getCostPrice()).divide(pg.getPrice(), 4, java.math.RoundingMode.HALF_UP));
            out.add(m);
        }
        return out;
    }
}
