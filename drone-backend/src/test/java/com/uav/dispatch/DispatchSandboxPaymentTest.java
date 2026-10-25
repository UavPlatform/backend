package com.uav.dispatch;

import com.uav.dispatch.service.DispatchSandboxPayment;
import com.uav.pay.MockPayGuard;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThat;

class DispatchSandboxPaymentTest {
    @ParameterizedTest
    @CsvSource({"test,true,true", "dev,true,true", "prod,true,false", "test+prod,true,false", "test,false,false", "none,true,false"})
    void sandboxCannotBeActivatedInProductionOrMixedProfiles(String profiles, boolean enabled, boolean expected) {
        var environment = new MockEnvironment();
        if (!profiles.equals("none")) environment.setActiveProfiles(profiles.split("\\+"));
        var payment = new DispatchSandboxPayment(new MockPayGuard(environment, enabled), environment);
        assertThat(payment.available()).isEqualTo(expected);
    }
}
