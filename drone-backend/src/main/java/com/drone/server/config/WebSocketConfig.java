package com.drone.server.config;

import com.drone.server.handler.DroneWebSocketHandler;
import com.drone.server.handler.WebWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final DroneWebSocketHandler droneWebSocketHandler;

    private final WebWebSocketHandler webWebSocketHandler;

    public WebSocketConfig(DroneWebSocketHandler droneWebSocketHandler,WebWebSocketHandler webWebSocketHandler) {
        this.droneWebSocketHandler = droneWebSocketHandler;
        this.webWebSocketHandler = webWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // App端WebSocket端点
        registry.addHandler(droneWebSocketHandler, "/ws/drone")
                .setAllowedOrigins("*");

        // Web端WebSocket端点
        registry.addHandler(webWebSocketHandler, "/ws/web")
                .setAllowedOrigins("*");
    }
}