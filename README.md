# 智能自适应AI面试系统（本地实验版）

> 设计文档见 [智能自适应AI面试系统设计文档.md](./智能自适应AI面试系统设计文档.md)
> 仅供个人本地使用的 AI 面试实验系统：简历-JD匹配 → 多Agent自适应面试 → 结构化评测 → 复盘训练闭环。

## 一、系统架构

```
┌────────────────────────────────────────────────────────┐
│  web/  接入层（Nginx :3000，面试交互单页应用）           │
├────────────────────────────────────────────────────────┤
│  java-backend/  业务后端（Spring Boot 3.2 / Java 21）   │
│  :8080  会话生命周期·文件解析·数据持久化·API网关         │
├────────────────────────────────────────────────────────┤
│  python-ai/  AI能力层（FastAPI / LangGraph / LangChain）│
│  :8000  多Agent编排·RAG·评测·Skills·MCP·LLM交互         │
├────────────────────────────────────────────────────────┤
│  基础设施：PostgreSQL16+pgvector :5432 · Redis7 :6379   │
└────────────────────────────────────────────────────────┘
```

- **数据真源在 Java**：面试轮次/评测全部落 PostgreSQL，会话状态同步 Redis；Python AI 层按请求无状态重建 LangGraph 状态机，天然支持中断恢复与服务重启。
- **LLM 调用全部收敛在 Python 层**（OpenAI 兼容协议，支持 DeepSeek / 通义千问 / GPT 切换），Java 不直连大模型。

## 二、目录结构

```
Interview-Agent/
├── docker-compose.yml          # 一键编排 5 个服务
├── .env.example                # 环境变量模板（API Key 等）
├── init/sql/                   # 数据库初始化（9张关系表 + 3张向量表 + 种子数据）
├── java-backend/               # Spring Boot 业务后端
│   └── src/main/java/com/interview/agent/
│       ├── controller/         # 简历/JD/面试/题库/知识库/画像 API
│       ├── service/            # 生命周期编排、文件解析、存储
│       ├── entity|mapper/      # MyBatis-Plus（JSONB TypeHandler）
│       └── client/             # AiServiceClient（Java→Python REST）
├── python-ai/                  # FastAPI AI 能力层
│   └── app/
│       ├── agents/             # ★ LangGraph 状态机 + Director/Interviewer/Evaluator/Coach
│       ├── rag/                # 清洗→分块→Embedding→pgvector→混合检索→重排
│       ├── skills/ (loader)    # Skills 动态加载（配合根目录 skills/）
│       ├── tools/ + mcp/       # 8 个标准工具（MCP 协议暴露 / 进程内直调）
│       ├── llm/ + prompts/     # 结构化输出工厂 + Few-Shot 评分校准模板
│       └── api/                # /ai/* 路由
├── skills/                     # 领域Skill：java / redis / mysql / system-design
└── web/                        # 前端单页 + Nginx 反代
```

## 三、快速开始

### 1. 环境要求

- Docker Desktop（含 Compose）—— **推荐，唯一标准运行路径**
- 本机无需安装 Java 21 / Python 3.11（容器内提供）

### 2. 配置密钥

```powershell
Copy-Item .env.example .env
# 编辑 .env，至少填写：
#   LLM_API_KEY / LLM_MODEL / LLM_API_BASE     （大模型）
#   EMBEDDING_API_KEY / EMBEDDING_API_BASE     （向量化，缺省回落LLM配置）
#   ASR_API_KEY（可选，语音模式）
#   APP_JWT_SECRET / APP_INTERNAL_TOKEN        （开发有默认值；生产必须替换）
```

支持任意 OpenAI 兼容网关，例如：

| 供应商 | LLM_API_BASE | LLM_MODEL |
|--------|--------------|-----------|
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` |
| 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |

### 3. 启动

```powershell
docker-compose up -d --build
```

| 服务 | 地址 | 说明 |
|------|------|------|
| 面试界面 | http://localhost:3000 | 唯一对外入口 |
| Java API / Swagger | http://localhost:8080/swagger-ui.html | 开发期仅绑定 127.0.0.1 |
| AI 层 API / Docs | http://localhost:8000/docs | 开发期仅绑定 127.0.0.1，需 `X-Internal-Token` |
| AI 健康检查 | http://localhost:8000/health | 免鉴权（容器 healthcheck 用） |

### 4. 使用流程

1. **准备页**：创建候选人 → 上传简历（自动 LLM 解析）→ 粘贴/上传 JD（自动解析）→ 选择两者
2. **面试页**：系统展示面试计划并出首题；文字回答（Ctrl+Enter 提交）或 🎤 录音上传（ASR 转写）
3. 每轮回答后 Evaluator 六维评分 → Director 依据阈值规则决策（追问/换题/调难度/收尾）
4. **复盘页**：自动跳转生成总报告、能力雷达图、短板根因、7 天训练计划；技能评级与薄弱点回写长期画像
5. **管理页**：上传知识库文档（RAG 入库）、题库向量重建

首次使用前建议执行 `POST /api/questions/reindex`（Java 8080）为种子题库补建向量。

> 角色体系：首个注册用户自动成为 **ADMIN**（可管理题库/知识库/AI 配置）；其余注册用户为 USER。
> 管理类写接口由后端 `@RequireRole` 拦截，前端仅在 ADMIN 下显示入口。

### 5. 生产部署前必改清单

开发编排（`docker-compose.yml`）把 DB/Redis/AI/Java 端口都绑定在 `127.0.0.1`，默认口令与默认密钥开箱可用。
**上线必须改用生产编排**，并按下列清单逐项确认：

```powershell
docker compose -f docker-compose.prod.yml up -d --build   # 只暴露 web:80
```

| # | 必改项 | 说明 |
|---|--------|------|
| 1 | `POSTGRES_PASSWORD` | 强随机口令；生产编排用 `${VAR:?}` 强制必填 |
| 2 | `APP_JWT_SECRET` | ≥32 字节随机串（`openssl rand -base64 48`）；否则拒绝启动 |
| 3 | `APP_INTERNAL_TOKEN` | Java ↔ python-ai 内部调用密钥，两侧必须一致；否则拒绝启动 |
| 4 | `APP_CORS_ALLOWED_ORIGINS` | 改成线上域名，禁止 `*` |
| 5 | `LLM_API_KEY` | AI 层缺失则无法出题/评测 |
| 6 | 反向代理 / TLS | 生产编排只开 `web:80`，建议外层再套一层 TLS 终结 |
| 7 | 首个账号 | 注册后为 ADMIN；如需指定他人，`UPDATE app_user SET role='ADMIN' WHERE username='...'` |
| 8 | 数据库迁移 | 已存在 `pg_data` 卷不会重跑 `init/sql`，新增脚本需手工执行（如 `09-migration-role.sql`；Java 启动会幂等补 `role` 列） |

其他已生效的默认加固（无需改动）：

- AI 层（8000）拒绝未携带 `X-Internal-Token` 的请求；CORS 白名单化。
- 登录 5 次失败 / 5 分钟 → 锁定 15 分钟；访问令牌 30 分钟 + refresh token 7 天（可吊销）。
- 异常返回真实 HTTP 状态码（400/403/409/429/502/504），前端可区分失败类型。
- `code-runner` 只挂 `sandbox`（internal，无公网出口，不可达 DB/Redis），cap 全丢、根文件系统只读、并发与体积上限。


## 四、核心机制速览

| 设计点 | 实现位置 |
|--------|----------|
| LangGraph 状态机（INIT/PLAN/QUESTION/EVALUATE/DECIDE/FOLLOW_UP/NEXT_TOPIC/FINAL_REPORT） | `python-ai/app/agents/graph.py` |
| 自适应决策阈值（≥85 升难 / 60-84 追问≤2次 / <60 降难） | `python-ai/app/agents/director.py` `decide()` |
| 评测一致性（Few-Shot 刻度 / 总分清算 / 极端分二次校验 / 同场波动≤20） | `python-ai/app/agents/evaluator.py` |
| RAG 混合检索（向量0.7 + BM25 0.3 融合，可选 bge 重排，Top10→Top3） | `python-ai/app/rag/retriever.py` |
| Skills 插拔（新增领域只需加 `skills/<name>/SKILL.md`） | `python-ai/app/skills/loader.py` |
| MCP 8 工具 | `python-ai/app/tools/registry.py` + `app/mcp/server.py` |
| 长期记忆回写（技能评级/薄弱点/成长趋势） | `agents/coach.py` → `update_skill_rating` |

## 五、可选能力

- **本地重排**：`requirements.txt` 取消注释 `sentence-transformers`，`.env` 设 `RERANK_ENABLED=true`（会显著增大镜像）
- **MCP 协议模式**：`python -m app.mcp.server` 以 SSE 暴露工具端点（:8001/sse），LangChain 客户端可经 langchain-mcp-adapters 统一接入；默认进程内直调，行为一致
- **独立开发运行**（需本机 Java 21 / Python 3.11）：
  ```powershell
  docker-compose up -d postgres redis
  cd python-ai; pip install -r requirements.txt; uvicorn app.main:app --reload   # :8000
  cd java-backend; mvn spring-boot:run                                            # :8080
  ```

## 六、故障排查

| 现象 | 处理 |
|------|------|
| 开始面试报 500 / AI启动失败 | 检查 `.env` LLM_API_KEY；`docker logs interview-python-ai` |
| 出题/评测明显变慢 | 属正常，每轮含多次 LLM 调用；换更快模型 |
| 向量检索无结果 | 未做题库 reindex / Embedding Key 未配置（不阻塞主流程，自动降级） |
| 语音回答失败 | ASR_API_KEY 未配置或音频网关不支持 webm；改用文字模式 |
| JSONB 写入报错 | 确认 JDBC URL 带 `?stringtype=unspecified`（compose 已默认携带） |
| 改了 SQL 不生效 | `docker-compose down -v` 重建（初始化脚本仅在空数据卷执行）；或手工 `psql -f init/sql/xx.sql` |
| 接口报 401「内部调用鉴权失败」 | `APP_INTERNAL_TOKEN` 两侧不一致：检查 java-backend 与 python-ai 容器同名环境变量 |
| 接口报 403「无权限执行该操作」 | 当前账号不是 ADMIN；执行 `UPDATE app_user SET role='ADMIN' WHERE username='...'` 后重新登录 |
| 登录报 429 | 触发失败次数锁定（5 次/5 分钟，锁 15 分钟）；等待或 `redis-cli DEL login:lock:<ip>:<user>` |
