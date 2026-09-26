package com.uav.server.enums;

import lombok.Getter;

/**
 * 吊运货物类别（REQ-BACKEND-001 计价规则「货物类别」，与
 * {@code transport.pricing.category-surcharge} 配置表一一对齐：CONSTRUCTION/EQUIPMENT/AGRICULTURAL）。
 *
 * <p>类别决定报价中的类别附加费；未指定类别（null）按
 * {@code transport.pricing.unknown-category-surcharge} 计费，行为确定。新增类别必须同步
 * 更新计价配置，否则会静默落入未知类别附加费。
 */
@Getter
public enum CargoCategory {

    /** 建材。 */
    CONSTRUCTION("建材"),

    /** 设备。 */
    EQUIPMENT("设备"),

    /** 农产品。 */
    AGRICULTURAL("农产品");

    private final String description;

    CargoCategory(String description) {
        this.description = description;
    }
}
