package com.uav.server.enums;

import lombok.Getter;

@Getter
public enum UavWeight {
    SMALL("SMALL", "小型"),
    MEDIUM("MEDIUM", "中型");

    private final String code;
    private final String desc;

    UavWeight(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
