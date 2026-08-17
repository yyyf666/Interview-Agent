package com.interview.agent.auth;

import com.interview.agent.exception.TooManyRequestsException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 登录防爆破：按「IP + 用户名」计数，5 次失败 / 5 分钟窗口 → 锁定 15 分钟。
 * - key: login:fail:{ip}:{username}（滚动窗口）、login:lock:{ip}:{username}（锁）
 * - 登录成功即清零，避免正常用户被历史失败拖累
 * - Redis 不可用时降级放行（记 warn），保证认证链路不被缓存故障放大成全站不可登录
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    static final int MAX_FAILS = 5;
    static final Duration WINDOW = Duration.ofMinutes(5);
    static final Duration LOCK = Duration.ofMinutes(15);

    private static final String FAIL_PREFIX = "login:fail:";
    private static final String LOCK_PREFIX = "login:lock:";

    private final StringRedisTemplate redis;

    /** 已锁定则抛出 429 */
    public void assertNotLocked(String ip, String username) {
        String lockKey = LOCK_PREFIX + scope(ip, username);
        try {
            Long ttl = redis.getExpire(lockKey, TimeUnit.SECONDS);
            if (ttl != null && ttl > 0) {
                long minutes = Math.max(1, (ttl + 59) / 60);
                throw new TooManyRequestsException("登录失败次数过多，请 " + minutes + " 分钟后重试");
            }
        } catch (TooManyRequestsException e) {
            throw e;
        } catch (Exception e) {
            log.warn("登录锁定状态读取失败（降级放行）: {}", e.getMessage());
        }
    }

    /** 记录一次失败；达到阈值即锁定并清空计数 */
    public void onFailure(String ip, String username) {
        String failKey = FAIL_PREFIX + scope(ip, username);
        try {
            Long n = redis.opsForValue().increment(failKey);
            if (n != null && n == 1L) {
                redis.expire(failKey, WINDOW);
            }
            if (n != null && n >= MAX_FAILS) {
                redis.opsForValue().set(LOCK_PREFIX + scope(ip, username), "1", LOCK);
                redis.delete(failKey);
                log.warn("登录失败次数达阈值，已锁定 {} / {} 共 {} 分钟", ip, username, LOCK.toMinutes());
            }
        } catch (Exception e) {
            log.warn("登录失败计数写入失败: {}", e.getMessage());
        }
    }

    /** 登录成功清零 */
    public void onSuccess(String ip, String username) {
        try {
            redis.delete(FAIL_PREFIX + scope(ip, username));
        } catch (Exception e) {
            log.warn("登录失败计数清零失败: {}", e.getMessage());
        }
    }

    /** 统一 key 片段：用户名可能含冒号等字符，清洗避免 key 串味 */
    private static String scope(String ip, String username) {
        String safeIp = (ip == null || ip.isBlank()) ? "unknown" : ip.trim();
        String safeUser = (username == null || username.isBlank())
                ? "anonymous" : username.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.@-]", "_");
        return safeIp + ":" + safeUser;
    }
}
