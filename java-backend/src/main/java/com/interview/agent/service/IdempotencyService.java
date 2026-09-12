package com.interview.agent.service;

import com.interview.agent.auth.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 幂等键：前端为每次"创建类"请求生成 X-Request-Id，后端用 Redis SETNX 去重，
 * 防止双击/多标签页/网络重放产生两个会话。
 *
 * Redis 不可用时一律放行：幂等是增强，不应成为单点故障。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final StringRedisTemplate redis;

    /** 首次占用返回 true；重复提交返回 false；requestId 为空或 Redis 异常均放行 */
    public boolean claim(String scope, String requestId, Duration ttl) {
        if (requestId == null || requestId.isBlank()) {
            return true;
        }
        String key = "idem:" + scope + ":" + UserContext.userId() + ":" + requestId.trim();
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(key, "1", ttl);
            return !Boolean.FALSE.equals(ok);
        } catch (Exception e) {
            log.warn("幂等键写入失败，放行本次请求: {}", e.getMessage());
            return true;
        }
    }
}
