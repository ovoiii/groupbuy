package org.jeecg.modules.groupbuy.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import org.jeecg.modules.groupbuy.service.GbCommissionService;
import org.jeecg.modules.groupbuy.service.GbCoreService;
import org.jeecg.modules.groupbuy.service.GbLoginService;
import org.jeecg.modules.groupbuy.service.GbPayService;
import org.jeecg.modules.groupbuy.service.GbPosterService;
import org.jeecg.modules.groupbuy.service.GbSmsService;
import org.jeecg.modules.groupbuy.service.GbTransferService;
import org.jeecg.common.util.IpUtils;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.jeecg.modules.groupbuy.service.GbWxService;
import org.springframework.http.ResponseEntity;
import org.jeecg.modules.groupbuy.service.GbNotifyService;
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
    private final GbCommissionService commission;
    private final GbCommissionMapper commMapper;
    private final GbWithdrawMapper wdMapper;
    private final GbLeaderDeductMapper dedMapper;
    private final GbViewService view;
    private final GbNotifyService notify;
    private final GbPayService pay;
    private final GbWxService wx;
    private final GbLoginService login;
    private final GbSmsService sms;
    private final GbPosterService poster;
    private final GbTransferService transfer;

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
        return Result.OK(Map.of("devLogin", props.isDevLogin(), "payMock", props.isPayMock(), "wxLogin", props.getWx().getAppid() != null && !props.getWx().getAppid().isBlank(),
                "smsLogin", sms.enabled(), "tmpl", notify.templates()));
    }

    /** 用户在订阅框里点了「允许」的模板，每个多 1 次发送机会 */
    @IgnoreAuth
    @PostMapping("/notify/subscribe")
    public Result<?> subscribe(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        Object ids = body.get("ids");
        if (!(ids instanceof List<?> l)) return Result.error("参数不对");
        return Result.OK(notify.subscribe(me(req).getId(), l.stream().map(String::valueOf).toList()));
    }

    /**
     * 微信登录第一步：wx.login 的 code 换 openid。这个微信绑过手机号就直接登录；
     * 没绑过返回 needPhone + 票据，小程序再让用户点「手机号快捷登录」调 /login/phone。
     */
    @IgnoreAuth
    @PostMapping("/login")
    public Result<?> login(@RequestBody Map<String, String> body) {
        String openid;
        try {
            openid = wx.openid(body.get("code"));
        } catch (IllegalStateException e) {
            log.warn("[团购] 微信登录出错：{}", e.getMessage());
            return Result.error("微信服务暂时不可用，请稍后再试");
        }
        GbMember m = login.byOpenid(openid);
        if (m != null) return Result.OK(Map.of("token", tokens.issue(m.getId()), "member", profile(m)));
        // 小程序「手机号快捷登录」按钮会同时带上手机号 code：老用户不用（不去换手机号，省一次收费调用），新用户直接注册
        String phoneCode = body.get("phoneCode");
        if (phoneCode == null || phoneCode.isBlank()) return Result.OK(Map.of("needPhone", true, "ticket", tokens.issueTicket(openid)));
        String phone;
        try {
            phone = wx.phone(phoneCode);
        } catch (IllegalStateException e) {
            log.warn("[团购] 获取手机号出错：{}", e.getMessage());
            return Result.error("微信服务暂时不可用，请稍后再试");
        }
        m = login.bind(openid, phone, body.get("pt"));
        return Result.OK(Map.of("token", tokens.issue(m.getId()), "member", profile(m)));
    }

    /** 微信登录第二步：票据 + 手机号快速验证组件给的 code → 按手机号找到或新建账号 */
    @IgnoreAuth
    @PostMapping("/login/phone")
    public Result<?> loginPhone(@RequestBody Map<String, String> body) {
        String openid = tokens.verifyTicket(body.get("ticket"));
        if (openid == null) return Result.error(410, "登录超时了，请再点一次");
        String phone;
        try {
            phone = wx.phone(body.get("code"));
        } catch (IllegalStateException e) {
            log.warn("[团购] 获取手机号出错：{}", e.getMessage());
            return Result.error("微信服务暂时不可用，请稍后再试");
        }
        GbMember m = login.bind(openid, phone, body.get("pt"));
        return Result.OK(Map.of("token", tokens.issue(m.getId()), "member", profile(m)));
    }

    /** 发短信验证码（防刷规则见 GbSmsService） */
    @IgnoreAuth
    @PostMapping("/sms/send")
    public Result<?> smsSend(HttpServletRequest req, @RequestBody Map<String, String> body) {
        return Result.OK("验证码已发送", sms.send(body.get("phone"), IpUtils.getIpAddr(req)));
    }

    /**
     * 短信验证码登录。小程序里会同时带上 wx.login 的 code：把这个微信绑到手机号对应的账号上（和手机号快捷登录一样），付款要用；
     * H5 等没有微信的地方只按手机号登录。
     */
    @IgnoreAuth
    @PostMapping("/login/sms")
    public Result<?> loginSms(@RequestBody Map<String, String> body) {
        String phone = body.get("phone") == null ? null : body.get("phone").trim();
        if (phone == null || !phone.matches("1\\d{10}")) return Result.error("请输入 11 位手机号");
        // 先换 openid 再核验证码：微信出错时验证码不会白白作废
        String openid = null, wxCode = body.get("wxCode");
        if (wxCode != null && !wxCode.isBlank() && wx.ready()) {
            try {
                openid = wx.openid(wxCode);
            } catch (IllegalStateException e) {
                log.warn("[团购] 微信登录出错：{}", e.getMessage());
                return Result.error("微信服务暂时不可用，请稍后再试");
            }
        }
        sms.verify(phone, body.get("code"));
        GbMember m = openid != null ? login.bind(openid, phone, body.get("pt")) : login.byPhone(phone, body.get("pt"));
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
        if (m == null) m = login.create(phone, null, body.get("nickname"), body.get("pt"));
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
        GbPoint ap = lp == null ? latestApply(m) : null;
        p.put("applyStatus", ap == null ? null : ap.getStatus());
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

    // ================================================================ 分享海报

    /** 分享海报 PNG：type=goods（id = 团品 id）或 home（本期团）；pt = 分享人带的提货点 */
    @IgnoreAuth
    @GetMapping("/poster")
    public ResponseEntity<byte[]> poster(HttpServletRequest req, @RequestParam(defaultValue = "goods") String type, @RequestParam(required = false) String id,
                                         @RequestParam(required = false) String pt) {
        me(req);
        try {
            byte[] png = "home".equals(type) ? poster.homePoster(pt) : poster.goodsPoster(id, pt);
            return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore()).body(png);
        } catch (org.jeecg.common.exception.JeecgBootException e) {
            // 小程序用 downloadFile 拿图，出错时给个非 200 状态码，它才知道不是图片
            return ResponseEntity.badRequest().contentType(new MediaType("text", "plain", StandardCharsets.UTF_8)).body(e.getMessage().getBytes(StandardCharsets.UTF_8));
        }
    }

    /** 小程序码 scene → 商品 / 提货点（小程序端自己也能解析，这里给 H5 和排查用） */
    @IgnoreAuth
    @GetMapping("/scene")
    public Result<?> scene(@RequestParam String s) {
        Map<String, String> m = GbPosterService.parseScene(s);
        return m == null ? Result.error("二维码参数不对") : Result.OK(m);
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

    /**
     * 付款。0 元单、模拟支付直接成功，返回 {paid: true}；
     * 微信支付返回 {paid: false, payParams}，小程序拿去调 uni.requestPayment，付完再调 /order/pay/check 确认。
     */
    @IgnoreAuth
    @PostMapping("/order/pay")
    public Result<?> pay(HttpServletRequest req, @RequestBody Map<String, String> body) {
        GbMember m = me(req);
        GbOrder o = core.payable(m.getId(), body.get("id"));
        if (o.getPayAmount().signum() <= 0) {
            core.payDirect(o, "free");
            return Result.OK(Map.of("paid", true));
        }
        if (!pay.real()) {
            core.payDirect(o, "mock");
            return Result.OK(Map.of("paid", true));
        }
        if (m.getOpenid() == null || m.getOpenid().isBlank()) return Result.error("请先退出，用微信登录后再付款");
        Map<String, Object> params = pay.prepay(o, m.getOpenid());
        if (Boolean.TRUE.equals(params.get("paid"))) return Result.OK(Map.of("paid", true));
        return Result.OK(Map.of("paid", false, "payParams", params));
    }

    /** 付完款确认：问一下微信（不等支付回调），返回订单现在是不是已付款 */
    @IgnoreAuth
    @PostMapping("/order/pay/check")
    public Result<?> payCheck(HttpServletRequest req, @RequestBody Map<String, String> body) {
        GbOrder o = orderMapper.selectById(body.get("id"));
        if (o == null || !o.getMemberId().equals(me(req).getId())) return Result.error("订单不存在");
        if (S_UNPAID.equals(o.getStatus())) {
            pay.sync(o);
            o = orderMapper.selectById(o.getId());
        }
        return Result.OK(Map.of("paid", !S_UNPAID.equals(o.getStatus()) && !S_CLOSED.equals(o.getStatus()), "status", o.getStatus()));
    }

    /** 微信支付结果通知（不要登录，靠验签）。成功返回 200 空，失败返回 4xx/5xx 让微信重发 */
    @IgnoreAuth
    @PostMapping("/pay/notify")
    public ResponseEntity<?> payNotify(HttpServletRequest req, @RequestBody String body) {
        return wxNotify(req, body, true);
    }

    /** 微信退款结果通知 */
    @IgnoreAuth
    @PostMapping("/pay/refund-notify")
    public ResponseEntity<?> refundNotify(HttpServletRequest req, @RequestBody String body) {
        return wxNotify(req, body, false);
    }

    /** 微信商家转账结果通知（团长提现到零钱） */
    @IgnoreAuth
    @PostMapping("/pay/transfer-notify")
    public ResponseEntity<?> transferNotify(HttpServletRequest req, @RequestBody String body) {
        com.wechat.pay.java.core.notification.RequestParam p = new com.wechat.pay.java.core.notification.RequestParam.Builder().serialNumber(req.getHeader("Wechatpay-Serial")).nonce(req.getHeader("Wechatpay-Nonce"))
                .signature(req.getHeader("Wechatpay-Signature")).timestamp(req.getHeader("Wechatpay-Timestamp"))
                .signType(req.getHeader("Wechatpay-Signature-Type")).body(body).build();
        try {
            transfer.handleNotify(p);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            boolean sign = GbPayService.isSignError(e);
            log.warn("[团购] 微信转账通知处理失败：{}", e.getMessage());
            return ResponseEntity.status(sign ? 401 : 500).body(Map.of("code", "FAIL", "message", sign ? "签名验证失败" : "处理失败"));
        }
    }

    private ResponseEntity<?> wxNotify(HttpServletRequest req, String body, boolean payment) {
        com.wechat.pay.java.core.notification.RequestParam p = new com.wechat.pay.java.core.notification.RequestParam.Builder().serialNumber(req.getHeader("Wechatpay-Serial")).nonce(req.getHeader("Wechatpay-Nonce"))
                .signature(req.getHeader("Wechatpay-Signature")).timestamp(req.getHeader("Wechatpay-Timestamp"))
                .signType(req.getHeader("Wechatpay-Signature-Type")).body(body).build();
        try {
            if (payment) pay.handlePayNotify(p);
            else pay.handleRefundNotify(p);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            boolean sign = GbPayService.isSignError(e);
            log.warn("[团购] 微信{}通知处理失败：{}", payment ? "支付" : "退款", e.getMessage());
            return ResponseEntity.status(sign ? 401 : 500).body(Map.of("code", "FAIL", "message", sign ? "签名验证失败" : "处理失败"));
        }
    }

    @IgnoreAuth
    @PostMapping("/order/cancel")
    public Result<?> cancel(HttpServletRequest req, @RequestBody Map<String, String> body) {
        GbOrder o = orderMapper.selectById(body.get("id"));
        // 向微信下过单的待付款订单：先问微信付没付，付了就按已付款取消（原路退款）
        if (o != null && S_UNPAID.equals(o.getStatus()) && GbPayService.PENDING.equals(pay.sync(o))) return Result.error("这单正在付款中，稍后再试");
        core.cancelByMember(me(req).getId(), body.get("id"));
        GbOrder after = orderMapper.selectById(body.get("id"));
        return Result.OK(after != null && after.getPayTime() != null ? "已取消，钱会原路退回" : "已取消");
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
        // 通过这个提货点的分享注册的新用户
        out.put("newcomers", memberMapper.selectCount(new LambdaQueryWrapper<GbMember>().eq(GbMember::getSourcePointId, pt.getId())));
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
        GbLeaderAccount acc = commission.account(pt.getId());
        out.put("income", Map.of("balance", acc.getBalance(), "estimate", money(commission.estimate(pt.getId()).getOrDefault(pt.getId(), BigDecimal.ZERO))));
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
        notify.pickup(o);
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

    // ================================================================ 团长佣金

    /** 我的佣金：可提现、审核中、待结算预估、累计；审核中的那笔提现；上次的收款账户（预填用） */
    @IgnoreAuth
    @GetMapping("/leader/income")
    public Result<?> leaderIncome(HttpServletRequest req) {
        GbPoint pt = mustLeader(req);
        GbLeaderAccount a = commission.account(pt.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pointName", pt.getName());
        out.put("rate", pt.getCommissionRate());
        out.put("balance", a.getBalance());
        out.put("frozen", a.getFrozen());
        out.put("totalIncome", a.getTotalIncome());
        out.put("totalWithdrawn", a.getTotalWithdrawn());
        out.put("totalDeducted", a.getTotalDeducted());
        out.put("estimate", money(commission.estimate(pt.getId()).getOrDefault(pt.getId(), BigDecimal.ZERO)));
        out.put("withdrawMin", props.getWithdrawMin());
        out.put("settleHours", props.getAftersaleWindowHours());
        GbWithdraw p = commission.pendingWithdraw(pt.getId());
        out.put("pending", p == null ? null : withdrawVo(p));
        GbWithdraw last = wdMapper.selectOne(new LambdaQueryWrapper<GbWithdraw>().eq(GbWithdraw::getPointId, pt.getId()).orderByDesc(GbWithdraw::getCreateTime).last("limit 1"));
        if (last != null) {
            Map<String, Object> acc = new LinkedHashMap<>();
            acc.put("method", last.getMethod());
            acc.put("accountName", last.getAccountName());
            acc.put("accountNo", last.getAccountNo());
            acc.put("bankName", last.getBankName());
            out.put("lastAccount", acc);
        }
        return Result.OK(out);
    }

    private Map<String, Object> withdrawVo(GbWithdraw w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", w.getId());
        m.put("wdNo", w.getWdNo());
        m.put("amount", w.getAmount());
        m.put("method", w.getMethod());
        m.put("account", "bank".equals(w.getMethod()) ? w.getBankName() + " " + GbCommissionService.maskCard(w.getAccountNo()) : "微信零钱");
        m.put("accountName", w.getAccountName());
        m.put("status", w.getStatus());
        m.put("reply", w.getReply());
        // 打款中：等团长确认收款时小程序显示「确认收款」按钮
        m.put("needConfirm", "paying".equals(w.getStatus()) && "WAIT_USER_CONFIRM".equals(w.getTransferState()) && w.getPackageInfo() != null);
        m.put("transferFail", "wait".equals(w.getStatus()) ? w.getTransferFail() : null);
        m.put("createTime", w.getCreateTime());
        m.put("handleTime", w.getHandleTime());
        return m;
    }

    /** 佣金入账明细（type=in）、提现记录（type=out）或售后扣款（type=ded），分页 */
    @IgnoreAuth
    @GetMapping("/leader/income/list")
    public Result<?> leaderIncomeList(HttpServletRequest req, @RequestParam(defaultValue = "in") String type, @RequestParam(defaultValue = "1") int pageNo) {
        GbPoint pt = mustLeader(req);
        if ("out".equals(type)) {
            Page<GbWithdraw> pg = wdMapper.selectPage(new Page<>(pageNo, 20), new LambdaQueryWrapper<GbWithdraw>().eq(GbWithdraw::getPointId, pt.getId()).orderByDesc(GbWithdraw::getCreateTime));
            return Result.OK(Map.of("records", pg.getRecords().stream().map(this::withdrawVo).toList(), "total", pg.getTotal()));
        }
        if ("ded".equals(type)) {
            Page<GbLeaderDeduct> pg = dedMapper.selectPage(new Page<>(pageNo, 20), new LambdaQueryWrapper<GbLeaderDeduct>().eq(GbLeaderDeduct::getPointId, pt.getId()).orderByDesc(GbLeaderDeduct::getCreateTime));
            List<Map<String, Object>> rows = pg.getRecords().stream().map(d -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", d.getId());
                m.put("orderNo", d.getOrderNo());
                m.put("buyerName", d.getBuyerName());
                m.put("asType", d.getAsType());
                m.put("refundAmount", d.getRefundAmount());
                m.put("amount", d.getAmount());
                m.put("reason", d.getReason());
                m.put("status", d.getStatus());
                m.put("revokeReason", d.getRevokeReason());
                m.put("createTime", d.getCreateTime());
                return m;
            }).toList();
            return Result.OK(Map.of("records", rows, "total", pg.getTotal()));
        }
        Page<GbCommission> pg = commMapper.selectPage(new Page<>(pageNo, 20), new LambdaQueryWrapper<GbCommission>().eq(GbCommission::getPointId, pt.getId())
                .orderByDesc(GbCommission::getSettleTime).orderByDesc(GbCommission::getPickupAt));
        List<Map<String, Object>> rows = pg.getRecords().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("orderNo", c.getOrderNo());
            m.put("buyerName", c.getBuyerName());
            m.put("goodsAmount", c.getGoodsAmount());
            m.put("baseAmount", c.getBaseAmount());
            m.put("refunded", c.getRefundAmount() != null && c.getRefundAmount().signum() > 0);
            m.put("rate", c.getRate());
            m.put("amount", c.getAmount());
            m.put("settleTime", c.getSettleTime());
            return m;
        }).toList();
        return Result.OK(Map.of("records", rows, "total", pg.getTotal()));
    }

    @IgnoreAuth
    @PostMapping("/leader/withdraw")
    public Result<?> leaderWithdraw(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        GbMember m = me(req);
        GbPoint pt = myPoint(m);
        if (pt == null) return Result.error("你还不是团长");
        BigDecimal amount;
        try {
            amount = new BigDecimal(String.valueOf(body.get("amount")).trim());
        } catch (Exception e) {
            return Result.error("提现金额填得不对");
        }
        GbWithdraw w = commission.apply(pt, m.getId(), amount, (String) body.get("method"), (String) body.get("accountName"), (String) body.get("accountNo"), (String) body.get("bankName"));
        return Result.OK("已提交，1–3 个工作日到账", withdrawVo(w));
    }

    /** 团长确认收款要的参数（wx.requestMerchantTransfer）：只能是自己提货点、正在等确认的那笔 */
    @IgnoreAuth
    @GetMapping("/leader/withdraw/confirm")
    public Result<?> withdrawConfirmParams(HttpServletRequest req, @RequestParam String id) {
        GbPoint pt = mustLeader(req);
        GbWithdraw w = wdMapper.selectById(id);
        if (w == null || !pt.getId().equals(w.getPointId())) return Result.error("提现单不存在");
        if (!"paying".equals(w.getStatus()) || !"WAIT_USER_CONFIRM".equals(w.getTransferState()) || w.getPackageInfo() == null)
            return Result.error("这笔现在不用确认收款");
        return Result.OK(Map.of("mchId", props.getPay().getMchId(), "appId", props.getWx().getAppid(), "package", w.getPackageInfo()));
    }

    /** 确认收款后（或下拉刷新时）去微信查一下这笔转账 */
    @IgnoreAuth
    @PostMapping("/leader/withdraw/check")
    public Result<?> withdrawCheck(HttpServletRequest req, @RequestBody Map<String, String> body) {
        GbPoint pt = mustLeader(req);
        GbWithdraw w = wdMapper.selectById(body.get("id"));
        if (w == null || !pt.getId().equals(w.getPointId())) return Result.error("提现单不存在");
        return Result.OK(withdrawVo(transfer.sync(w.getId())));
    }

    // ================================================================ 团长入驻申请

    /** 我最近一次的入驻申请（没有返回 null）；已经是团长时 isLeader=true */
    @IgnoreAuth
    @GetMapping("/leader/apply")
    public Result<?> myApply(HttpServletRequest req) {
        GbMember m = me(req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("isLeader", myPoint(m) != null);
        GbPoint a = latestApply(m);
        out.put("apply", a == null ? null : applyVo(a));
        out.put("phone", m.getPhone());
        out.put("nickname", m.getNickname());
        return Result.OK(out);
    }

    private GbPoint latestApply(GbMember m) {
        return pointMapper.selectOne(new LambdaQueryWrapper<GbPoint>().eq(GbPoint::getLeaderMemberId, m.getId()).orderByDesc(GbPoint::getCreateTime).last("limit 1"));
    }

    private Map<String, Object> applyVo(GbPoint p) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", p.getId());
        v.put("status", p.getStatus());
        v.put("statusNote", p.getStatusNote());
        v.put("name", p.getName());
        v.put("address", p.getAddress());
        v.put("lat", p.getLat());
        v.put("lng", p.getLng());
        v.put("leaderName", p.getLeaderName());
        v.put("leaderPhone", p.getLeaderPhone());
        v.put("households", p.getHouseholds());
        v.put("hasFridge", p.getHasFridge() != null && p.getHasFridge() == 1);
        v.put("openHours", p.getOpenHours());
        v.put("applyNote", p.getApplyNote());
        v.put("createTime", p.getCreateTime());
        v.put("updateTime", p.getUpdateTime());
        return v;
    }

    /** 提交入驻申请。被拒后可以改了重新提交（覆盖原申请，回到待审核） */
    @IgnoreAuth
    @PostMapping("/leader/apply")
    public Result<?> apply(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        GbMember m = me(req);
        if (myPoint(m) != null) return Result.error("你已经是团长了");
        GbPoint last = latestApply(m);
        if (last != null && "pending".equals(last.getStatus())) return Result.error("申请正在审核，运营会尽快联系你");
        if (last != null && List.of("ok", "paused").contains(last.getStatus())) return Result.error("你已经有提货点了，有问题请联系运营");

        String name = str(body.get("name")), address = str(body.get("address")), leaderName = str(body.get("leaderName")),
                phone = str(body.get("leaderPhone")), openHours = str(body.get("openHours")), note = str(body.get("applyNote"));
        if (name.isEmpty() || name.length() > 32) return Result.error("提货点名称要填，32 个字以内，如「幸福里 3 号楼便利店」");
        if (address.length() < 5 || address.length() > 100) return Result.error("把地址写清楚，到楼栋门牌");
        if (leaderName.isEmpty() || leaderName.length() > 16) return Result.error("填一下你的真实姓名");
        if (!phone.matches("1\\d{10}")) return Result.error("手机号是 11 位数字");
        if (!openHours.isEmpty() && !openHours.matches("([01]\\d|2[0-3]):[0-5]\\d-([01]\\d|2[0-3]):[0-5]\\d")) return Result.error("营业时间格式像 08:00-21:00");
        if (note.length() > 200) return Result.error("补充说明 200 个字以内");
        int households;
        try {
            households = body.get("households") == null || str(body.get("households")).isEmpty() ? 0 : Integer.parseInt(str(body.get("households")));
        } catch (NumberFormatException e) {
            return Result.error("覆盖户数填数字");
        }
        if (households < 0 || households > 100000) return Result.error("覆盖户数填得不对");
        BigDecimal lat = dec(body.get("lat")), lng = dec(body.get("lng"));
        if (lat != null && (lat.abs().compareTo(BigDecimal.valueOf(90)) > 0 || lng == null || lng.abs().compareTo(BigDecimal.valueOf(180)) > 0)) { lat = null; lng = null; }
        // 这个手机号已经是别的提货点的团长 / 正在申请
        long used = pointMapper.selectCount(new LambdaQueryWrapper<GbPoint>().eq(GbPoint::getLeaderPhone, phone).in(GbPoint::getStatus, "ok", "paused", "pending")
                .ne(last != null, GbPoint::getId, last == null ? "" : last.getId()));
        if (used > 0) return Result.error("这个手机号已经登记过提货点了，有问题请联系运营");

        GbPoint p = last != null ? last : new GbPoint();
        p.setName(name);
        p.setAddress(address);
        p.setLat(lat);
        p.setLng(lng);
        p.setLeaderName(leaderName);
        p.setLeaderPhone(phone);
        p.setLeaderMemberId(m.getId());
        p.setHouseholds(households);
        p.setHasFridge(Boolean.TRUE.equals(body.get("hasFridge")) || "1".equals(str(body.get("hasFridge"))) ? 1 : 0);
        p.setOpenHours(openHours.isEmpty() ? "08:00-21:00" : openHours);
        p.setApplyNote(note.isEmpty() ? null : note);
        p.setStatus("pending");
        p.setStatusNote(null);
        if (last == null) {
            p.setCommissionRate(new BigDecimal("0.10"));
            pointMapper.insert(p);
        } else {
            p.setCreateTime(new Date());
            pointMapper.updateById(p);
            pointMapper.update(null, new LambdaUpdateWrapper<GbPoint>().eq(GbPoint::getId, p.getId()).set(GbPoint::getStatusNote, null).set(lat == null, GbPoint::getLat, null).set(lng == null, GbPoint::getLng, null)
                    .set(note.isEmpty(), GbPoint::getApplyNote, null));
        }
        log.info("[团购] 新的团长入驻申请：{} {}", leaderName, name);
        return Result.OK("已提交，运营会在 1–2 个工作日内电话联系你", applyVo(pointMapper.selectById(p.getId())));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static BigDecimal dec(Object o) {
        try {
            return o == null || str(o).isEmpty() ? null : new BigDecimal(str(o)).setScale(6, java.math.RoundingMode.HALF_UP);
        } catch (Exception e) {
            return null;
        }
    }
}
