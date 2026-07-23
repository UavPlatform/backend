package com.uav.server.enums;

import lombok.Getter;

@Getter
public enum ComplaintStatus {
    PENDING("待处理"),
    APPROVED("已批准"),
    REJECTED("已驳回");

    private final String desc;

    ComplaintStatus(String desc) {
        this.desc = desc;
    }
}
