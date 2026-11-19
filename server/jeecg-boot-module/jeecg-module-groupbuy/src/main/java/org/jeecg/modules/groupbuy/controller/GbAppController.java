package org.jeecg.modules.groupbuy.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.config.shiro.IgnoreAuth;
import org.jeecg.modules.groupbuy.config.GbAuthInterceptor;
import org.jeecg.modules.groupbuy.config.GbProperties;
import org.jeecg.modules.groupbuy.entity.*;
import org.jeecg.modules.groupbuy.mapper.*;
import org.jeecg.modules.groupbuy.service.GbCoreService;
import org.jeecg.modules.groupbuy.service.GbTokenService;
import org.jeecg.modules.groupbuy.service.GbViewService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

import static org.jeecg.modules.groupbuy.service.GbCoreService.*;

/**
 * 小程序接口（居民 + 团长）。不走后台登录，用请求头 X-Gb-Token（见 GbAuthInterceptor）。
 * 每个方法都要加 @IgnoreAuth，让 Shiro 放行。
 */
@Slf4j
@Tag(name = "团购-小程序")
@RestController
@RequestMapping("/gb/app")
@RequiredArgsConstructor
public class GbAppController {
    private final GbProperties props;
    private final GbTokenService tokens;
    private final GbMemberMapper memberMapper;
    private final GbPointMapper pointMapper;
    private final GbPeriodMapper periodMapper;
    private final GbPeriodGoodsMapper pgMapper;
    private final GbCouponMapper couponMapper;
    private final GbUserCouponMapper ucMapper;
    private final GbOrderMapper orderMapper;
    private final GbAftersaleMapper asMapper;
    private final GbDeliveryMapper deliveryMapper;
    private final GbCoreService core;
    private final GbViewService view;

    @Value("${jeecg.path.upload:}")
    private String uploadPath;

    private GbMember me(HttpServletRequest req) {
        GbMember m = memberMapper.selectById((String) req.getAttribute(GbAuthInterceptor.ATTR));
        if (m == null) throw fail("账号不存在，请重新登录");
        return m;
    }

    /** 我当团长的提货点（按绑定的用户或手机号） */
    private GbPoint myPoint(GbMember m) {
        return pointMapper.selectOne(new LambdaQueryWrapper<GbPoint>().eq(GbPoint::getStatus, "ok")
                .and(w -> w.eq(GbPoint::getLeaderMemberId, m.getId()).or(m.getPhone() != null, x -> x.eq(GbPoint::getLeaderPhone, m.getPhone()))).last("limit 1"));
    }

    // ================================================================ 登录

    @IgnoreAuth
    @GetMapping("/config")
    public Result<?> config() {
        return Result.OK(Map.of("devLogin", props.isDevLogin(), "payMock", props.isPayMock(), "wxLogin", props.getWx().getAppid() != null && !props.getWx().getAppid().isBlank()));
    }

    /** 微信登录：wx.login 拿到的 code 换 openid */
    @IgnoreAuth
    @PostMapping("/login")
    public Result<?> login(@RequestBody Map<String, String> body) throws Exception {
        String appid = props.getWx().getAppid(), secret = props.getWx().getSecret();
        if (appid == null || appid.isBlank()) return Result.error("还没配置微信小程序 AppID，请先用手机号开发登录");
        String url = "https://api.weixin.qq.com/sns/jscode2session?appid=" + appid + "&secret=" + secret + "&js_code=" + URLEncoder.encode(body.get("code"), StandardCharsets.UTF_8) + "&grant_type=authorization_code";
        HttpResponse<String> r = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
        JSONObject j = JSON.parseObject(r.body());
        String openid = j.getString("openid");
        if (openid == null) { log.warn("[团购] 微信登录失败 {}", r.body()); return Result.error("微信登录失败，请重试"); }
        GbMember m = memberMapper.selectOne(new LambdaQueryWrapper<GbMember>().eq(GbMember::getOpenid, openid));
        if (m == null) { m = new GbMember(); m.setOpenid(openid); m.setNickname("微信用户"); m.setFirstOrderDone(0); memberMapper.insert(m); }
        return Result.OK(Map.of("token", tokens.issue(m.getId()), "member", profile(m)));
    }

    /** 开发登录：输入手机号直接登录（groupbuy.dev-login=true 时可用） */
    @IgnoreAuth
    @PostMapping("/dev-login")
    public Result<?> devLogin(@RequestBody Map<String, String> body) {
        if (!props.isDevLogin()) return Result.error("开发登录已关闭");
        String phone = body.get("phone");
        if (phone == null || !phone.matches("1\\d{10}")) return Result.error("请输入 11 位手机号");
        GbMember m = memberMapper.selectOne(new LambdaQueryWrapper<GbMember>().eq(GbMember::getPhone, phone).last("limit 1"));
        if (m == null) {
            m = new GbMember();
            m.setPhone(phone);
            String nick = body.get("nickname");
            m.setNickname(nick == null || nick.isBlank() ? "用户" + phone.substring(7) : nick);
            m.setFirstOrderDone(0);
            memberMapper.insert(m);
        }
        return Result.OK(Map.of("token", tokens.issue(m.getId()), "member", profile(m)));
    }

    private Map<String, Object> profile(GbMember m) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", m.getId());
        p.put("nickname", m.getNickname());
        p.put("phone", GbViewService.mask(m.getPhone()));
        p.put("rawPhone", m.getPhone());
        p.put("isNew", m.getFirstOrderDone() == null || m.getFirstOrderDone() == 0);
        GbPoint dp = m.getDefaultPointId() == null ? null : pointMapper.selectById(m.getDefaultPointId());
        p.put("defaultPoint", dp != null && "ok".equals(dp.getStatus()) ? pointVo(dp, null, null) : null);
        GbPoint lp = myPoint(m);
        p.put("leaderPoint", lp == null ? null : pointVo(lp, null, null));
        return p;
    }

    @IgnoreAuth
    @GetMapping("/me")
    public Result<?> getMe(HttpServletRequest req) {
        GbMember m = me(req);
        Map<String, Object> p = profile(m);
        p.put("couponCount", ucMapper.selectCount(new LambdaQueryWrapper<GbUserCoupon>().eq(GbUserCoupon::getMemberId, m.getId()).eq(GbUserCoupon::getStatus, "unused").gt(GbUserCoupon::getExpireTime, new Date())));
        List<GbOrder> os = orderMapper.selectList(new LambdaQueryWrapper<GbOrder>().eq(GbOrder::getMemberId, m.getId()));
        Map<String, Long> cnt = os.stream().collect(Collectors.groupingBy(GbOrder::getStatus, Collectors.counting()));
        p.put("orderCounts", Map.of("unpaid", cnt.getOrDefault(S_UNPAID, 0L), "paid", cnt.getOrDefault(S_PAID, 0L), "arrived", cnt.getOrDefault(S_ARRIVED, 0L),
                "refund", os.stream().filter(o -> "applying".equals(o.getAfterStatus())).count(), "all", (long) os.size()));
        // 累计省下：优惠 + 比参考价便宜的部分只算优惠，避免夸大
        p.put("saved", money(os.stream().filter(o -> !S_CLOSED.equals(o.getStatus()) && o.getPayTime() != null).map(GbOrder::getDiscountAmount).reduce(BigDecimal.ZERO, BigDecimal::add)));
        return Result.OK(p);
    }

    @IgnoreAuth
    @PostMapping("/me/update")
    public Result<?> meUpdate(HttpServletRequest req, @RequestBody Map<String, String> body) {
        GbMember m = me(req);
        if (body.containsKey("pointId")) {
            GbPoint p = pointMapper.selectById(body.get("pointId"));
            if (p == null || !"ok".equals(p.getStatus())) return Result.error("这个提货点暂停营业了");
            m.setDefaultPointId(p.getId());
        }
        if (body.get("nickname") != null && !body.get("nickname").isBlank()) m.setNickname(body.get("nickname").trim());
        if (body.get("phone") != null && body.get("phone").matches("1\\d{10}")) m.setPhone(body.get("phone"));
        memberMapper.updateById(m);
        return Result.OK(profile(m));
    }

    // ================================================================ 提货点 / 首页 / 商品

    private Map<String, Object> pointVo(GbPoint p, Double lat, Double lng) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("address", p.getAddress());
        m.put("leaderName", p.getLeaderName());
        m.put("leaderPhone", p.getLeaderPhone());
        m.put("openHours", p.getOpenHours());
        m.put("hasFridge", p.getHasFridge() != null && p.getHasFridge() == 1);
        m.put("lat", p.getLat());
        m.put("lng", p.getLng());
        if (lat != null && lng != null && p.getLat() != null)
            m.put("km", GbAdminController.distanceKm(lat, lng, p.getLat().doubleValue(), p.getLng().doubleValue()));
        return m;
    }

    @IgnoreAuth
    @GetMapping("/points")
    public Result<?> points(@RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng) {
        List<Map<String, Object>> list = pointMapper.selectList(new LambdaQueryWrapper<GbPoint>().eq(GbPoint::getStatus, "ok")).stream().map(p -> pointVo(p, lat, lng))
                .sorted(Comparator.comparing(m -> (Double) m.getOrDefault("km", 0d))).toList();
        return Result.OK(list);
    }

    private Map<String, Object> periodVo(GbPeriod p) {
        if (p == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("openTime", p.getOpenTime());
        m.put("cutoffTime", p.getCutoffTime());
        m.put("pickupTime", p.getPickupTime());
        m.put("pickupEndTime", p.getPickupEndTime());
        m.put("status", p.getStatus());
        m.put("serverTime", new Date());
        return m;
    }

    @IgnoreAuth
    @GetMapping("/home")
    public Result<?> home(HttpServletRequest req) {
        GbMember m = me(req);
        Map<String, Object> out = new LinkedHashMap<>();
        GbPeriod live = core.livePeriod();
        out.put("period", periodVo(live));
        if (live == null) {
            GbPeriod next = periodMapper.selectOne(new LambdaQueryWrapper<GbPeriod>().eq(GbPeriod::getStatus, "plan").orderByAsc(GbPeriod::getOpenTime).last("limit 1"));
            out.put("next", periodVo(next));
            out.put("goods", List.of());
        } else {
            List<Map<String, Object>> goods = view.periodGoods(pgMapper.selectList(new LambdaQueryWrapper<GbPeriodGoods>().eq(GbPeriodGoods::getPeriodId, live.getId()).eq(GbPeriodGoods::getStatus, "on").orderByAsc(GbPeriodGoods::getSortNo)));
            goods.forEach(g -> {
                g.remove("costPrice"); g.remove("margin"); g.remove("supplier");
                // 限购商品带上「我本期已买」，前端加购时就按剩余可买数量限制
                if ((Integer) g.get("limitQty") > 0) g.put("bought", core.boughtQty(m.getId(), (String) g.get("id")));
            });
            out.put("goods", goods);
        }
        out.put("categories", GbAdminController.CATEGORIES);
        // 首页领券条
        Date now = new Date();
        Set<String> mine = ucMapper.selectList(new LambdaQueryWrapper<GbUserCoupon>().eq(GbUserCoupon::getMemberId, m.getId())).stream().map(GbUserCoupon::getCouponId).collect(Collectors.toSet());
        boolean isNew = m.getFirstOrderDone() == null || m.getFirstOrderDone() == 0;
        out.put("coupons", couponMapper.selectList(new LambdaQueryWrapper<GbCoupon>().eq(GbCoupon::getStatus, "on").eq(GbCoupon::getChannel, "home")
                .and(w -> w.isNull(GbCoupon::getValidStart).or().le(GbCoupon::getValidStart, now))
                .and(w -> w.isNull(GbCoupon::getValidEnd).or().ge(GbCoupon::getValidEnd, now))).stream()
                .filter(c -> !"newbie".equals(c.getType()) || isNew)
                .map(c -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("id", c.getId()); x.put("name", c.getName()); x.put("type", c.getType());
                    x.put("amount", c.getAmount()); x.put("rate", c.getRate()); x.put("threshold", c.getThreshold());
                    x.put("desc", couponDesc(c));
                    x.put("received", mine.contains(c.getId()));
                    x.put("soldOut", c.getTotal() > 0 && c.getReceived() >= c.getTotal());
                    x.put("newbieGoodsId", c.getNewbieGoodsId());
                    return x;
                }).filter(x -> !(Boolean) x.get("soldOut") || (Boolean) x.get("received")).toList());
        return Result.OK(out);
    }

    public static String couponDesc(GbCoupon c) {
        String scope = c.getScopeCategory() == null || c.getScopeCategory().isBlank() ? "全部商品" : c.getScopeCategory() + "类";
        String valid = c.getValidDays() != null ? "领取后 " + c.getValidDays() + " 天有效" : (c.getValidEnd() == null ? "" : new java.text.SimpleDateFormat("M/d").format(c.getValidEnd()) + " 前有效");
        return switch (c.getType()) {
            case "full" -> "满 " + c.getThreshold().stripTrailingZeros().toPlainString() + " 元可用 · " + valid;
            case "discount" -> scope + " " + c.getRate().multiply(BigDecimal.TEN).stripTrailingZeros().toPlainString() + " 折" + (c.getMaxDiscount() == null ? "" : "，最多减 " + c.getMaxDiscount().stripTrailingZeros().toPlainString()) + " · " + valid;
            case "newbie" -> "首单 1 元买指定商品 · " + valid;
            default -> valid;
        };
    }

    @IgnoreAuth
    @GetMapping("/goods")
    public Result<?> goods(HttpServletRequest req, @RequestParam String id) {
        GbMember m = me(req);
        GbPeriodGoods pg = pgMapper.selectById(id);
        if (pg == null) return Result.error("商品不存在");
        Map<String, Object> g = view.periodGoods(List.of(pg)).get(0);
        g.remove("costPrice"); g.remove("margin");
        g.put("bought", core.boughtQty(m.getId(), id));
        g.put("period", periodVo(periodMapper.selectById(pg.getPeriodId())));
        return Result.OK(g);
    }

    // ================================================================ 券

    @IgnoreAuth
    @PostMapping("/coupon/receive")
    public Result<?> receive(HttpServletRequest req, @RequestBody Map<String, String> body) {
        core.receiveCoupon(me(req).getId(), body.get("couponId"));
        return Result.OK("领取成功，下单时自动使用");
    }

    @IgnoreAuth
    @GetMapping("/coupon/mine")
    public Result<?> myCoupons(HttpServletRequest req) {
        GbMember m = me(req);
        List<GbUserCoupon> list = ucMapper.selectList(new LambdaQueryWrapper<GbUserCoupon>().eq(GbUserCoupon::getMemberId, m.getId()).orderByAsc(GbUserCoupon::getStatus).orderByAsc(GbUserCoupon::getExpireTime));
        Map<String, GbCoupon> defs = list.isEmpty() ? Map.of() : couponMapper.selectBatchIds(list.stream().map(GbUserCoupon::getCouponId).collect(Collectors.toSet())).stream().collect(Collectors.toMap(GbCoupon::getId, c -> c));
        Date now = new Date();
        return Result.OK(list.stream().map(u -> {
            GbCoupon c = defs.get(u.getCouponId());
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", u.getId()); x.put("name", c.getName()); x.put("type", c.getType()); x.put("amount", c.getAmount()); x.put("rate", c.getRate());
            x.put("desc", couponDesc(c)); x.put("expireTime", u.getExpireTime());
            x.put("status", "unused".equals(u.getStatus()) && u.getExpireTime().before(now) ? "expired" : u.getStatus());
            return x;
        }).toList());
    }

    // ================================================================ 下单

    private List<Map<String, Object>> items(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("items");
    }

    /** 结算预览：金额、可用券、自动选的券 */
    @IgnoreAuth
    @PostMapping("/order/preview")
    public Result<?> preview(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        GbMember m = me(req);
        GbPeriod live = core.livePeriod();
        if (live == null) return Result.error("本期团已截单，明天再来");
        Calc c = core.calc(m, live, items(body), (String) body.get("userCouponId"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("goodsAmount", c.goodsAmount());
        out.put("discount", c.discount());
        out.put("pay", c.pay());
        out.put("userCouponId", c.coupon() == null ? null : c.coupon().getId());
        out.put("couponName", c.couponDef() == null ? null : c.couponDef().getName());
        out.put("coupons", c.couponOptions());
        out.put("period", periodVo(live));
        out.put("lines", c.lines().stream().map(l -> Map.of("periodGoodsId", l.pg().getId(), "name", l.goods().getName(), "spec", l.goods().getSpec(),
                "image", Objects.toString(firstImage(l.goods().getImages()), ""), "price", l.pg().getPrice(), "qty", l.qty(), "left", l.pg().getStock() - l.pg().getSold())).toList());
        return Result.OK(out);
    }

    @IgnoreAuth
    @PostMapping("/order/create")
    public Result<?> create(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        GbMember m = me(req);
        GbOrder o = core.createOrder(m, (String) body.get("pointId"), items(body), (String) body.get("userCouponId"), (String) body.get("remark"),
                (String) body.get("buyerName"), (String) body.get("buyerPhone"));
        if (m.getDefaultPointId() == null || !m.getDefaultPointId().equals(o.getPointId()))
            memberMapper.update(null, new LambdaUpdateWrapper<GbMember>().eq(GbMember::getId, m.getId()).set(GbMember::getDefaultPointId, o.getPointId()));
        return Result.OK(Map.of("id", o.getId(), "payAmount", o.getPayAmount(), "payExpireTime", o.getPayExpireTime(), "payMock", props.isPayMock()));
    }

    /** 支付。模拟支付直接成功；接入微信支付后这里改成返回 wx.requestPayment 的参数 */
    @IgnoreAuth
    @PostMapping("/order/pay")
    public Result<?> pay(HttpServletRequest req, @RequestBody Map<String, String> body) {
        GbOrder o = core.pay(me(req).getId(), body.get("id"));
        return Result.OK(view.orderDetail(o, false, true));
    }

    @IgnoreAuth
    @PostMapping("/order/cancel")
    public Result<?> cancel(HttpServletRequest req, @RequestBody Map<String, String> body) {
        core.cancelByMember(me(req).getId(), body.get("id"));
        return Result.OK("已取消，钱会原路退回");
    }

    @IgnoreAuth
    @GetMapping("/order/list")
    public Result<?> orders(HttpServletRequest req, @RequestParam(required = false) String status) {
        GbMember m = me(req);
        LambdaQueryWrapper<GbOrder> q = new LambdaQueryWrapper<GbOrder>().eq(GbOrder::getMemberId, m.getId()).orderByDesc(GbOrder::getCreateTime).last("limit 50");
        if ("refund".equals(status)) q.ne(GbOrder::getAfterStatus, "none");
        else if (status != null && !status.isBlank()) q.eq(GbOrder::getStatus, status);
        return Result.OK(view.orders(orderMapper.selectList(q), false, true));
    }

    @IgnoreAuth
    @GetMapping("/order/detail")
    public Result<?> detail(HttpServletRequest req, @RequestParam String id) {
        GbOrder o = orderMapper.selectById(id);
        if (o == null || !o.getMemberId().equals(me(req).getId())) return Result.error("订单不存在");
        return Result.OK(view.orderDetail(o, false, true));
    }

    @IgnoreAuth
    @PostMapping("/aftersale/apply")
    public Result<?> applyAs(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        List<String> ids = ((List<Object>) body.getOrDefault("itemIds", List.of())).stream().map(String::valueOf).toList();
        GbAftersale a = core.applyAftersale(me(req).getId(), (String) body.get("orderId"), ids, (String) body.get("type"), (String) body.get("reason"), (String) body.get("images"));
        return Result.OK(Map.of("id", a.getId(), "applyAmount", a.getApplyAmount(), "deadline", a.getDeadline()));
    }

    /** 售后照片上传：只收图片、最大 5MB，存到上传目录 gb/as/日期/ 下，返回相对路径 */
    @IgnoreAuth
    @PostMapping("/upload")
    public Result<?> upload(HttpServletRequest req, @RequestParam("file") MultipartFile file) throws Exception {
        me(req);
        if (file == null || file.isEmpty()) return Result.error("没有收到图片");
        if (file.getSize() > 5 * 1024 * 1024) return Result.error("图片太大了，请小于 5MB");
        String name = Objects.toString(file.getOriginalFilename(), "").toLowerCase();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "jpg";
        if (!List.of("jpg", "jpeg", "png", "webp", "gif").contains(ext)) return Result.error("只能上传图片");
        String day = new java.text.SimpleDateFormat("yyyyMMdd").format(new Date());
        String rel = "gb/as/" + day + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
        java.nio.file.Path target = java.nio.file.Paths.get(uploadPath, rel);
        java.nio.file.Files.createDirectories(target.getParent());
        file.transferTo(target.toFile());
        return Result.OK(Map.of("path", rel));
    }

    // ================================================================ 团长

    private GbPoint mustLeader(HttpServletRequest req) {
        GbPoint p = myPoint(me(req));
        if (p == null) throw fail("你还不是团长");
        return p;
    }

    /** 团长工作台：正在自提的团期进度 + 到货签收 + 本期预售 */
    @IgnoreAuth
    @GetMapping("/leader/dashboard")
    public Result<?> leaderDash(HttpServletRequest req) {
        GbPoint pt = mustLeader(req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("point", pointVo(pt, null, null));
        // 正在提货的团期：有待提货或配送中订单的最近一期
        List<GbDelivery> ds = deliveryMapper.selectList(new LambdaQueryWrapper<GbDelivery>().eq(GbDelivery::getPointId, pt.getId()).orderByDesc(GbDelivery::getCreateTime).last("limit 3"));
        Map<String, GbPeriod> pm = view.periodMap(ds.stream().map(GbDelivery::getPeriodId).toList());
        List<Map<String, Object>> batches = new ArrayList<>();
        for (GbDelivery d : ds) {
            GbPeriod p = pm.get(d.getPeriodId());
            if (p == null || "done".equals(p.getStatus())) continue;
            List<GbOrder> os = orderMapper.selectList(new LambdaQueryWrapper<GbOrder>().eq(GbOrder::getPeriodId, p.getId()).eq(GbOrder::getPointId, pt.getId()).notIn(GbOrder::getStatus, S_UNPAID, S_CLOSED));
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("period", periodVo(p));
            b.put("delivery", d);
            b.put("total", os.size());
            b.put("picked", os.stream().filter(o -> S_DONE.equals(o.getStatus())).count());
            b.put("waiting", os.stream().filter(o -> S_ARRIVED.equals(o.getStatus())).count());
            batches.add(b);
        }
        out.put("batches", batches);
        GbPeriod live = core.livePeriod();
        if (live != null) {
            List<GbOrder> os = orderMapper.selectList(new LambdaQueryWrapper<GbOrder>().eq(GbOrder::getPeriodId, live.getId()).eq(GbOrder::getPointId, pt.getId()).notIn(GbOrder::getStatus, S_UNPAID, S_CLOSED));
            out.put("presale", Map.of("period", periodVo(live), "orders", os.size(),
                    "qty", view.itemsOf(os.stream().map(GbOrder::getId).toList()).values().stream().flatMap(List::stream).mapToInt(GbOrderItem::getQty).sum(),
                    "gmv", money(os.stream().map(GbOrder::getGoodsAmount).reduce(BigDecimal.ZERO, BigDecimal::add))));
        }
        return Result.OK(out);
    }

    /** 待提货订单，可按手机尾号 / 提货码搜 */
    @IgnoreAuth
    @GetMapping("/leader/orders")
    public Result<?> leaderOrders(HttpServletRequest req, @RequestParam(required = false) String keyword) {
        GbPoint pt = mustLeader(req);
        List<GbOrder> os = orderMapper.selectList(new LambdaQueryWrapper<GbOrder>().eq(GbOrder::getPointId, pt.getId()).eq(GbOrder::getStatus, S_ARRIVED)
                .and(keyword != null && !keyword.isBlank(), w -> w.likeLeft(GbOrder::getBuyerPhone, keyword).or().eq(GbOrder::getPickupCode, keyword))
                .orderByAsc(GbOrder::getPayTime).last("limit 100"));
        List<Map<String, Object>> list = view.orders(os, true, false);
        long now = System.currentTimeMillis();
        for (Map<String, Object> o : list) {
            Date pick = (Date) o.get("pickupTime");
            o.put("late", pick != null && now - pick.getTime() > 24 * 3600_000L);
        }
        return Result.OK(list);
    }

    /** 扫码 / 输入提货码查订单 */
    @IgnoreAuth
    @GetMapping("/leader/verify/query")
    public Result<?> verifyQuery(HttpServletRequest req, @RequestParam(required = false) String code, @RequestParam(required = false) String orderId) {
        GbPoint pt = mustLeader(req);
        GbOrder o = orderId != null && !orderId.isBlank() ? orderMapper.selectById(orderId)
                : orderMapper.selectOne(new LambdaQueryWrapper<GbOrder>().eq(GbOrder::getPointId, pt.getId()).eq(GbOrder::getPickupCode, code).ne(GbOrder::getStatus, S_CLOSED).orderByDesc(GbOrder::getCreateTime).last("limit 1"));
        if (o == null || !o.getPointId().equals(pt.getId())) return Result.error("没找到这个提货码，确认是本提货点的订单");
        return Result.OK(view.orderDetail(o, true, true));
    }

    @IgnoreAuth
    @PostMapping("/leader/verify")
    public Result<?> verify(HttpServletRequest req, @RequestBody Map<String, String> body) {
        core.verify(mustLeader(req).getId(), body.get("orderId"));
        return Result.OK("交货完成");
    }

    @IgnoreAuth
    @PostMapping("/leader/remind")
    public Result<?> remind(HttpServletRequest req, @RequestBody Map<String, String> body) {
        GbPoint pt = mustLeader(req);
        GbOrder o = orderMapper.selectById(body.get("orderId"));
        if (o == null || !o.getPointId().equals(pt.getId()) || !S_ARRIVED.equals(o.getStatus())) return Result.error("只有待提货的订单可以提醒");
        orderMapper.update(null, new LambdaUpdateWrapper<GbOrder>().eq(GbOrder::getId, o.getId()).setSql("remind_count = remind_count + 1"));
        return Result.OK("已给顾客发取货提醒");
    }

    @IgnoreAuth
    @PostMapping("/leader/sign")
    public Result<?> sign(HttpServletRequest req, @RequestBody Map<String, String> body) {
        GbPoint pt = mustLeader(req);
        GbDelivery d = deliveryMapper.selectById(body.get("deliveryId"));
        if (d == null || !d.getPointId().equals(pt.getId())) return Result.error("不是本提货点的配送单");
        core.sign(d.getId(), body.get("shortNote"));
        return Result.OK("已签收，顾客会收到到货通知");
    }
}
