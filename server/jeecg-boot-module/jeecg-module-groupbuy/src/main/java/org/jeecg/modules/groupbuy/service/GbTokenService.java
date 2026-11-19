package org.jeecg.modules.groupbuy.service;

import lombok.RequiredArgsConstructor;
import org.jeecg.modules.groupbuy.config.GbProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** 小程序登录凭证：memberId.过期时间戳.HMAC 签名，无状态、不占 Redis */
@Service
@RequiredArgsConstructor
public class GbTokenService {
    private final GbProperties props;

    public String issue(String memberId) {
        long exp = System.currentTimeMillis() + props.getTokenDays() * 86400_000L;
        String body = memberId + "." + exp;
        return body + "." + sign(body);
    }

    /** 合法返回 memberId，否则 null */
    public String verify(String token) {
        if (token == null) return null;
        String[] p = token.split("[.]");
        if (p.length != 3) return null;
        String body = p[0] + "." + p[1];
        if (!MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8), p[2].getBytes(StandardCharsets.UTF_8))) return null;
        try {
            if (Long.parseLong(p[1]) < System.currentTimeMillis()) return null;
        } catch (NumberFormatException e) {
            return null;
        }
        return p[0];
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.getTokenSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
