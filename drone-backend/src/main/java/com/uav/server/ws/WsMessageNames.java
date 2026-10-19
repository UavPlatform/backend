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
    public static final String TYPE_RESPONSE = "response";
    public static final String TYPE_ERROR = "error";
    public static final String TYPE_COMMAND = "command";

    public static final String PING = "PING";
    public static final String PONG = "PONG";
    public static final String UAV_STATUS = "UAV_STATUS";
    public static final String UAV_STATUS_UPDATE = "UAV_STATUS_UPDATE";
    public static final String LIVE_STARTED = "LIVE_STARTED";
    public static final String LIVE_STOPPED = "LIVE_STOPPED";
    public static final String CONNECT_SUCCESS = "CONNECT_SUCCESS";
    public static final String ERROR = "ERROR";
    public static final String START_LIVE = "START_LIVE";
    public static final String STOP_LIVE = "STOP_LIVE";

    private WsMessageNames() {
    }
}
