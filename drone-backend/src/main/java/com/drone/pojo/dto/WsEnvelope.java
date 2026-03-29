package com.drone.pojo.dto;

import lombok.Data;

@Data
public class WsEnvelope {
    private String id;
    private String type;
    private String name;
    private String replyTo;
    private String deviceId;
    private Long timestamp;
    private Boolean success;
    private String code;
    private String message;
    private Object data;
}
