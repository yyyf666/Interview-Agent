package com.interview.agent.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动期密钥/口令自检（fail-fast）。
 *
 * 生产 profile（SPRING_PROFILES_ACTIVE=prod）下，下列任一项缺失或仍为开发默认值时
 * 直接拒绝启动，避免「默认口令上网」这类最常见的失陷入口：
 * - APP_JWT_SECRET：JWT 签名密钥
 * - APP_INTERNAL_TOKEN：Java → python-ai 的内部调用密钥
 * - SPRING_DATASOURCE_PASSWORD：数据库口令
 *
 * 开发环境不校验（保留开箱即用），仅打一条提示。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupConfigValidator {

    private static final String DEV_JWT_SECRET = "interview-agent-local-dev-secret-key-32bytes";
    private static final String DEV_INTERNAL_TOKEN = "interview-agent-local-dev-internal-token";
    private static final String DEV_DB_PASSWORD = "interview123";

    private final Environment environment;

    @Value("${app.jwt.secret:}")
    private String jwtSecret;

    @Value("${app.ai.internal-token:}")
    private String internalToken;

    @Value("${spring.datasource.password:}")
    private String datasourcePassword;

    @PostConstruct
    public void validate() {
        boolean prod = List.of(environment.getActiveProfiles()).contains("prod");
        List<String> problems = new ArrayList<>();
        if (isBlankOrDev(jwtSecret, DEV_JWT_SECRET)) {
            problems.add("APP_JWT_SECRET 未配置或仍为开发默认值");
        }
        if (isBlankOrDev(internalToken, DEV_INTERNAL_TOKEN)) {
            problems.add("APP_INTERNAL_TOKEN 未配置或仍为开发默认值");
        }
        if (isBlankOrDev(datasourcePassword, DEV_DB_PASSWORD)) {
            problems.add("SPRING_DATASOURCE_PASSWORD 未配置或仍为开发默认值");
        }
        if (!prod) {
            if (!problems.isEmpty()) {
                log.warn("开发环境使用默认密钥/口令，生产部署前请按 .env.example 覆盖：{}", problems);
            }
            return;
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("生产环境配置校验失败，拒绝启动："
                    + String.join("；", problems) + "（参考 .env.example）");
        }
        log.info("生产环境配置校验通过（JWT 密钥 / 内部调用密钥 / 数据库口令均已覆盖）");
    }

    private static boolean isBlankOrDev(String value, String devDefault) {
        return value == null || value.isBlank() || devDefault.equals(value);
    }
}
