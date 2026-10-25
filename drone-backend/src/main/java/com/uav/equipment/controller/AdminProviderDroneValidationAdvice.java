package com.uav.equipment.controller;

import com.uav.server.enums.ApiErrorCode;
import com.uav.server.result.Result;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Non-numeric page/size/providerId on the admin drone list is a 400, not the legacy generic 500. */
@RestControllerAdvice(assignableTypes = AdminProviderDroneController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminProviderDroneValidationAdvice {
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> invalidQuery(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest().body(Result.fail(400, ApiErrorCode.INVALID_PARAM.getCode(), "参数 " + exception.getName() + " 格式不正确"));
    }
}
