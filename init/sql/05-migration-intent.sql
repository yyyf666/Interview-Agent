-- 05: 插话意图识别（对话逻辑优化）增量迁移
-- interview_turn 新增插话意图列；answer_type 语义扩展 INTERJECTION（无枚举约束，无需改类型）
ALTER TABLE interview_turn ADD COLUMN IF NOT EXISTS interjection_intent VARCHAR(24);
COMMENT ON COLUMN interview_turn.answer_type IS 'TEXT / ASR / CODE / INTERJECTION';
