package org.jeecg.modules.groupbuy.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.groupbuy.config.GbProperties;
import org.jeecg.modules.groupbuy.entity.*;
import org.jeecg.modules.groupbuy.mapper.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信订阅消息。
 * <p>
 * 业务代码只管说「哪个场景、发给谁、内容是什么」（下面 arrived / pickup / ... 几个方法），
 * 内容用固定的名字（point、code、goods…），在配置里把模板字段对到这些名字上，换模板不用改代码。
 * <p>
 * 发送规则：
 * <ul>
 *   <li>没配 AppID 或这个场景没配模板：只写一条 mock 记录，方便开发时在后台「消息通知」里看内容。</li>
 *   <li>微信是一次性订阅：用户点一次「允许」只能发一条。小程序上报后记在 gb_notify_sub，发一条扣一次，没次数就不发。</li>
 *   <li>等业务事务提交后再在后台线程发，微信接口慢或出错都不影响业务。</li>
 * </ul>
 * 各场景可用的内容：
 * <pre>
 * arrived  到货通知（居民）  point 提货点  address 地址  code 提货码  goods 商品  time 取货截止  hours 营业时间  orderNo 订单号  tip 提示
 * pickup   取货提醒（居民）  同 arrived
 * aftersale 售后结果（居民） orderNo 订单号  goods 商品  result 结果  amount 退款金额  reply 回复  time 处理时间
 * ship     发车通知（团长）  point 提货点  orders 订单数  items 件数  boxes 箱数  driver 司机  time 发车时间  tip 提示
 * apply    入驻审核（申请人） point 提货点  name 姓名  result 结果  reply 说明  time 审核时间  tip 提示
 * withdraw 提现结果（团长）  wdNo 提现单号  amount 金额  result 结果  reply 说明  time 处理时间  method 收款方式
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GbNotifyService {
    public static final List<String> SCENES = List.of("arrived", "pickup", "aftersale", "ship", "apply", "withdraw");
    public static final Map<String, String> SCENE_NAMES = Map.of("arrived", "到货通知", "pickup", "取货提醒", "aftersale", "售后结果",
            "ship", "发车通知", "apply", "入驻审核结果", "withdraw", "提现结果");

    private final GbProperties props;
    private final GbNotifyLogMapper logMapper;
    private final GbMemberMapper memberMapper;
    private final GbOrderItemMapper itemMapper;
    private final GbPointMapper pointMapper;
    private final GbPeriodMapper periodMapper;

    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gb-notify");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile String accessToken;
    private volatile long tokenExpireAt;

    @PreDestroy
    public void shutdown() {
        pool.shutdown();
    }

    // ======================================================== 配置

    public boolean wxReady() {
        GbProperties.Wx wx = props.getWx();
        return notBlank(wx.getAppid()) && notBlank(wx.getSecret());
    }

    public String templateOf(String scene) {
        GbProperties.Scene s = props.getNotify().getScenes().get(scene);
        return s == null || !notBlank(s.getTemplateId()) ? null : s.getTemplateId().trim();
    }

    /** 给小程序：场景 → 模板 ID（只返回能真发的，小程序据此弹订阅框） */
    public Map<String, String> templates() {
        Map<String, String> m = new LinkedHashMap<>();
        if (!wxReady()) return m;
        for (String s : SCENES) if (templateOf(s) != null) m.put(s, templateOf(s));
        return m;
    }

    /** 小程序上报：用户在订阅框里点了「允许」的模板，每个加 1 次；不认识的模板忽略 */
    public int subscribe(String memberId, Collection<String> templateIds) {
        Set<String> known = new HashSet<>(templates().values());
        int n = 0;
        for (String t : new LinkedHashSet<>(templateIds)) if (known.contains(t)) n += logMapper.addQuota(memberId, t) > 0 ? 1 : 0;
        return n;
    }

    /** 给后台「消息通知」页：每个场景配没配好 */
    public List<Map<String, Object>> configView() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String s : SCENES) {
            GbProperties.Scene c = props.getNotify().getScenes().get(s);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scene", s);
            m.put("name", SCENE_NAMES.get(s));
            m.put("templateId", templateOf(s));
            m.put("fields", c == null ? Map.of() : c.getFields());
            out.add(m);
        }
        return out;
    }

    public String state() {
        return props.getNotify().getState();
    }

    // ======================================================== 各场景

    /** 到货：团长签收后，给这批订单的居民发 */
    public void arrived(GbOrder o) {
        send("arrived", o.getMemberId(), o.getId(), "pages/order/order?id=" + o.getId(), orderValues(o, "货到了，凭提货码到店取货"));
    }

    /** 团长点「提醒取货」 */
    public void pickup(GbOrder o) {
        send("pickup", o.getMemberId(), o.getId(), "pages/order/order?id=" + o.getId(), orderValues(o, "货还在提货点，记得来取"));
    }

    /** 售后处理完（同意 / 驳回 / 平台取消退款） */
    public void aftersale(GbOrder o, String result, BigDecimal amount, String reply) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("orderNo", o.getOrderNo());
        v.put("goods", goodsOf(o.getId()));
        v.put("result", result);
        v.put("amount", amount == null ? BigDecimal.ZERO : amount);
        v.put("reply", notBlank(reply) ? reply : result);
        v.put("time", new Date());
        send("aftersale", o.getMemberId(), o.getId(), "pages/order/order?id=" + o.getId(), v);
    }

    /** 发车：通知团长准备收货 */
    public void ship(GbDelivery d) {
        GbPoint pt = pointMapper.selectById(d.getPointId());
        if (pt == null) return;
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("point", pt.getName());
        v.put("orders", d.getOrderCount());
        v.put("items", d.getItemCount());
        v.put("boxes", d.getBoxes());
        v.put("driver", notBlank(d.getDriver()) ? d.getDriver() : "配送司机");
        v.put("time", d.getShipTime() == null ? new Date() : d.getShipTime());
        v.put("tip", d.getOrderCount() + " 单已发车，到了请清点签收");
        send("ship", leaderMemberOf(pt), d.getId(), "pages/leader/leader", v);
    }

    /** 入驻审核结果：发给提交申请的人 */
    public void apply(GbPoint p, boolean pass) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("point", p.getName());
        v.put("name", p.getLeaderName());
        v.put("result", pass ? "审核通过" : "未通过");
        v.put("reply", notBlank(p.getStatusNote()) ? p.getStatusNote() : pass ? "欢迎加入" : "可修改后重新提交");
        v.put("time", new Date());
        v.put("tip", pass ? "登录小程序进入团长工作台" : "点开查看原因，修改后可重新提交");
        send("apply", leaderMemberOf(p), p.getId(), "pages/leader/apply", v);
    }

    /** 提现处理完（已打款 / 驳回） */
    public void withdraw(GbWithdraw w, boolean paid) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("wdNo", w.getWdNo());
        v.put("amount", w.getAmount());
        v.put("result", paid ? "已打款" : "已驳回");
        v.put("reply", paid ? "请留意到账" : w.getReply());
        v.put("time", new Date());
        v.put("method", "bank".equals(w.getMethod()) ? "银行卡" : "微信零钱");
        GbPoint pt = pointMapper.selectById(w.getPointId());
        String to = notBlank(w.getMemberId()) ? w.getMemberId() : pt == null ? null : leaderMemberOf(pt);
        send("withdraw", to, w.getId(), "pages/leader/income", v);
    }

    private Map<String, Object> orderValues(GbOrder o, String tip) {
        GbPoint pt = pointMapper.selectById(o.getPointId());
        GbPeriod pd = periodMapper.selectById(o.getPeriodId());
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("point", pt == null ? "" : pt.getName());
        v.put("address", pt == null ? "" : pt.getAddress());
        v.put("code", o.getPickupCode());
        v.put("goods", goodsOf(o.getId()));
        v.put("time", pd == null ? null : pd.getPickupEndTime());
        v.put("hours", pt == null ? "" : pt.getOpenHours());
        v.put("orderNo", o.getOrderNo());
        v.put("tip", tip);
        return v;
    }

    /** 「土鸡蛋 等 3 件」 */
    private String goodsOf(String orderId) {
        List<GbOrderItem> items = itemMapper.selectList(new LambdaQueryWrapper<GbOrderItem>().eq(GbOrderItem::getOrderId, orderId));
        if (items.isEmpty()) return "";
        int qty = items.stream().mapToInt(GbOrderItem::getQty).sum();
        return items.get(0).getGoodsName() + (qty > 1 ? " 等 " + qty + " 件" : "");
    }

    /** 提货点的团长：绑定了用户就用用户，否则按团长手机号找 */
    private String leaderMemberOf(GbPoint p) {
        if (notBlank(p.getLeaderMemberId())) return p.getLeaderMemberId();
        if (!notBlank(p.getLeaderPhone())) return null;
        GbMember m = memberMapper.selectOne(new LambdaQueryWrapper<GbMember>().eq(GbMember::getPhone, p.getLeaderPhone()).last("limit 1"));
        return m == null ? null : m.getId();
    }

    // ======================================================== 发送

    /** 业务事务提交后再发（事务回滚就不发） */
    public void send(String scene, String memberId, String bizId, String page, Map<String, Object> values) {
        Runnable job = () -> pool.execute(() -> {
            try {
                doSend(scene, memberId, bizId, page, values);
            } catch (Exception e) {
                log.error("[团购] 订阅消息 {} 发送出错", scene, e);
            }
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    job.run();
                }
            });
        } else {
            job.run();
        }
    }

    void doSend(String scene, String memberId, String bizId, String page, Map<String, Object> values) {
        GbNotifyLog lg = new GbNotifyLog();
        lg.setScene(scene);
        lg.setMemberId(memberId);
        lg.setBizId(bizId);
        lg.setCreateTime(new Date());
        String tmpl = templateOf(scene);
        lg.setTemplateId(tmpl);
        Map<String, String> fields = tmpl == null ? Map.of() : props.getNotify().getScenes().get(scene).getFields();
        lg.setContent(cut(JSON.toJSONString(fields.isEmpty() ? readable(values) : data(fields, values)), 1000));

        if (memberId == null) {
            done(lg, "skipped", "找不到接收人");
        } else if (!wxReady() || tmpl == null) {
            done(lg, "mock", !wxReady() ? "没配微信 AppID，只记录不发送" : "这个场景没配模板，只记录不发送");
        } else if (fields.isEmpty()) {
            done(lg, "failed", "模板没配字段（groupbuy.notify.scenes." + scene + ".fields）");
        } else {
            GbMember m = memberMapper.selectById(memberId);
            if (m == null || !notBlank(m.getOpenid())) {
                done(lg, "skipped", "用户没用微信登录过，没有 openid");
            } else if (logMapper.useQuota(memberId, tmpl) == 0) {
                done(lg, "skipped", "用户没订阅或次数已用完");
            } else {
                post(lg, m.getOpenid(), tmpl, page, data(fields, values));
            }
        }
    }

    private void post(GbNotifyLog lg, String openid, String tmpl, String page, Map<String, Object> data) {
        JSONObject body = new JSONObject();
        body.put("touser", openid);
        body.put("template_id", tmpl);
        body.put("page", page);
        body.put("miniprogram_state", props.getNotify().getState());
        body.put("lang", "zh_CN");
        body.put("data", data);
        try {
            JSONObject r = callSend(body, false);
            int code = r.getIntValue("errcode");
            if (code == 0) {
                done(lg, "sent", null);
            } else if (code == 43101) {
                // 用户拒收（在设置里关了），这次次数作废
                done(lg, "skipped", "用户拒收：" + r.getString("errmsg"));
            } else {
                logMapper.returnQuota(lg.getMemberId(), tmpl);
                done(lg, "failed", "微信返回 " + code + "：" + r.getString("errmsg"));
            }
        } catch (Exception e) {
            logMapper.returnQuota(lg.getMemberId(), tmpl);
            done(lg, "failed", "调用微信接口出错：" + e.getMessage());
        }
    }

    private JSONObject callSend(JSONObject body, boolean retried) throws Exception {
        String url = "https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token=" + token(retried);
        JSONObject r = postJson(url, body.toJSONString());
        int code = r.getIntValue("errcode");
        // access_token 失效：强制刷新后重试一次
        if (!retried && (code == 40001 || code == 42001 || code == 40014)) return callSend(body, true);
        return r;
    }

    /** 稳定版 access_token，提前 5 分钟刷新 */
    private synchronized String token(boolean force) throws Exception {
        if (!force && accessToken != null && System.currentTimeMillis() < tokenExpireAt) return accessToken;
        JSONObject req = new JSONObject();
        req.put("grant_type", "client_credential");
        req.put("appid", props.getWx().getAppid());
        req.put("secret", props.getWx().getSecret());
        req.put("force_refresh", false);
        JSONObject r = postJson("https://api.weixin.qq.com/cgi-bin/stable_token", req.toJSONString());
        String t = r.getString("access_token");
        if (t == null) throw new IllegalStateException("获取 access_token 失败：" + r.getString("errmsg"));
        accessToken = t;
        tokenExpireAt = System.currentTimeMillis() + (r.getLongValue("expires_in", 7200) - 300) * 1000L;
        return t;
    }

    private JSONObject postJson(String url, String json) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
        return JSON.parseObject(r.body());
    }

    private void done(GbNotifyLog lg, String status, String note) {
        lg.setStatus(status);
        lg.setNote(cut(note, 255));
        logMapper.insert(lg);
        if ("failed".equals(status)) log.warn("[团购] 订阅消息 {} 发送失败：{}", lg.getScene(), note);
    }

    // ======================================================== 按微信字段类型整理内容

    private static final Pattern KEY = Pattern.compile("^([a-z_]+?)\\d+$");

    /** 模板字段 → {value: 按字段类型整理好的内容} */
    static Map<String, Object> data(Map<String, String> fields, Map<String, Object> values) {
        Map<String, Object> d = new LinkedHashMap<>();
        fields.forEach((k, name) -> {
            Object raw = values.containsKey(name) ? values.get(name) : name; // 配的不是内容名就当固定文字
            d.put(k, Map.of("value", format(k, raw)));
        });
        return d;
    }

    /** 没配字段时（mock）记录可读的内容 */
    private static Map<String, Object> readable(Map<String, Object> values) {
        Map<String, Object> m = new LinkedHashMap<>();
        values.forEach((k, v) -> m.put(k, v instanceof Date dt ? new SimpleDateFormat("yyyy-MM-dd HH:mm").format(dt) : v instanceof BigDecimal b ? b.setScale(2, RoundingMode.HALF_UP).toPlainString() : v));
        return m;
    }

    /** 微信对每种字段有长度和格式要求，超了整条发不出去，这里统一截断、转换 */
    static String format(String key, Object v) {
        Matcher mt = KEY.matcher(key);
        String type = mt.matches() ? mt.group(1) : "thing";
        if (v == null) v = "";
        if (v instanceof Date d) {
            return new SimpleDateFormat("date".equals(type) ? "yyyy-MM-dd" : "yyyy-MM-dd HH:mm").format(d);
        }
        if (v instanceof BigDecimal b) {
            String s = b.setScale(2, RoundingMode.HALF_UP).toPlainString();
            return switch (type) {
                case "amount" -> "¥" + s;
                case "number" -> s;
                default -> s + "元";
            };
        }
        String s = String.valueOf(v).replaceAll("\\s+", " ").trim();
        return switch (type) {
            case "thing" -> cut(s, 20);
            case "name" -> head(s, 10);
            case "phrase", "symbol" -> head(s, 5);
            case "character_string", "letter" -> head(s.replaceAll("[^\\x21-\\x7e]", ""), 32);
            case "number" -> head(s.replaceAll("[^0-9.]", ""), 32);
            case "phone_number" -> head(s, 17);
            case "car_number" -> head(s, 8);
            default -> cut(s, 20);
        };
    }

    private static String cut(String s, int n) {
        if (s == null) return null;
        return s.codePointCount(0, s.length()) <= n ? s : s.substring(0, s.offsetByCodePoints(0, n - 1)) + "…";
    }

    /** 只截不加省略号（姓名、短语、编号这类字段不能出现「…」） */
    private static String head(String s, int n) {
        return s.codePointCount(0, s.length()) <= n ? s : s.substring(0, s.offsetByCodePoints(0, n));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
