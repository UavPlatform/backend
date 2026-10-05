package com.uav.server.config;

import com.uav.server.enums.CargoCategory;
import com.uav.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 计价配置与 {@link CargoCategory} 的对齐守卫。
 *
 * <p>背景：{@code transport.pricing.unknown-category-surcharge} 为 0.00，且计算器查不到类别时
 * <b>不报错</b>而是按未知类别计费 —— 也就是说「加了枚举却忘了配 yml」不会失败，只会静默少收钱。
 * 本类是该静默失败的唯一自动防线（{@link CargoCategory} javadoc 中的警告即指此事）。
 */
class TransportPricingConfigIT extends IntegrationTestBase {

    @Autowired
    private TransportPricingConfig pricingConfig;

    @Test
    @DisplayName("每个货物类别都在计价配置表里有附加费（漏配会静默按 0 元计费）")
    void everyCargoCategoryHasConfiguredSurcharge() {
        for (CargoCategory category : CargoCategory.values()) {
            assertThat(pricingConfig.getCategorySurcharge())
                    .as("类别 %s 未配置于 transport.pricing.category-surcharge，"
                            + "报价会静默按未知类别（%s 元）计费",
                            category.name(), pricingConfig.getUnknownCategorySurcharge())
                    .containsKey(category.name());
        }
    }

    @Test
    @DisplayName("新增类别附加费：生活物资 30 元、家具家居 60 元")
    void newCategoriesUseAgreedSurcharge() {
        // 用数值比较而非 equals：配置绑定出的 BigDecimal 小数位与写法无关（30.00 会绑成 30.0）
        assertThat(pricingConfig.getCategorySurcharge().get("DAILY_SUPPLIES"))
                .as("生活物资附加费").isEqualByComparingTo("30.00");
        assertThat(pricingConfig.getCategorySurcharge().get("FURNITURE"))
                .as("家具家居附加费").isEqualByComparingTo("60.00");
    }
}
