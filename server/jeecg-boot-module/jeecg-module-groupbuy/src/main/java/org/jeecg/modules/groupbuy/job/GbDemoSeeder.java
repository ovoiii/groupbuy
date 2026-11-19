package org.jeecg.modules.groupbuy.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.groupbuy.config.GbProperties;
import org.jeecg.modules.groupbuy.entity.*;
import org.jeecg.modules.groupbuy.mapper.*;
import org.jeecg.modules.groupbuy.service.GbCoreService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.jeecg.modules.groupbuy.service.GbCoreService.*;

/**
 * 空库时写入演示数据：14 个商品、6 个提货点、5 个优惠、前后 5 期团和几百个订单。
 * 时间都按「今天」推算，所以任何一天启动看到的都是「进行中」的团。关掉：groupbuy.demo-seed=false
 * 演示账号（小程序开发登录用手机号）：居民 13800001029，团长 13800002716
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GbDemoSeeder implements ApplicationRunner {
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
    private final GbCoreService core;
    private final TransactionTemplate tx;

    @Value("${jeecg.path.upload:}")
    private String uploadPath;

    private final Random rnd = new Random(20261117);

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isDemoSeed()) return;
        try {
            copyImages();
            if (goodsMapper.selectCount(null) > 0) return;
            tx.executeWithoutResult(s -> seed());
            log.info("[团购] 已写入演示数据");
        } catch (Exception e) {
            log.error("[团购] 写入演示数据失败", e);
        }
    }

    /** 把示意商品图复制到上传目录 gb/ 下（前端用 /sys/common/static/gb/xxx.png 访问） */
    private void copyImages() throws Exception {
        if (uploadPath == null || uploadPath.isBlank()) return;
        Path dir = Paths.get(uploadPath, "gb");
        Files.createDirectories(dir);
        for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath:groupbuy/seed/*.png")) {
            Path t = dir.resolve(Objects.requireNonNull(r.getFilename()));
            if (Files.exists(t)) continue;
            try (InputStream in = r.getInputStream()) { Files.copy(in, t); }
        }
    }

    private static Date at(LocalDate d, int h, int m) {
        return Date.from(d.atTime(h, 0).plusMinutes(m).atZone(ZoneId.systemDefault()).toInstant());
    }

    private static BigDecimal b(String v) { return new BigDecimal(v); }

    /** 整体在一个事务里，出错全部回滚，下次启动重来 */
    public void seed() {
        // ---------------- 商品 ----------------
        String[][] G = {
                // 名称, 规格, 类目, 采购, 建议, 参考, 供应商, 产地, 储存, 图, 限购, 本期库存
                {"丹东 99 草莓", "500g / 盒 · 约 20 颗", "水果", "21.50", "29.90", "45.80", "丹东鑫果园", "辽宁丹东", "normal", "strawberry", "2", "400"},
                {"散养土鸡蛋", "30 枚 / 箱 · 约 1.5kg", "肉禽蛋", "19.20", "26.80", "39.90", "平谷蛋禽合作社", "北京平谷", "normal", "egg", "3", "600"},
                {"本地小油菜", "500g / 袋", "蔬菜", "2.10", "3.50", "6.00", "顺义蔬菜基地", "北京顺义", "normal", "greens", "0", "800"},
                {"鲜牛奶 950ml", "950ml / 瓶 · 低温", "乳品烘焙", "9.60", "12.90", "16.50", "三元乳业", "北京", "cold", "milk", "0", "500"},
                {"烟台红富士苹果", "5 斤 / 箱 · 果径 80mm+", "水果", "17.80", "24.90", "35.00", "栖霞果品", "山东栖霞", "normal", "apple", "0", "300"},
                {"赣南脐橙", "5 斤 / 箱", "水果", "13.50", "19.90", "29.90", "信丰果业", "江西赣州", "normal", "citrus", "2", "300"},
                {"冰鲜鲈鱼", "约 600g / 条 · 已去鳞去脏", "水产", "16.90", "22.80", "32.00", "京深海鲜批发", "广东", "cold", "fish", "0", "150"},
                {"黑猪五花肉", "500g / 盒", "肉禽蛋", "19.80", "25.90", "36.00", "千喜鹤", "黑龙江", "cold", "pork", "0", "200"},
                {"五常大米", "5kg / 袋 · 当季新米", "粮油", "38.00", "49.90", "69.00", "五常米业", "黑龙江五常", "normal", "rice", "0", "120"},
                {"普罗旺斯西红柿", "1kg / 盒", "蔬菜", "6.20", "9.90", "15.80", "顺义蔬菜基地", "北京顺义", "normal", "tomato", "0", "400"},
                {"香蕉", "1.5kg / 把", "水果", "5.90", "8.80", "12.90", "新发地", "云南", "normal", "banana", "0", "300"},
                {"鲜香菇", "300g / 盒", "蔬菜", "4.40", "6.90", "9.90", "顺义蔬菜基地", "河北", "normal", "mushroom", "0", "300"},
                {"手撕吐司", "400g / 袋", "乳品烘焙", "6.80", "9.90", "15.00", "好利来中央工厂", "北京", "normal", "bread", "0", "0"},
                {"抽纸 3 层 × 24 包", "100 抽 / 包", "日用", "25.00", "32.90", "49.90", "清风", "", "normal", "tissue", "0", "200"},
        };
        List<GbGoods> goods = new ArrayList<>();
        List<Integer> stocks = new ArrayList<>(), limits = new ArrayList<>();
        for (String[] r : G) {
            GbGoods g = new GbGoods();
            g.setName(r[0]); g.setSpec(r[1]); g.setCategory(r[2]);
            g.setCostPrice(b(r[3])); g.setSuggestPrice(b(r[4])); g.setOrigPrice(b(r[5]));
            g.setSupplier(r[6]); g.setOrigin(r[7]); g.setStorage(r[8]);
            g.setImages("gb/" + r[9] + ".png");
            g.setDetail(r[0] + "，" + r[1] + "。产地直采，当天分拣，坏了包退。");
            boolean off = r[0].equals("手撕吐司");
            g.setStatus(off ? "off" : "on");
            if (off) g.setOffReason("供应商停产");
            goodsMapper.insert(g);
            goods.add(g); limits.add(Integer.parseInt(r[10])); stocks.add(Integer.parseInt(r[11]));
        }

        // ---------------- 提货点 ----------------
        Object[][] P = {
                {"幸福里 3 号楼便利店", "朝阳区幸福里小区 3 号楼底商", "王桂芳", "13800002716", 1260, 1, "ok", "2025-03-01", 116.4702, 39.9289},
                {"阳光花园南门驿站", "朝阳区阳光花园南门菜鸟驿站", "李建国", "13900000821", 2100, 1, "ok", "2025-01-01", 116.4812, 39.9351},
                {"翠竹苑物业服务中心", "朝阳区翠竹苑 1 号楼 1 层", "赵敏", "18600005532", 860, 1, "ok", "2025-06-01", 116.4588, 39.9197},
                {"和平里水果店", "东城区和平里七区 12 号楼底商", "陈晓燕", "13500009902", 1540, 1, "ok", "2025-04-01", 116.4205, 39.9550},
                {"望京 SOHO 快递柜旁", "朝阳区望京街 10 号", "刘洋", "15800004410", 0, 0, "pending", null, 116.4810, 39.9960},
                {"百子湾家园 7 号楼", "朝阳区百子湾家园 7 号楼 102", "孙丽", "13700006638", 980, 1, "paused", "2025-02-01", 116.4950, 39.8920},
        };
        List<GbPoint> points = new ArrayList<>();
        for (Object[] r : P) {
            GbPoint p = new GbPoint();
            p.setName((String) r[0]); p.setAddress((String) r[1]); p.setLeaderName((String) r[2]); p.setLeaderPhone((String) r[3]);
            p.setHouseholds((Integer) r[4]); p.setHasFridge((Integer) r[5]); p.setStatus((String) r[6]);
            p.setSince(r[7] == null ? null : java.sql.Date.valueOf((String) r[7]));
            p.setLng(BigDecimal.valueOf((Double) r[8])); p.setLat(BigDecimal.valueOf((Double) r[9]));
            p.setOpenHours("08:00-21:00"); p.setCommissionRate(BigDecimal.ZERO);
            if ("pending".equals(r[6])) p.setApplyNote("写字楼一层快递柜旁空位，自有货架 2 个，无冰柜；估计每天 60–80 单。写字楼周末不开门。");
            if ("paused".equals(r[6])) p.setStatusNote("店面装修，12 月恢复");
            pointMapper.insert(p);
            points.add(p);
        }
        List<GbPoint> okPoints = points.subList(0, 4);

        // ---------------- 小程序用户 ----------------
        String[] names = {"张女士", "刘先生", "周阿姨", "吴女士", "郑先生", "王女士", "冯先生", "陈女士", "褚先生", "卫女士", "蒋先生", "沈女士", "韩先生", "杨女士", "朱先生", "秦阿姨", "尤先生", "许女士", "何先生", "吕女士",
                "施先生", "孔女士", "曹先生", "严女士", "华阿姨", "金先生", "魏女士", "陶先生", "姜女士", "戚先生"};
        List<GbMember> members = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            GbMember m = new GbMember();
            m.setNickname(names[i]);
            m.setPhone(i == 0 ? "13800001029" : "1" + (35 + rnd.nextInt(55)) + String.format("%08d", rnd.nextInt(100_000_000)));
            m.setDefaultPointId(okPoints.get(i % 4).getId());
            m.setFirstOrderDone(1);
            memberMapper.insert(m);
            members.add(m);
        }
        GbMember leaderMember = new GbMember();
        leaderMember.setNickname("王桂芳"); leaderMember.setPhone("13800002716"); leaderMember.setDefaultPointId(points.get(0).getId()); leaderMember.setFirstOrderDone(1);
        memberMapper.insert(leaderMember);
        points.get(0).setLeaderMemberId(leaderMember.getId());
        pointMapper.updateById(points.get(0));

        // ---------------- 优惠 ----------------
        GbCoupon c39 = coupon("满 39 减 5", "full", "39", "5", null, null, null, null, 5000, 7, null, null);
        GbCoupon c59 = coupon("满 59 减 8", "full", "59", "8", null, null, null, null, 3000, null, LocalDate.now().minusDays(7), LocalDate.now().plusDays(13));
        coupon("水果 9 折", "discount", "0", null, "0.90", "10", "水果", null, 2000, null, LocalDate.now().minusDays(16), LocalDate.now().plusDays(3));
        GbCoupon cNew = coupon("新人 1 元购", "newbie", "0", null, null, null, null, goods.get(9).getId(), 0, 3, null, null);
        GbCoupon c99 = coupon("双 12 预热 满 99 减 15", "full", "99", "15", null, null, null, null, 5000, null, LocalDate.now().plusDays(14), LocalDate.now().plusDays(25));
        c99.setStatus("on");

        // ---------------- 团期：前 3 期、本期、下一期 ----------------
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        int liveOffset = now.getHour() >= 23 ? 1 : 0;   // 23 点后今天的团已截单，下一期才是进行中
        for (int d = -3; d <= 1 + liveOffset; d++) {
            LocalDate day = today.plusDays(d);
            GbPeriod p = new GbPeriod();
            p.setName(day.getMonthValue() + "月" + day.getDayOfMonth() + "日团");
            p.setOpenTime(at(day.minusDays(1), 23, 0));
            p.setCutoffTime(at(day, 23, 0));
            p.setPickupTime(at(day.plusDays(1), 16, 0));
            p.setPickupEndTime(at(day.plusDays(2), 21, 0));
            Date n = new Date();
            String st = n.before(p.getOpenTime()) ? "plan" : n.before(p.getCutoffTime()) ? "live" : n.before(p.getPickupTime()) ? "cutoff" : n.before(p.getPickupEndTime()) ? "pickup" : "done";
            p.setStatus(st);
            periodMapper.insert(p);
            // 团品：下架的吐司本期不上；越早的期团品越少一点
            List<GbPeriodGoods> pgs = new ArrayList<>();
            for (int i = 0; i < goods.size(); i++) {
                GbGoods g = goods.get(i);
                if ("off".equals(g.getStatus()) && d >= 0) continue;
                if (d < 0 && i == 13) continue;
                GbPeriodGoods pg = new GbPeriodGoods();
                pg.setPeriodId(p.getId()); pg.setGoodsId(g.getId());
                pg.setPrice(i == 1 && "plan".equals(st) ? b("25.80") : g.getSuggestPrice());
                pg.setStock(stocks.get(i) == 0 ? 120 : stocks.get(i) + ("plan".equals(st) && (i == 0 || i == 5) ? 100 : 0));
                pg.setSold(0); pg.setLimitQty(limits.get(i)); pg.setStatus("on"); pg.setSortNo(i);
                if (i == 0) pg.setTag("爆品");
                if (i == 9) pg.setTag("新人 1 元");
                pgMapper.insert(pg);
                pgs.add(pg);
            }
            if ("plan".equals(st)) continue;
            // 订单：本期按售罄率生成，往期 150 单左右
            int target = "live".equals(st) ? 140 : 160 + rnd.nextInt(40);
            Map<String, Integer> soldMap = new HashMap<>();
            List<GbOrder> made = new ArrayList<>();
            for (int k = 0; k < target; k++) {
                GbMember m = members.get(rnd.nextInt(members.size()));
                GbPoint pt = okPoints.get(Math.min(3, (int) Math.floor(Math.abs(rnd.nextGaussian()) * 1.6)));
                int lines = 1 + rnd.nextInt(3);
                List<GbPeriodGoods> pick = new ArrayList<>();
                for (int t = 0; t < lines; t++) {
                    GbPeriodGoods pg = pgs.get(Math.min(pgs.size() - 1, (int) Math.floor(Math.abs(rnd.nextGaussian()) * 3.2)));
                    if (!pick.contains(pg)) pick.add(pg);
                }
                // 冷藏商品不给没冰柜的点（四个合作点都有冰柜，这里只是保持规则一致）
                String status;
                Date created;
                if ("live".equals(st)) {
                    status = k < 6 ? S_UNPAID : S_PAID;
                    created = new Date(System.currentTimeMillis() - 60_000L * (k < 6 ? k + 1 : 6 + (target - k) * 5L));
                    if (created.before(p.getOpenTime())) created = new Date(p.getOpenTime().getTime() + 60_000L * k);
                } else {
                    created = new Date(p.getOpenTime().getTime() + (long) ((p.getCutoffTime().getTime() - p.getOpenTime().getTime()) * rnd.nextDouble()));
                    status = "done".equals(st) ? S_DONE : "pickup".equals(st) ? (rnd.nextDouble() < 0.9 ? S_DONE : S_ARRIVED) : S_SHIPPING;
                }
                GbOrder o = order(p, pt, m, pick, status, created, soldMap);
                made.add(o);
            }
            for (GbPeriodGoods pg : pgs) {
                int s = soldMap.getOrDefault(pg.getId(), 0);
                // 让两个爆品在本期接近售罄，和后台「快卖完」提示对上
                if ("live".equals(st) && (pg.getSortNo() == 0 || pg.getSortNo() == 5)) s = Math.max(s, pg.getStock() - 10 - pg.getSortNo());
                pg.setSold(Math.min(s, pg.getStock()));
                pgMapper.updateById(pg);
            }
            // 配送单
            if (!"live".equals(st)) {
                for (int i = 0; i < okPoints.size(); i++) {
                    GbPoint pt = okPoints.get(i);
                    List<GbOrder> os = made.stream().filter(o -> o.getPointId().equals(pt.getId())).toList();
                    if (os.isEmpty()) continue;
                    GbDelivery dv = new GbDelivery();
                    dv.setPeriodId(p.getId()); dv.setPointId(pt.getId()); dv.setOrderCount(os.size());
                    dv.setItemCount(os.stream().mapToInt(o -> core.items(o.getId()).stream().mapToInt(GbOrderItem::getQty).sum()).sum());
                    dv.setBoxes(Math.max(1, dv.getItemCount() / 45));
                    dv.setSortTime(at(day.plusDays(1), 9, 40 + i * 4));
                    String ds = "cutoff".equals(st) ? (i == 3 ? "sorting" : i == 2 ? "shipping" : "signed") : "signed";
                    dv.setStatus(ds);
                    if (!"sorting".equals(ds)) { dv.setShipTime(at(day.plusDays(1), 11, 5)); dv.setDriver("张师傅 139****2207"); }
                    if ("signed".equals(ds)) dv.setSignTime(at(day.plusDays(1), 12, 18 + i * 14));
                    if ("signed".equals(ds) && i == 0 && "cutoff".equals(st)) dv.setShortNote("黑猪五花肉 少 1 盒");
                    deliveryMapper.insert(dv);
                    if ("signed".equals(ds) && "cutoff".equals(st))
                        for (GbOrder o : os) { o.setStatus(S_ARRIVED); orderMapper.updateById(o); }
                }
            }
            // 售后：最近一期配送完的订单里挑几单
            if ("cutoff".equals(st) || "pickup".equals(st)) {
                List<GbOrder> arrived = made.stream().filter(o -> S_ARRIVED.equals(o.getStatus()) || S_DONE.equals(o.getStatus())).toList();
                String[][] A = {{"质量问题", "鲈鱼不新鲜，打开就闻到异味"}, {"少件", "五花肉只收到 1 盒，订单是 2 盒"}, {"破损", "鸡蛋碎了 3 个"}, {"质量问题", "草莓底下几颗发软"}};
                for (int i = 0; i < Math.min(A.length, arrived.size()) && ("cutoff".equals(st) ? i < 2 : i >= 2); i += 1) {
                    GbOrder o = arrived.get(i * 7 % arrived.size());
                    if (!"none".equals(o.getAfterStatus())) continue;
                    List<GbOrderItem> its = core.items(o.getId());
                    GbAftersale a = new GbAftersale();
                    a.setAsNo("SH" + o.getOrderNo().substring(2) + i);
                    a.setOrderId(o.getId()); a.setMemberId(o.getMemberId()); a.setPointId(o.getPointId());
                    a.setItemIds(its.get(0).getId()); a.setType(A[i][0]); a.setReason(A[i][1]);
                    a.setApplyAmount(core.refundCap(o, List.of(its.get(0))));
                    boolean wait = "cutoff".equals(st);
                    a.setStatus(wait ? "wait" : "done");
                    a.setDeadline(new Date(System.currentTimeMillis() + (wait ? (22 - i) : -20) * 3600_000L));
                    if (!wait) { a.setRefundAmount(a.getApplyAmount()); a.setDuty(i == 2 ? "平台" : "供应商"); a.setReply("抱歉给您带来不好的体验，已全额退款。"); a.setHandleTime(new Date()); a.setHandleBy("admin"); }
                    asMapper.insert(a);
                    o.setAfterStatus(wait ? "applying" : "refunded");
                    if (!wait) o.setRefundAmount(a.getRefundAmount());
                    orderMapper.updateById(o);
                }
                if ("pickup".equals(st)) {   // 往期有一单没来得及取的被驳回
                    arrived.stream().filter(o -> S_ARRIVED.equals(o.getStatus()) && "none".equals(o.getAfterStatus())).findFirst().ifPresent(o -> {
                        GbAftersale a = new GbAftersale();
                        a.setAsNo("SH" + o.getOrderNo().substring(2) + "9");
                        a.setOrderId(o.getId()); a.setMemberId(o.getMemberId()); a.setPointId(o.getPointId());
                        a.setItemIds(core.items(o.getId()).get(0).getId()); a.setType("没来得及取"); a.setReason("出差没来得及取");
                        a.setApplyAmount(core.refundCap(o, core.items(o.getId())));
                        a.setStatus("rej"); a.setReply("团长已帮您保管，提货截止前回来自提即可。"); a.setDeadline(new Date()); a.setHandleTime(new Date()); a.setHandleBy("admin");
                        asMapper.insert(a);
                        o.setAfterStatus("rejected"); orderMapper.updateById(o);
                    });
                }
            }
        }

        // 演示居民：领好两张券
        GbMember demo = members.get(0);
        for (GbCoupon c : List.of(c39, c59)) {
            couponMapper.incReceived(c.getId());
            GbUserCoupon uc = new GbUserCoupon();
            uc.setCouponId(c.getId()); uc.setMemberId(demo.getId()); uc.setStatus("unused");
            uc.setExpireTime(new Date(System.currentTimeMillis() + 7 * 86400_000L));
            ucMapper.insert(uc);
        }
        // 统计数字（领取 / 使用）给一点历史量
        for (GbCoupon c : couponMapper.selectList(null)) {
            if (c.getTotal() > 0 && c.getValidStart() != null && c.getValidStart().after(new Date())) continue;
            int got = c.getTotal() == 0 ? 412 : Math.min(c.getTotal(), (int) (c.getTotal() * ("discount".equals(c.getType()) ? 1.0 : 0.55)));
            c.setReceived(Math.max(c.getReceived(), got));
            c.setUsed(Math.max(c.getUsed(), (int) (got * 0.6)));
            couponMapper.updateById(c);
        }
    }

    private GbCoupon coupon(String name, String type, String threshold, String amount, String rate, String max, String cat, String newbieGoods, int total, Integer validDays, LocalDate start, LocalDate end) {
        GbCoupon c = new GbCoupon();
        c.setName(name); c.setType(type); c.setThreshold(b(threshold));
        c.setAmount(amount == null ? null : b(amount)); c.setRate(rate == null ? null : b(rate)); c.setMaxDiscount(max == null ? null : b(max));
        c.setScopeCategory(cat); c.setNewbieGoodsId(newbieGoods); c.setTotal(total); c.setReceived(0); c.setUsed(0); c.setPerLimit(1);
        c.setValidDays(validDays);
        c.setValidStart(start == null ? null : at(start, 0, 0)); c.setValidEnd(end == null ? null : at(end, 23, 59));
        c.setChannel("home"); c.setStatus("on");
        couponMapper.insert(c);
        return c;
    }

    private GbOrder order(GbPeriod p, GbPoint pt, GbMember m, List<GbPeriodGoods> pick, String status, Date created, Map<String, Integer> soldMap) {
        BigDecimal amount = BigDecimal.ZERO;
        List<GbOrderItem> its = new ArrayList<>();
        for (GbPeriodGoods pg : pick) {
            GbGoods g = goodsMapper.selectById(pg.getGoodsId());
            int max = pg.getLimitQty() > 0 ? pg.getLimitQty() : 3;
            int q = 1 + (rnd.nextDouble() < 0.3 ? rnd.nextInt(max) : 0);
            GbOrderItem it = new GbOrderItem();
            it.setPeriodGoodsId(pg.getId()); it.setGoodsId(g.getId()); it.setGoodsName(g.getName()); it.setSpec(g.getSpec());
            it.setImage(firstImage(g.getImages())); it.setPrice(pg.getPrice()); it.setQty(q);
            it.setAmount(money(pg.getPrice().multiply(BigDecimal.valueOf(q)))); it.setNewbie(0);
            amount = amount.add(it.getAmount());
            its.add(it);
            soldMap.merge(pg.getId(), q, Integer::sum);
        }
        GbOrder o = new GbOrder();
        o.setOrderNo(new java.text.SimpleDateFormat("yyMMdd").format(created) + String.format("%06d", rnd.nextInt(1_000_000)));
        o.setPeriodId(p.getId()); o.setPointId(pt.getId()); o.setMemberId(m.getId());
        o.setBuyerName(m.getNickname()); o.setBuyerPhone(m.getPhone());
        o.setGoodsAmount(money(amount));
        BigDecimal disc = amount.compareTo(b("59")) >= 0 && rnd.nextDouble() < 0.4 ? b("8") : amount.compareTo(b("39")) >= 0 && rnd.nextDouble() < 0.4 ? b("5") : BigDecimal.ZERO;
        o.setDiscountAmount(disc);
        if (disc.signum() > 0) o.setCouponName(disc.intValue() == 8 ? "满 59 减 8" : "满 39 减 5");
        o.setPayAmount(money(amount.subtract(disc)));
        o.setRefundAmount(BigDecimal.ZERO);
        o.setStatus(status); o.setAfterStatus("none"); o.setRemindCount(0);
        o.setPayExpireTime(new Date(created.getTime() + 15 * 60_000L));
        if (!S_UNPAID.equals(status)) { o.setPayTime(new Date(created.getTime() + 40_000L)); o.setPickupCode(core.newPickupCode(p.getId())); }
        if (S_DONE.equals(status)) o.setPickupAt(new Date(p.getPickupTime().getTime() + (long) (rnd.nextDouble() * 5 * 3600_000L)));
        o.setCreateTime(created);
        orderMapper.insert(o);
        for (GbOrderItem it : its) { it.setOrderId(o.getId()); itemMapper.insert(it); }
        return o;
    }
}
