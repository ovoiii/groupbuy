package org.jeecg.modules.groupbuy.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.core.exception.ValidationException;
import com.wechat.pay.java.core.exception.WechatPayException;
import com.wechat.pay.java.core.notification.NotificationConfig;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.*;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.AmountReq;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.QueryByOutRefundNoRequest;
import com.wechat.pay.java.service.refund.model.Refund;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import com.wechat.pay.java.service.refund.model.Status;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.groupbuy.config.GbProperties;
import org.jeecg.modules.groupbuy.entity.*;
import org.jeecg.modules.groupbuy.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.jeecg.modules.groupbuy.service.GbCoreService.*;

/**
 * 微信支付（API v3，小程序 JSAPI）：下单、查单、关单、退款、支付 / 退款结果通知。
 * <p>
 * 几条保证：
 * <ul>
 *   <li>到账以服务器为准：支付回调和小程序付完后的主动查单都会走 {@link #onPaid}，锁订单行处理，重复到达只处理一次。</li>
 *   <li>关单前先问微信：向微信下过单的待付款订单，超时关单 / 截单前先查一次，付了就算付款成功。</li>
 *   <li>关单后才到账（极少）：原路自动退回。</li>
 *   <li>退款先落库再调微信，失败按退款单号重试（同一个退款单号微信只会退一次）。</li>
 * </ul>
 * 没配商户号时（pay-mock=true）这里的方法都不会被调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GbPayService {
    public static final String PAID = "paid", UNPAID = "unpaid", PENDING = "pending";
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    private static final int MAX_ATTEMPTS = 6;

    private final GbProperties props;
    private final GbOrderMapper orderMapper;
    private final GbOrderItemMapper itemMapper;
    private final GbPeriodMapper periodMapper;
    private final GbRefundMapper refundMapper;
    private final GbCoreService core;
    private final TransactionTemplate tx;

    private volatile JsapiServiceExtension jsapi;
    private volatile RefundService refundApi;
    private volatile NotificationParser parser;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gb-pay");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    void checkConfig() {
        if (props.isPayMock()) return;
        String miss = missing();
        if (miss != null) log.error("[团购] pay-mock=false 但微信支付没配齐，缺 {}，居民付款会失败", miss);
        if (props.isDevLogin()) log.error("[团购] 正式收款时开发登录还开着（dev-login=true）：任何人输入手机号就能登录别人的账号，上线前务必关掉");
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }

    public boolean real() {
        return !props.isPayMock();
    }

    /** 缺哪些配置，齐了返回 null */
    public String missing() {
        GbProperties.Pay p = props.getPay();
        List<String> m = new ArrayList<>();
        if (blank(props.getWx().getAppid())) m.add("wx.appid");
        if (blank(p.getMchId())) m.add("pay.mch-id");
        if (blank(p.getMerchantSerialNumber())) m.add("pay.merchant-serial-number");
        if (blank(p.getPrivateKeyPath())) m.add("pay.private-key-path");
        if (blank(p.getApiV3Key())) m.add("pay.api-v3-key");
        if (!blank(p.getPublicKeyId()) && blank(p.getPublicKeyPath())) m.add("pay.public-key-path");
        if (blank(p.getNotifyBase())) m.add("pay.notify-base");
        return m.isEmpty() ? null : String.join("、", m);
    }

    /** SDK 对象第一次用到时再建（没配商户号时后端照样能启动）；平台证书模式同一商户只能建一次 */
    private synchronized void init() {
        if (jsapi != null) return;
        String miss = missing();
        if (miss != null) throw fail("微信支付还没配好（缺 " + miss + "），请联系管理员");
        GbProperties.Pay p = props.getPay();
        Config c;
        try {
            c = blank(p.getPublicKeyId())
                    ? new RSAAutoCertificateConfig.Builder().merchantId(p.getMchId()).privateKeyFromPath(p.getPrivateKeyPath())
                    .merchantSerialNumber(p.getMerchantSerialNumber()).apiV3Key(p.getApiV3Key()).build()
                    : new RSAPublicKeyConfig.Builder().merchantId(p.getMchId()).privateKeyFromPath(p.getPrivateKeyPath())
                    .publicKeyFromPath(p.getPublicKeyPath()).publicKeyId(p.getPublicKeyId())
                    .merchantSerialNumber(p.getMerchantSerialNumber()).apiV3Key(p.getApiV3Key()).build();
        } catch (Exception e) {
            log.error("[团购] 微信支付初始化失败", e);
            throw fail("微信支付初始化失败，请联系管理员");
        }
        refundApi = new RefundService.Builder().config(c).build();
        parser = new NotificationParser((NotificationConfig) c);
        jsapi = new JsapiServiceExtension.Builder().config(c).signType("RSA").build();
    }

    private String notifyUrl(String path) {
        return props.getPay().getNotifyBase().replaceAll("/+$", "") + path;
    }

    // ======================================================== 下单

    /**
     * 向微信下单，返回小程序 uni.requestPayment 要的参数。
     * 同一个订单可以多次调（用户取消后再付）：商户订单号、金额、描述、过期时间都不变，微信会返回同一笔预支付。
     * 微信说这单已经付过了时返回 {paid: true}。
     */
    public Map<String, Object> prepay(GbOrder o, String openid) {
        init();
        GbPeriod pd = periodMapper.selectById(o.getPeriodId());
        Date expire = pd != null && pd.getCutoffTime().before(o.getPayExpireTime()) ? pd.getCutoffTime() : o.getPayExpireTime();
        PrepayRequest r = new PrepayRequest();
        r.setAppid(props.getWx().getAppid());
        r.setMchid(props.getPay().getMchId());
        r.setDescription(description(o));
        r.setOutTradeNo(o.getOrderNo());
        r.setTimeExpire(rfc3339(expire));
        r.setNotifyUrl(notifyUrl("/gb/app/pay/notify"));
        Amount a = new Amount();
        a.setTotal(fen(o.getPayAmount()));
        a.setCurrency("CNY");
        r.setAmount(a);
        Payer payer = new Payer();
        payer.setOpenid(openid);
        r.setPayer(payer);
        // 先记下「向微信下过单」再调：就算微信收到了而我们没收到响应，关单前也会去查
        orderMapper.update(null, new LambdaUpdateWrapper<GbOrder>().eq(GbOrder::getId, o.getId()).isNull(GbOrder::getPrepayTime).set(GbOrder::getPrepayTime, new Date()));
        o.setPrepayTime(o.getPrepayTime() == null ? new Date() : o.getPrepayTime());
        try {
            PrepayWithRequestPaymentResponse resp = jsapi.prepayWithRequestPayment(r);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("timeStamp", resp.getTimeStamp());
            m.put("nonceStr", resp.getNonceStr());
            m.put("package", resp.getPackageVal());
            m.put("signType", resp.getSignType());
            m.put("paySign", resp.getPaySign());
            return m;
        } catch (ServiceException e) {
            if ("ORDERPAID".equals(e.getErrorCode())) {
                sync(o);
                return Map.of("paid", true);
            }
            log.warn("[团购] 微信下单失败 {} {} {}", o.getOrderNo(), e.getErrorCode(), e.getErrorMessage());
            throw fail("微信支付下单失败：" + (blank(e.getErrorMessage()) ? e.getErrorCode() : e.getErrorMessage()));
        } catch (WechatPayException e) {
            log.warn("[团购] 微信下单出错 {}", o.getOrderNo(), e);
            throw fail("微信支付暂时不可用，请稍后再试");
        }
    }

    /** 「邻里鲜-土鸡蛋等3件」，同一订单每次都一样（重复下单时参数必须一致） */
    private String description(GbOrder o) {
        List<GbOrderItem> items = itemMapper.selectList(new LambdaQueryWrapper<GbOrderItem>().eq(GbOrderItem::getOrderId, o.getId()).orderByAsc(GbOrderItem::getId));
        int qty = items.stream().mapToInt(GbOrderItem::getQty).sum();
        String d = "邻里鲜-" + (items.isEmpty() ? "团购订单" : items.get(0).getGoodsName() + (qty > 1 ? "等" + qty + "件" : ""));
        return d.length() > 60 ? d.substring(0, 60) : d;
    }

    // ======================================================== 到账

    /**
     * 问微信这单付没付。PAID 已付（并已改成付款成功）/ UNPAID 没付，可以关单 / PENDING 正在付或查不到结果，先别关。
     * 没向微信下过单的直接返回 UNPAID。
     */
    public String sync(GbOrder o) {
        if (!real() || o.getPrepayTime() == null) return UNPAID;
        init();
        QueryOrderByOutTradeNoRequest q = new QueryOrderByOutTradeNoRequest();
        q.setMchid(props.getPay().getMchId());
        q.setOutTradeNo(o.getOrderNo());
        try {
            Transaction t = jsapi.queryOrderByOutTradeNo(q);
            Transaction.TradeStateEnum st = t.getTradeState();
            if (st == Transaction.TradeStateEnum.SUCCESS || st == Transaction.TradeStateEnum.REFUND) {
                onPaid(t.getOutTradeNo(), t.getTransactionId(), t.getAmount() == null ? null : t.getAmount().getTotal());
                return PAID;
            }
            return st == Transaction.TradeStateEnum.USERPAYING || st == Transaction.TradeStateEnum.ACCEPT ? PENDING : UNPAID;
        } catch (ServiceException e) {
            if ("ORDER_NOT_EXIST".equals(e.getErrorCode()) || "ORDERNOTEXIST".equals(e.getErrorCode())) return UNPAID;
            log.warn("[团购] 查单失败 {} {} {}", o.getOrderNo(), e.getErrorCode(), e.getErrorMessage());
            return PENDING;
        } catch (WechatPayException e) {
            log.warn("[团购] 查单出错 {}：{}", o.getOrderNo(), e.getMessage());
            return PENDING;
        }
    }

    /** 微信确认到账（回调或查单）。锁订单行，重复到达只处理一次 */
    public void onPaid(String orderNo, String transactionId, Integer totalFen) {
        tx.executeWithoutResult(s -> {
            GbOrder o = orderMapper.lockByNo(orderNo);
            if (o == null) {
                log.error("[团购] 收到不存在的订单的付款 {} {}", orderNo, transactionId);
                return;
            }
            if (transactionId != null && transactionId.equals(o.getTransactionId())) return; // 处理过了
            BigDecimal got = totalFen == null ? o.getPayAmount() : BigDecimal.valueOf(totalFen).movePointLeft(2);
            if (S_UNPAID.equals(o.getStatus()) && got.compareTo(o.getPayAmount()) == 0) {
                core.markPaid(o, "wx", transactionId);
                log.info("[团购] 订单 {} 微信支付到账 {}", orderNo, got);
                return;
            }
            // 订单已经关了（超时 / 截单 / 取消）才到账，或者金额对不上：记下支付单号，原路全额退回
            orderMapper.update(null, new LambdaUpdateWrapper<GbOrder>().eq(GbOrder::getId, o.getId())
                    .set(GbOrder::getTransactionId, transactionId).set(GbOrder::getPayChannel, "wx"));
            o.setPayChannel("wx");
            GbOrder forRefund = new GbOrder();
            forRefund.setId(o.getId());
            forRefund.setOrderNo(o.getOrderNo());
            forRefund.setPayChannel("wx");
            forRefund.setPayAmount(got);
            String why = S_UNPAID.equals(o.getStatus()) ? "支付金额和订单不一致，自动退回" : "订单已关闭后才到账，自动退回";
            log.warn("[团购] 订单 {}（{}）收到付款 {}：{}", orderNo, o.getStatus(), got, why);
            core.requestRefund(forRefund, got, why, null);
        });
    }

    /** 支付结果通知：验签、解密，成功的交给 onPaid。验签失败抛 ValidationException */
    public void handlePayNotify(RequestParam param) {
        init();
        Transaction t = parser.parse(param, Transaction.class);
        if (t.getTradeState() == Transaction.TradeStateEnum.SUCCESS)
            onPaid(t.getOutTradeNo(), t.getTransactionId(), t.getAmount() == null ? null : t.getAmount().getTotal());
    }

    /** 微信那边关单（尽力而为，失败不影响，过期时间到了微信也不让付） */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUnpaidClosed(GbPayEvents.UnpaidClosed e) {
        if (!real()) return;
        pool.execute(() -> {
            try {
                init();
                CloseOrderRequest r = new CloseOrderRequest();
                r.setMchid(props.getPay().getMchId());
                r.setOutTradeNo(e.orderNo());
                jsapi.closeOrder(r);
            } catch (Exception ex) {
                log.info("[团购] 微信关单 {} 没成功：{}", e.orderNo(), ex.getMessage());
            }
        });
    }

    // ======================================================== 退款

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRefundCreated(GbPayEvents.RefundCreated e) {
        pool.execute(() -> submitRefund(e.refundId()));
    }

    /** 提交一笔退款。先「抢」这条记录（提交次数 +1 的条件更新），定时重试和事件同时到时只会提交一次 */
    public void submitRefund(String id) {
        GbRefund r = refundMapper.selectById(id);
        if (r == null || !List.of("pending", "failed").contains(r.getStatus())) return;
        int got = refundMapper.update(null, new LambdaUpdateWrapper<GbRefund>().eq(GbRefund::getId, id).eq(GbRefund::getAttempts, r.getAttempts())
                .in(GbRefund::getStatus, "pending", "failed").setSql("attempts = attempts + 1").set(GbRefund::getUpdateTime, new Date()));
        if (got == 0) return;
        try {
            init();
            CreateRequest c = new CreateRequest();
            c.setOutTradeNo(r.getOrderNo());
            c.setOutRefundNo(r.getRefundNo());
            c.setReason(r.getReason());
            c.setNotifyUrl(notifyUrl("/gb/app/pay/refund-notify"));
            AmountReq a = new AmountReq();
            a.setRefund((long) fen(r.getAmount()));
            a.setTotal((long) fen(r.getTotal()));
            a.setCurrency("CNY");
            c.setAmount(a);
            Refund res = refundApi.create(c);
            applyRefund(r.getRefundNo(), res.getStatus(), res.getRefundId(), res.getSuccessTime(), null);
        } catch (ServiceException e) {
            String msg = e.getErrorCode() + "：" + e.getErrorMessage();
            if ("NOT_ENOUGH".equals(e.getErrorCode())) msg = "商户号可用余额不足，充值后会自动重试";
            mark(id, "failed", msg);
            log.warn("[团购] 退款 {} 提交失败 {}", r.getRefundNo(), msg);
        } catch (Exception e) {
            // 网络问题：不知道微信收没收到，保持 pending 用同一个退款单号重试，不会重复退
            mark(id, "pending", "调用微信出错：" + e.getMessage());
            log.warn("[团购] 退款 {} 提交出错：{}", r.getRefundNo(), e.getMessage());
        }
    }

    /** 退款结果通知 */
    public void handleRefundNotify(RequestParam param) {
        init();
        RefundNotification n = parser.parse(param, RefundNotification.class);
        applyRefund(n.getOutRefundNo(), n.getRefundStatus(), n.getRefundId(), n.getSuccessTime(), null);
    }

    private void applyRefund(String refundNo, Status st, String wxRefundId, String successTime, String err) {
        String status = st == null ? "processing" : switch (st) {
            case SUCCESS -> "success";
            case CLOSED -> "closed";
            case ABNORMAL -> "abnormal";
            default -> "processing";
        };
        String note = switch (status) {
            case "abnormal" -> "退款异常（如用户银行卡已注销），需要在微信支付商户平台手动处理";
            case "closed" -> "微信关闭了这笔退款";
            default -> err;
        };
        refundMapper.update(null, new LambdaUpdateWrapper<GbRefund>().eq(GbRefund::getRefundNo, refundNo).ne(GbRefund::getStatus, "success")
                .set(GbRefund::getStatus, status).set(wxRefundId != null, GbRefund::getWxRefundId, wxRefundId)
                .set(GbRefund::getLastError, note).set("success".equals(status), GbRefund::getSuccessTime, parseTime(successTime))
                .set(GbRefund::getUpdateTime, new Date()));
    }

    private void mark(String id, String status, String err) {
        refundMapper.update(null, new LambdaUpdateWrapper<GbRefund>().eq(GbRefund::getId, id)
                .set(GbRefund::getStatus, status).set(GbRefund::getLastError, err.length() > 250 ? err.substring(0, 250) : err).set(GbRefund::getUpdateTime, new Date()));
    }

    /** 定时任务调：重试没提交成功的退款；退款中超过 10 分钟还没通知的主动查一次 */
    public void retryRefunds() {
        if (!real()) return;
        long now = System.currentTimeMillis();
        for (GbRefund r : refundMapper.selectList(new LambdaQueryWrapper<GbRefund>().in(GbRefund::getStatus, "pending", "failed").lt(GbRefund::getAttempts, MAX_ATTEMPTS).last("limit 20"))) {
            long wait = 60_000L * Math.max(1, r.getAttempts() * r.getAttempts()); // 1、1、4、9、16、25 分钟后重试
            if (r.getUpdateTime() == null || now - r.getUpdateTime().getTime() >= wait) submitRefund(r.getId());
        }
        for (GbRefund r : refundMapper.selectList(new LambdaQueryWrapper<GbRefund>().eq(GbRefund::getStatus, "processing")
                .lt(GbRefund::getUpdateTime, new Date(now - 10 * 60_000L)).last("limit 20"))) {
            try {
                init();
                QueryByOutRefundNoRequest q = new QueryByOutRefundNoRequest();
                q.setOutRefundNo(r.getRefundNo());
                Refund res = refundApi.queryByOutRefundNo(q);
                applyRefund(r.getRefundNo(), res.getStatus(), res.getRefundId(), res.getSuccessTime(), null);
            } catch (Exception e) {
                refundMapper.update(null, new LambdaUpdateWrapper<GbRefund>().eq(GbRefund::getId, r.getId()).set(GbRefund::getUpdateTime, new Date()));
                log.warn("[团购] 查退款 {} 出错：{}", r.getRefundNo(), e.getMessage());
            }
        }
    }

    /** 后台点「重试」：提交失败（含超过自动重试次数）的退款重新提交 */
    public void retry(String id) {
        GbRefund r = refundMapper.selectById(id);
        if (r == null) throw fail("退款记录不存在");
        if (!"failed".equals(r.getStatus()) && !"pending".equals(r.getStatus())) throw fail("只有提交失败的退款可以重试");
        refundMapper.update(null, new LambdaUpdateWrapper<GbRefund>().eq(GbRefund::getId, id).set(GbRefund::getAttempts, 0).set(GbRefund::getStatus, "pending"));
        pool.execute(() -> submitRefund(id));
    }

    // ======================================================== 工具

    public static boolean isSignError(Exception e) {
        return e instanceof ValidationException;
    }

    static int fen(BigDecimal yuan) {
        return yuan.movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    static String rfc3339(Date d) {
        return OffsetDateTime.ofInstant(d.toInstant(), CN).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
    }

    private static Date parseTime(String s) {
        if (blank(s)) return new Date();
        try {
            return Date.from(OffsetDateTime.parse(s).toInstant());
        } catch (Exception e) {
            return new Date();
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
