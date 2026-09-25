package com.uav.pay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 1B-1a 守卫单元测试：mock 支付开关仅 dev/test profile 生效；
 * 生产 profile 即使误配属性也必须拒绝（双重门禁的生产禁用证明）。
 */
class MockPayGuardTest {

    private MockPayGuard guard(String profile, boolean propertyEnabled) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profile);
        return new MockPayGuard(env, propertyEnabled);
    }

    @Test
    @DisplayName("prod profile + 属性开启 → 仍拒绝（生产显式禁用证明）")
    void prodProfileRejectedEvenIfPropertyEnabled() {
        assertThat(guard("prod", true).isMockPayActive()).isFalse();
    }

    @Test
    @DisplayName("prod profile + 属性关闭 → 拒绝")
    void prodProfilePropertyOffRejected() {
        assertThat(guard("prod", false).isMockPayActive()).isFalse();
    }

    @Test
    @DisplayName("dev profile + 属性开启 → 生效")
    void devProfileEnabled() {
        assertThat(guard("dev", true).isMockPayActive()).isTrue();
    }

    @Test
    @DisplayName("test profile + 属性开启 → 生效")
    void testProfileEnabled() {
        assertThat(guard("test", true).isMockPayActive()).isTrue();
    }

    @Test
    @DisplayName("dev profile + 属性关闭 → 拒绝（开关默认关闭）")
    void devProfilePropertyOffRejected() {
        assertThat(guard("dev", false).isMockPayActive()).isFalse();
    }
}
