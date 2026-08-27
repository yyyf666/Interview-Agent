-- =====================================================
-- P1 性能：RAG 召回索引 + 历史会话分页索引（幂等，可重复执行）
--
-- 背景：
--   1) retriever._kw_search_knowledge 由 ILIKE + ORDER BY id DESC 改为
--      ILIKE + ORDER BY similarity()，需要 trgm GIN 索引支撑；
--   2) _vec_search_knowledge 的 skill 过滤走 metadata->>'skill' 与 tags ?，
--      分别需要表达式索引与 jsonb GIN 索引；
--   3) GET /api/interviews/page 按 user_id 过滤 + id 倒序分页。
--
-- 注意：本脚本仅在**空数据卷首次初始化**时由 docker-entrypoint 执行；
--       存量库由 Java 启动期 SchemaUpgradeRunner 执行同样的幂等 DDL。
-- =====================================================

-- pg_trgm 已在 01-schema.sql 创建（用于 similarity() 与 gin_trgm_ops）
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- skill 精确过滤（metadata->>'skill' = ?）
CREATE INDEX IF NOT EXISTS idx_kv_skill
    ON knowledge_vector ((metadata->>'skill'));

-- 标签包含过滤（metadata->'tags' ? ?）
CREATE INDEX IF NOT EXISTS idx_kv_meta
    ON knowledge_vector USING gin (metadata jsonb_path_ops);

-- 关键词召回（content ILIKE '%kw%' 走 trigram 索引）
CREATE INDEX IF NOT EXISTS idx_kv_content_trgm
    ON knowledge_vector USING gin (content gin_trgm_ops);

-- 历史会话分页：user_id 过滤 + id 倒序
CREATE INDEX IF NOT EXISTS idx_session_user_id
    ON interview_session (user_id, id DESC);
