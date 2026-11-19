package org.jeecg.modules.groupbuy.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
    /** 空库时写入演示数据（商品、提货点、团期、订单） */
    private boolean demoSeed = true;
    /** 微信小程序 */
    private Wx wx = new Wx();

    @Data
    public static class Wx {
        private String appid;
        private String secret;
    }
}
