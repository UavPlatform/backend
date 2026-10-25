package com.uav.dispatch.service;

import com.uav.dispatch.pojo.vo.DispatchConfigVO;
import com.uav.server.config.DispatchPaymentConfig;
import org.springframework.stereotype.Service;

@Service
public class DispatchSettingsService {
    private final DispatchPaymentConfig config;
    private final DispatchReadinessService readiness;

    public DispatchSettingsService(DispatchPaymentConfig config, DispatchReadinessService readiness) {
        this.config = config;
        this.readiness = readiness;
    }

    public DispatchConfigVO current() {
        return new DispatchConfigVO("CNY", config.getDepositAmount(), config.getPilotServiceFee(), readiness.current().orderingEnabled());
    }
}
