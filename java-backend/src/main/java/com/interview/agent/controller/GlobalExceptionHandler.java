package com.interview.agent.controller;

import com.interview.agent.dto.ApiResult;
import com.interview.agent.exception.AiTimeoutException;
import com.interview.agent.exception.AiUpstreamException;
import com.interview.agent.exception.TooManyRequestsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常 → HTTP 状态码语义对齐（此前一律 200 + code=500，前端无法区分失败类型）。
 * 状态码约定：参数错 400 / 未登录 401 / 无权限 403 / 状态冲突 409 / 上游异常 502 / 上游超时 504。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 参数/业务校验失败 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResult<Void>> badRequest(IllegalArgumentException e) {
        log.info("参数校验失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResult.error(400, "BAD_REQUEST", e.getMessage()));
    }

    /** @Valid 请求体校验失败：取第一条字段错误信息 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Void>> invalidBody(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getDefaultMessage() == null ? err.getField() + " 不合法" : err.getDefaultMessage())
                .orElse("请求参数不合法");
        log.info("请求体校验失败: {}", msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResult.error(400, "BAD_REQUEST", msg));
    }

    /** 状态冲突（重复提交、会话已结束、幂等键不匹配等） */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResult<Void>> conflict(IllegalStateException e) {
        log.info("状态冲突: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResult.error(409, "CONFLICT", e.getMessage()));
    }

    /** 登录失败超限 / 请求过频 */
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ApiResult<Void>> tooManyRequests(TooManyRequestsException e) {
        log.warn("请求被限制: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiResult.error(429, "TOO_MANY_REQUESTS", e.getMessage()));
    }

    /** AI 能力层超时：前端可提示「稍后重试」 */
    @ExceptionHandler(AiTimeoutException.class)
    public ResponseEntity<ApiResult<Void>> aiTimeout(AiTimeoutException e) {
        log.warn("AI 能力层超时: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(ApiResult.error(504, e.getErrorCode(), "AI 服务响应超时，请稍后重试"));
    }

    /** AI 能力层异常/不可达 */
    @ExceptionHandler(AiUpstreamException.class)
    public ResponseEntity<ApiResult<Void>> aiUpstream(AiUpstreamException e) {
        log.error("AI 能力层异常: {}", e.getMessage(), e.getCause());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResult.error(502, e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> internal(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.error(500, "INTERNAL_ERROR", "服务内部错误，请稍后重试"));
    }
}
