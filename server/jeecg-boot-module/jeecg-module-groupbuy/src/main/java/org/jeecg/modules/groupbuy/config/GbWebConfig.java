package org.jeecg.modules.groupbuy.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 小程序接口 /gb/app/** 用自己的登录凭证（不走后台的 Shiro 登录），并开启定时任务 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class GbWebConfig implements WebMvcConfigurer {
    private final GbAuthInterceptor gbAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(gbAuthInterceptor).addPathPatterns("/gb/app/**")
                .excludePathPatterns("/gb/app/login", "/gb/app/login/phone", "/gb/app/dev-login", "/gb/app/config",
                        "/gb/app/pay/notify", "/gb/app/pay/refund-notify", "/gb/app/pay/transfer-notify",
                        "/gb/app/sms/send", "/gb/app/login/sms", "/gb/app/scene");
    }
}
