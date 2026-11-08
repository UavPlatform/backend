package com.uav.server.ws;

/**
 * WebSocket message vocabulary.
 *
 * <p>These constants must match {@code backend/spec/ws/ws-manifest.json}.
 * Do not change a value without updating the manifest, schemas and examples;
 * {@code WsProtocolContractIT} enforces the correspondence.
 */
public final class WsMessageNames {

    public static final String TYPE_EVENT = "event";

    public static final String FLIGHT_TELEMETRY = "FLIGHT_TELEMETRY";

    private WsMessageNames() {
    }
}
