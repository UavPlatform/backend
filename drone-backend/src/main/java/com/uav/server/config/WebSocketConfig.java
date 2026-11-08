package com.uav.server.config;

import com.uav.chat.websocket.ChatWebSocketHandler;
import com.uav.server.handler.WebWebSocketHandler;
import com.uav.server.util.JwtUtil;
import com.uav.server.ws.auth.WsAuthHandshakeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WebWebSocketHandler webWebSocketHandler;
    private final ChatWebSocketHandler chatWebSocketHandler;
    private final JwtUtil jwtUtil;

    public WebSocketConfig(WebWebSocketHandler webWebSocketHandler,
                           ChatWebSocketHandler chatWebSocketHandler,
                           JwtUtil jwtUtil) {
        this.webWebSocketHandler = webWebSocketHandler;
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.jwtUtil = jwtUtil;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(webWebSocketHandler, "/ws/web")
                .addInterceptors(new WsAuthHandshakeInterceptor(
                        jwtUtil, WsAuthHandshakeInterceptor.Mode.WEB_CHANNEL))
                .setAllowedOrigins("*");

        registry.addHandler(chatWebSocketHandler, "/ws/{sid}")
                .addInterceptors(new WsAuthHandshakeInterceptor(
                        jwtUtil, WsAuthHandshakeInterceptor.Mode.CHAT))
                .setAllowedOrigins("*");
    }
}
