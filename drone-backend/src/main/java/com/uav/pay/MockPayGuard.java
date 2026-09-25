package com.uav.pay;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Mock 支付开关守卫（1B-1a，裁决 Q1/Q2=A）。
 *
 * <p>开关名：{@code wechat.pay.mock-enabled}（默认 false）。
 * <p><b>双重生效条件</b>（缺一不可，生产环境双保险禁用）：
 * <ol>
 *   <li>属性显式开启；</li>
 *   <li>当前激活 profile 仅含 dev 或 test（生产 profile 不在白名单，即使误配属性也拒绝）。</li>
 * </ol>
 * mock 通道复用真实 handleNotify 状态机（PENDING→PAID，含金额比对），仅替代微信统一下单环节。
 */
@Slf4j
@Component
public class MockPayGuard {

    /** 允许 mock 支付的 profile 白名单（生产不在列，显式禁用） */
    static final List<String> ALLOWED_PROFILES = List.of("dev", "test");

    private final Environment environment;
    private final boolean mockEnabledProperty;

    public MockPayGuard(Environment environment,
                        @Value("${wechat.pay.mock-enabled:false}") boolean mockEnabledProperty) {
        this.environment = environment;
        this.mockEnabledProperty = mockEnabledProperty;
    }

    @PostConstruct
    void reportState() {
        if (isMockPayActive()) {
            log.warn("[MOCK PAY] mock 支付已启用（profile={}, wechat.pay.mock-enabled=true）——"
                    + "仅限开发/测试环境，生产严禁开启", Arrays.toString(environment.getActiveProfiles()));
        }
    }

    /**
     * mock 支付通道是否可用。
     */
    public boolean isMockPayActive() {
        if (!mockEnabledProperty) {
            return false;
        }
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        boolean safeProfile = profiles.stream().anyMatch(ALLOWED_PROFILES::contains);
        if (!safeProfile) {
            log.error("[MOCK PAY] wechat.pay.mock-enabled=true 但当前 profile={} 不在白名单 {}，mock 支付拒绝启用",
                    Arrays.toString(environment.getActiveProfiles()), ALLOWED_PROFILES);
            return false;
        }
        return true;
    }
}
