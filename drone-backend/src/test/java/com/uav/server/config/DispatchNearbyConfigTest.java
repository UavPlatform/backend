package com.uav.server.config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import static org.assertj.core.api.Assertions.assertThat;
class DispatchNearbyConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);
    @Configuration(proxyBeanMethods = false) @EnableConfigurationProperties @Import(DispatchNearbyConfig.class)
    static class Config { }
    @Test void ownerConfirmedRulesAreIndependentAndConfigurable() {
        runner.run(c -> {
            var config = c.getBean(DispatchNearbyConfig.class);
            assertThat(config.getRadiusKm()).isEqualTo(10);
            assertThat(config.getLocationTtlSeconds()).isEqualTo(300);
            assertThat(config.getReminderSeconds()).isEqualTo(600);
        });
        runner.withPropertyValues("dispatch.nearby.radius-km=12", "dispatch.nearby.location-ttl-seconds=120", "dispatch.nearby.reminder-seconds=900").run(c -> {
            var config = c.getBean(DispatchNearbyConfig.class);
            assertThat(config.getRadiusKm()).isEqualTo(12);
            assertThat(config.getLocationTtlSeconds()).isEqualTo(120);
            assertThat(config.getReminderSeconds()).isEqualTo(900);
        });
    }
    @ParameterizedTest @ValueSource(strings = {"radius-km=0", "radius-km=NaN", "radius-km=20001", "location-ttl-seconds=0", "reminder-seconds=0"})
    void invalidRulesFailStartup(String property) { runner.withPropertyValues("dispatch.nearby." + property).run(c -> assertThat(c).hasFailed()); }
}
