package com.uav.server.aspect;

import com.uav.server.annotation.RateLimiter;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.server.handler.GlobalExceptionHandler;
import com.uav.server.util.UserContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimiterRetryAfterTest {
    @RateLimiter(limit = 1, windowSeconds = 30)
    void once() { }

    @Test
    void limitedCallSetsRetryAfterBeforeThe429() throws Throwable {
        var aspect = new RateLimiterAspect();
        var limiter = RateLimiterRetryAfterTest.class.getDeclaredMethod("once").getAnnotation(RateLimiter.class);
        var request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        UserContext.setUser(9L, "rate-probe", 1);
        var join = mock(ProceedingJoinPoint.class);
        var signature = mock(Signature.class);
        when(signature.toShortString()).thenReturn("RateLimiterRetryAfterTest.once()");
        when(join.getSignature()).thenReturn(signature);
        when(join.proceed()).thenReturn("ok");
        try {
            assertThat(aspect.around(join, limiter)).isEqualTo("ok");
            assertThatThrownBy(() -> aspect.around(join, limiter)).isInstanceOf(BusinessException.class);
            assertThat(request.getAttribute(RateLimiterAspect.RETRY_AFTER_ATTRIBUTE)).isEqualTo(30);
            var response = new GlobalExceptionHandler().handleBusinessException(
                    new BusinessException(HttpStatus.TOO_MANY_REQUESTS, ApiErrorCode.RATE_LIMITED), request);
            assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("30");
            request.removeAttribute(RateLimiterAspect.RETRY_AFTER_ATTRIBUTE);
            var fallback = new GlobalExceptionHandler().handleBusinessException(
                    new BusinessException(HttpStatus.TOO_MANY_REQUESTS, ApiErrorCode.RATE_LIMITED), request);
            assertThat(fallback.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        } finally {
            RequestContextHolder.resetRequestAttributes();
            UserContext.clear();
        }
    }
}
