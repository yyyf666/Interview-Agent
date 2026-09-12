package com.interview.agent.exception;

/**
 * AI 能力层调用超时（连接超时 / 读超时）。
 * 由 GlobalExceptionHandler 映射为 HTTP 504 + errorCode=AI_TIMEOUT，前端可据此提示重试。
 */
public class AiTimeoutException extends AiUpstreamException {

    public AiTimeoutException(String message, Throwable cause) {
        super(message, "AI_TIMEOUT", cause);
    }
}
