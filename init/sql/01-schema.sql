-- =====================================================
-- 智能自适应AI面试系统 数据库初始化脚本
-- PostgreSQL 16 + pgvector
-- =====================================================
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ----------------------------------------------------
-- 技能字典表
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS skill (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(128) NOT NULL UNIQUE,
    category    VARCHAR(64),                 -- 分类：language / middleware / framework / design
    parent_id   BIGINT REFERENCES skill(id), -- 上下级关系
    weight      NUMERIC(5,2) DEFAULT 1.0,    -- 权重配置
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ----------------------------------------------------
-- 系统用户表（登录主体，06-migration-auth）
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(128) NOT NULL,          -- BCrypt
    nickname      VARCHAR(64),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ----------------------------------------------------
-- 候选人画像表（长期记忆载体）
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS candidate_profile (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT REFERENCES app_user(id),
    nickname            VARCHAR(64)  NOT NULL,
    target_role         VARCHAR(128),
    years_of_experience NUMERIC(4,1) DEFAULT 0,
    skill_tags          JSONB        NOT NULL DEFAULT '[]'::jsonb,   -- ["java","redis",...]
    skill_ratings       JSONB        NOT NULL DEFAULT '{}'::jsonb,   -- {"java":{"score":72,"level":"B","misconceptions":[...]}}
    total_interviews    INT          NOT NULL DEFAULT 0,
    avg_score           NUMERIC(5,2) NOT NULL DEFAULT 0,
    weak_points         JSONB        NOT NULL DEFAULT '[]'::jsonb,   -- 高频失分点追踪
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ----------------------------------------------------
-- 简历表
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS resume (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT REFERENCES app_user(id),
    candidate_id BIGINT REFERENCES candidate_profile(id),
    file_name    VARCHAR(256),
    file_path    VARCHAR(512),
    raw_text     TEXT,
    parsed       JSONB,                       -- LLM解析后的结构化技能画像
    version      INT NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ----------------------------------------------------
-- 岗位JD表
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS job_description (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT REFERENCES app_user(id),
    title         VARCHAR(256),
    company       VARCHAR(128),
    file_path     VARCHAR(512),
    raw_text      TEXT,
    parsed        JSONB,                      -- LLM解析后的岗位能力模型
    skill_weights JSONB NOT NULL DEFAULT '{}'::jsonb, -- {"java":0.3,...}
    focus_points  JSONB NOT NULL DEFAULT '[]'::jsonb, -- 考察重点
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ----------------------------------------------------
-- 面试会话表
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS interview_session (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT REFERENCES app_user(id),
    candidate_id      BIGINT REFERENCES candidate_profile(id),
    resume_id         BIGINT REFERENCES resume(id),
    jd_id             BIGINT REFERENCES job_description(id),
    title             VARCHAR(256),
    status            VARCHAR(16) NOT NULL DEFAULT 'INIT',  -- INIT/RUNNING/COMPLETED/ABORTED
    mode              VARCHAR(16) NOT NULL DEFAULT 'TEXT',  -- TEXT/AUDIO
    persona           VARCHAR(64) NOT NULL DEFAULT 'standard', -- 面试官人格；Panel多人时为逗号分隔CSV
    plan              JSONB,                                 -- 面试计划：主题顺序/题量/难度权重
    current_topic     VARCHAR(128),
    current_difficulty INT DEFAULT 2,                         -- 1-5级难度
    turn_count        INT NOT NULL DEFAULT 0,
    overall_score     NUMERIC(5,2),
    report            JSONB,                                  -- Coach生成的复盘报告
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_session_status ON interview_session(status);

-- ----------------------------------------------------
-- 面试轮次表
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS interview_turn (
    id              BIGSERIAL PRIMARY KEY,
    session_id      BIGINT NOT NULL REFERENCES interview_session(id),
    turn_no         INT NOT NULL,
    topic           VARCHAR(128),
    difficulty      INT DEFAULT 2,
    is_follow_up    BOOLEAN NOT NULL DEFAULT FALSE,
    question        TEXT,
    question_source VARCHAR(32),                -- rag / question_bank / generated
    question_type   VARCHAR(16),                -- concept/principle/scenario/project/behavior/code
    coding          JSONB,                      -- 代码题元数据 {starter_code, language, test_cases}
    interviewer_reaction TEXT,                  -- 面试官对该轮回答的现场回应
    reference_points  JSONB NOT NULL DEFAULT '[]'::jsonb, -- 参考答题要点（回放对照/分级提示）
    hint_count        INT NOT NULL DEFAULT 0,   -- 本轮分级提示使用次数（评测降档）
    interjection_intent VARCHAR(24),            -- 插话意图（answer_type=INTERJECTION 时）
    speaker_persona   VARCHAR(16),              -- 当轮发言人设（Panel轮转结果）
    answer          TEXT,
    answer_type     VARCHAR(16) DEFAULT 'TEXT', -- TEXT / ASR / CODE / INTERJECTION
    skill_tags      JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_turn_session ON interview_turn(session_id);

-- ----------------------------------------------------
-- 评测表（每轮回答的多维度评分）
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS evaluation (
    id                  BIGSERIAL PRIMARY KEY,
    session_id          BIGINT NOT NULL REFERENCES interview_session(id),
    turn_id             BIGINT NOT NULL REFERENCES interview_turn(id),
    score_accuracy      NUMERIC(5,2),   -- 技术准确性 30%
    score_completeness  NUMERIC(5,2),   -- 内容完整度 20%
    score_depth         NUMERIC(5,2),   -- 技术深度 20%
    score_logic         NUMERIC(5,2),   -- 逻辑推理 15%
    score_expression    NUMERIC(5,2),   -- 表达结构 10%
    score_tradeoff      NUMERIC(5,2),   -- 选型权衡 5%
    total_score         NUMERIC(5,2),
    evidence            JSONB NOT NULL DEFAULT '[]'::jsonb, -- 与issues对应的候选人原文片段（证据化评测）
    checklist           JSONB NOT NULL DEFAULT '[]'::jsonb, -- 参考要点命中清单（清单聚合）
    suspected_recital   BOOLEAN NOT NULL DEFAULT FALSE,     -- 疑似背诵参考答案
    basis               JSONB NOT NULL DEFAULT '[]'::jsonb,  -- 得分依据（关键正确点）
    issues              JSONB NOT NULL DEFAULT '[]'::jsonb,  -- 错误/遗漏点
    should_follow_up    BOOLEAN NOT NULL DEFAULT FALSE,
    follow_up_direction VARCHAR(512),
    skill_tags          JSONB NOT NULL DEFAULT '[]'::jsonb,
    decision_reason     VARCHAR(512),                        -- Director决策依据（可解释性）
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_eval_session ON evaluation(session_id);

-- ----------------------------------------------------
-- 题库表
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS question_bank (
    id               BIGSERIAL PRIMARY KEY,
    content          TEXT NOT NULL,
    skill_name       VARCHAR(128),
    difficulty       INT NOT NULL DEFAULT 2,      -- 1-5
    q_type           VARCHAR(32),                 -- concept / principle / scenario / project / behavior
    tags             JSONB NOT NULL DEFAULT '[]'::jsonb,
    reference_answer TEXT,
    scoring_points   JSONB NOT NULL DEFAULT '[]'::jsonb, -- 评测打分要点
    source           VARCHAR(64),
    status           VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version          INT NOT NULL DEFAULT 1,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_qb_skill ON question_bank(skill_name, difficulty);

-- ----------------------------------------------------
-- 知识库文档表
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS knowledge_doc (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT REFERENCES app_user(id),
    title        VARCHAR(256),
    file_path    VARCHAR(512),
    doc_type     VARCHAR(32),                 -- question_bank / article / note / experience
    tags         JSONB NOT NULL DEFAULT '[]'::jsonb,
    chunk_status VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING/INDEXED/FAILED
    chunk_count  INT NOT NULL DEFAULT 0,
    version      INT NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ----------------------------------------------------
-- 向量数据表（统一1024维，余弦相似度；DashScope text-embedding-v3 默认维度）
-- ----------------------------------------------------
CREATE TABLE IF NOT EXISTS knowledge_vector (
    id          BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES knowledge_doc(id) ON DELETE CASCADE,
    chunk_index INT NOT NULL,
    content     TEXT NOT NULL,
    embedding   vector(1024),
    metadata    JSONB NOT NULL DEFAULT '{}'::jsonb, -- 技能标签/难度/来源
    UNIQUE (document_id, chunk_index)
);

CREATE TABLE IF NOT EXISTS question_vector (
    id          BIGSERIAL PRIMARY KEY,
    question_id BIGINT NOT NULL REFERENCES question_bank(id) ON DELETE CASCADE UNIQUE,
    content     TEXT NOT NULL,
    embedding   vector(1024),
    metadata    JSONB NOT NULL DEFAULT '{}'::jsonb  -- 技能/题型/难度
);

CREATE TABLE IF NOT EXISTS skill_vector (
    id        BIGSERIAL PRIMARY KEY,
    skill_id  BIGINT NOT NULL REFERENCES skill(id) ON DELETE CASCADE UNIQUE,
    skill_name VARCHAR(128) NOT NULL,
    embedding vector(1024)
);

-- 向量近邻索引：HNSW（余弦），新库直接带；存量库见 07-migration-hnsw.sql
CREATE INDEX IF NOT EXISTS idx_kv_hnsw ON knowledge_vector USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);
CREATE INDEX IF NOT EXISTS idx_qv_hnsw ON question_vector USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);

-- ----------------------------------------------------
-- 种子数据：核心技能字典
-- ----------------------------------------------------
INSERT INTO skill (name, category, weight, description) VALUES
    ('java',          'language',   1.5, 'Java语言与JVM'),
    ('spring',        'framework',  1.2, 'Spring Boot / Spring生态'),
    ('mysql',         'database',   1.2, 'MySQL索引/事务/调优'),
    ('redis',         'middleware', 1.2, 'Redis数据结构/持久化/高可用'),
    ('mq',            'middleware', 1.0, '消息队列 Kafka/RocketMQ/RabbitMQ'),
    ('network',       'foundation', 0.8, '计算机网络'),
    ('os',            'foundation', 0.8, '操作系统'),
    ('system-design', 'design',     1.3, '分布式与高并发系统设计'),
    ('concurrency',   'language',   1.2, 'Java并发编程')
ON CONFLICT (name) DO NOTHING;

INSERT INTO candidate_profile (nickname, target_role) VALUES ('default', 'Java后端开发工程师')
ON CONFLICT DO NOTHING;

-- ----------------------------------------------------
-- 种子数据：示例题库（java/redis），带向量占位
-- 实际向量由 Python AI 层 embedding 后回填（POST /ai/questions/embed）
-- ----------------------------------------------------
INSERT INTO question_bank (content, skill_name, difficulty, q_type, tags, reference_answer, scoring_points) VALUES
    ('请简述JVM内存区域的划分，哪些区域会发生OOM？', 'java', 2, 'concept',
     '["jvm","内存模型"]',
     '堆、虚拟机栈、本地方法栈、方法区/元空间、程序计数器；除程序计数器外均可能OOM，堆最常见，栈StackOverflowError/OOM，元空间加载类过多也会OOM。',
     '["说出堆/栈/方法区等主要区域","指出程序计数器不会OOM","能区分OOM与StackOverflow"]'),
    ('HashMap的put流程是怎样的？JDK8做了哪些优化？', 'java', 2, 'principle',
     '["集合","hash"]',
     '计算hash->定位桶->无冲突直接插入->冲突时链表尾插，长度>=8且数组>=64树化->size超过阈值扩容2倍。JDK8优化：头插改尾插避免并发死循环、引入红黑树、扰动函数简化。',
     '["完整put流程","树化条件8/64","扩容机制","JDK7头插死循环问题"]'),
    ('synchronized和ReentrantLock的区别？锁升级过程？', 'java', 3, 'concept',
     '["并发","锁"]',
     'synchronized是JVM内置锁、自动加锁释放、非公平、不可中断；ReentrantLock基于AQS、可公平可非公平、支持condition/尝试锁/可中断。锁升级：无锁->偏向锁->轻量级锁(CAS自旋)->重量级锁(monitor)。',
     '["两者功能与性能差异","锁升级四个阶段","AQS/condition特性","适用场景"]'),
    ('Redis的RDB和AOF有什么区别？生产如何选型？', 'redis', 2, 'concept',
     '["持久化"]',
     'RDB是fork子进程的时间点快照，恢复快、可能丢数据；AOF追加写命令，appendfsync everysec兼顾性能与安全，文件大需rewrite。生产通常二者结合：AOF保证实时性+RDB做全量备份与快速重启。',
     '["原理差异","数据安全性对比","恢复速度","混合持久化方案"]'),
    ('Redis过期键的删除策略和内存淘汰策略？', 'redis', 3, 'principle',
     '["过期","lru"]',
     '过期删除：惰性删除+定期扫描；内存淘汰8种：noeviction及LRU/LFU/random/ttl的allkeys/volatile系列。缓存场景常用allkeys-lfu，消息类用noeviction。',
     '["两种过期策略配合","区分allkeys与volatile","LRU与LFU差异","业务选型"]'),
    ('MySQL索引失效的常见场景有哪些？', 'mysql', 2, 'concept',
     '["索引","explain"]',
     '最左前缀被破坏、对索引列函数计算/隐式类型转换、like左前缀%、OR两侧非全索引列、区分度过低优化器放弃、字符集排序规则不一致join等。通过explain观察type/key/rows。',
     '["最左前缀","隐式转换","函数导致失效","explain关键字段"]')
ON CONFLICT DO NOTHING;
