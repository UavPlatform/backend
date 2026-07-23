package com.uav.server.enums;

import lombok.Getter;

@Getter
public enum ComplaintReason {
    QUALITY_ISSUE("质量问题"),
    SERVICE_ISSUE("服务问题"),
    NOT_AS_DESCRIBED("与描述不符"),
    OTHER("其他");

    private final String desc;

    ComplaintReason(String desc) {
        this.desc = desc;
    }
}
