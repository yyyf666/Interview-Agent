package com.interview.agent.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 开前缺口分析缓存（R-03）。
 * key = interview:gap:{resumeId}:{jdId}，TTL 24h；简历/JD 重新解析后按前缀清理。
 */
@Component
@RequiredArgsConstructor
public class GapCache {

    public static final String PREFIX = "interview:gap:";

    private final StringRedisTemplate redis;

    public String key(Long resumeId, Long jdId) {
        return PREFIX + resumeId + ":" + jdId;
    }

    /** 简历或 JD 结构变化后清理相关缓存；本地数据量小，用通配删除可接受。 */
    public void evict(Long resumeId, Long jdId) {
        Set<String> keys = redis.keys(PREFIX + (resumeId == null ? "*" : resumeId)
                + ":" + (jdId == null ? "*" : jdId));
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
