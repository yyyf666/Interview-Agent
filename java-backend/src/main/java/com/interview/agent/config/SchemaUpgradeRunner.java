package com.interview.agent.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 轻量结构自愈（Flyway 引入前的过渡）。
 *
 * 背景：docker-compose 只在数据卷首次初始化时执行 init/sql，已存在的 pg_data 卷
 * 不会自动跑后续迁移脚本；而实体/查询已依赖新列与新索引，缺失会直接报错或全表扫描。
 * 故启动时按幂等 DDL 补齐，并排在业务 Runner 之前。
 *
 * 引入 Flyway 后应删除本类，对应脚本见 init/sql/09-migration-role.sql、
 * 10-migration-rag-index.sql。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class SchemaUpgradeRunner implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        // 09：角色体系
        exec("ALTER TABLE app_user ADD COLUMN IF NOT EXISTS role VARCHAR(16) NOT NULL DEFAULT 'USER'");

        // 10：RAG 召回索引（skill 过滤 / tags 包含 / 关键词 trigram）
        exec("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        exec("CREATE INDEX IF NOT EXISTS idx_kv_skill ON knowledge_vector ((metadata->>'skill'))");
        exec("CREATE INDEX IF NOT EXISTS idx_kv_meta ON knowledge_vector USING gin (metadata jsonb_path_ops)");
        exec("CREATE INDEX IF NOT EXISTS idx_kv_content_trgm ON knowledge_vector USING gin (content gin_trgm_ops)");

        // 10：历史会话分页（user_id 过滤 + id 倒序）
        exec("CREATE INDEX IF NOT EXISTS idx_session_user_id ON interview_session (user_id, id DESC)");

        // 11：评测详情持久化（遗漏知识点，回放/刷新后可展开）
        exec("ALTER TABLE evaluation ADD COLUMN IF NOT EXISTS "
                + "missed_knowledge_points JSONB NOT NULL DEFAULT '[]'::jsonb");

        // 12：P1+P2（异步报告状态机 / 会话级 AI 用量 / 错题本 / 训练进度）
        exec("ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS "
                + "report_status VARCHAR(16) NOT NULL DEFAULT 'NONE'");
        exec("ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS report_error VARCHAR(512)");
        exec("ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS "
                + "ai_meta JSONB NOT NULL DEFAULT '{}'::jsonb");
        exec("UPDATE interview_session SET report_status='READY' "
                + "WHERE report IS NOT NULL AND report_status='NONE'");
        exec("CREATE TABLE IF NOT EXISTS review_item ("
                + "id BIGSERIAL PRIMARY KEY, user_id BIGINT, candidate_id BIGINT, "
                + "session_id BIGINT REFERENCES interview_session(id), turn_id BIGINT, "
                + "question TEXT, answer TEXT, "
                + "reference_points JSONB NOT NULL DEFAULT '[]'::jsonb, "
                + "missed_points JSONB NOT NULL DEFAULT '[]'::jsonb, "
                + "issues JSONB NOT NULL DEFAULT '[]'::jsonb, "
                + "tags JSONB NOT NULL DEFAULT '[]'::jsonb, "
                + "mastered VARCHAR(16) NOT NULL DEFAULT 'new', note VARCHAR(512), "
                + "created_at TIMESTAMPTZ NOT NULL DEFAULT now(), "
                + "updated_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        exec("CREATE UNIQUE INDEX IF NOT EXISTS uk_review_user_turn ON review_item(user_id, turn_id)");
        exec("CREATE INDEX IF NOT EXISTS idx_review_user_mastered ON review_item(user_id, mastered)");
        exec("CREATE TABLE IF NOT EXISTS training_progress ("
                + "id BIGSERIAL PRIMARY KEY, user_id BIGINT, candidate_id BIGINT, "
                + "source_session_id BIGINT, plan_day INT, task_index INT, "
                + "tag_ids JSONB NOT NULL DEFAULT '[]'::jsonb, gauntlet_session_id BIGINT, "
                + "status VARCHAR(16) NOT NULL DEFAULT 'done', "
                + "created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        exec("CREATE UNIQUE INDEX IF NOT EXISTS uk_training_task "
                + "ON training_progress(source_session_id, plan_day, task_index)");
        exec("CREATE TABLE IF NOT EXISTS ai_usage ("
                + "id BIGSERIAL PRIMARY KEY, user_id BIGINT, session_id BIGINT, "
                + "scene VARCHAR(24), llm_calls INT NOT NULL DEFAULT 0, "
                + "elapsed_ms BIGINT NOT NULL DEFAULT 0, model VARCHAR(128), "
                + "created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        exec("CREATE INDEX IF NOT EXISTS idx_ai_usage_created ON ai_usage(created_at)");
        exec("CREATE INDEX IF NOT EXISTS idx_ai_usage_session ON ai_usage(session_id)");
    }

    private void exec(String sql) {
        try {
            jdbc.execute(sql);
        } catch (Exception e) {
            // 表尚未创建（极旧数据卷）时忽略，交由初始化脚本处理
            log.warn("结构自愈跳过 [{}]: {}", sql, e.getMessage());
        }
    }
}
