-- =====================================================
-- 迁移 07：RAG 增强 —— pgvector HNSW 向量索引
-- 替代精确（全表）扫描；本地小数据量下行为一致，数据增长后自动受益
-- =====================================================

CREATE INDEX IF NOT EXISTS idx_kv_hnsw
    ON knowledge_vector USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

CREATE INDEX IF NOT EXISTS idx_qv_hnsw
    ON question_vector USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

COMMENT ON INDEX idx_kv_hnsw IS '知识分块向量近邻索引（余弦）';
COMMENT ON INDEX idx_qv_hnsw IS '题库向量近邻索引（余弦）';
