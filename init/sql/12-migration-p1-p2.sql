-- 12：P1+P2 数据层（R-09 错题本 / R-10 训练进度 / R-16 异步报告 / R-17 AI 用量）
-- 约定：仅幂等加列与新建表，不改既有列语义；已有数据卷可重复执行。

-- R-16 报告状态机 + R-17 会话级 AI 用量累计
ALTER TABLE interview_session
    ADD COLUMN IF NOT EXISTS report_status VARCHAR(16) NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS report_error  VARCHAR(512),
    ADD COLUMN IF NOT EXISTS ai_meta       JSONB NOT NULL DEFAULT '{}'::jsonb;

-- 回填：历史上已有报告的会话直接视为 READY，避免前端反复轮询
UPDATE interview_session SET report_status = 'READY'
 WHERE report IS NOT NULL AND report_status = 'NONE';

-- R-09 错题本：每用户每轮唯一（uk_review_user_turn 冲突即幂等）
CREATE TABLE IF NOT EXISTS review_item (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    candidate_id BIGINT,
    session_id BIGINT REFERENCES interview_session(id),
    turn_id BIGINT,
    question TEXT,
    answer TEXT,
    reference_points JSONB NOT NULL DEFAULT '[]'::jsonb,
    missed_points    JSONB NOT NULL DEFAULT '[]'::jsonb,
    issues           JSONB NOT NULL DEFAULT '[]'::jsonb,
    tags             JSONB NOT NULL DEFAULT '[]'::jsonb,
    mastered VARCHAR(16) NOT NULL DEFAULT 'new',   -- new/learning/mastered
    note VARCHAR(512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE UNIQUE INDEX IF NOT EXISTS uk_review_user_turn ON review_item(user_id, turn_id);
CREATE INDEX IF NOT EXISTS idx_review_user_mastered ON review_item(user_id, mastered);

-- R-10 训练进度：报告训练任务 → 八股练习的完成回写（uk_training_task 冲突即已完成）
CREATE TABLE IF NOT EXISTS training_progress (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    candidate_id BIGINT,
    source_session_id BIGINT,
    plan_day INT,
    task_index INT,
    tag_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    gauntlet_session_id BIGINT,
    status VARCHAR(16) NOT NULL DEFAULT 'done',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE UNIQUE INDEX IF NOT EXISTS uk_training_task
    ON training_progress(source_session_id, plan_day, task_index);

-- R-17 AI 用量：只记 LLM 类调用（meta.llm_calls>0），不含沙箱执行
CREATE TABLE IF NOT EXISTS ai_usage (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    session_id BIGINT,
    scene VARCHAR(24),
    llm_calls INT NOT NULL DEFAULT 0,
    elapsed_ms BIGINT NOT NULL DEFAULT 0,
    model VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX IF NOT EXISTS idx_ai_usage_created ON ai_usage(created_at);
CREATE INDEX IF NOT EXISTS idx_ai_usage_session ON ai_usage(session_id);
