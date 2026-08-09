package com.uav.server.enums;

import lombok.Getter;

@Getter
public enum AircraftCategory {
    MULTI_ROTOR("MULTI_ROTOR", "多旋翼"),
    FIXED_WING("FIXED_WING", "固定翼"),
    VTOL("VTOL", "垂直起降"),
    HELICOPTER("HELICOPTER", "直升机");

    private final String code;
    private final String desc;

    AircraftCategory(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
