package com.uav.equipment.controller;

import com.uav.server.enums.ApiErrorCode;
import com.uav.server.result.Result;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Non-numeric page/size on admin equipment endpoints is a 400, not the legacy generic 500. */
@RestControllerAdvice(assignableTypes = {AdminDroneController.class, AdminBorrowApplicationController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminDroneValidationAdvice {
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> invalidQuery(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest().body(Result.fail(400, ApiErrorCode.INVALID_PARAM.getCode(), "参数 " + exception.getName() + " 格式不正确"));
    }
}
