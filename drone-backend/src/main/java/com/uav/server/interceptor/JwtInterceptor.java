package com.uav.server.interceptor;

import com.uav.server.annotation.RequireRole;
import com.uav.server.annotation.SkipJwt;
import com.uav.server.exception.UnauthorizedException;
import com.uav.server.util.JwtUtil;
import com.uav.server.util.UserContext;
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
            Long userId = jwtUtil.extractUserId(token);
            String username = jwtUtil.extractUsername(token);
            Integer role = jwtUtil.extractRole(token);
            UserContext.setUser(userId, username, role);
            MDC.put("userId", String.valueOf(userId));
            log.debug("Set user: {}(id={}, role={}) into context", username, userId, role);
        } catch (JwtException e) {
            throw new UnauthorizedException("Failed to extract from token");
        }

        //角色鉴权
        if (handler instanceof HandlerMethod) {
            HandlerMethod handlerMethod = (HandlerMethod) handler;
            RequireRole requireRole = handlerMethod.getMethodAnnotation(RequireRole.class);
            if (requireRole == null) {
                requireRole = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
            }
            if (requireRole != null && requireRole.value().length > 0) {
                Integer userRole = UserContext.getRole();
                boolean allowed = java.util.Arrays.stream(requireRole.value())
                        .anyMatch(r -> r == (userRole != null ? userRole : -1));
                if (!allowed) {
                    throw new UnauthorizedException("权限不足");
                }
            }
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
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
