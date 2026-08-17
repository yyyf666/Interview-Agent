package com.interview.agent.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.interview.agent.entity.AppUser;
import com.interview.agent.mapper.AppUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 鉴权体系启动引导（幂等）：
 * 1. 不存在任何用户时种子本地开发账号 demo/demo1234（ADMIN）；
 * 2. 存量数据（user_id IS NULL）回填到 demo —— BCrypt 哈希无法写在 SQL 迁移里，
 *    故 06-migration-auth.sql 只建表加列，种子与回填在此完成；
 * 3. 确保至少存在一个 ADMIN（升级到角色体系后，存量用户默认 USER，否则无人可管理题库）。
 *
 * 必须在 SchemaUpgradeRunner（补 role 列）之后执行。
 */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
public class AuthBootstrapRunner implements ApplicationRunner {

    private static final String[] BACKFILL_TABLES =
            {"candidate_profile", "resume", "job_description", "knowledge_doc", "interview_session"};

    private final AppUserMapper userMapper;
    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        AppUser demo = userMapper.selectOne(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getUsername, "demo"));
        if (demo == null) {
            boolean anyUser = userMapper.selectCount(new LambdaQueryWrapper<>()) > 0;
            if (!anyUser) {
                demo = new AppUser();
                demo.setUsername("demo");
                demo.setPasswordHash(new BCryptPasswordEncoder().encode("demo1234"));
                demo.setNickname("演示用户");
                demo.setRole(AppUser.ROLE_ADMIN);
                userMapper.insert(demo);
                log.info("已种子本地开发账号 demo / demo1234（ADMIN）");
            }
        }
        if (demo != null) {
            for (String table : BACKFILL_TABLES) {
                int n = jdbc.update("UPDATE " + table + " SET user_id = ? WHERE user_id IS NULL", demo.getId());
                if (n > 0) {
                    log.info("存量数据回填: {} -> demo, {} 行", table, n);
                }
            }
        }
        ensureAdminExists();
    }

    /** 角色体系升级后保证可用：无 ADMIN 时提升最早注册的用户 */
    private void ensureAdminExists() {
        try {
            Long admins = userMapper.selectCount(new LambdaQueryWrapper<AppUser>()
                    .eq(AppUser::getRole, AppUser.ROLE_ADMIN));
            if (admins != null && admins > 0) {
                return;
            }
            List<AppUser> earliest = userMapper.selectList(new LambdaQueryWrapper<AppUser>()
                    .orderByAsc(AppUser::getId)
                    .last("LIMIT 1"));
            if (earliest.isEmpty()) {
                return;
            }
            AppUser target = earliest.get(0);
            target.setRole(AppUser.ROLE_ADMIN);
            userMapper.updateById(target);
            log.info("未发现管理员，已将最早用户 {} (id={}) 提升为 ADMIN", target.getUsername(), target.getId());
        } catch (Exception e) {
            log.warn("管理员兜底提升失败（可能 role 列尚未就绪）: {}", e.getMessage());
        }
    }
}
