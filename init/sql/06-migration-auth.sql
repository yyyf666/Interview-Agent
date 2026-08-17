-- =====================================================
-- 迁移 06：多用户与鉴权（含 Panel 多面试官所需的列变更）
-- 幂等：可重复执行
-- 注意：demo 用户种子与存量数据 user_id 回填由 Java 启动器
--       （AuthBootstrapRunner）以 BCrypt 编程式完成，SQL 不存哈希。
-- =====================================================

CREATE TABLE IF NOT EXISTS app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(128) NOT NULL,          -- BCrypt
    nickname      VARCHAR(64),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 业务数据归属用户（question_bank 保持全局共享，不加 user_id）
ALTER TABLE candidate_profile ADD COLUMN IF NOT EXISTS user_id BIGINT REFERENCES app_user(id);
ALTER TABLE resume            ADD COLUMN IF NOT EXISTS user_id BIGINT REFERENCES app_user(id);
ALTER TABLE job_description   ADD COLUMN IF NOT EXISTS user_id BIGINT REFERENCES app_user(id);
ALTER TABLE knowledge_doc     ADD COLUMN IF NOT EXISTS user_id BIGINT REFERENCES app_user(id);
ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS user_id BIGINT REFERENCES app_user(id);

CREATE INDEX IF NOT EXISTS idx_cp_user    ON candidate_profile(user_id);
CREATE INDEX IF NOT EXISTS idx_resume_user   ON resume(user_id);
CREATE INDEX IF NOT EXISTS idx_jd_user       ON job_description(user_id);
CREATE INDEX IF NOT EXISTS idx_kdoc_user     ON knowledge_doc(user_id);
CREATE INDEX IF NOT EXISTS idx_session_user  ON interview_session(user_id);

-- Panel 多面试官：persona 支持逗号分隔 CSV；轮次记录当轮发言人设
ALTER TABLE interview_session ALTER COLUMN persona TYPE VARCHAR(64);
ALTER TABLE interview_turn  ADD COLUMN IF NOT EXISTS speaker_persona VARCHAR(16);

COMMENT ON TABLE app_user IS '系统用户（登录主体），与 candidate_profile 一对一间接关联';
COMMENT ON COLUMN interview_session.persona IS '面试官人格，多人Panel时为逗号分隔CSV（如 pressure,standard）';
COMMENT ON COLUMN interview_turn.speaker_persona IS '当轮发言人设（Panel轮转结果，单人时与persona一致）';
