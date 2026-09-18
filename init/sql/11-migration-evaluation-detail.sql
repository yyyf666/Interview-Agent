-- 11：评测详情持久化（R-04）
-- 遗漏知识点原先只存在于 AI 实时响应中，回放/刷新后丢失；补列后与 basis/issues/evidence/checklist 同级落库。
-- 幂等：ADD COLUMN IF NOT EXISTS，可在已有数据卷重复执行。
ALTER TABLE evaluation
    ADD COLUMN IF NOT EXISTS missed_knowledge_points JSONB NOT NULL DEFAULT '[]'::jsonb;
