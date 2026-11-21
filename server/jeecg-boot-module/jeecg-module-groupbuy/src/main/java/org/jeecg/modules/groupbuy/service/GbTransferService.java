package org.jeecg.modules.groupbuy.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.cipher.PrivacyEncryptor;
import com.wechat.pay.java.core.exception.ServiceException;
import com.wechat.pay.java.core.http.*;
import com.wechat.pay.java.core.notification.RequestParam;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.groupbuy.config.GbProperties;
import org.jeecg.modules.groupbuy.entity.GbMember;
import org.jeecg.modules.groupbuy.entity.GbWithdraw;
import org.jeecg.modules.groupbuy.mapper.GbLeaderAccountMapper;
import org.jeecg.modules.groupbuy.mapper.GbMemberMapper;
import org.jeecg.modules.groupbuy.mapper.GbWithdrawMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.jeecg.modules.groupbuy.service.GbCoreService.fail;

/**
 * 团长提现自动打款：微信支付「商家转账」（新版，用户要在小程序里确认收款）。
 * <ol>
 *   <li>后台点「打款到微信零钱」→ 提现变「打款中」，调转账接口（商户单号 = 提现单号，重打加 R2、R3…）。</li>
 *   <li>微信返回 WAIT_USER_CONFIRM 和 package_info → 团长在小程序「我的佣金」点「确认收款」（wx.requestMerchantTransfer）。</li>
 *   <li>结果以转账结果通知和主动查询为准：SUCCESS → 已打款、扣掉冻结金额；FAIL / CANCELLED → 回到「待审核」，后台可以重打、线下打款或驳回。</li>
 * </ol>
 * 只有提到微信零钱的、团长用微信登录过（有 openid）、并且是真实支付（pay-mock=false）时才能自动打款，其余照旧线下打款后登记。
 * SDK 0.2.17 还没有新版商家转账的接口，这里用 SDK 的 HttpClient 自己拼请求（签名、验签、敏感字段加密都由 SDK 做）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GbTransferService {
    public static final Set<String> FINAL = Set.of("SUCCESS", "FAIL", "CANCELLED");

    private final GbProperties props;
    private final GbPayService pay;
    private final GbWithdrawMapper wdMapper;
    private final GbLeaderAccountMapper accMapper;
    private final GbMemberMapper memberMapper;
    private final GbNotifyService notify;
    private final TransactionTemplate tx;

    private volatile HttpClient http;
    /** 不转义 = 和 <>（加密后的姓名是 base64，带 =） */
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    /** 微信转账单（请求返回、查询、结果通知都是这些字段） */
    @Data
    public static class Bill {
        @SerializedName("out_bill_no") private String outBillNo;
        @SerializedName("transfer_bill_no") private String transferBillNo;
        @SerializedName("state") private String state;
        @SerializedName("package_info") private String packageInfo;
        @SerializedName("fail_reason") private String failReason;
        @SerializedName("transfer_amount") private Integer transferAmount;
        @SerializedName("openid") private String openid;
        @SerializedName("create_time") private String createTime;
        @SerializedName("update_time") private String updateTime;
    }

    /** 这笔提现能不能自动打款，不能返回原因 */
    public String unavailable(GbWithdraw w) {
        if (!pay.real()) return "现在是模拟支付（pay-mock），没接微信支付";
        if (!props.getPay().isTransferEnabled()) return "没开商家转账（groupbuy.pay.transfer-enabled）";
        if (!"wechat".equals(w.getMethod())) return "提到银行卡的只能线下打款";
        GbMember m = w.getMemberId() == null ? null : memberMapper.selectById(w.getMemberId());
        if (m == null || m.getOpenid() == null || m.getOpenid().isBlank()) return "团长没用微信登录过小程序，打不到微信零钱";
        return null;
    }

    // ======================================================== 发起 / 查询 / 撤销

    /** 后台点「打款到微信零钱」 */
    public GbWithdraw start(String id, String by) {
        GbWithdraw w = wdMapper.selectById(id);
        if (w == null) throw fail("提现单不存在");
        if (!"wait".equals(w.getStatus())) throw fail("只有待审核的提现可以打款");
        String why = unavailable(w);
        if (why != null) throw fail(why);
        String openid = memberMapper.selectById(w.getMemberId()).getOpenid();
        int n = (w.getTransferAttempts() == null ? 0 : w.getTransferAttempts()) + 1;
        String outNo = w.getWdNo() + (n > 1 ? "R" + n : "");
        // 先占住这笔提现（待审核 → 打款中），两个人同时点只有一个能发起
        int got = wdMapper.update(null, new LambdaUpdateWrapper<GbWithdraw>().eq(GbWithdraw::getId, id).eq(GbWithdraw::getStatus, "wait")
                .eq(GbWithdraw::getTransferAttempts, n - 1)
                .set(GbWithdraw::getStatus, "paying").set(GbWithdraw::getTransferNo, outNo).set(GbWithdraw::getTransferAttempts, n)
                .set(GbWithdraw::getTransferState, "CREATING").set(GbWithdraw::getTransferBillNo, null).set(GbWithdraw::getPackageInfo, null)
                .set(GbWithdraw::getTransferFail, null).set(GbWithdraw::getTransferTime, new Date()).set(GbWithdraw::getHandleBy, by)
                .set(GbWithdraw::getUpdateTime, new Date()));
        if (got == 0) throw fail("已经在处理了，请刷新");
        try {
            apply(outNo, create(w, openid, outNo), false);
        } catch (ServiceException e) {
            if (e.getHttpStatusCode() >= 500 || "SYSTEM_ERROR".equals(e.getErrorCode()) || "FREQUENCY_LIMITED".equals(e.getErrorCode())) {
                unknown(outNo, "微信繁忙，稍后自动查询结果");
            } else {
                // 微信明确拒绝（参数不对、余额不足、没开通…），没有建单，回到待审核
                back(outNo, "FAIL", "微信拒绝：" + errText(e));
            }
        } catch (Exception e) {
            log.warn("[团购] 提现 {} 发起转账出错：{}", w.getWdNo(), e.getMessage());
            unknown(outNo, "网络出错，稍后自动查询结果");
        }
        return wdMapper.selectById(id);
    }

    /** 去微信查这笔转账的最新状态（后台「刷新结果」、团长确认收款后、定时任务） */
    public GbWithdraw sync(String id) {
        GbWithdraw w = wdMapper.selectById(id);
        if (w == null) throw fail("提现单不存在");
        if (!"paying".equals(w.getStatus()) || w.getTransferNo() == null) return w;
        try {
            apply(w.getTransferNo(), query(w.getTransferNo()), false);
        } catch (ServiceException e) {
            if (e.getHttpStatusCode() == 404 || "NOT_FOUND".equals(e.getErrorCode())) {
                // 发起时网络出错、微信其实没收到：没有这笔转账，回到待审核可以重打
                back(w.getTransferNo(), "FAIL", "微信那边没有这笔转账（发起时没成功），可以重新打款");
            } else {
                touch(w.getTransferNo());
                log.warn("[团购] 查询转账 {} 失败：{}", w.getTransferNo(), errText(e));
            }
        } catch (Exception e) {
            touch(w.getTransferNo());
            log.warn("[团购] 查询转账 {} 出错：{}", w.getTransferNo(), e.getMessage());
        }
        return wdMapper.selectById(id);
    }

    /** 撤销还没完成的转账（团长一直不确认收款时）；撤销成功后提现回到待审核 */
    public GbWithdraw cancel(String id) {
        GbWithdraw w = wdMapper.selectById(id);
        if (w == null) throw fail("提现单不存在");
        if (!"paying".equals(w.getStatus())) throw fail("不在打款中，不用撤销");
        if (!List.of("ACCEPTED", "PROCESSING", "WAIT_USER_CONFIRM").contains(w.getTransferState())) throw fail("微信正在打款，撤销不了，等结果出来");
        try {
            Bill b = post("/v3/fund-app/mch-transfer/transfer-bills/out-bill-no/" + enc(w.getTransferNo()) + "/cancel", Map.of(), false);
            apply(w.getTransferNo(), b, false);
        } catch (ServiceException e) {
            throw fail("撤销失败：" + errText(e));
        }
        return wdMapper.selectById(id);
    }

    /** 转账结果通知（验签、解密后交给 apply） */
    public void handleNotify(RequestParam param) {
        Bill b = pay.parser().parse(param, Bill.class);
        if (b.getOutBillNo() == null) throw new IllegalArgumentException("通知里没有商户单号");
        apply(b.getOutBillNo(), b, true);
    }

    /** 定时任务：打款中的每 5 分钟查一次（团长迟迟不确认、通知丢了、发起时网络出错） */
    public void syncPaying() {
        if (!pay.real()) return;
        Date before = new Date(System.currentTimeMillis() - 5 * 60_000L);
        for (GbWithdraw w : wdMapper.selectList(new LambdaQueryWrapper<GbWithdraw>().eq(GbWithdraw::getStatus, "paying")
                .and(q -> q.isNull(GbWithdraw::getTransferTime).or().le(GbWithdraw::getTransferTime, before)).last("limit 50"))) {
            try {
                sync(w.getId());
            } catch (Exception e) {
                log.warn("[团购] 同步提现 {} 出错：{}", w.getWdNo(), e.getMessage());
            }
        }
    }

    // ======================================================== 状态落库

    /** 按微信返回的状态更新提现。锁行处理，通知、查询、发起返回同时到也只结算一次 */
    void apply(String outNo, Bill b, boolean fromNotify) {
        String state = b == null || b.getState() == null ? "UNKNOWN" : b.getState();
        GbWithdraw done = tx.execute(s -> {
            GbWithdraw w = wdMapper.lockByTransferNo(outNo);
            if (w == null) {
                log.warn("[团购] 收到不认识的转账单 {}（{}）", outNo, state);
                return null;
            }
            if (!"paying".equals(w.getStatus()) || !outNo.equals(w.getTransferNo())) return null;
            if (b != null && b.getTransferAmount() != null && b.getTransferAmount() != GbPayService.fen(w.getAmount())) {
                log.error("[团购] 转账 {} 金额对不上：提现 {} 元，微信 {} 分，不处理", outNo, w.getAmount(), b.getTransferAmount());
                return null;
            }
            Date now = new Date();
            switch (state) {
                case "SUCCESS" -> {
                    wdMapper.update(null, new LambdaUpdateWrapper<GbWithdraw>().eq(GbWithdraw::getId, w.getId())
                            .set(GbWithdraw::getStatus, "paid").set(GbWithdraw::getTransferState, state)
                            .set(GbWithdraw::getTransferBillNo, b.getTransferBillNo() != null ? b.getTransferBillNo() : w.getTransferBillNo())
                            .set(GbWithdraw::getVoucher, "微信转账 " + (b.getTransferBillNo() != null ? b.getTransferBillNo() : w.getTransferBillNo()))
                            .set(GbWithdraw::getPackageInfo, null).set(GbWithdraw::getHandleTime, now).set(GbWithdraw::getTransferTime, now).set(GbWithdraw::getUpdateTime, now));
                    if (accMapper.paidOut(w.getPointId(), w.getAmount()) == 0) log.error("[团购] 提现 {} 已到账，但账户冻结金额不够扣，请人工核对", w.getWdNo());
                    log.info("[团购] 提现 {} 已打到团长微信零钱（{}）", w.getWdNo(), fromNotify ? "通知" : "查询");
                    return wdMapper.selectById(w.getId());
                }
                case "FAIL", "CANCELLED" -> {
                    backLocked(w, state, "CANCELLED".equals(state) ? "转账已撤销" : "转账失败：" + reasonText(b.getFailReason()));
                    return null;
                }
                default -> {
                    LambdaUpdateWrapper<GbWithdraw> u = new LambdaUpdateWrapper<GbWithdraw>().eq(GbWithdraw::getId, w.getId())
                            .set(GbWithdraw::getTransferState, state).set(GbWithdraw::getTransferTime, now).set(GbWithdraw::getUpdateTime, now);
                    // 知道结果了，之前「网络出错，稍后查询」之类的说明去掉
                    if (!"UNKNOWN".equals(state)) u.set(GbWithdraw::getTransferFail, null);
                    if (b != null && b.getTransferBillNo() != null) u.set(GbWithdraw::getTransferBillNo, b.getTransferBillNo());
                    if (b != null && b.getPackageInfo() != null) u.set(GbWithdraw::getPackageInfo, b.getPackageInfo());
                    wdMapper.update(null, u);
                    return null;
                }
            }
        });
        if (done != null) notify.withdraw(done, true);
    }

    private void back(String outNo, String state, String reason) {
        tx.executeWithoutResult(s -> {
            GbWithdraw w = wdMapper.lockByTransferNo(outNo);
            if (w != null && "paying".equals(w.getStatus())) backLocked(w, state, reason);
        });
    }

    /** 回到待审核（钱还冻结着），后台可以重打、线下打款或驳回 */
    private void backLocked(GbWithdraw w, String state, String reason) {
        wdMapper.update(null, new LambdaUpdateWrapper<GbWithdraw>().eq(GbWithdraw::getId, w.getId()).eq(GbWithdraw::getStatus, "paying")
                .set(GbWithdraw::getStatus, "wait").set(GbWithdraw::getTransferState, state).set(GbWithdraw::getTransferFail, cut(reason))
                .set(GbWithdraw::getPackageInfo, null).set(GbWithdraw::getTransferTime, new Date()).set(GbWithdraw::getUpdateTime, new Date()));
        log.info("[团购] 提现 {} 自动打款没成功，回到待审核：{}", w.getWdNo(), reason);
    }

    private void unknown(String outNo, String note) {
        wdMapper.update(null, new LambdaUpdateWrapper<GbWithdraw>().eq(GbWithdraw::getTransferNo, outNo).eq(GbWithdraw::getStatus, "paying")
                .set(GbWithdraw::getTransferState, "UNKNOWN").set(GbWithdraw::getTransferFail, note).set(GbWithdraw::getTransferTime, new Date()));
    }

    private void touch(String outNo) {
        wdMapper.update(null, new LambdaUpdateWrapper<GbWithdraw>().eq(GbWithdraw::getTransferNo, outNo).set(GbWithdraw::getTransferTime, new Date()));
    }

    // ======================================================== 调微信

    private Bill create(GbWithdraw w, String openid, String outNo) {
        GbProperties.Pay p = props.getPay();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", props.getWx().getAppid());
        body.put("out_bill_no", outNo);
        body.put("transfer_scene_id", p.getTransferSceneId());
        body.put("openid", openid);
        body.put("transfer_amount", GbPayService.fen(w.getAmount()));
        body.put("transfer_remark", cut(p.getTransferRemark() == null ? "团长佣金提现" : p.getTransferRemark(), 32));
        body.put("notify_url", pay.notifyUrl("/gb/app/pay/transfer-notify"));
        body.put("user_recv_perception", p.getTransferPerception());
        body.put("transfer_scene_report_infos", List.of(Map.of("info_type", "岗位类型", "info_content", p.getTransferPostType()),
                Map.of("info_type", "报酬说明", "info_content", p.getTransferPayDesc())));
        // 收款人姓名：微信会核对和微信实名是否一致（2000 元以上必须传），要用微信支付公钥加密
        boolean withName = w.getAccountName() != null && !w.getAccountName().isBlank();
        if (withName) body.put("user_name", encryptor().encrypt(w.getAccountName().trim()));
        return post("/v3/fund-app/mch-transfer/transfer-bills", body, withName);
    }

    private Bill query(String outNo) {
        HttpHeaders h = new HttpHeaders();
        h.addHeader("Accept", MediaType.APPLICATION_JSON.getValue());
        HttpRequest req = new HttpRequest.Builder().httpMethod(HttpMethod.GET).url(base() + "/v3/fund-app/mch-transfer/transfer-bills/out-bill-no/" + enc(outNo))
                .headers(h).build();
        return client().execute(req, Bill.class).getServiceResponse();
    }

    private Bill post(String path, Map<String, ?> body, boolean encrypted) {
        HttpHeaders h = new HttpHeaders();
        h.addHeader("Accept", MediaType.APPLICATION_JSON.getValue());
        h.addHeader("Content-Type", MediaType.APPLICATION_JSON.getValue());
        if (encrypted) h.addHeader("Wechatpay-Serial", encryptor().getWechatpaySerial());
        HttpRequest req = new HttpRequest.Builder().httpMethod(HttpMethod.POST).url(base() + path).headers(h)
                .body(new JsonRequestBody.Builder().body(gson.toJson(body)).build()).build();
        Bill b = client().execute(req, Bill.class).getServiceResponse();
        return b == null ? new Bill() : b;
    }

    private HttpClient client() {
        if (http == null) {
            synchronized (this) {
                if (http == null) http = new DefaultHttpClientBuilder().config(pay.config()).build();
            }
        }
        return http;
    }

    private PrivacyEncryptor encryptor() {
        Config c = pay.config();
        return c.createEncryptor();
    }

    private String base() {
        String b = props.getPay().getApiBase();
        return b == null || b.isBlank() ? "https://api.mch.weixin.qq.com" : b.replaceAll("/+$", "");
    }

    // ======================================================== 文案

    static String errText(ServiceException e) {
        String code = e.getErrorCode(), msg = e.getErrorMessage();
        if ("NOT_ENOUGH".equals(code)) return "商户号可用余额不足，请先充值";
        if ("NO_AUTH".equals(code)) return "商户号没开通商家转账或这个转账场景（" + msg + "）";
        return (code == null ? "HTTP " + e.getHttpStatusCode() : code) + (msg == null ? "" : " " + msg);
    }

    static String reasonText(String code) {
        if (code == null) return "未知原因";
        return switch (code) {
            case "REAL_NAME_CHECK_FAIL", "NAME_NOT_CORRECT" -> "收款人姓名和微信实名不一致";
            case "ACCOUNT_FROZEN", "PAYEE_ACCOUNT_ABNORMAL" -> "团长微信账户异常或被冻结";
            case "ACCOUNT_NOT_EXIST", "OPENID_INVALID" -> "团长微信账户不存在";
            case "TRANSFER_QUOTA_EXCEED", "DAY_RECEIVED_QUOTA_EXCEED", "MONTH_RECEIVED_QUOTA_EXCEED", "DAY_RECEIVED_COUNT_EXCEED",
                    "REALNAME_ACCOUNT_RECEIVED_QUOTA_EXCEED" -> "超过微信收款限额，可以拆小一点或改天再打";
            case "OVERDUE_CLOSE" -> "团长太久没确认收款，微信已关闭这笔转账";
            case "TRANSFER_RISK" -> "微信风控拦截";
            case "PAYER_ACCOUNT_ABNORMAL" -> "商户号异常";
            default -> code;
        };
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String cut(String s) {
        return cut(s, 255);
    }

    private static String cut(String s, int n) {
        return s == null || s.codePointCount(0, s.length()) <= n ? s : s.substring(0, s.offsetByCodePoints(0, n));
    }
}
