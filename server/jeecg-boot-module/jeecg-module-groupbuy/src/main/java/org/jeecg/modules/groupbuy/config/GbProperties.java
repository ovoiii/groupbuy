package org.jeecg.modules.groupbuy.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 团购模块配置，前缀 groupbuy（见 application-dev.yml） */
@Data
@Component
@ConfigurationProperties(prefix = "groupbuy")
public class GbProperties {
    /** 小程序登录凭证签名密钥，生产环境必须改 */
    private String tokenSecret = "change-me-groupbuy-secret";
    /** 登录有效天数 */
    private int tokenDays = 30;
    /** 开发登录：用手机号直接登录（没有微信 AppID 时用），生产环境关掉 */
    private boolean devLogin = true;
    /** 模拟支付：点「微信支付」直接成功，没有商户号时用，生产环境关掉 */
    private boolean payMock = true;
    /** 待付款多少分钟后自动关闭 */
    private int payTimeoutMinutes = 15;
    /** 售后多少小时内没处理自动同意 */
    private int aftersaleAutoHours = 24;
    /** 提货后多少小时内可以申请售后 */
    private int aftersaleWindowHours = 24;
    /** 团长单笔最少提现多少元 */
    private int withdrawMin = 10;
    /** 空库时写入演示数据（商品、提货点、团期、订单） */
    private boolean demoSeed = true;
    /** 微信小程序 */
    private Wx wx = new Wx();
    /** 订阅消息 */
    private Notify notify = new Notify();
    /** 微信支付 */
    private Pay pay = new Pay();

    @Data
    public static class Wx {
        private String appid;
        private String secret;
        /** 微信接口地址，一般不用改（测试时可以指到本地模拟服务） */
        private String apiBase = "https://api.weixin.qq.com";
    }

    /** 微信支付（API v3）。pay-mock=false 时必须配齐 */
    @Data
    public static class Pay {
        /** 商户号 */
        private String mchId;
        /** 商户 API 证书序列号 */
        private String merchantSerialNumber;
        /** 商户 API 私钥文件 apiclient_key.pem 的路径 */
        private String privateKeyPath;
        /** APIv3 密钥（32 位） */
        private String apiV3Key;
        /** 微信支付公钥 ID（PUB_KEY_ID_ 开头）。新商户用公钥；不填则用平台证书（自动下载） */
        private String publicKeyId;
        /** 微信支付公钥文件 pub_key.pem 的路径 */
        private String publicKeyPath;
        /** 后端对外地址，用来拼支付 / 退款结果通知地址，必须是 https，如 https://shop.example.com/jeecg-boot */
        private String notifyBase;
    }

    @Data
    public static class Notify {
        /** 点开消息跳到哪个版本：developer 开发版 / trial 体验版 / formal 正式版 */
        private String state = "formal";
        /** 场景 → 模板。场景：arrived 到货 / pickup 取货提醒 / aftersale 售后结果 / ship 发车 / apply 入驻审核 / withdraw 提现结果 */
        private Map<String, Scene> scenes = new LinkedHashMap<>();
    }

    @Data
    public static class Scene {
        /** 公众平台「订阅消息」里选用模板后得到的模板 ID；不填就只记录不发送 */
        private String templateId;
        /** 模板字段 → 内容，如 thing1: point。可用的内容见 GbNotifyService 各场景的说明 */
        private Map<String, String> fields = new LinkedHashMap<>();
    }
}
