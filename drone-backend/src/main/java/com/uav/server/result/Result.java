package com.uav.server.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "统一响应结果封装")
public class Result<T> {

    @Schema(description = "是否成功：true 成功 / false 失败")
    private boolean success;

    @Schema(description = "响应状态码（200 成功；400 参数或业务错误；401 未登录；404 资源不存在；409 状态冲突；500 服务端错误）")
    private Integer code;

    @Schema(description = "业务错误码（失败时非空，取值见 ApiErrorCode 枚举，如 UAV_NOT_CONNECTED；成功时为 null）")
    private String errorCode;

    @Schema(description = "提示信息（成功为“操作成功”，失败为具体失败原因）")
    private String message;

    @Schema(description = "业务数据（泛型；成功且有返回值时非空，无数据时为 null）")
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