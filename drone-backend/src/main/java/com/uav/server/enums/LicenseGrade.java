package com.uav.server.enums;

import lombok.Getter;

/**
 * 无人机驾驶员执照等级（飞手资质 {@code rider_qualification.license_grade}，按枚举名持久化）。
 *
 * <p>{@link #getLabel()} 为面向客户端的中文展示名，接口在 {@code licenseGradeLabel} 字段回显，
 * 客户端不必自行维护映射表。
 */
@Getter
public enum LicenseGrade {
    VLOS("视距内"),
    BVLOS("超视距"),
    TEACHER("教员");

    private final String label;

    LicenseGrade(String label) {
        this.label = label;
    }
}
