package com.drone.server.aspect;

import com.drone.server.annotation.RateLimiter;
import com.drone.pojo.enums.ApiErrorCode;
import com.drone.server.exception.BusinessException;
import com.drone.server.util.LogMaskUtil;
import com.drone.server.util.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Slf4j
@Aspect
@Component
public class RateLimiterAspect {

    /** key = "methodSignature:username", value = 时间戳队列 */
    private final ConcurrentHashMap<String, Deque<Long>> windowMap = new ConcurrentHashMap<>();

    @Around("@annotation(rateLimiter)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimiter rateLimiter) throws Throwable {
        String username = UserContext.getUsername();
        String method = joinPoint.getSignature().toShortString();
        String key = method + ":" + (username != null ? username : "anonymous");

        int limit = rateLimiter.limit();
        int windowSeconds = rateLimiter.windowSeconds();

        if (isRateLimited(key, limit, windowSeconds)) {
            String maskedUser = username != null ? LogMaskUtil.maskUserName(username) : "anonymous";
            log.warn("限流触发，用户: {}, 接口: {}, {}秒内超过{}次",
                    maskedUser, method, windowSeconds, limit);
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, ApiErrorCode.RATE_LIMITED);
        }

        return joinPoint.proceed();
    }

    private boolean isRateLimited(String key, int limit, int windowSeconds) {
        long now = System.currentTimeMillis();
        long windowStart = now - windowSeconds * 1000L;

        Deque<Long> timestamps = windowMap.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());

        synchronized (timestamps) {
            // 清理过期的时间戳
            while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                timestamps.pollFirst();
            }

            if (timestamps.size() >= limit) {
                return true;
            }

            timestamps.addLast(now);
            return false;
        }
    }
}
