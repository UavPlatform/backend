package com.uav.server.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.server.annotation.RequireDrone;
import com.uav.server.annotation.RequireRole;
import com.uav.server.annotation.SkipJwt;
import com.uav.server.result.Result;
import com.uav.server.util.JwtUtil;
import com.uav.server.util.UserContext;
import com.uav.user.mapper.RiderUavRepository;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;


@Slf4j
@Component
public class JwtInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private RiderUavRepository riderUavRepository;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, @Nullable Object handler) throws Exception {
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
            sendUnauthorized(response, "Missing token");
            return false;
        }

        if (!jwtUtil.validateToken(token)) {
            sendUnauthorized(response, "Invalid token");
            return false;
        }

        try {
            Long userId = jwtUtil.extractUserId(token);
            String username = jwtUtil.extractUsername(token);
            Integer role = jwtUtil.extractRole(token);
            UserContext.setUser(userId, username, role);
            MDC.put("userId", String.valueOf(userId));
            log.debug("Set user: {}(id={}, role={}) into context", username, userId, role);
        } catch (JwtException e) {
            sendUnauthorized(response, "Failed to extract from token");
            return false;
        }

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
                    sendUnauthorized(response, "权限不足");
                    return false;
                }
            }
        }

        // 仅在端点标注了 @RequireDrone 时才检查无人机绑定
        Integer role = UserContext.getRole();
        if (role != null && role == 1) {
            if (handler instanceof HandlerMethod) {
                HandlerMethod handlerMethod = (HandlerMethod) handler;
                boolean requireDrone = handlerMethod.hasMethodAnnotation(RequireDrone.class)
                        || handlerMethod.getBeanType().isAnnotationPresent(RequireDrone.class);
                if (requireDrone && !riderUavRepository.existsByUserId(UserContext.getUserId())) {
                    sendUnauthorized(response, "请先绑定至少一台无人机");
                    return false;
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

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        Result<Void> result = Result.fail(401, "UNAUTHORIZED", message);
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
