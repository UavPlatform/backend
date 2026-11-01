package com.uav.server.ws.auth;

import com.uav.server.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * WebSocket 握手鉴权。DEVICE_CHANNEL 用注入的 deviceAccess（派单连机或已批准借用），不再默认 rider_uav。
 */
@Slf4j
public class WsAuthHandshakeInterceptor implements HandshakeInterceptor {

    public static final int ROLE_RIDER = 1;
    public static final String ATTR_USER_ID = "wsUserId";
    public static final String ATTR_USERNAME = "wsUsername";
    public static final String ATTR_ROLE = "wsRole";

    public enum Mode { DEVICE_CHANNEL, WEB_CHANNEL, CHAT }

    private final JwtUtil jwtUtil;
    private final Mode mode;
    private final BiPredicate<Long, String> deviceAccess;

    public WsAuthHandshakeInterceptor(JwtUtil jwtUtil, Mode mode) {
        this(jwtUtil, mode, (pilot, device) -> false);
    }

    public WsAuthHandshakeInterceptor(JwtUtil jwtUtil, Mode mode, BiPredicate<Long, String> deviceAccess) {
        this.jwtUtil = jwtUtil;
        this.mode = mode;
        this.deviceAccess = deviceAccess == null ? (p, d) -> false : deviceAccess;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = extractToken(request);
        if (!StringUtils.hasText(token) || !jwtUtil.validateToken(token)) {
            log.warn("拒绝 WebSocket 握手（{}）：令牌缺失或无效, uri={}", mode, request.getURI());
            return false;
        }

        Long userId;
        String username;
        Integer role;
        try {
            userId = jwtUtil.extractUserId(token);
            username = jwtUtil.extractUsername(token);
            role = jwtUtil.extractRole(token);
        } catch (io.jsonwebtoken.JwtException e) {
            log.warn("拒绝 WebSocket 握手（{}）：令牌解析失败, uri={}", mode, request.getURI());
            return false;
        }
        if (userId == null) {
            log.warn("拒绝 WebSocket 握手（{}）：令牌缺少用户身份, uri={}", mode, request.getURI());
            return false;
        }

        if (mode == Mode.CHAT) {
            String sid = lastPathSegment(request);
            if (sid == null || !sid.equals(String.valueOf(userId))) {
                log.warn("拒绝 WebSocket 握手（聊天）：连接身份与令牌不符, userId={}, path={}", userId, request.getURI());
                return false;
            }
        } else if (mode == Mode.DEVICE_CHANNEL) {
            String deviceId = queryParam(request, "deviceId");
            if (!StringUtils.hasText(deviceId)) {
                log.warn("拒绝 WebSocket 握手（设备通道）：缺少 deviceId, uri={}", request.getURI());
                return false;
            }
            if (role == null || role != ROLE_RIDER) {
                log.warn("拒绝 WebSocket 握手（设备通道）：非飞手身份连接设备通道, userId={}, role={}", userId, role);
                return false;
            }
            if (!deviceAccess.test(userId, deviceId)) {
                log.warn("拒绝 WebSocket 握手（设备通道）：设备不是该飞手派单连机或已批准借用机, userId={}, deviceId={}", userId, deviceId);
                return false;
            }
        }

        attributes.put(ATTR_USER_ID, userId);
        attributes.put(ATTR_USERNAME, username);
        attributes.put(ATTR_ROLE, role);
        if (log.isDebugEnabled()) {
            log.debug("WebSocket 握手鉴权通过（{}）：userId={}, uri={}", mode, userId, request.getURI());
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    private String extractToken(ServerHttpRequest request) {
        String bearerToken = request.getHeaders().getFirst("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return queryParam(request, "token");
    }

    private String queryParam(ServerHttpRequest request, String name) {
        if (request.getURI() == null) return null;
        List<String> values = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().get(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    private String lastPathSegment(ServerHttpRequest request) {
        if (request.getURI() == null || request.getURI().getPath() == null) return null;
        String path = request.getURI().getPath();
        int lastSlash = path.lastIndexOf('/');
        return lastSlash < 0 || lastSlash == path.length() - 1 ? null : path.substring(lastSlash + 1);
    }
}
