-- AI 运行时配置：单行 JSONB 存储对 env 默认的覆盖项（apikey 等页面可改，免重启热生效）
CREATE TABLE IF NOT EXISTS ai_config (
    id         SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    payload    JSONB NOT NULL DEFAULT '{}',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO ai_config (id, payload) VALUES (1, '{}') ON CONFLICT (id) DO NOTHING;
