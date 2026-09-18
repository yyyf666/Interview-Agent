-- 增量迁移：评测可解释性增强 + 分级提示 + 断点辅助字段
-- 对已存在的数据库卷执行（幂等，可重复运行）

ALTER TABLE interview_turn ADD COLUMN IF NOT EXISTS reference_points JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE interview_turn ADD COLUMN IF NOT EXISTS hint_count INT NOT NULL DEFAULT 0;

ALTER TABLE evaluation ADD COLUMN IF NOT EXISTS evidence JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE evaluation ADD COLUMN IF NOT EXISTS checklist JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE evaluation ADD COLUMN IF NOT EXISTS suspected_recital BOOLEAN NOT NULL DEFAULT FALSE;
