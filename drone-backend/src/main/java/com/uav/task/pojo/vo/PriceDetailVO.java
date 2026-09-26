package com.uav.task.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** 计费明细：total = items 之和（明细可加性），items 顺序固定：起步价/里程费/重量费/夜间附加费，0 元项省略 */
@Data
public class PriceDetailVO {
    private BigDecimal total;
    private List<PriceItem> items;
    private BigDecimal distanceKm;
    /** 计费所用货物重量（kg），与 {@code Task.cargoWeightKg} 同源 */
    private BigDecimal weightKg;
    private Boolean isNight;
    /** true = 重量超出阶梯上限，平台将人工协商报价/调配机型 */
    private Boolean needManualQuote;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriceItem {
        private String name;
        private BigDecimal amount;
    }
}
