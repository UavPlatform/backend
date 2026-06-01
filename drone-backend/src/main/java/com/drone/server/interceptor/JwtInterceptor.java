package com.drone.server.interceptor;

import com.drone.server.annotation.SkipJwt;
import com.drone.server.exception.UnauthorizedException;
import com.drone.server.util.JwtUtil;
import com.drone.server.util.UserContext;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;


@Slf4j
@Component
public class JwtInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 全链路 traceId
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put("traceId", traceId);
        response.setHeader("X-Trace-Id", traceId);

        if (handler instanceof HandlerMethod) {
            HandlerMethod handlerMethod = (HandlerMethod) handler;
            if (handlerMethod.hasMethodAnnotation(SkipJwt.class)) {
                log.debug("Skip JWT validation for method: {}", handlerMethod.getMethod().getName());
                return true;
            }
            if (handlerMethod.getBeanType().isAnnotationPresent(SkipJwt.class)) {
                log.debug("Skip JWT validation for class: {}", handlerMethod.getBeanType().getName());
                return true;
            }
        }

        String token = extractToken(request);
        if (!StringUtils.hasText(token)) {
            throw new UnauthorizedException("Missing token");
        }

        if (!jwtUtil.validateToken(token)) {
            throw new UnauthorizedException("Invalid token");
        }

        try {
            String username = jwtUtil.extractUsername(token);
            UserContext.setUsername(username);
            MDC.put("userId", username);
            log.debug("Set user: {} into context", username);
        } catch (JwtException e) {
            throw new UnauthorizedException("Failed to extract username from token");
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserContext.clear();
        MDC.clear();
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
