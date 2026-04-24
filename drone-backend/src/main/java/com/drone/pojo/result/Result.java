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

    private String message;

    private T data;

    public static <T> Result<T> success() {
        return new Result<>(true, 200, "操作成功", null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(true, 200, "操作成功", data);
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>(true, 200, message, data);
    }

    public static <T> Result<T> success(String message) {
        return new Result<>(true, 200, message, null);
    }

    public static <T> Result<T> fail(Integer code, String message) {
        return new Result<>(false, code, message, null);
    }

    public static <T> Result<T> fail(String message) {
        return new Result<>(false, 400, message, null);
    }
}