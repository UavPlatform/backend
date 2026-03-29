package com.drone.server.exception;

import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {
    private final HttpStatus httpStatus;
    private final String code;

    public BusinessException(ApiErrorCode errorCode) {
        this(HttpStatus.BAD_REQUEST, errorCode, errorCode.getDefaultMessage());
    }

    public BusinessException(HttpStatus httpStatus, ApiErrorCode errorCode) {
        this(httpStatus, errorCode, errorCode.getDefaultMessage());
    }

    public BusinessException(HttpStatus httpStatus, ApiErrorCode errorCode, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = errorCode.getCode();
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }
}
