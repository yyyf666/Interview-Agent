-- =====================================================
-- 增量迁移：面试官人格 + 现场回应 + 代码题支持
-- （对已存在的数据库卷执行；对新初始化库幂等无害）
-- =====================================================
ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS persona VARCHAR(16) NOT NULL DEFAULT 'standard';
ALTER TABLE interview_turn    ADD COLUMN IF NOT EXISTS question_type VARCHAR(16);
ALTER TABLE interview_turn    ADD COLUMN IF NOT EXISTS coding JSONB;
ALTER TABLE interview_turn    ADD COLUMN IF NOT EXISTS interviewer_reaction TEXT;
