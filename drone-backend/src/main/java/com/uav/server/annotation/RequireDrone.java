package com.uav.server.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要无人机绑定的接口。
 * 只有 role=1（飞手）的用户且已绑定无人机才能访问。
 * 未标注此注解的端点对飞手开放，不要求绑定无人机。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireDrone {
}
