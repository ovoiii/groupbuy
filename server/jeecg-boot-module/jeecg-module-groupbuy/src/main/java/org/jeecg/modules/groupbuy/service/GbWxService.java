package org.jeecg.modules.groupbuy.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.groupbuy.config.GbProperties;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.jeecg.modules.groupbuy.service.GbCoreService.fail;

/**
 * 微信小程序服务端接口：登录换 openid、access_token、手机号、通用 POST。
 * access_token 全局只有一份（登录和订阅消息共用），用稳定版接口，提前 5 分钟刷新。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GbWxService {
    private final GbProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile String accessToken;
    private volatile long tokenExpireAt;

    public boolean ready() {
        GbProperties.Wx wx = props.getWx();
        return notBlank(wx.getAppid()) && notBlank(wx.getSecret());
    }

    private String base() {
        String b = props.getWx().getApiBase();
        return notBlank(b) ? b.replaceAll("/+$", "") : "https://api.weixin.qq.com";
    }

    /** wx.login 的 code 换 openid（code 只能用一次，5 分钟有效） */
    public String openid(String code) {
        if (!ready()) throw fail("还没配置微信小程序 AppID，请先用手机号开发登录");
        if (!notBlank(code)) throw fail("登录参数不对，请重试");
        JSONObject j = get(base() + "/sns/jscode2session?appid=" + props.getWx().getAppid() + "&secret=" + props.getWx().getSecret()
                + "&js_code=" + URLEncoder.encode(code, StandardCharsets.UTF_8) + "&grant_type=authorization_code");
        String openid = j.getString("openid");
        if (openid == null) {
            log.warn("[团购] 微信登录失败 {} {}", j.getIntValue("errcode"), j.getString("errmsg"));
            throw fail("微信登录失败，请重试");
        }
        return openid;
    }

    /** 手机号快速验证组件（button open-type=getPhoneNumber）给的 code 换手机号；只返回中国大陆 11 位手机号 */
    public String phone(String code) {
        if (!notBlank(code)) throw fail("没拿到手机号，请重新授权");
        JSONObject body = new JSONObject();
        body.put("code", code);
        JSONObject j = postWithToken("/wxa/business/getuserphonenumber", body);
        if (j.getIntValue("errcode") != 0) {
            log.warn("[团购] 获取手机号失败 {} {}", j.getIntValue("errcode"), j.getString("errmsg"));
            throw fail("获取手机号失败，请重试");
        }
        JSONObject info = j.getJSONObject("phone_info");
        String phone = info == null ? null : info.getString("purePhoneNumber");
        String cc = info == null ? null : info.getString("countryCode");
        if (phone == null || !phone.matches("1\\d{10}") || (cc != null && !"86".equals(cc))) throw fail("目前只支持中国大陆手机号");
        return phone;
    }

    /** 带 access_token 调接口；token 失效时刷新后重试一次 */
    public JSONObject postWithToken(String path, JSONObject body) {
        JSONObject r = post(base() + path + "?access_token=" + token(false), body.toJSONString());
        int code = r.getIntValue("errcode");
        if (code == 40001 || code == 42001 || code == 40014) r = post(base() + path + "?access_token=" + token(true), body.toJSONString());
        return r;
    }

    /** 稳定版 access_token */
    public synchronized String token(boolean force) {
        if (!force && accessToken != null && System.currentTimeMillis() < tokenExpireAt) return accessToken;
        JSONObject req = new JSONObject();
        req.put("grant_type", "client_credential");
        req.put("appid", props.getWx().getAppid());
        req.put("secret", props.getWx().getSecret());
        req.put("force_refresh", false);
        JSONObject r = post(base() + "/cgi-bin/stable_token", req.toJSONString());
        String t = r.getString("access_token");
        if (t == null) throw new IllegalStateException("获取 access_token 失败：" + r.getString("errmsg"));
        accessToken = t;
        tokenExpireAt = System.currentTimeMillis() + (r.getLongValue("expires_in", 7200) - 300) * 1000L;
        return t;
    }

    /**
     * 小程序码（不限数量的那种）。scene 最多 32 个字符，扫码进 page 时以 scene 参数带上；
     * envVersion：release 正式版 / trial 体验版 / develop 开发版。失败抛 IllegalStateException
     */
    public byte[] wxacode(String scene, String page, String envVersion, int width) {
        if (!ready()) throw new IllegalStateException("没配小程序 AppID");
        JSONObject body = new JSONObject();
        body.put("scene", scene);
        body.put("page", page);
        body.put("check_path", false);
        body.put("env_version", envVersion);
        body.put("width", width);
        String json = body.toJSONString();
        for (int i = 0; i < 2; i++) {
            HttpResponse<byte[]> r;
            try {
                r = http.send(HttpRequest.newBuilder(URI.create(base() + "/wxa/getwxacodeunlimit?access_token=" + token(i > 0))).timeout(Duration.ofSeconds(8))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofByteArray());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("调用微信接口被中断");
            } catch (Exception e) {
                throw new IllegalStateException("调用微信接口出错：" + e.getMessage(), e);
            }
            // 成功返回图片；失败返回 JSON（errcode）
            String type = r.headers().firstValue("Content-Type").orElse("");
            byte[] b = r.body();
            if (!type.contains("json") && b.length > 0 && b[0] != '{') return b;
            JSONObject j = JSON.parseObject(new String(b, StandardCharsets.UTF_8));
            int code = j == null ? -1 : j.getIntValue("errcode");
            if (i == 0 && (code == 40001 || code == 42001 || code == 40014)) continue;
            throw new IllegalStateException("生成小程序码失败 " + code + "：" + (j == null ? "" : j.getString("errmsg")));
        }
        throw new IllegalStateException("生成小程序码失败");
    }

    private JSONObject get(String url) {
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build());
    }

    private JSONObject post(String url, String json) {
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    private JSONObject send(HttpRequest req) {
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            JSONObject j = JSON.parseObject(r.body());
            return j == null ? new JSONObject() : j;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用微信接口被中断");
        } catch (Exception e) {
            throw new IllegalStateException("调用微信接口出错：" + e.getMessage(), e);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
