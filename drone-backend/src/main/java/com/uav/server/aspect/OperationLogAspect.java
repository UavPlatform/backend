package com.uav.server.aspect;

import com.uav.server.annotation.OperationLog;
import com.uav.server.exception.BusinessException;
import com.uav.server.util.LogMaskUtil;
import com.uav.server.util.UserContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class OperationLogAspect {

    @Around("@annotation(operationLog)")
    public Object around(ProceedingJoinPoint joinPoint, OperationLog operationLog) throws Throwable {
        Logger log = LoggerFactory.getLogger(joinPoint.getTarget().getClass());
        String action = operationLog.value();
        String masked = getMaskedUser();

        log.info("用户: {} 尝试{}", masked, action);
        try {
            Object result = joinPoint.proceed();
            log.info("{}成功，用户: {}", action, getMaskedUser());
            return result;
        } catch (BusinessException e) {
            log.warn("{}失败，用户: {}, 原因: {}", action, getMaskedUser(), e.getMessage());
            throw e;
        }
    }

    private String getMaskedUser() {
        String name = UserContext.getUsername();
        return name != null ? LogMaskUtil.maskUserName(name) : "none";
    }
}
