package com.drone.pojo.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    private boolean success;

    private Integer code;

    private String errorCode;

    private String message;

    private T data;

    public static <T> Result<T> success() {
        return new Result<>(true, 200, null, "操作成功", null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(true, 200, null, "操作成功", data);
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>(true, 200, null, message, data);
    }

    public static <T> Result<T> success(String message) {
        return new Result<>(true, 200, null, message, null);
    }

    public static <T> Result<T> fail(Integer code, String message) {
        return new Result<>(false, code, null, message, null);
    }

    public static <T> Result<T> fail(Integer code, String errorCode, String message) {
        return new Result<>(false, code, errorCode, message, null);
    }

    public static <T> Result<T> fail(String message) {
        return new Result<>(false, 400, null, message, null);
    }
}