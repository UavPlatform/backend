package com.uav.dispatch.service;

import com.uav.dispatch.pojo.vo.DispatchEvaluationVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Owner-authorized demo only. These fixtures are never real route or airspace clearance. */
@Component
public class DemoDispatchAssessment implements DispatchAssessmentGateway {
    private final DispatchSandboxPayment payment;
    private final boolean enabled;
    private final String scenario;
    public DemoDispatchAssessment(DispatchSandboxPayment payment,
            @Value("${dispatch.demo.enabled:false}") boolean enabled,
            @Value("${dispatch.demo.airspace:CAUTION}") String scenario) {
        if (!List.of("ALLOWED", "CAUTION", "PROHIBITED").contains(scenario))
            throw new IllegalArgumentException("dispatch.demo.airspace must be ALLOWED, CAUTION or PROHIBITED");
        this.payment = payment;
        this.enabled = enabled;
        this.scenario = scenario;
    }
    public boolean available() { return enabled && payment.available(); }
    public DispatchEvaluationVO evaluate(Map<String, String> fields) {
        if (!available()) throw new IllegalStateException("模拟评估尚未开启或环境不允许");
        return new DispatchEvaluationVO(scenario, "SIMULATED_DEMO",
                new BigDecimal("1000"), new BigDecimal("5"), new BigDecimal("20"), "模拟平地",
                List.of("空域、航程、时长、高差及地形均为演示样例，不代表所选地点真实情况，不可用于飞行决策"));
    }
}
