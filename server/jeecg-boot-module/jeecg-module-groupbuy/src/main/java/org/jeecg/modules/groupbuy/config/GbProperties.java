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
    /** 短信验证码登录 */
    private Sms sms = new Sms();
    /** 小程序 H5 版地址（如 https://shop.example.com/h5），没配小程序 AppID 时海报上的二维码指到这里；都没配就不画二维码 */
    private String h5Base;

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
        /** 团长提现到微信零钱用「商家转账」自动打款（要在商户平台开通商家转账）；false 就只能线下打款后登记 */
        private boolean transferEnabled = true;
        /** 商家转账的转账场景 ID，商户平台「商家转账 → 场景管理」里开通的；1005 = 佣金报酬 */
        private String transferSceneId = "1005";
        /** 用户收款时看到的收款感知，要和场景匹配，1005 场景可选「劳务报酬」「报销款」等 */
        private String transferPerception = "劳务报酬";
        /** 转账备注，用户在微信账单里能看到，最多 32 个字 */
        private String transferRemark = "邻里鲜团长佣金提现";
        /** 转账场景报备信息（1005 佣金报酬场景要报）：岗位类型 */
        private String transferPostType = "社区团购团长";
        /** 转账场景报备信息：报酬说明 */
        private String transferPayDesc = "团购订单佣金";
        /** 微信支付接口地址，只有商家转账用，一般不用改（测试时可以指到本地模拟服务） */
        private String apiBase = "https://api.mch.weixin.qq.com";
    }

    /** 短信验证码。provider：mock 只记日志不发（开发用）/ aliyun 阿里云短信 / off 关掉短信登录 */
    @Data
    public static class Sms {
        private String provider = "mock";
        /** 阿里云 AccessKey（建议只授权短信发送的 RAM 子账号） */
        private String accessKeyId;
        private String accessKeySecret;
        /** 短信签名名称，如「邻里鲜」 */
        private String signName;
        /** 验证码模板 CODE，模板内容要有 ${code} 变量，如 SMS_123456789 */
        private String templateCode;
        /** 阿里云短信接口域名，一般不用改（测试时可以指到本地模拟服务，如 127.0.0.1:18080） */
        private String endpoint = "dysmsapi.aliyuncs.com";
        /** 验证码有效分钟数 */
        private int codeMinutes = 5;
        /** 同一个手机号两次发送至少间隔多少秒 */
        private int intervalSeconds = 60;
        /** 同一个手机号每天最多发几条 */
        private int perPhoneDaily = 10;
        /** 同一个 IP 每小时最多发几条（防刷） */
        private int perIpHourly = 20;
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
