package com.uav.dispatch.service;

import com.uav.pay.MockPayGuard;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.Set;

/** User-selected simulated payment only. No real charge or refund is made. */
@Component
public class DispatchSandboxPayment {
    private final MockPayGuard guard;
    private final Environment environment;
    public DispatchSandboxPayment(MockPayGuard guard, Environment environment) {
        this.guard = guard;
        this.environment = environment;
    }
    public boolean available() {
        String[] profiles = environment.getActiveProfiles();
        return profiles.length > 0 && Arrays.stream(profiles).allMatch(Set.of("dev", "test", "it")::contains)
                && guard.isMockPayActive();
    }
    public void requireAvailable() {
        if (!available()) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.DISPATCH_UNAVAILABLE, "模拟支付仅限显式启用的开发或测试环境");
    }
}
