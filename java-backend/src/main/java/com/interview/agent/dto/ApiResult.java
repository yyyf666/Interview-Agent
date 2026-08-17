package com.interview.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ApiResult<T> {

    /** 业务码：0 成功，其余为 HTTP 语义对齐的错误码 */
    private int code;
    /** 人读文案，前端可直接展示 */
    private String message;
    private T data;
    /** 机器可读错误标识（如 AI_TIMEOUT / FORBIDDEN），成功时为 null */
    private String errorCode;

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(0, "ok", data, null);
    }

    public static <T> ApiResult<T> error(String message) {
        return new ApiResult<>(500, message, null, "INTERNAL_ERROR");
    }

    public static <T> ApiResult<T> error(int code, String message) {
        return new ApiResult<>(code, message, null, defaultErrorCode(code));
    }

    public static <T> ApiResult<T> error(int code, String errorCode, String message) {
        return new ApiResult<>(code, message, null, errorCode);
    }

    private static String defaultErrorCode(int code) {
        return switch (code) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            case 429 -> "TOO_MANY_REQUESTS";
            case 504 -> "AI_TIMEOUT";
            default -> code >= 500 ? "INTERNAL_ERROR" : "CLIENT_ERROR";
        };
    }
}
