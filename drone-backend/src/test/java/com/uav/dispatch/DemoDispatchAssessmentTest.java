package com.uav.dispatch;

import com.uav.dispatch.service.DemoDispatchAssessment;
import com.uav.dispatch.service.DispatchSandboxPayment;
import com.uav.pay.MockPayGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class DemoDispatchAssessmentTest {
    private DispatchSandboxPayment payment(String... profiles) {
        var environment = new MockEnvironment(); environment.setActiveProfiles(profiles);
        return new DispatchSandboxPayment(new MockPayGuard(environment, true), environment);
    }
    @Test void explicitDemoFlagsAndSafeProfileAreRequired() {
        assertThat(new DemoDispatchAssessment(payment("test"), false, "CAUTION").available()).isFalse();
        assertThat(new DemoDispatchAssessment(payment("test", "prod"), true, "CAUTION").available()).isFalse();
        var disabled = new DemoDispatchAssessment(payment("prod"), true, "ALLOWED");
        assertThatThrownBy(() -> disabled.evaluate(Map.of())).isInstanceOf(IllegalStateException.class);
    }
    @Test void syntheticFixturesAreLabeledAndSupportProhibitionScenario() {
        var caution = new DemoDispatchAssessment(payment("test"), true, "CAUTION").evaluate(Map.of());
        assertThat(caution.complete()).isTrue();
        assertThat(caution.source()).isEqualTo("SIMULATED_DEMO");
        assertThat(caution.warnings()).anyMatch(text -> text.contains("不可用于飞行决策"));
        assertThat(new DemoDispatchAssessment(payment("test"), true, "PROHIBITED").evaluate(Map.of()).airspace())
                .isEqualTo("PROHIBITED");
        assertThatThrownBy(() -> new DemoDispatchAssessment(payment("test"), true, "UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
