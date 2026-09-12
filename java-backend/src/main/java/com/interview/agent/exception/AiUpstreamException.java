package com.interview.agent.exception;

/**
 * AI 能力层（python-ai）返回异常或不可达。
 * 由 GlobalExceptionHandler 映射为 HTTP 502 + errorCode=AI_UPSTREAM_ERROR。
 */
public class AiUpstreamException extends RuntimeException {

    private final String errorCode;

    public AiUpstreamException(String message) {
        this(message, "AI_UPSTREAM_ERROR", null);
    }

    public AiUpstreamException(String message, Throwable cause) {
        this(message, "AI_UPSTREAM_ERROR", cause);
    }

    public AiUpstreamException(String message, String errorCode, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
