package com.uav.dispatch.controller;

import com.uav.server.enums.ApiErrorCode;
import com.uav.server.result.Result;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Query validation in the new pilot API must not fall through to legacy generic HTTP 500. */
@RestControllerAdvice(assignableTypes = DispatchPilotController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DispatchPilotValidationAdvice {
    @ExceptionHandler({HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result<Void>> invalidQuery(Exception exception) {
        return ResponseEntity.badRequest().body(Result.fail(400, ApiErrorCode.INVALID_PARAM.getCode(), "分页参数须为非负整数"));
    }
}
