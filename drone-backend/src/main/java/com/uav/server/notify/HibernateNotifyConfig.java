package com.uav.server.notify;

import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 1B-3：将 {@link SystemNotifyInterceptor} 注册为 SessionFactory 级 Hibernate 拦截器，
 * 被动捕获 Task/MissionOrder 的状态迁移（状态变更点所在模块无需感知通知逻辑）。
 */
@Configuration
public class HibernateNotifyConfig {

    @Bean
    public HibernatePropertiesCustomizer notifyHibernatePropertiesCustomizer(SystemNotifyInterceptor interceptor) {
        return properties -> properties.put(AvailableSettings.INTERCEPTOR, interceptor);
    }
}
