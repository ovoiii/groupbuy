package org.jeecg.modules.groupbuy.config;

import com.alibaba.fastjson2.JSON;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.groupbuy.service.GbTokenService;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/** 校验小程序请求头 X-Gb-Token，把 memberId 放进 request attribute */
@Component
@RequiredArgsConstructor
public class GbAuthInterceptor implements HandlerInterceptor {
    public static final String HEADER = "X-Gb-Token";
    public static final String ATTR = "gbMemberId";
    private final GbTokenService tokenService;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        if (HttpMethod.OPTIONS.matches(req.getMethod())) return true;
        String memberId = tokenService.verify(req.getHeader(HEADER));
        if (memberId == null) {
            resp.setStatus(200);
            resp.setContentType("application/json;charset=UTF-8");
            Result<?> r = Result.error(401, "请先登录");
            resp.getOutputStream().write(JSON.toJSONString(r).getBytes(StandardCharsets.UTF_8));
            return false;
        }
        req.setAttribute(ATTR, memberId);
        return true;
    }
}
