package org.jeecg.modules.groupbuy.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.apache.shiro.SecurityUtils;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.modules.groupbuy.entity.*;
import org.jeecg.modules.groupbuy.mapper.*;
import org.jeecg.modules.groupbuy.service.GbCommissionService;
import org.jeecg.modules.groupbuy.service.GbCoreService;
import org.jeecg.modules.groupbuy.service.GbNotifyService;
import org.jeecg.modules.groupbuy.service.GbViewService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

import static org.jeecg.modules.groupbuy.service.GbCoreService.*;

/** 运营后台接口（走 JeecgBoot 后台登录） */
@Tag(name = "团购-运营后台")
@RestController
@RequestMapping("/gb/admin")
@RequiredArgsConstructor
public class GbAdminController {
    private final GbGoodsMapper goodsMapper;
    private final GbPeriodMapper periodMapper;
    private final GbPeriodGoodsMapper pgMapper;
    private final GbPointMapper pointMapper;
    private final GbOrderMapper orderMapper;
    private final GbCouponMapper couponMapper;
    private final GbAftersaleMapper asMapper;
    private final GbDeliveryMapper deliveryMapper;
    private final GbCoreService core;
    private final GbCommissionService commission;
    private final GbCommissionMapper commMapper;
    private final GbLeaderAccountMapper accMapper;
    private final GbWithdrawMapper wdMapper;
    private final GbViewService view;
    private final GbNotifyService notify;
    private final GbNotifyLogMapper notifyMapper;
    private final GbMemberMapper memberMapper;

    public static final List<String> CATEGORIES = List.of("水果", "蔬菜", "肉禽蛋", "水产", "乳品烘焙", "粮油", "日用");

    private static String me() {
        try {
            return ((LoginUser) SecurityUtils.getSubject().getPrincipal()).getUsername();
        } catch (Exception e) {
            return "admin";
        }
    }

    private static Map<String, Object> page(Page<?> p, List<?> records) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("records", records);
        m.put("total", p.getTotal());
        m.put("current", p.getCurrent());
        m.put("size", p.getSize());
        return m;
    }

    private static List<String> toList(Object o) {
        if (o == null) return List.of();
        if (o instanceof Collection<?> c) return c.stream().map(String::valueOf).toList();
        return Arrays.stream(String.valueOf(o).split(",")).filter(s -> !s.isBlank()).toList();
    }

    // ================================================================ 公共

    /** 下拉选项 + 侧栏徽标数字 */
    @GetMapping("/meta")
    public Result<?> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("categories", CATEGORIES);
        m.put("points", pointMapper.selectList(new LambdaQueryWrapper<GbPoint>().orderByAsc(GbPoint::getCreateTime)));
        m.put("periods", periodMapper.selectList(new LambdaQueryWrapper<GbPeriod>().ne(GbPeriod::getStatus, "draft").orderByDesc(GbPeriod::getOpenTime).last("limit 30")));
        GbPeriod live = core.livePeriod();
        m.put("live", live);
        m.put("badges", Map.of(
                "aftersale", asMapper.selectCount(new LambdaQueryWrapper<GbAftersale>().eq(GbAftersale::getStatus, "wait")),
                "points", pointMapper.selectCount(new LambdaQueryWrapper<GbPoint>().eq(GbPoint::getStatus, "pending")),
                "withdraw", wdMapper.selectCount(new LambdaQueryWrapper<GbWithdraw>().eq(GbWithdraw::getStatus, "wait")),
                "sorting", deliveryMapper.selectCount(new LambdaQueryWrapper<GbDelivery>().ne(GbDelivery::getStatus, "signed"))));
        m.put("now", new Date());
        return Result.OK(m);
    }

    // ================================================================ 工作台

    @GetMapping("/dashboard")
    public Result<?> dashboard() {
        Map<String, Object> out = new LinkedHashMap<>();
        GbPeriod live = core.livePeriod();
        out.put("live", live);
        List<GbPeriod> recent = periodMapper.selectList(new LambdaQueryWrapper<GbPeriod>().in(GbPeriod::getStatus, "live", "cutoff", "pickup", "done").orderByDesc(GbPeriod::getOpenTime).last("limit 7"));
        Collections.reverse(recent);
        List<Map<String, Object>> series = new ArrayList<>();
        for (GbPeriod p : recent) {
            List<GbOrder> os = validOrders(p.getId(), null);
            series.add(Map.of("name", p.getName(), "live", "live".equals(p.getStatus()), "gmv", sum(os)));
        }
        out.put("series", series);
        if (live != null) {
            List<GbOrder> os = validOrders(live.getId(), null);
            BigDecimal gmv = sum(os);
            long buyers = os.stream().map(GbOrder::getMemberId).distinct().count();
            out.put("gmv", gmv);
            out.put("orders", os.size());
            out.put("buyers", buyers);
            out.put("avg", os.isEmpty() ? BigDecimal.ZERO : gmv.divide(BigDecimal.valueOf(os.size()), 2, RoundingMode.HALF_UP));
            // 和上一期「同一进度」比：同样开团后过了多久时的销售额
            GbPeriod prev = periodMapper.selectOne(new LambdaQueryWrapper<GbPeriod>().lt(GbPeriod::getOpenTime, live.getOpenTime()).ne(GbPeriod::getStatus, "draft").orderByDesc(GbPeriod::getOpenTime).last("limit 1"));
            if (prev != null) {
                Date sameTime = new Date(prev.getOpenTime().getTime() + (System.currentTimeMillis() - live.getOpenTime().getTime()));
                List<GbOrder> po = validOrders(prev.getId(), sameTime);
                BigDecimal pg = sum(po);
                out.put("prevGmv", pg);
                out.put("prevAvg", po.isEmpty() ? null : pg.divide(BigDecimal.valueOf(po.size()), 2, RoundingMode.HALF_UP));
            }
            List<Map<String, Object>> goods = view.periodGoods(pgMapper.selectList(new LambdaQueryWrapper<GbPeriodGoods>().eq(GbPeriodGoods::getPeriodId, live.getId()).eq(GbPeriodGoods::getStatus, "on")));
            goods.sort(Comparator.comparing((Map<String, Object> g) -> ((BigDecimal) g.get("price")).multiply(BigDecimal.valueOf((Integer) g.get("sold")))).reversed());
            out.put("topGoods", goods.stream().limit(6).toList());
            out.put("goodsCount", goods.size());
            Map<String, List<GbOrder>> byPoint = os.stream().collect(Collectors.groupingBy(GbOrder::getPointId));
            Map<String, GbPoint> pts = view.pointMap(byPoint.keySet());
            out.put("points", byPoint.entrySet().stream().map(e -> {
                GbPoint p = pts.get(e.getKey());
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("name", p.getName()); r.put("leaderName", p.getLeaderName()); r.put("households", p.getHouseholds());
                r.put("orders", e.getValue().size()); r.put("gmv", sum(e.getValue()));
                return r;
            }).sorted(Comparator.comparing((Map<String, Object> r) -> (BigDecimal) r.get("gmv")).reversed()).toList());
            // 待处理：快卖完
            out.put("hotGoods", goods.stream().filter(g -> (Integer) g.get("stock") > 0 && (Integer) g.get("left") * 10 <= (Integer) g.get("stock")).toList());
        }
        out.put("pointTotal", pointMapper.selectCount(null));
        out.put("pointOk", pointMapper.selectCount(new LambdaQueryWrapper<GbPoint>().eq(GbPoint::getStatus, "ok")));
        out.put("pointPending", pointMapper.selectList(new LambdaQueryWrapper<GbPoint>().eq(GbPoint::getStatus, "pending")));
        out.put("pointPaused", pointMapper.selectCount(new LambdaQueryWrapper<GbPoint>().eq(GbPoint::getStatus, "paused")));
        List<GbAftersale> waits = asMapper.selectList(new LambdaQueryWrapper<GbAftersale>().eq(GbAftersale::getStatus, "wait").orderByAsc(GbAftersale::getDeadline));
        out.put("aftersaleWait", waits);
        List<GbDelivery> undelivered = deliveryMapper.selectList(new LambdaQueryWrapper<GbDelivery>().ne(GbDelivery::getStatus, "signed"));
        Map<String, GbPoint> dp = view.pointMap(undelivered.stream().map(GbDelivery::getPointId).toList());
        Map<String, GbPeriod> dpd = view.periodMap(undelivered.stream().map(GbDelivery::getPeriodId).toList());
        out.put("undelivered", undelivered.stream().map(d -> Map.of("pointName", dp.get(d.getPointId()).getName(), "status", d.getStatus(), "periodName", dpd.get(d.getPeriodId()).getName(), "pickupTime", dpd.get(d.getPeriodId()).getPickupTime())).toList());
        out.put("offGoods", goodsMapper.selectList(new LambdaQueryWrapper<GbGoods>().eq(GbGoods::getStatus, "off")));
        return Result.OK(out);
    }

    /** 有效订单 = 已付款且未关闭（截至某时刻） */
    private List<GbOrder> validOrders(String periodId, Date before) {
        return orderMapper.selectList(new LambdaQueryWrapper<GbOrder>().eq(GbOrder::getPeriodId, periodId).notIn(GbOrder::getStatus, S_UNPAID, S_CLOSED)
                .le(before != null, GbOrder::getPayTime, before));
    }

    private static BigDecimal sum(List<GbOrder> os) {
        return money(os.stream().map(GbOrder::getGoodsAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    // ================================================================ 商品库

    @GetMapping("/goods/list")
    public Result<?> goodsList(@RequestParam(required = false) String keyword, @RequestParam(required = false) String category, @RequestParam(required = false) String status,
                               @RequestParam(defaultValue = "1") int pageNo, @RequestParam(defaultValue = "20") int pageSize) {
        LambdaQueryWrapper<GbGoods> q = new LambdaQueryWrapper<GbGoods>()
                .and(keyword != null && !keyword.isBlank(), w -> w.like(GbGoods::getName, keyword).or().like(GbGoods::getSupplier, keyword))
                .eq(category != null && !category.isBlank(), GbGoods::getCategory, category)
                .eq(status != null && !status.isBlank(), GbGoods::getStatus, status)
                .orderByAsc(GbGoods::getStatus).orderByAsc(GbGoods::getCreateTime);
        Page<GbGoods> p = goodsMapper.selectPage(new Page<>(pageNo, pageSize), q);
        // 近 7 期销量
        List<GbPeriod> ps = periodMapper.selectList(new LambdaQueryWrapper<GbPeriod>().in(GbPeriod::getStatus, "live", "cutoff", "pickup", "done").orderByDesc(GbPeriod::getOpenTime).last("limit 7"));
        Collections.reverse(ps);
        Map<String, Map<String, Integer>> sold = new HashMap<>();
        if (!ps.isEmpty() && !p.getRecords().isEmpty())
            for (GbPeriodGoods pg : pgMapper.selectList(new LambdaQueryWrapper<GbPeriodGoods>().in(GbPeriodGoods::getPeriodId, ps.stream().map(GbPeriod::getId).toList())
                    .in(GbPeriodGoods::getGoodsId, p.getRecords().stream().map(GbGoods::getId).toList())))
                sold.computeIfAbsent(pg.getGoodsId(), k -> new HashMap<>()).put(pg.getPeriodId(), pg.getSold());
        List<Map<String, Object>> rows = p.getRecords().stream().map(g -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("goods", g);
            Map<String, Integer> s = sold.getOrDefault(g.getId(), Map.of());
            m.put("trend", ps.stream().map(x -> s.getOrDefault(x.getId(), 0)).toList());
            m.put("sold7", s.values().stream().mapToInt(Integer::intValue).sum());
            return m;
        }).toList();
        Map<String, Object> out = page(p, rows);
        out.put("counts", Map.of("all", goodsMapper.selectCount(null), "on", goodsMapper.selectCount(new LambdaQueryWrapper<GbGoods>().eq(GbGoods::getStatus, "on")),
                "off", goodsMapper.selectCount(new LambdaQueryWrapper<GbGoods>().eq(GbGoods::getStatus, "off"))));
        return Result.OK(out);
    }

    @PostMapping("/goods/save")
    public Result<?> goodsSave(@RequestBody GbGoods g) {
        if (g.getName() == null || g.getName().isBlank()) return Result.error("商品名称必填");
        if (g.getSpec() == null || g.getSpec().isBlank()) return Result.error("规格必填");
        if (g.getCategory() == null || !CATEGORIES.contains(g.getCategory())) return Result.error("类目不对");
        if (g.getCostPrice() == null || g.getSuggestPrice() == null) return Result.error("采购价和建议团购价必填");
        if (g.getSuggestPrice().compareTo(g.getCostPrice()) < 0) return Result.error("建议团购价低于采购价，会亏本");
        if (g.getStorage() == null) g.setStorage("normal");
        if (g.getId() == null) { g.setStatus("on"); goodsMapper.insert(g); } else goodsMapper.updateById(g);
        return Result.OK("已保存", g);
    }

    @PostMapping("/goods/status")
    public Result<?> goodsStatus(@RequestBody Map<String, Object> body) {
        List<String> ids = toList(body.get("ids"));
        String status = String.valueOf(body.get("status"));
        if (!List.of("on", "off").contains(status) || ids.isEmpty()) return Result.error("参数不对");
        goodsMapper.update(null, new LambdaUpdateWrapper<GbGoods>().in(GbGoods::getId, ids).set(GbGoods::getStatus, status)
                .set(GbGoods::getOffReason, "off".equals(status) ? (String) body.get("reason") : null));
        return Result.OK(("off".equals(status) ? "已下架 " : "已上架 ") + ids.size() + " 个商品");
    }

    // ================================================================ 团期

    @GetMapping("/period/list")
    public Result<?> periodList() {
        List<GbPeriod> ps = periodMapper.selectList(new LambdaQueryWrapper<GbPeriod>().orderByDesc(GbPeriod::getOpenTime).last("limit 30"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (GbPeriod p : ps) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("period", p);
            m.put("statusText", GbViewService.PERIOD_STATUS.get(p.getStatus()));
            m.put("goodsCount", pgMapper.selectCount(new LambdaQueryWrapper<GbPeriodGoods>().eq(GbPeriodGoods::getPeriodId, p.getId()).eq(GbPeriodGoods::getStatus, "on")));
            List<GbOrder> os = validOrders(p.getId(), null);
            m.put("orders", os.size());
            m.put("gmv", sum(os));
            if (List.of("pickup", "done").contains(p.getStatus())) {
                long need = os.stream().filter(o -> List.of(S_ARRIVED, S_DONE).contains(o.getStatus())).count();
                long done = os.stream().filter(o -> S_DONE.equals(o.getStatus())).count();
                m.put("pickupRate", need == 0 ? null : BigDecimal.valueOf(done).divide(BigDecimal.valueOf(need), 4, RoundingMode.HALF_UP));
                m.put("notPicked", need - done);
            }
            rows.add(m);
        }
        return Result.OK(rows);
    }

    @GetMapping("/period/detail")
    public Result<?> periodDetail(@RequestParam(required = false) String id) {
        GbPeriod p = id == null || id.isBlank() ? core.livePeriod() : periodMapper.selectById(id);
        if (p == null) return Result.OK(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("period", p);
        m.put("statusText", GbViewService.PERIOD_STATUS.get(p.getStatus()));
        List<Map<String, Object>> goods = view.periodGoods(pgMapper.selectList(new LambdaQueryWrapper<GbPeriodGoods>().eq(GbPeriodGoods::getPeriodId, p.getId()).orderByAsc(GbPeriodGoods::getSortNo)));
        // 上期已售，方便定库存
        GbPeriod prev = periodMapper.selectOne(new LambdaQueryWrapper<GbPeriod>().lt(GbPeriod::getOpenTime, p.getOpenTime()).ne(GbPeriod::getStatus, "draft").orderByDesc(GbPeriod::getOpenTime).last("limit 1"));
        if (prev != null) {
            Map<String, GbPeriodGoods> last = pgMapper.selectList(new LambdaQueryWrapper<GbPeriodGoods>().eq(GbPeriodGoods::getPeriodId, prev.getId()))
                    .stream().collect(Collectors.toMap(GbPeriodGoods::getGoodsId, x -> x, (a, b) -> a));
            for (Map<String, Object> g : goods) {
                GbPeriodGoods l = last.get(g.get("goodsId"));
                if (l != null) { g.put("lastSold", l.getSold()); g.put("lastStock", l.getStock()); }
            }
        }
        m.put("goods", goods);
        List<GbOrder> os = validOrders(p.getId(), null);
        m.put("orders", os.size());
        m.put("gmv", sum(os));
        m.put("soldQty", goods.stream().mapToInt(g -> (Integer) g.get("sold")).sum());
        return Result.OK(m);
    }

    /** 下一期的建议时间：接在最后一期截单之后 */
    @GetMapping("/period/next")
    public Result<?> periodNext() {
        GbPeriod last = periodMapper.selectOne(new LambdaQueryWrapper<GbPeriod>().orderByDesc(GbPeriod::getCutoffTime).last("limit 1"));
        LocalDate day = last == null ? LocalDate.now() : last.getCutoffTime().toInstant().atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", day.getMonthValue() + "月" + day.getDayOfMonth() + "日团");
        m.put("openTime", Date.from(day.minusDays(1).atTime(23, 0).atZone(ZoneId.systemDefault()).toInstant()));
        m.put("cutoffTime", Date.from(day.atTime(23, 0).atZone(ZoneId.systemDefault()).toInstant()));
        m.put("pickupTime", Date.from(day.plusDays(1).atTime(16, 0).atZone(ZoneId.systemDefault()).toInstant()));
        m.put("pickupEndTime", Date.from(day.plusDays(2).atTime(21, 0).atZone(ZoneId.systemDefault()).toInstant()));
        m.put("lastId", last == null ? null : last.getId());
        return Result.OK(m);
    }

    /**
     * 新建 / 编辑团期。body: {period:{...}, goods:[{goodsId, price, stock, limitQty, tag}], publish:true|false}
     * 进行中的团期只能改团品库存和上下架（用 /period/goods/update），不能整体覆盖。
     */
    @PostMapping("/period/save")
    @Transactional(rollbackFor = Exception.class)
    public Result<?> periodSave(@RequestBody Map<String, Object> body) {
        Map<String, Object> pm = (Map<String, Object>) body.get("period");
        List<Map<String, Object>> goods = (List<Map<String, Object>>) body.getOrDefault("goods", List.of());
        boolean publish = Boolean.TRUE.equals(body.get("publish"));
        GbPeriod p = pm.get("id") == null ? new GbPeriod() : periodMapper.selectById(String.valueOf(pm.get("id")));
        if (p == null) return Result.error("团期不存在");
        if (p.getStatus() != null && !List.of("draft", "plan").contains(p.getStatus())) return Result.error("已开团的团期不能整体修改，请在团期页改库存或下架");
        p.setName(String.valueOf(pm.get("name")));
        p.setOpenTime(parse(pm.get("openTime")));
        p.setCutoffTime(parse(pm.get("cutoffTime")));
        p.setPickupTime(parse(pm.get("pickupTime")));
        p.setPickupEndTime(parse(pm.get("pickupEndTime")));
        p.setPointScope(pm.get("pointScope") == null ? null : String.join(",", toList(pm.get("pointScope"))));
        if (!p.getOpenTime().before(p.getCutoffTime()) || !p.getCutoffTime().before(p.getPickupTime()) || !p.getPickupTime().before(p.getPickupEndTime()))
            return Result.error("时间顺序要是：开团 < 截单 < 开始提货 < 提货截止");
        if (publish && goods.isEmpty()) return Result.error("至少要有一个团品才能发布");
        // 不能和别的团期的下单时间重叠
        long overlap = periodMapper.selectCount(new LambdaQueryWrapper<GbPeriod>().ne(p.getId() != null, GbPeriod::getId, p.getId()).ne(GbPeriod::getStatus, "draft")
                .lt(GbPeriod::getOpenTime, p.getCutoffTime()).gt(GbPeriod::getCutoffTime, p.getOpenTime()));
        if (publish && overlap > 0) return Result.error("下单时间和别的团期重叠了，请调整开团 / 截单时间");
        p.setStatus(publish ? (p.getOpenTime().after(new Date()) ? "plan" : "live") : "draft");
        if (p.getId() == null) periodMapper.insert(p); else periodMapper.updateById(p);
        pgMapper.delete(new LambdaQueryWrapper<GbPeriodGoods>().eq(GbPeriodGoods::getPeriodId, p.getId()));
        int i = 0;
        for (Map<String, Object> g : goods) {
            GbGoods gd = goodsMapper.selectById(String.valueOf(g.get("goodsId")));
            if (gd == null) continue;
            if ("off".equals(gd.getStatus())) return Result.error("「" + gd.getName() + "」已下架，不能上团");
            GbPeriodGoods pg = new GbPeriodGoods();
            pg.setPeriodId(p.getId());
            pg.setGoodsId(gd.getId());
            pg.setPrice(money(new BigDecimal(String.valueOf(g.get("price")))));
            pg.setStock(Integer.parseInt(String.valueOf(g.getOrDefault("stock", 0))));
            Object lim = g.get("limitQty");
            pg.setLimitQty(lim == null || String.valueOf(lim).isBlank() ? 0 : Integer.parseInt(String.valueOf(lim)));
            pg.setTag(g.get("tag") == null ? null : String.valueOf(g.get("tag")));
            if (pg.getPrice().signum() <= 0 || pg.getStock() < 0) return Result.error("「" + gd.getName() + "」价格或库存不对");
            pg.setSold(0);
            pg.setStatus("on");
            pg.setSortNo(i++);
            pgMapper.insert(pg);
        }
        return Result.OK(publish ? "已发布，到开团时间自动上架" : "草稿已保存", p);
    }

    private static Date parse(Object v) {
        if (v == null) throw fail("时间必填");
        if (v instanceof Number n) return new Date(n.longValue());
        try {
            return new java.text.SimpleDateFormat(String.valueOf(v).length() > 16 ? "yyyy-MM-dd HH:mm:ss" : "yyyy-MM-dd HH:mm").parse(String.valueOf(v));
        } catch (Exception e) {
            throw fail("时间格式不对：" + v);
        }
    }

    /** 进行中的团期改库存 / 上下架。body: {id, stock?, status?} */
    @PostMapping("/period/goods/update")
    public Result<?> periodGoodsUpdate(@RequestBody Map<String, Object> body) {
        GbPeriodGoods pg = pgMapper.selectById(String.valueOf(body.get("id")));
        if (pg == null) return Result.error("团品不存在");
        if (body.get("stock") != null) {
            int stock = Integer.parseInt(String.valueOf(body.get("stock")));
            if (stock < pg.getSold()) return Result.error("库存不能少于已售的 " + pg.getSold() + " 件");
            pg.setStock(stock);
        }
        if (body.get("status") != null) pg.setStatus(String.valueOf(body.get("status")));
        if (body.get("limitQty") != null) pg.setLimitQty(Integer.parseInt(String.valueOf(body.get("limitQty"))));
        pgMapper.updateById(pg);
        return Result.OK("已保存，小程序立即生效");
    }

    /** 往进行中 / 待开团的团期加团品。body: {periodId, goodsIds} */
    @PostMapping("/period/goods/add")
    public Result<?> periodGoodsAdd(@RequestBody Map<String, Object> body) {
        GbPeriod p = periodMapper.selectById(String.valueOf(body.get("periodId")));
        if (p == null || !List.of("draft", "plan", "live").contains(p.getStatus())) return Result.error("这个团期已截单，不能加团品");
        int n = 0;
        for (String gid : toList(body.get("goodsIds"))) {
            if (pgMapper.selectCount(new LambdaQueryWrapper<GbPeriodGoods>().eq(GbPeriodGoods::getPeriodId, p.getId()).eq(GbPeriodGoods::getGoodsId, gid)) > 0) continue;
            GbGoods g = goodsMapper.selectById(gid);
            if (g == null || "off".equals(g.getStatus())) continue;
            GbPeriodGoods pg = new GbPeriodGoods();
            pg.setPeriodId(p.getId()); pg.setGoodsId(gid); pg.setPrice(g.getSuggestPrice()); pg.setStock(100); pg.setSold(0); pg.setLimitQty(0); pg.setStatus("on"); pg.setSortNo(99);
            pgMapper.insert(pg);
            n++;
        }
        return Result.OK("已加入 " + n + " 个团品，默认库存 100，可以再改");
    }

    // ================================================================ 订单

    @GetMapping("/order/list")
    public Result<?> orderList(@RequestParam(required = false) String status, @RequestParam(required = false) String periodId, @RequestParam(required = false) String pointId,
                               @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "1") int pageNo, @RequestParam(defaultValue = "20") int pageSize) {
        java.util.function.Supplier<LambdaQueryWrapper<GbOrder>> base = () -> new LambdaQueryWrapper<GbOrder>()
                .eq(periodId != null && !periodId.isBlank(), GbOrder::getPeriodId, periodId)
                .eq(pointId != null && !pointId.isBlank(), GbOrder::getPointId, pointId)
                .and(keyword != null && !keyword.isBlank(), w -> w.like(GbOrder::getOrderNo, keyword).or().likeLeft(GbOrder::getBuyerPhone, keyword).or().eq(GbOrder::getPickupCode, keyword));
        LambdaQueryWrapper<GbOrder> q = base.get().orderByDesc(GbOrder::getCreateTime);
        if ("refund".equals(status)) q.eq(GbOrder::getAfterStatus, "applying");
        else if (status != null && !status.isBlank()) q.eq(GbOrder::getStatus, status);
        Page<GbOrder> p = orderMapper.selectPage(new Page<>(pageNo, pageSize), q);
        Map<String, Object> out = page(p, view.orders(p.getRecords(), true, false));
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("", orderMapper.selectCount(base.get()));
        for (String s : List.of(S_UNPAID, S_PAID, S_SHIPPING, S_ARRIVED, S_DONE, S_CLOSED)) counts.put(s, orderMapper.selectCount(base.get().eq(GbOrder::getStatus, s)));
        counts.put("refund", orderMapper.selectCount(base.get().eq(GbOrder::getAfterStatus, "applying")));
        out.put("counts", counts);
        return Result.OK(out);
    }

    @GetMapping("/order/detail")
    public Result<?> orderDetail(@RequestParam String id) {
        GbOrder o = orderMapper.selectById(id);
        return o == null ? Result.error("订单不存在") : Result.OK(view.orderDetail(o, true, false));
    }

    @PostMapping("/order/cancel")
    public Result<?> orderCancel(@RequestBody Map<String, String> body) {
        core.cancelByAdmin(body.get("id"));
        return Result.OK("已取消并原路退款");
    }

    @PostMapping("/order/verify")
    public Result<?> orderVerify(@RequestBody Map<String, String> body) {
        core.verify(null, body.get("id"));
        return Result.OK("已代团长核销");
    }

    @PostMapping("/order/remind")
    public Result<?> orderRemind(@RequestBody Map<String, String> body) {
        GbOrder o = orderMapper.selectById(body.get("id"));
        if (o == null || !S_ARRIVED.equals(o.getStatus())) return Result.error("只有待提货的订单可以提醒");
        orderMapper.update(null, new LambdaUpdateWrapper<GbOrder>().eq(GbOrder::getId, o.getId()).setSql("remind_count = remind_count + 1"));
        notify.pickup(o);
        return Result.OK("已给 " + o.getBuyerName() + " 发送取货提醒");
    }

    // ================================================================ 分拣配送

    @GetMapping("/sorting")
    public Result<?> sorting(@RequestParam(required = false) String periodId) {
        List<GbPeriod> ps = periodMapper.selectList(new LambdaQueryWrapper<GbPeriod>().in(GbPeriod::getStatus, "cutoff", "pickup", "done").orderByDesc(GbPeriod::getOpenTime).last("limit 10"));
        GbPeriod p = periodId == null || periodId.isBlank() ? ps.stream().findFirst().orElse(null) : periodMapper.selectById(periodId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("periods", ps);
        out.put("live", core.livePeriod());
        if (p == null) return Result.OK(out);
        out.put("period", p);
        List<GbDelivery> ds = deliveryMapper.selectList(new LambdaQueryWrapper<GbDelivery>().eq(GbDelivery::getPeriodId, p.getId()));
        Map<String, GbPoint> pts = view.pointMap(ds.stream().map(GbDelivery::getPointId).toList());
        Map<String, Integer> rank = Map.of("sorting", 0, "shipping", 1, "signed", 2);
        out.put("deliveries", ds.stream().sorted(Comparator.comparing(d -> rank.get(d.getStatus()))).map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("delivery", d);
            GbPoint pt = pts.get(d.getPointId());
            m.put("pointName", pt.getName()); m.put("leaderName", pt.getLeaderName()); m.put("leaderPhone", GbViewService.mask(pt.getLeaderPhone()));
            return m;
        }).toList());
        // 按商品汇总：应拣 = 本期有效订单的商品数，按提货点拆开
        List<GbOrder> os = orderMapper.selectList(new LambdaQueryWrapper<GbOrder>().eq(GbOrder::getPeriodId, p.getId()).notIn(GbOrder::getStatus, S_UNPAID, S_CLOSED));
        Map<String, String> pointOf = os.stream().collect(Collectors.toMap(GbOrder::getId, GbOrder::getPointId));
        Map<String, Map<String, Integer>> byGoods = new LinkedHashMap<>();
        Map<String, GbOrderItem> sample = new HashMap<>();
        view.itemsOf(pointOf.keySet()).values().forEach(list -> list.forEach(it -> {
            byGoods.computeIfAbsent(it.getGoodsId(), k -> new LinkedHashMap<>()).merge(pointOf.get(it.getOrderId()), it.getQty(), Integer::sum);
            sample.putIfAbsent(it.getGoodsId(), it);
        }));
        Map<String, GbGoods> gm = view.goodsMap(byGoods.keySet());
        List<String> pointIds = ds.stream().map(GbDelivery::getPointId).toList();
        out.put("pointCols", pointIds.stream().map(id -> Map.of("id", id, "name", pts.get(id).getName())).toList());
        out.put("goods", byGoods.entrySet().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            GbOrderItem it = sample.get(e.getKey());
            GbGoods g = gm.get(e.getKey());
            m.put("goodsId", e.getKey()); m.put("name", it.getGoodsName()); m.put("spec", it.getSpec()); m.put("image", it.getImage());
            m.put("supplier", g == null ? null : g.getSupplier());
            m.put("total", e.getValue().values().stream().mapToInt(Integer::intValue).sum());
            m.put("byPoint", e.getValue());
            return m;
        }).sorted(Comparator.comparing((Map<String, Object> m) -> (Integer) m.get("total")).reversed()).toList());
        return Result.OK(out);
    }

    @PostMapping("/delivery/ship")
    public Result<?> ship(@RequestBody Map<String, Object> body) {
        Object boxes = body.get("boxes");
        core.ship(String.valueOf(body.get("id")), boxes == null || String.valueOf(boxes).isBlank() ? null : Integer.parseInt(String.valueOf(boxes)), (String) body.get("driver"));
        return Result.OK("已发车，团长会收到到货提醒");
    }

    @PostMapping("/delivery/sign")
    public Result<?> sign(@RequestBody Map<String, String> body) {
        core.sign(body.get("id"), body.get("shortNote"));
        return Result.OK("已签收，这个提货点的订单变成待提货");
    }

    // ================================================================ 售后

    @GetMapping("/aftersale/list")
    public Result<?> asList(@RequestParam(required = false) String status) {
        List<GbAftersale> list = asMapper.selectList(new LambdaQueryWrapper<GbAftersale>().eq(status != null && !status.isBlank(), GbAftersale::getStatus, status)
                .orderByAsc(GbAftersale::getStatus).orderByDesc(GbAftersale::getCreateTime).last("limit 200"));
        Map<String, GbOrder> om = list.isEmpty() ? Map.of() : orderMapper.selectBatchIds(list.stream().map(GbAftersale::getOrderId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(GbOrder::getId, x -> x));
        List<Map<String, Object>> orders = view.orders(new ArrayList<>(om.values()), true, false);
        Map<String, Map<String, Object>> ov = orders.stream().collect(Collectors.toMap(m -> (String) m.get("id"), m -> m));
        List<Map<String, Object>> rows = list.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("aftersale", a);
            Map<String, Object> o = ov.get(a.getOrderId());
            m.put("order", o);
            List<String> ids = Arrays.asList(a.getItemIds().split(","));
            m.put("items", ((List<GbOrderItem>) o.get("items")).stream().filter(i -> ids.contains(i.getId())).toList());
            return m;
        }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", rows);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String s : List.of("wait", "done", "rej")) counts.put(s, asMapper.selectCount(new LambdaQueryWrapper<GbAftersale>().eq(GbAftersale::getStatus, s)));
        counts.put("", asMapper.selectCount(null));
        out.put("counts", counts);
        return Result.OK(out);
    }

    @PostMapping("/aftersale/approve")
    public Result<?> asApprove(@RequestBody Map<String, String> body) {
        String amt = body.get("refundAmount");
        core.approveAftersale(body.get("id"), amt == null || amt.isBlank() ? null : new BigDecimal(amt), body.get("duty"), body.get("reply"), me());
        return Result.OK("已退款，钱会在 1–3 天内原路回到买家微信");
    }

    @PostMapping("/aftersale/reject")
    public Result<?> asReject(@RequestBody Map<String, String> body) {
        core.rejectAftersale(body.get("id"), body.get("reply"), me());
        return Result.OK("已驳回，买家可以再次申请");
    }

    // ================================================================ 团长 / 提货点

    @GetMapping("/point/list")
    public Result<?> pointList(@RequestParam(required = false) String status, @RequestParam(required = false) String keyword) {
        List<GbPoint> list = pointMapper.selectList(new LambdaQueryWrapper<GbPoint>().eq(status != null && !status.isBlank(), GbPoint::getStatus, status)
                .and(keyword != null && !keyword.isBlank(), w -> w.like(GbPoint::getName, keyword).or().like(GbPoint::getLeaderName, keyword))
                .orderByAsc(GbPoint::getCreateTime));
        GbPeriod live = core.livePeriod();
        GbPeriod last = periodMapper.selectOne(new LambdaQueryWrapper<GbPeriod>().in(GbPeriod::getStatus, "pickup", "done").orderByDesc(GbPeriod::getOpenTime).last("limit 1"));
        List<GbOrder> liveOs = live == null ? List.of() : validOrders(live.getId(), null);
        List<GbOrder> lastOs = last == null ? List.of() : validOrders(last.getId(), null);
        List<Map<String, Object>> rows = list.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("point", p);
            m.put("leaderPhoneMasked", GbViewService.mask(p.getLeaderPhone()));
            List<GbOrder> mine = liveOs.stream().filter(o -> o.getPointId().equals(p.getId())).toList();
            m.put("orders", mine.size());
            m.put("gmv", sum(mine));
            List<GbOrder> lm = lastOs.stream().filter(o -> o.getPointId().equals(p.getId()) && List.of(S_ARRIVED, S_DONE).contains(o.getStatus())).toList();
            m.put("lastPickupRate", lm.isEmpty() ? null : BigDecimal.valueOf(lm.stream().filter(o -> S_DONE.equals(o.getStatus())).count()).divide(BigDecimal.valueOf(lm.size()), 4, RoundingMode.HALF_UP));
            return m;
        }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", rows);
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("", pointMapper.selectCount(null));
        for (String s : List.of("ok", "pending", "paused")) counts.put(s, pointMapper.selectCount(new LambdaQueryWrapper<GbPoint>().eq(GbPoint::getStatus, s)));
        out.put("counts", counts);
        return Result.OK(out);
    }

    /** 审核用的自动检查：附近有没有提货点、有没有冰柜 */
    @GetMapping("/point/check")
    public Result<?> pointCheck(@RequestParam String id) {
        GbPoint p = pointMapper.selectById(id);
        List<Map<String, Object>> checks = new ArrayList<>();
        GbPoint nearest = null;
        double best = Double.MAX_VALUE;
        if (p.getLat() != null) for (GbPoint o : pointMapper.selectList(new LambdaQueryWrapper<GbPoint>().eq(GbPoint::getStatus, "ok").ne(GbPoint::getId, id))) {
            if (o.getLat() == null) continue;
            double d = distanceKm(p.getLat().doubleValue(), p.getLng().doubleValue(), o.getLat().doubleValue(), o.getLng().doubleValue());
            if (d < best) { best = d; nearest = o; }
        }
        if (nearest != null) checks.add(Map.of("ok", best >= 1.5, "text", best >= 1.5 ? String.format("1.5 公里内没有其他提货点（最近：%s %.1f 公里）", nearest.getName(), best)
                : String.format("离「%s」只有 %.1f 公里，会抢同一批顾客", nearest.getName(), best)));
        checks.add(Map.of("ok", p.getHasFridge() != null && p.getHasFridge() == 1, "text", p.getHasFridge() != null && p.getHasFridge() == 1 ? "有冰柜，可以卖冷藏商品" : "没有冰柜：牛奶、鲈鱼、五花肉等冷藏商品不能选这个提货点"));
        checks.add(Map.of("ok", p.getLeaderPhone() != null && p.getLeaderPhone().matches("1\\d{10}"), "text", "手机号格式正确"));
        return Result.OK(Map.of("checks", checks, "nearest", nearest == null ? Map.of() : Map.of("name", nearest.getName(), "km", best)));
    }

    public static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371, dLat = Math.toRadians(lat2 - lat1), dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    @PostMapping("/point/save")
    public Result<?> pointSave(@RequestBody GbPoint p) {
        if (p.getName() == null || p.getName().isBlank() || p.getAddress() == null || p.getAddress().isBlank()) return Result.error("提货点名称和地址必填");
        if (p.getLeaderName() == null || p.getLeaderPhone() == null || !p.getLeaderPhone().matches("1\\d{10}")) return Result.error("团长姓名和 11 位手机号必填");
        if (p.getCommissionRate() != null && (p.getCommissionRate().signum() < 0 || p.getCommissionRate().compareTo(GbCommissionService.MAX_RATE) > 0))
            return Result.error("佣金比例要在 0% 到 30% 之间");
        if (p.getId() == null) {
            p.setStatus("ok");
            p.setSince(new Date());
            if (p.getCommissionRate() == null) p.setCommissionRate(new BigDecimal("0.10"));
            if (p.getHouseholds() == null) p.setHouseholds(0);
            if (p.getHasFridge() == null) p.setHasFridge(0);
            pointMapper.insert(p);
        } else pointMapper.updateById(p);
        return Result.OK("已保存", p);
    }

    @PostMapping("/point/audit")
    public Result<?> pointAudit(@RequestBody Map<String, Object> body) {
        GbPoint p = pointMapper.selectById(String.valueOf(body.get("id")));
        if (p == null || !"pending".equals(p.getStatus())) return Result.error("这个申请已经处理过了");
        boolean pass = Boolean.TRUE.equals(body.get("pass"));
        p.setStatus(pass ? "ok" : "rejected");
        p.setStatusNote((String) body.get("note"));
        if (pass) p.setSince(new Date());
        pointMapper.updateById(p);
        notify.apply(p, pass);
        return Result.OK(pass ? "已通过，下一期团起居民可以选这个提货点" : "已拒绝");
    }

    @PostMapping("/point/status")
    public Result<?> pointStatus(@RequestBody Map<String, String> body) {
        String st = body.get("status");
        if (!List.of("ok", "paused").contains(st)) return Result.error("参数不对");
        pointMapper.update(null, new LambdaUpdateWrapper<GbPoint>().eq(GbPoint::getId, body.get("id")).set(GbPoint::getStatus, st).set(GbPoint::getStatusNote, "paused".equals(st) ? body.get("note") : null));
        return Result.OK("paused".equals(st) ? "已暂停：居民不能再选这个提货点，已下单的照常送达" : "已恢复");
    }

    // ================================================================ 优惠

    @GetMapping("/coupon/list")
    public Result<?> couponList(@RequestParam(required = false) String type) {
        List<GbCoupon> list = couponMapper.selectList(new LambdaQueryWrapper<GbCoupon>().eq(type != null && !type.isBlank(), GbCoupon::getType, type).orderByAsc(GbCoupon::getCreateTime));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", list);
        GbPeriod live = core.livePeriod();
        if (live != null) {
            List<GbOrder> os = validOrders(live.getId(), null);
            List<GbOrder> withCoupon = os.stream().filter(o -> o.getDiscountAmount().signum() > 0).toList();
            out.put("liveOrders", os.size());
            out.put("couponOrders", withCoupon.size());
            out.put("discount", money(withCoupon.stream().map(GbOrder::getDiscountAmount).reduce(BigDecimal.ZERO, BigDecimal::add)));
            out.put("gmv", sum(os));
            out.put("newbieOrders", withCoupon.stream().filter(o -> o.getCouponName() != null && o.getCouponName().contains("新人")).count());
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("", couponMapper.selectCount(null));
        for (String s : List.of("full", "discount", "newbie")) counts.put(s, couponMapper.selectCount(new LambdaQueryWrapper<GbCoupon>().eq(GbCoupon::getType, s)));
        out.put("counts", counts);
        out.put("goods", goodsMapper.selectList(new LambdaQueryWrapper<GbGoods>().eq(GbGoods::getStatus, "on").select(GbGoods::getId, GbGoods::getName)));
        return Result.OK(out);
    }

    @PostMapping("/coupon/save")
    public Result<?> couponSave(@RequestBody GbCoupon c) {
        if (c.getName() == null || c.getName().isBlank()) return Result.error("名称必填");
        if (!List.of("full", "discount", "newbie").contains(c.getType())) return Result.error("类型不对");
        if (c.getThreshold() == null) c.setThreshold(BigDecimal.ZERO);
        if ("full".equals(c.getType()) && (c.getAmount() == null || c.getAmount().signum() <= 0 || c.getAmount().compareTo(c.getThreshold()) >= 0)) return Result.error("减免金额要大于 0 且小于使用门槛");
        if ("discount".equals(c.getType()) && (c.getRate() == null || c.getRate().compareTo(BigDecimal.ONE) >= 0 || c.getRate().signum() <= 0)) return Result.error("折扣要在 0.1 到 0.99 之间，如 0.9 表示 9 折");
        if ("newbie".equals(c.getType()) && c.getNewbieGoodsId() == null) return Result.error("新人 1 元购要选一个商品");
        if (c.getValidDays() == null && c.getValidEnd() == null) return Result.error("有效期必填");
        if (c.getTotal() == null) c.setTotal(0);
        if (c.getPerLimit() == null) c.setPerLimit(1);
        if (c.getChannel() == null) c.setChannel("home");
        if (c.getId() == null) { c.setReceived(0); c.setUsed(0); c.setStatus("on"); couponMapper.insert(c); }
        else { c.setReceived(null); c.setUsed(null); couponMapper.updateById(c); }
        return Result.OK("已保存", c);
    }

    @PostMapping("/coupon/status")
    public Result<?> couponStatus(@RequestBody Map<String, Object> body) {
        GbCoupon c = couponMapper.selectById(String.valueOf(body.get("id")));
        if (c == null) return Result.error("不存在");
        if (body.get("addTotal") != null) {
            c.setTotal(c.getTotal() + Integer.parseInt(String.valueOf(body.get("addTotal"))));
            c.setStatus("on");
        } else c.setStatus(String.valueOf(body.get("status")));
        couponMapper.updateById(c);
        return Result.OK("已保存");
    }

    // ================================================================ 佣金结算

    /** 各团长的佣金汇总：比例、待结算预估、本月入账、累计入账、已提现、可提现、审核中 */
    @GetMapping("/commission/summary")
    public Result<?> commissionSummary() {
        List<GbPoint> pts = pointMapper.selectList(new LambdaQueryWrapper<GbPoint>().in(GbPoint::getStatus, "ok", "paused").orderByAsc(GbPoint::getCreateTime));
        Map<String, GbLeaderAccount> accs = accMapper.selectList(null).stream().collect(Collectors.toMap(GbLeaderAccount::getPointId, a -> a));
        Map<String, BigDecimal> est = commission.estimate(null);
        Date monthStart = Date.from(LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
        Map<String, BigDecimal> month = new HashMap<>();
        commMapper.selectList(new LambdaQueryWrapper<GbCommission>().ge(GbCommission::getSettleTime, monthStart).select(GbCommission::getPointId, GbCommission::getAmount))
                .forEach(c -> month.merge(c.getPointId(), c.getAmount(), BigDecimal::add));
        BigDecimal z = BigDecimal.ZERO.setScale(2);
        List<Map<String, Object>> rows = pts.stream().map(p -> {
            GbLeaderAccount a = accs.get(p.getId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pointId", p.getId());
            m.put("pointName", p.getName());
            m.put("leaderName", p.getLeaderName());
            m.put("leaderPhone", GbViewService.mask(p.getLeaderPhone()));
            m.put("status", p.getStatus());
            m.put("rate", p.getCommissionRate());
            m.put("estimate", money(est.getOrDefault(p.getId(), z)));
            m.put("month", money(month.getOrDefault(p.getId(), z)));
            m.put("totalIncome", a == null ? z : a.getTotalIncome());
            m.put("totalWithdrawn", a == null ? z : a.getTotalWithdrawn());
            m.put("balance", a == null ? z : a.getBalance());
            m.put("frozen", a == null ? z : a.getFrozen());
            return m;
        }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", rows);
        out.put("totalIncome", money(accs.values().stream().map(GbLeaderAccount::getTotalIncome).reduce(BigDecimal.ZERO, BigDecimal::add)));
        out.put("estimate", money(est.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)));
        out.put("balance", money(accs.values().stream().map(GbLeaderAccount::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add)));
        out.put("month", money(month.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)));
        List<GbWithdraw> wait = wdMapper.selectList(new LambdaQueryWrapper<GbWithdraw>().eq(GbWithdraw::getStatus, "wait").select(GbWithdraw::getAmount));
        out.put("waitCount", wait.size());
        out.put("waitAmount", money(wait.stream().map(GbWithdraw::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add)));
        return Result.OK(out);
    }

    /** 某个团长的佣金入账明细 */
    @GetMapping("/commission/list")
    public Result<?> commissionList(@RequestParam String pointId, @RequestParam(defaultValue = "1") int pageNo, @RequestParam(defaultValue = "20") int pageSize) {
        Page<GbCommission> pg = commMapper.selectPage(new Page<>(pageNo, Math.min(pageSize, 100)), new LambdaQueryWrapper<GbCommission>().eq(GbCommission::getPointId, pointId)
                .orderByDesc(GbCommission::getSettleTime).orderByDesc(GbCommission::getPickupAt));
        Map<String, GbPeriod> pm = view.periodMap(pg.getRecords().stream().map(GbCommission::getPeriodId).toList());
        List<Map<String, Object>> rows = pg.getRecords().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("c", c);
            GbPeriod p = pm.get(c.getPeriodId());
            m.put("periodName", p == null ? "" : p.getName());
            return m;
        }).toList();
        return Result.OK(page(pg, rows));
    }

    @PostMapping("/point/rate")
    public Result<?> pointRate(@RequestBody Map<String, Object> body) {
        BigDecimal rate;
        try {
            rate = new BigDecimal(String.valueOf(body.get("rate")));
        } catch (Exception e) {
            return Result.error("佣金比例不对");
        }
        if (rate.signum() < 0 || rate.compareTo(GbCommissionService.MAX_RATE) > 0) return Result.error("佣金比例要在 0% 到 30% 之间");
        int n = pointMapper.update(null, new LambdaUpdateWrapper<GbPoint>().eq(GbPoint::getId, String.valueOf(body.get("id"))).set(GbPoint::getCommissionRate, rate.setScale(4, RoundingMode.HALF_UP)));
        return n == 0 ? Result.error("提货点不存在") : Result.OK("已保存，之后付款的订单按新比例算");
    }

    // ================================================================ 消息通知

    /** 订阅消息发送记录 + 各场景配置情况 */
    @GetMapping("/notify/list")
    public Result<?> notifyList(@RequestParam(required = false) String status, @RequestParam(required = false) String scene,
                                @RequestParam(defaultValue = "1") int pageNo, @RequestParam(defaultValue = "20") int pageSize) {
        Page<GbNotifyLog> pg = notifyMapper.selectPage(new Page<>(pageNo, Math.min(pageSize, 100)), new LambdaQueryWrapper<GbNotifyLog>()
                .eq(status != null && !status.isBlank(), GbNotifyLog::getStatus, status)
                .eq(scene != null && !scene.isBlank(), GbNotifyLog::getScene, scene)
                .orderByDesc(GbNotifyLog::getCreateTime));
        List<String> mids = pg.getRecords().stream().map(GbNotifyLog::getMemberId).filter(Objects::nonNull).distinct().toList();
        Map<String, GbMember> ms = mids.isEmpty() ? Map.of() : memberMapper.selectBatchIds(mids).stream().collect(Collectors.toMap(GbMember::getId, m -> m));
        List<Map<String, Object>> rows = pg.getRecords().stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("l", l);
            m.put("sceneName", GbNotifyService.SCENE_NAMES.getOrDefault(l.getScene(), l.getScene()));
            GbMember mb = ms.get(l.getMemberId());
            m.put("nickname", mb == null ? null : mb.getNickname());
            m.put("phone", mb == null ? null : mb.getPhone());
            return m;
        }).toList();
        Map<String, Object> out = page(pg, rows);
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("", notifyMapper.selectCount(new LambdaQueryWrapper<GbNotifyLog>().eq(scene != null && !scene.isBlank(), GbNotifyLog::getScene, scene)));
        for (String st : List.of("sent", "mock", "skipped", "failed"))
            counts.put(st, notifyMapper.selectCount(new LambdaQueryWrapper<GbNotifyLog>().eq(GbNotifyLog::getStatus, st).eq(scene != null && !scene.isBlank(), GbNotifyLog::getScene, scene)));
        out.put("counts", counts);
        out.put("scenes", notify.configView());
        out.put("wxReady", notify.wxReady());
        out.put("state", notify.state());
        return Result.OK(out);
    }

    // ================================================================ 提现审核

    @GetMapping("/withdraw/list")
    public Result<?> withdrawList(@RequestParam(required = false) String status, @RequestParam(defaultValue = "1") int pageNo, @RequestParam(defaultValue = "20") int pageSize) {
        Page<GbWithdraw> pg = wdMapper.selectPage(new Page<>(pageNo, Math.min(pageSize, 100)), new LambdaQueryWrapper<GbWithdraw>()
                .eq(status != null && !status.isBlank(), GbWithdraw::getStatus, status)
                .orderByDesc(GbWithdraw::getCreateTime));
        Map<String, GbPoint> pts = view.pointMap(pg.getRecords().stream().map(GbWithdraw::getPointId).toList());
        Map<String, GbLeaderAccount> accs = pg.getRecords().isEmpty() ? Map.of()
                : accMapper.selectBatchIds(pg.getRecords().stream().map(GbWithdraw::getPointId).distinct().toList()).stream().collect(Collectors.toMap(GbLeaderAccount::getPointId, a -> a));
        List<Map<String, Object>> rows = pg.getRecords().stream().map(w -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("w", w);
            GbPoint p = pts.get(w.getPointId());
            m.put("pointName", p == null ? "" : p.getName());
            m.put("leaderName", p == null ? "" : p.getLeaderName());
            m.put("leaderPhone", p == null ? "" : p.getLeaderPhone());
            GbLeaderAccount a = accs.get(w.getPointId());
            m.put("totalIncome", a == null ? null : a.getTotalIncome());
            m.put("totalWithdrawn", a == null ? null : a.getTotalWithdrawn());
            return m;
        }).toList();
        Map<String, Object> out = page(pg, rows);
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("", wdMapper.selectCount(null));
        for (String st : List.of("wait", "paid", "rej")) counts.put(st, wdMapper.selectCount(new LambdaQueryWrapper<GbWithdraw>().eq(GbWithdraw::getStatus, st)));
        out.put("counts", counts);
        return Result.OK(out);
    }

    @PostMapping("/withdraw/approve")
    public Result<?> withdrawApprove(@RequestBody Map<String, String> body) {
        commission.approve(body.get("id"), body.get("voucher"), me());
        return Result.OK("已登记打款");
    }

    @PostMapping("/withdraw/reject")
    public Result<?> withdrawReject(@RequestBody Map<String, String> body) {
        commission.reject(body.get("id"), body.get("reply"), me());
        return Result.OK("已驳回，金额退回团长的可提现余额");
    }
}
