package com.interview.agent.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Refresh token 颁发/校验/吊销（Redis 存储，故可主动失效）。
 * - 不透明随机串（非 JWT），仅能由本服务解析，泄露面更小
 * - 有效期 7 天，登录/续期时轮换（旧的立即吊销，防重放）
 * - Redis 不可用时降级：仅记日志，不阻断登录（访客仍可用 30 分钟访问令牌）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    private static final Duration REFRESH_TTL = Duration.ofDays(7);
    private static final String KEY_PREFIX = "auth:refresh:";

    private final StringRedisTemplate redis;

    /** 颁发 refresh token，返回不透明串 */
    public String issue(Long userId) {
        String token = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        try {
            redis.opsForValue().set(key(token), String.valueOf(userId), REFRESH_TTL);
        } catch (Exception e) {
            log.warn("Redis 不可用，refresh token 未能落库: {}", e.getMessage());
        }
        return token;
    }

    /** 解析 refresh token -> userId；无效/过期返回 null */
    public Long resolveUserId(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            String v = redis.opsForValue().get(key(token));
            return v == null ? null : Long.valueOf(v);
        } catch (Exception e) {
            log.warn("Redis 不可用，refresh token 校验失败: {}", e.getMessage());
            return null;
        }
    }

    /** 吊销（登出 / 轮换） */
    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try {
            redis.delete(key(token));
        } catch (Exception e) {
            log.warn("Redis 不可用，refresh token 吊销失败: {}", e.getMessage());
        }
    }

    public long refreshTtlSeconds() {
        return REFRESH_TTL.toSeconds();
    }

    private String key(String token) {
        return KEY_PREFIX + token;
    }
}
