package com.drone.server.config;

import com.drone.server.handler.DroneWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final DroneWebSocketHandler droneWebSocketHandler;

    public WebSocketConfig(DroneWebSocketHandler droneWebSocketHandler) {
        this.droneWebSocketHandler = droneWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(droneWebSocketHandler, "/ws/drone")
                .setAllowedOrigins("*");
    }
}