package com.uav.dispatch.service;

import com.uav.dispatch.pojo.vo.DispatchReadinessVO;
import org.springframework.stereotype.Service;
import java.util.ArrayList;

@Service
public class DispatchReadinessService {
    private final DispatchAssessmentGateway assessment;
    private final DispatchSandboxPayment payment;
    public DispatchReadinessService(DispatchAssessmentGateway assessment, DispatchSandboxPayment payment) {
        this.assessment = assessment;
        this.payment = payment;
    }
    public DispatchReadinessVO current() {
        var blockers = new ArrayList<String>();
        if (!assessment.available()) blockers.add("真实空域服务尚未接入，且模拟评估未启用；暂不能下单");
        if (!payment.available()) blockers.add("模拟支付尚未启用，需开发或测试环境及显式开关");
        return new DispatchReadinessVO(blockers.isEmpty(), "SIMULATED", java.util.List.copyOf(blockers));
    }
}
