package com.drone.server.ws.handler;

import com.alibaba.fastjson.JSONObject;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.util.UriComponentsBuilder;

final class WsSessionDeviceIdResolver {

    private WsSessionDeviceIdResolver() {
    }

    static String resolve(WebSocketSession session, JSONObject json) {
        String fromAttributes = (String) session.getAttributes().get("deviceId");
        if (isNotBlank(fromAttributes)) {
            return fromAttributes;
        }

        if (session.getUri() != null) {
            String fromQuery = UriComponentsBuilder.fromUri(session.getUri())
                    .build()
                    .getQueryParams()
                    .getFirst("deviceId");
            if (isNotBlank(fromQuery)) {
                return fromQuery;
            }
        }

        if (json != null) {
            String fromEnvelope = json.getString("deviceId");
            if (isNotBlank(fromEnvelope)) {
                return fromEnvelope;
            }

            JSONObject data = json.getJSONObject("data");
            if (data != null) {
                String fromPayload = data.getString("deviceId");
                if (isNotBlank(fromPayload)) {
                    return fromPayload;
                }
            }
        }

        return null;
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
