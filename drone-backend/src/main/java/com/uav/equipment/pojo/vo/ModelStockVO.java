package com.uav.equipment.pojo.vo;
import java.util.List;
/** In-stock overview by model for the pilot apply screen. */
public record ModelStockVO(List<Item> items) {
    public record Item(Long aircraftModelId, String modelName, String modelCode, long inStockCount) { }
}
