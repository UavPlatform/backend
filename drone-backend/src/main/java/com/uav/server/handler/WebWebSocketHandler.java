package com.uav.server.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import com.uav.flight.service.FlightTelemetryBroadcaster;

@Slf4j
@Component
public class WebWebSocketHandler extends TextWebSocketHandler {

    private final FlightTelemetryBroadcaster flightTelemetryBroadcaster;

    public WebWebSocketHandler(FlightTelemetryBroadcaster flightTelemetryBroadcaster) {
        this.flightTelemetryBroadcaster = flightTelemetryBroadcaster;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws java.io.IOException {
        // FLIGHT_TELEMETRY: supervisors get all orders; ?orderId= limits to one order (owner or supervisor).
        if (!flightTelemetryBroadcaster.register(session, getQueryParam(session, "orderId"))) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        log.info("Web端连接已建立");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        flightTelemetryBroadcaster.unregister(session);
        log.info("Web端连接已关闭");
    }

    private String getQueryParam(WebSocketSession session, String name) {
        if (session.getUri() == null) {
            return null;
        }
        return UriComponentsBuilder.fromUri(session.getUri())
                .build()
                .getQueryParams()
                .getFirst(name);
    }
}
