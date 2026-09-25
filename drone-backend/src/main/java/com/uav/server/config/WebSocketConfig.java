package com.uav.server.config;

import com.uav.chat.websocket.ChatWebSocketHandler;
import com.uav.server.handler.DroneWebSocketHandler;
import com.uav.server.handler.WebWebSocketHandler;
import com.uav.server.util.JwtUtil;
import com.uav.server.ws.auth.WsAuthHandshakeInterceptor;
import com.uav.user.mapper.RiderUavRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final DroneWebSocketHandler droneWebSocketHandler;

    private final WebWebSocketHandler webWebSocketHandler;

    private final ChatWebSocketHandler chatWebSocketHandler;

    private final JwtUtil jwtUtil;

    private final RiderUavRepository riderUavRepository;

    public WebSocketConfig(DroneWebSocketHandler droneWebSocketHandler,
                           WebWebSocketHandler webWebSocketHandler,
                           ChatWebSocketHandler chatWebSocketHandler,
                           JwtUtil jwtUtil,
                           RiderUavRepository riderUavRepository) {
        this.droneWebSocketHandler = droneWebSocketHandler;
        this.webWebSocketHandler = webWebSocketHandler;
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.jwtUtil = jwtUtil;
        this.riderUavRepository = riderUavRepository;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // App端WebSocket端点（P0-1：握手需 JWT + 飞手角色 + 设备归属校验）
        registry.addHandler(droneWebSocketHandler, "/ws/drone")
                .addInterceptors(new WsAuthHandshakeInterceptor(
                        jwtUtil, riderUavRepository, WsAuthHandshakeInterceptor.Mode.DEVICE_CHANNEL))
                .setAllowedOrigins("*");

        // Web端WebSocket端点（P0-1：握手需登录）
        registry.addHandler(webWebSocketHandler, "/ws/web")
                .addInterceptors(new WsAuthHandshakeInterceptor(
                        jwtUtil, riderUavRepository, WsAuthHandshakeInterceptor.Mode.WEB_CHANNEL))
                .setAllowedOrigins("*");

        // 聊天通道（P0-1：握手需 JWT 且令牌 userId == 路径 sid）。
        // 原为 Jakarta @ServerEndpoint，因 WsFilter 模板匹配会遮蔽上面两个通道，迁移至此；
        // Spring 精确路径优先于 /ws/{sid} 模板，三个通道可共存。
        registry.addHandler(chatWebSocketHandler, "/ws/{sid}")
                .addInterceptors(new WsAuthHandshakeInterceptor(
                        jwtUtil, riderUavRepository, WsAuthHandshakeInterceptor.Mode.CHAT))
                .setAllowedOrigins("*");
    }
}
