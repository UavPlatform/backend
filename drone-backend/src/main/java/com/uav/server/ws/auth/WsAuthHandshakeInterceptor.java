package com.uav.server.ws.auth;

import com.uav.server.util.JwtUtil;
import com.uav.user.mapper.RiderUavRepository;
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
 * WebSocket 握手鉴权拦截器（修复 P0-1：/ws/{sid}、/ws/drone、/ws/web 握手无鉴权）。
 *
 * <p>协议：与 REST 共用同一枚登录 accessToken，
 * <ul>
 *   <li>首选：握手请求头 {@code Authorization: Bearer <accessToken>}（原生客户端）</li>
 *   <li>兜底：握手 URL query {@code ?token=<accessToken>}（浏览器 WebSocket API 无法自定义握手头）</li>
 * </ul>
 *
 * <p>拦截器返回 false 时，Spring 以 403 FORBIDDEN 拒绝握手。
 * <ul>
 *   <li>DEVICE_CHANNEL（/ws/drone）：要求 role=1（飞手）且 deviceId 已绑定到该飞手；</li>
 *   <li>WEB_CHANNEL（/ws/web）：仅要求有效登录；</li>
 *   <li>CHAT（/ws/{sid}）：要求令牌 userId 等于路径中的 sid（不能冒充他人收消息）。</li>
 * </ul>
 */
@Slf4j
public class WsAuthHandshakeInterceptor implements HandshakeInterceptor {

    /** 飞手角色码（P2-29 角色常量化的一部分，先固定使用） */
    public static final int ROLE_RIDER = 1;

    public static final String ATTR_USER_ID = "wsUserId";
    public static final String ATTR_USERNAME = "wsUsername";
    public static final String ATTR_ROLE = "wsRole";

    public enum Mode { DEVICE_CHANNEL, WEB_CHANNEL, CHAT }

    private final JwtUtil jwtUtil;
    private final RiderUavRepository riderUavRepository;
    private final Mode mode;

    public WsAuthHandshakeInterceptor(JwtUtil jwtUtil, RiderUavRepository riderUavRepository, Mode mode) {
        this.jwtUtil = jwtUtil;
        this.riderUavRepository = riderUavRepository;
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
            if (!riderUavRepository.existsByUserIdAndDjiId(userId, deviceId)) {
                log.warn("拒绝 WebSocket 握手（设备通道）：设备未绑定到该飞手, userId={}, deviceId={}", userId, deviceId);
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
        // no-op
    }

    private String extractToken(ServerHttpRequest request) {
        String bearerToken = request.getHeaders().getFirst("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return queryParam(request, "token");
    }

    private String queryParam(ServerHttpRequest request, String name) {
        if (request.getURI() == null) {
            return null;
        }
        List<String> values = UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .get(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    private String lastPathSegment(ServerHttpRequest request) {
        if (request.getURI() == null || request.getURI().getPath() == null) {
            return null;
        }
        String path = request.getURI().getPath();
        int lastSlash = path.lastIndexOf('/');
        return lastSlash < 0 || lastSlash == path.length() - 1 ? null : path.substring(lastSlash + 1);
    }
}
