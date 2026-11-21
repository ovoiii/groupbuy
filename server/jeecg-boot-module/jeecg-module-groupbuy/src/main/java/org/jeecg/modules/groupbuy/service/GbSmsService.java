package org.jeecg.modules.groupbuy.service;

import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.dysmsapi.model.v20170525.SendSmsRequest;
import com.aliyuncs.dysmsapi.model.v20170525.SendSmsResponse;
import com.aliyuncs.http.ProtocolType;
import com.aliyuncs.profile.DefaultProfile;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.groupbuy.config.GbProperties;
import org.jeecg.modules.groupbuy.entity.GbSmsCode;
import org.jeecg.modules.groupbuy.mapper.GbSmsCodeMapper;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static org.jeecg.modules.groupbuy.service.GbCoreService.fail;

/**
 * 短信验证码登录。
 * <ul>
 *   <li>6 位数字，有效 code-minutes 分钟，只认这个手机号最近一条发出去的；用过一次、输错 5 次都作废。</li>
 *   <li>防刷：同一手机号两次至少隔 interval-seconds 秒、每天最多 per-phone-daily 条；同一 IP 每小时最多 per-ip-hourly 条。</li>
 *   <li>库里只存验证码的 HMAC 摘要。</li>
 *   <li>provider=mock 时不真发，只写日志；同时开着开发登录时接口会把验证码返回给前端，方便本地调试。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GbSmsService {
    public static final int MAX_WRONG = 5;

    private final GbProperties props;
    private final GbSmsCodeMapper codeMapper;
    private final SecureRandom rnd = new SecureRandom();

    @PostConstruct
    void check() {
        if ("aliyun".equals(provider()) && missing() != null) log.error("[团购] 短信用阿里云但没配齐，缺 {}，验证码会发不出去", missing());
        if ("mock".equals(provider()) && !props.isDevLogin()) log.warn("[团购] 短信是 mock（groupbuy.sms.provider），用户收不到验证码，上线前配好阿里云短信");
    }

    public boolean enabled() {
        return !"off".equals(provider());
    }

    String provider() {
        String p = props.getSms().getProvider();
        return p == null || p.isBlank() ? "mock" : p.trim().toLowerCase();
    }

    String missing() {
        GbProperties.Sms c = props.getSms();
        List<String> m = new ArrayList<>();
        if (blank(c.getAccessKeyId())) m.add("sms.access-key-id");
        if (blank(c.getAccessKeySecret())) m.add("sms.access-key-secret");
        if (blank(c.getSignName())) m.add("sms.sign-name");
        if (blank(c.getTemplateCode())) m.add("sms.template-code");
        return m.isEmpty() ? null : String.join("、", m);
    }

    /** 发验证码。返回 {interval: 下次能发的秒数, devCode: 只在 mock + 开发登录时给} */
    public Map<String, Object> send(String phone, String ip) {
        if (!enabled()) throw fail("短信登录没有开通");
        if (phone == null || !phone.matches("1\\d{10}")) throw fail("请输入 11 位手机号");
        GbProperties.Sms c = props.getSms();
        long now = System.currentTimeMillis();
        GbSmsCode last = codeMapper.selectOne(new LambdaQueryWrapper<GbSmsCode>().eq(GbSmsCode::getPhone, phone).orderByDesc(GbSmsCode::getCreateTime).last("limit 1"));
        if (last != null) {
            long wait = c.getIntervalSeconds() - (now - last.getCreateTime().getTime()) / 1000;
            if (wait > 0) throw fail("发送太频繁，" + wait + " 秒后再试");
        }
        Date dayStart = Date.from(LocalDate.now(ZoneId.of("Asia/Shanghai")).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
        if (codeMapper.selectCount(new LambdaQueryWrapper<GbSmsCode>().eq(GbSmsCode::getPhone, phone).ge(GbSmsCode::getCreateTime, dayStart)) >= c.getPerPhoneDaily())
            throw fail("这个手机号今天收的验证码太多了，明天再试");
        if (!blank(ip) && codeMapper.selectCount(new LambdaQueryWrapper<GbSmsCode>().eq(GbSmsCode::getIp, ip).ge(GbSmsCode::getCreateTime, new Date(now - 3600_000L))) >= c.getPerIpHourly())
            throw fail("请求太频繁，请稍后再试");

        String code = String.format("%06d", rnd.nextInt(1_000_000));
        GbSmsCode r = new GbSmsCode();
        r.setPhone(phone);
        r.setCodeHash(hash(phone, code));
        r.setIp(ip == null ? null : ip.length() > 64 ? ip.substring(0, 64) : ip);
        r.setStatus("sending");
        r.setAttempts(0);
        r.setExpireTime(new Date(now + c.getCodeMinutes() * 60_000L));
        r.setCreateTime(new Date(now));
        codeMapper.insert(r);
        String note;
        try {
            note = deliver(phone, code);
        } catch (Exception e) {
            String err = String.valueOf(e.getMessage());
            codeMapper.update(null, new LambdaUpdateWrapper<GbSmsCode>().eq(GbSmsCode::getId, r.getId()).set(GbSmsCode::getStatus, "failed").set(GbSmsCode::getNote, cut(err)));
            log.warn("[团购] 验证码发送失败 {}：{}", GbViewService.mask(phone), err);
            throw fail("验证码没发出去，请稍后再试");
        }
        codeMapper.update(null, new LambdaUpdateWrapper<GbSmsCode>().eq(GbSmsCode::getId, r.getId()).set(GbSmsCode::getStatus, "sent").set(GbSmsCode::getNote, cut(note)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("interval", c.getIntervalSeconds());
        if ("mock".equals(provider()) && props.isDevLogin()) out.put("devCode", code);
        return out;
    }

    /** 校验验证码，对了就作废这条（只能用一次），不对抛错 */
    public void verify(String phone, String code) {
        if (phone == null || !phone.matches("1\\d{10}")) throw fail("请输入 11 位手机号");
        if (code == null || !code.trim().matches("\\d{6}")) throw fail("请输入 6 位验证码");
        GbSmsCode r = codeMapper.selectOne(new LambdaQueryWrapper<GbSmsCode>().eq(GbSmsCode::getPhone, phone).eq(GbSmsCode::getStatus, "sent")
                .orderByDesc(GbSmsCode::getCreateTime).last("limit 1"));
        if (r == null || r.getExpireTime().before(new Date())) throw fail("验证码已过期，请重新获取");
        // 先占一次机会再比对：并发猜也超不过 5 次
        int got = codeMapper.update(null, new LambdaUpdateWrapper<GbSmsCode>().eq(GbSmsCode::getId, r.getId()).eq(GbSmsCode::getStatus, "sent")
                .lt(GbSmsCode::getAttempts, MAX_WRONG).setSql("attempts = attempts + 1"));
        if (got == 0) throw fail("输错次数太多，请重新获取验证码");
        if (!MessageDigest.isEqual(hash(phone, code.trim()).getBytes(StandardCharsets.UTF_8), r.getCodeHash().getBytes(StandardCharsets.UTF_8))) {
            int left = MAX_WRONG - (r.getAttempts() + 1);
            throw fail(left > 0 ? "验证码不对，还能再试 " + left + " 次" : "输错次数太多，请重新获取验证码");
        }
        if (codeMapper.update(null, new LambdaUpdateWrapper<GbSmsCode>().eq(GbSmsCode::getId, r.getId()).eq(GbSmsCode::getStatus, "sent").set(GbSmsCode::getStatus, "used")) == 0)
            throw fail("验证码已经用过了，请重新获取");
    }

    /** 真正发短信，返回服务商的回执（记到 note） */
    private String deliver(String phone, String code) throws Exception {
        if ("mock".equals(provider())) {
            log.info("[团购] 短信验证码（mock，没有真发）{}：{}", GbViewService.mask(phone), code);
            return "mock";
        }
        if (!"aliyun".equals(provider())) throw new IllegalStateException("不支持的短信服务商 " + provider());
        String miss = missing();
        if (miss != null) throw new IllegalStateException("阿里云短信没配齐，缺 " + miss);
        GbProperties.Sms c = props.getSms();
        String endpoint = c.getEndpoint() == null || c.getEndpoint().isBlank() ? "dysmsapi.aliyuncs.com" : c.getEndpoint().trim();
        boolean plainHttp = endpoint.startsWith("http://");
        endpoint = endpoint.replaceFirst("^https?://", "").replaceAll("/+$", "");
        DefaultAcsClient client = new DefaultAcsClient(DefaultProfile.getProfile("cn-hangzhou", c.getAccessKeyId(), c.getAccessKeySecret()));
        SendSmsRequest req = new SendSmsRequest();
        req.setSysEndpoint(endpoint);
        req.setSysProtocol(plainHttp ? ProtocolType.HTTP : ProtocolType.HTTPS);
        req.setPhoneNumbers(phone);
        req.setSignName(c.getSignName());
        req.setTemplateCode(c.getTemplateCode());
        req.setTemplateParam("{\"code\":\"" + code + "\"}");
        SendSmsResponse resp = client.getAcsResponse(req);
        if (!"OK".equalsIgnoreCase(resp.getCode())) throw new IllegalStateException("阿里云返回 " + resp.getCode() + "：" + resp.getMessage());
        return "OK " + resp.getBizId();
    }

    String hash(String phone, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(("sms:" + props.getTokenSecret()).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((phone + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cut(String s) {
        return s == null || s.length() <= 255 ? s : s.substring(0, 255);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
