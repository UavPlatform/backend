package com.uav.server.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DispatchPaymentConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties
    @Import(DispatchPaymentConfig.class)
    static class Config {
    }

    @Test
    void usesOwnerConfirmedDepositAndFixedPilotFeeByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DispatchPaymentConfig.class).getDepositAmount())
                    .isEqualByComparingTo("100.00");
            assertThat(context.getBean(DispatchPaymentConfig.class).getPilotServiceFee())
                    .isEqualByComparingTo("100.00");
        });
    }

    @Test
    void platformPropertyOverridesDefaultWithoutClientChanges() {
        runner.withPropertyValues("dispatch.payment.deposit-amount=125.50").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DispatchPaymentConfig.class).getDepositAmount())
                    .isEqualByComparingTo("125.50");
            assertThat(context.getBean(DispatchPaymentConfig.class).getPilotServiceFee())
                    .isEqualByComparingTo("100.00");
        });
    }

    @Test
    void pilotFeeCanChangeIndependentlyOfDeposit() {
        runner.withPropertyValues("dispatch.payment.pilot-service-fee=180.50").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DispatchPaymentConfig.class).getPilotServiceFee())
                    .isEqualByComparingTo("180.50");
            assertThat(context.getBean(DispatchPaymentConfig.class).getDepositAmount())
                    .isEqualByComparingTo("100.00");
        });
    }

    @Test
    void environmentVariableBindsToDepositAmount() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("systemEnvironment",
                        Map.of("DISPATCH_PAYMENT_DEPOSITAMOUNT", "150.00",
                                "DISPATCH_PAYMENT_PILOTSERVICEFEE", "120.00"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DispatchPaymentConfig.class).getDepositAmount())
                            .isEqualByComparingTo("150.00");
                    assertThat(context.getBean(DispatchPaymentConfig.class).getPilotServiceFee())
                            .isEqualByComparingTo("120.00");
                });
    }

    @ParameterizedTest
    @MethodSource("invalidAmounts")
    void rejectsInvalidConfigurationInsteadOfRoundingOrStartingWithZero(String property, String amount) {
        runner.withPropertyValues(property + "=" + amount).run(context ->
                assertThat(context).hasFailed());
    }

    static Stream<Arguments> invalidAmounts() {
        return Stream.of("dispatch.payment.deposit-amount", "dispatch.payment.pilot-service-fee")
                .flatMap(property -> Stream.of("0", "-1", "0.001", "100.001",
                                "100000000.00", "invalid", "", "   ")
                        .map(amount -> Arguments.of(property, amount)));
    }
}
