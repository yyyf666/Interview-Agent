# 系统优化评估与路线图

> 评估对象：智能自适应 AI 面试系统（`java-backend` / `python-ai` / `code-runner` / `web` + PostgreSQL(pgvector) + Redis）
> 评估方式：全量静态阅读（Java 56 个类、Python 45 个模块、Compose / nginx / SQL / 前端 30 个文件），无运行时压测数据
> 分级口径：**P0 = 上线前必须修复的安全与可靠性缺口**；**P1 = 性能成本与工程质量**；**P2 = 体验演进**

---

## 0. 总体判断

架构方向是对的：Java 做数据真源 + 会话编排，Python 做无状态 AI 能力层，LangGraph 9 节点状态机承担自适应决策，前端纯静态。当前代码可完整跑通「正式面试 / 八股拷打」两条主链路。

主要矛盾集中在三处：

1. **边界没有收口**：AI 能力层（8000）无鉴权、无来源限制，数据库（5432）/ Redis（6379）直连宿主，`ai_config` 又是全局单例配置 —— 任何能触达端口的人都能改全局模型密钥与 base_url。
2. **错误语义失真**：后端所有异常一律返回 HTTP 200 + `code=500`，前端无法区分「参数错 / 冲突 / 上游挂了 / 超时」，重试与降级策略无从下手。
3. **工程护栏为零**：`src/test` 与 `python-ai/tests` 不存在、无 CI、无 Flyway、缺关键唯一约束与索引。当前靠人工回归保证正确性，改动成本会随功能增长线性上升。

---

## 1. P0 安全与可靠性（上线前必须）

### 1.1 AI 能力层裸奔

- **现状与证据**
  - `docker-compose.yml` 将 `python-ai` 的 `8000:8000` 映射到宿主，公网可直接调用 `/ai/interview/answer`、`/ai/config`。
  - `python-ai/app/main.py`：`CORSMiddleware(allow_origins=["*"], allow_credentials=True)`，且无任何入站鉴权中间件。
  - `python-ai/app/api/config.py`：`PUT /ai/config` 可改写 LLM `api_key` / `base_url`，是全局单例影响所有用户。
- **建议**
  - 生产编排不再映射 8000；`docker-compose.prod.yml` 只保留 `web:80` 对外。
  - CORS 改白名单（环境变量 `CORS_ALLOWED_ORIGINS`）。
  - 新增 `X-Internal-Token` 共享密钥：FastAPI 中间件校验，Java `AiServiceClient` 统一注入；Java 启动时校验密钥非空，缺失直接启动失败（fail-fast）。
- **预期收益**：外部无法触达 AI 层；配置篡改面收敛为「拥有 Java 侧密钥的服务」。

### 1.2 基础设施与密钥

- **现状与证据**
  - `postgres` / `redis` 端口直接映射 `5432` / `6379` 到 `0.0.0.0`。
  - `docker-compose.yml` 内联默认口令 `interview123` 与默认 `APP_JWT_SECRET`，`application.yml` 同样带默认值。
- **建议**
  - 开发编排把端口绑定收敛到 `127.0.0.1`；生产编排不映射 DB / Redis。
  - 新增 `StartupConfigValidator`：生产 profile 下 `APP_JWT_SECRET` / `POSTGRES_PASSWORD` / `APP_INTERNAL_TOKEN` 缺失或等于默认值时拒绝启动。
  - 新增 `.env.example` 列出必填项；README 增加「生产部署前必改清单」。
- **预期收益**：消除「默认口令上网」这一最常见的失陷入口。

### 1.3 无角色体系

- **现状与证据**
  - `app_user` 仅 `username/password_hash/nickname/created_at`，`AuthInterceptor` 只校验「是否登录」。
  - `AiConfigController`、`QuestionController` 写接口、`KnowledgeController` 写接口对所有登录用户开放。
- **建议**
  - `app_user` 增 `role`（`USER` / `ADMIN`，默认 `USER`）；首个注册用户与存量最早用户提升为 `ADMIN`。
  - Java 新增 `@RequireRole("ADMIN")` + `RoleInterceptor`（放在 `AuthInterceptor` 之后），角色随 JWT claim 下发。
  - 前端顶栏按 `user.role` 隐藏「知识库/题库」与「AI 配置」入口。
- **预期收益**：普通用户无法改题库 / 知识库 / 模型密钥；权限模型轻量（不引入完整 Spring Security）。

### 1.4 HTTP 状态码与错误码

- **现状与证据**：`GlobalExceptionHandler` 三个 handler 全部返回 `ApiResult.error(...)`，HTTP 状态恒为 200；`ApiResult` 只有 `code/message/data`。
- **建议**
  - 参数错 → 400、状态冲突 → 409、上游异常 → 502、上游超时 → 504、未捕获 → 500。
  - `ApiResult` 增 `errorCode`（机器可读，如 `AI_TIMEOUT`），`message` 保留人读文案。
  - `AiServiceClient` 捕获 `RestClientResponseException` 与超时，转成 `AiUpstreamException` / `AiTimeoutException`。
  - 前端 `client.js` 同时读 `message` 与 `errorCode`。
- **预期收益**：前端可做「超时重试 / 上游降级 / 权限跳转」的差异化处理；监控可按状态码聚合。

### 1.5 代码沙箱隔离不足

- **现状与证据**：`code-runner` 仅靠 `preexec_fn` 的资源限制，容器本身 `cap` 全开、根文件系统可写、可访问 compose 默认网络（能连 `postgres:5432` / `redis:6379` / 出公网）。`/run` 经 nginx 免鉴权暴露。
- **建议**
  - 独立 `sandbox` 网络（`internal: true`）只挂 `code-runner` 与 `web`：沙箱无法访问业务内网，也无公网出口；nginx 仍可代理 `/run`（若改成 `network_mode: none`，代码题功能会直接失效）。
  - `cap_drop: ALL`、`read_only: true` + `tmpfs: /tmp`、`security_opt: no-new-privileges`、`pids_limit`。
  - `code-runner/app.py` 增加全局并发信号量（默认 2）与请求体长度上限。
- **预期收益**：用户提交的代码无法横向访问数据库 / Redis / 内网，也无法作为出网跳板。

### 1.6 登录防护与令牌生命周期

- **现状与证据**
  - `AuthService.login` 无失败计数，可无限撞库。
  - `JwtService` TTL 固定 7 天、无刷新、无吊销；token 存 `localStorage`。
- **建议**
  - Redis 记录 `login:fail:{ip}:{username}`，5 次 / 5 分钟 → 锁定 15 分钟，成功即清零。
  - Access token 30 分钟；新增 refresh token（7 天，Redis 存储可吊销）；新增 `POST /api/auth/refresh`、`POST /api/auth/logout`。
  - 前端 401 时先静默 refresh 并重放原请求，refresh 失败才回登录门禁。
- **预期收益**：暴力破解成本显著上升；令牌泄露的可用窗口从 7 天降到 30 分钟且可主动吊销。

---

## 2. P1 性能与成本

### 2.1 单题作答的 LLM 调用串行且重复

- **现状与证据**：`python-ai/app/agents/evaluator.py` 的 `evaluate()` 依次执行「主评 → 清单判定」两批串行 LLM；边界（55–65 / 80–90）再追加双评委，极端分（≤15 / ≥95）再复核。一次作答最坏 5 次 LLM 往返。
- **建议**：主评与清单判定并发（`asyncio.gather`）；双评委与极端复核改为配置开关（`eval_double_judge_ratio` 默认 0.2 抽样、`eval_extreme_recheck` 默认开）。
- **预期收益**：单题作答 P95 有望下降 30%+；LLM 调用成本按抽样比例线性下降。

### 2.2 上下文全量重放

- **现状与证据**：`InterviewService.buildContext` 每轮把全部 `interview_turn` + `evaluation` 拼进上下文，`reference_points` 也每轮携带。长会话（>15 轮）下 prompt 体积线性膨胀。
- **建议**：近 5 轮保留全文，更早轮次压缩为 `turn_no/topic/total_score/issues` 摘要；`reference_points` 仅当前题携带。
- **预期收益**：prompt token 与首 token 延迟随轮次趋稳，长会话不再恶化。

### 2.3 RAG 召回与重排名不副实

- **现状与证据**
  - `retriever._kw_search_knowledge` 用 `ILIKE '%kw%'` + `ORDER BY id DESC`，无相关度排序也无 trgm 索引。
  - `reranker.py` 在 `sentence_transformers` 缺失时静默透传（`_init_failed=True`），日志仅 warning，上游无感知。
- **建议**：为 `metadata->>'skill'` 与 `content` 建 GIN/trgm 索引，关键词召回改用 `similarity()` 排序；rerank 支持 DashScope API 实现，不可用时日志明确标注「降级透传」。
- **预期收益**：知识召回准确率提升，SQL 从全表扫描降为索引扫描。

### 2.4 历史接口无分页

- **现状与证据**：`InterviewService.listSessions` 用 `selectList(orderByDesc(id))` 全量返回 `InterviewSession` 实体（含 `plan` 大 JSONB），前端 `HistoryView` 全量渲染 + 客户端过滤。
- **建议**：新增 `GET /api/interviews/page?pageNo&pageSize&scope`，服务端过滤排序并返回 `SessionSummaryDTO`（不含 `plan`）。
- **预期收益**：历史接口 P95 ≤200ms、payload ≤100KB。

### 2.5 前端请求治理缺失

- **现状与证据**：`web/src/api/client.js` 的 `req()` 无 `AbortController` 超时、无重试、无取消；LLM 链路偶发长挂时页面只能干等。
- **建议**：默认 90s 超时（可传参覆盖）、错误归一化、GET 幂等重试 2 次（指数退避）；`startInterview/submit` 携带 `requestId` 幂等键。
- **预期收益**：请求不再无限挂起，弱网重试成功率高。

### 2.6 静态资源与缓存

- **现状与证据**：`web/nginx.conf` 无 gzip、无 `Cache-Control`、无安全响应头；构建产物单个 ECharts chunk 537KB。
- **建议**：开启 gzip + 静态资源 `immutable` 缓存、补 `X-Content-Type-Options/X-Frame-Options`；图表组件异步化并 `manualChunks` 拆分。
- **预期收益**：首屏传输体积下降 60%+，初始包体下降 30%+。

### 2.7 无缓存层

- **现状与证据**：RAG 检索、题库语义检索每次实时计算，Redis 目前只做会话缓存。
- **建议**：`coach` / `interviewer` 的 RAG 结果按 `topic+skill` 做 Redis 缓存（TTL 600s）。
- **预期收益**：重复主题的检索与 embedding 调用显著减少。

---

## 3. P1 工程质量

### 3.1 零自动化测试、无 CI

- **现状与证据**：`java-backend/src/test` 与 `python-ai/tests` 不存在（全仓库无 `@Test`、无 `def test_`），无 `.github/workflows`。
- **建议**：Python 覆盖 `director.decide` / `need_replan` / 标签轮转 / 评测权重与漂移 / `intent_rules`；Java 用 `@SpringBootTest` + Testcontainers 覆盖 `startGauntlet` / `submitAnswer` 幂等 / `finish` 不写画像；Playwright 跑两条主链路；CI 执行 `mvn -B verify`、`pytest`、`npm run build`。
- **预期收益**：核心判定逻辑可回归，重构（如拆分 `InterviewService`）有安全网。

### 3.2 无迁移工具、缺关键约束

- **现状与证据**：`init/sql` 只在空数据卷首次初始化时执行，后续变更靠手工；`interview_turn` 缺 `UNIQUE(session_id, turn_no)`，`evaluation` 缺 `UNIQUE(turn_id)`，`interview_session(user_id, id desc)` 无索引。
- **建议**：引入 Flyway，把现有脚本折成 `V1__baseline.sql`（`baseline-on-migrate=true`），新变更走增量迁移并补索引 / 唯一约束。
- **预期收益**：环境一致；并发下重复提交/重复评分由数据库兜底。

### 3.3 可观测性只有 trace

- **现状与证据**：`TraceIdFilter` + Python `trace_id_var` 已能串联单请求，但没有指标（QPS / 延迟 / 错误率）、没有 LLM 调用账本。
- **建议**：新增 `llm_call_log`（agent / model / tokens / latency / trace_id / session_id）；Java 引入 actuator + Micrometer 暴露 `/actuator/prometheus`（仅内网）；关键节点结构化日志。
- **预期收益**：可回答「哪个 agent 最贵」「P95 退化在哪一段」。

### 3.4 事务边界与中间态缺失

- **现状与证据**：`InterviewService.submitAnswer` 把「AI 调用 + 多表写入」交错执行，无事务包裹；AI 中途失败会留下半写状态（turn 有题目、evaluation 缺失）。
- **建议**：引入 `EVALUATING` 中间态 + 定时补偿（超时中间态重试或回滚为待答）。
- **预期收益**：崩溃/超时后会话可自愈，不再出现「答了但没分」的僵尸会话。

### 3.5 单类膨胀

- **现状与证据**：`InterviewService` 623 行，混合生命周期（建会话/结束）、答题编排、报告落库、画像回写四类职责。
- **建议**：先补测试，再拆为 `InterviewLifecycleService` / `TurnService` / `ReportService`（行为等价）。
- **预期收益**：修改面收敛，评审可读性提升。

### 3.6 密钥治理

- **现状与证据**：LLM/Embedding/ASR key 存 `ai_config` 表明文，出参掩码但落库明文；`.env` 未进 `.gitignore` 检查。
- **建议**：列出必改清单；后续可接 KMS/Vault 或至少加密列。
- **预期收益**：降低「数据库备份泄露 = 密钥泄露」风险。

---

## 4. P2 体验演进

1. **八股报告可视化**：2 标签场次无法出雷达图（`RadarChart` 要求 ≥3 模块键），降级为双环/条形图。
2. **追问轮转策略**：`Director` 追问不消耗轮转位，可能出现连续同标签追问；建议每 2 次追问强制换标签。
3. **首题等待**：`start` 链路串行（plan → question），首题可见时间 3–6s；建议骨架屏 + 阶段文案或流式。
4. **会话管理**：历史页缺搜索 / 删除 / 重命名；点击历史项进入回放而非报告，需明确入口。
5. **训练闭环**：报告训练计划 → 推荐题单 → 一键开练，记录完成率。
6. **题库运营**：批量导入（Excel/Markdown）+ 去重 + 审核状态。
7. **语音**：ASR 流式已可用但缺 TTS 与打断。
8. **模型降级**：主备模型（DeepSeek → Qwen）与超时兜底模板题。
9. **a11y 与包体**：焦点可见性、`aria` 基线、图表异步组件。

---

## 5. 实施路线图

| 批次 | 内容 | 验收指标 |
| --- | --- | --- |
| 第一批 P0（上线前必须） | AI 层内网化 + 内部 token、基础设施端口收敛与密钥校验、角色体系、HTTP 状态码语义、沙箱加固、登录防爆破 + refresh token、`.env.example` 与启动校验 | 外网无法直连 8000 / 5432 / 6379；非管理员写管理接口返回 403；连续 5 次错误登录被锁定；沙箱无法访问业务内网与公网 |
| 第二批 P1（性能与质量） | 评测降本、上下文压缩、RAG 索引与重排、历史分页、前端请求治理、测试与 CI、Flyway + 索引约束、可观测性、事务中间态、服务拆分 | 单题作答 P95 下降 ≥30%；历史接口 P95 ≤200ms、payload ≤100KB；核心判定单测覆盖 ≥70%；CI 全绿 |
| 第三批 P2（体验） | 报告可视化降级、追问轮转、首题骨架、会话管理、训练闭环、题库运营、模型降级、包体与 a11y | 首题可见 ≤3s；初始包体下降 ≥30%；报告可导出 |

---

## 6. 假设与边界

- 不引入微服务拆分、K8s、Milvus 替换；不重写现有 UI。
- 角色体系采用轻量拦截器（`@RequireRole` + `RoleInterceptor`），不引入完整 Spring Security。
- 数据库变更新增脚本保持幂等（`IF NOT EXISTS`），已存在的 `pg_data` 卷不重建；后续引入 Flyway 时统一收敛。
- `code-runner` 采用「独立 internal 网络」而非 `network_mode: none`：nginx `/run` 需要可达沙箱，`none` 会直接破坏代码题功能。
- 第一批优先级最高，建议单独评审后再动代码。
