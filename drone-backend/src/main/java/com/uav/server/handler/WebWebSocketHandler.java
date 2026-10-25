package com.uav.server.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import com.uav.flight.service.FlightTelemetryBroadcaster;
import com.uav.server.ws.service.LiveWebSessionService;

@Slf4j
@Component
public class WebWebSocketHandler extends TextWebSocketHandler {

    private final LiveWebSessionService liveWebSessionService;
    private final FlightTelemetryBroadcaster flightTelemetryBroadcaster;

    public WebWebSocketHandler(LiveWebSessionService liveWebSessionService, FlightTelemetryBroadcaster flightTelemetryBroadcaster) {
        this.liveWebSessionService = liveWebSessionService;
        this.flightTelemetryBroadcaster = flightTelemetryBroadcaster;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws java.io.IOException {
        // FLIGHT_TELEMETRY: supervisors get all orders; ?orderId= limits to one order (owner or supervisor).
        if (!flightTelemetryBroadcaster.register(session, getQueryParam(session, "orderId"))) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        String deviceId = getDeviceId(session);
        if (deviceId != null && !deviceId.isBlank()) {
            liveWebSessionService.registerSession(deviceId, session);
        } else {
            liveWebSessionService.registerDashboardSession(session);
        }
        log.info("Web端连接已建立");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        flightTelemetryBroadcaster.unregister(session);
        String deviceId = getDeviceId(session);
        if (deviceId != null && !deviceId.isBlank()) {
            liveWebSessionService.removeSession(deviceId);
        } else {
            liveWebSessionService.removeDashboardSession(session);
        }
        log.info("Web端连接已关闭");
    }

    private String getDeviceId(WebSocketSession session) {
        return getQueryParam(session, "deviceId");
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
