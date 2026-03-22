package com.drone.server.interceptor;

import com.drone.server.annotation.SkipJwt;
import com.drone.server.exception.UnauthorizedException;
import com.drone.server.util.JwtUtil;
import com.drone.server.util.UserContext;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


@Slf4j
@Component
public class JwtInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 检查是否有@SkipJwt注解
        if (handler instanceof HandlerMethod) {
            HandlerMethod handlerMethod = (HandlerMethod) handler;
            // 检查方法上是否有@SkipJwt注解
            if (handlerMethod.hasMethodAnnotation(SkipJwt.class)) {
                log.debug("Skip JWT validation for method: {}", handlerMethod.getMethod().getName());
                return true;
            }
            // 检查类上是否有@SkipJwt注解
            if (handlerMethod.getBeanType().isAnnotationPresent(SkipJwt.class)) {
                log.debug("Skip JWT validation for class: {}", handlerMethod.getBeanType().getName());
                return true;
            }
        }

        //从请求头获取 token
        String token = extractToken(request);
        if (!StringUtils.hasText(token)) {
            throw new UnauthorizedException("Missing token");
        }

        //验证 token
        if (!jwtUtil.validateToken(token)) {
            throw new UnauthorizedException("Invalid token");
        }

        //提取用户名并存入上下文
        try {
            String username = jwtUtil.extractUsername(token);
            UserContext.setUsername(username);
            log.debug("Set user: {} into context", username);
        } catch (JwtException e) {
            throw new UnauthorizedException("Failed to extract username from token");
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 清理上下文，避免内存泄漏
        UserContext.clear();
    }

    /**
     * 从请求头提取 Bearer token
     */
    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}