package com.interview.agent.exception;

/**
 * 请求被限流/锁定（登录失败次数超限、沙箱并发超限等）。
 * 由 GlobalExceptionHandler 映射为 HTTP 429 + errorCode=TOO_MANY_REQUESTS。
 */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
