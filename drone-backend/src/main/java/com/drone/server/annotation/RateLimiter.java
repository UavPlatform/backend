package com.drone.server.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解，基于用户维度滑动窗口计数
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimiter {

    /** 时间窗口内允许的最大请求次数 */
    int limit() default 5;

    /** 时间窗口大小（秒） */
    int windowSeconds() default 60;
}
