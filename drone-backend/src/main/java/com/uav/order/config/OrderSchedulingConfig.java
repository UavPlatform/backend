package com.uav.order.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 1B-9a：启用 Spring 调度（验收超时自动确认骨架的调度入口）。
 * 调度任务本身受 order.auto-confirm-enabled 开关控制，默认关闭时零行为变化。
 */
@Configuration
@EnableScheduling
public class OrderSchedulingConfig {
}
