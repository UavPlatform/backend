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

/**
 * WebSocket 握手鉴权：令牌必须有效且带用户身份。
 * {@code CHAT} 额外要求路径身份与令牌一致（{@code /ws/{sid}}）。
 */
@Slf4j
public class WsAuthHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USER_ID = "wsUserId";
    public static final String ATTR_USERNAME = "wsUsername";
    public static final String ATTR_ROLE = "wsRole";

    public enum Mode { WEB_CHANNEL, CHAT }

    private final JwtUtil jwtUtil;
    private final Mode mode;

    public WsAuthHandshakeInterceptor(JwtUtil jwtUtil, Mode mode) {
        this.jwtUtil = jwtUtil;
        this.mode = mode;
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
