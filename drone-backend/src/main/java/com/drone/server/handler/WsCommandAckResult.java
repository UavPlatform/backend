package com.drone.server.handler;

import lombok.Data;

@Data
public class WsCommandAckResult {
    private String requestId;
    private boolean success;
    private boolean timedOut;
    private String code;
    private String message;
}
