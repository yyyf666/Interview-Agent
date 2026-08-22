"""全局配置：环境变量 + .env 多环境配置分离（设计文档 §4.3）；
ai_config 表存运行时覆盖项（页面可改，reload_from_db 级联清缓存免重启生效）"""
import logging
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict

logger = logging.getLogger(__name__)


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # 基础设施
    database_host: str = "localhost"
    database_port: int = 5432
    database_name: str = "interview_agent"
    database_user: str = "interview"
    database_password: str = "interview123"
    redis_url: str = "redis://localhost:6379/0"

    # 大模型（OpenAI兼容协议，支持 DeepSeek / 通义千问 / GPT）
    llm_api_base: str = "https://api.deepseek.com/v1"
    llm_api_key: str = ""
    llm_model: str = "deepseek-chat"

    # Embedding
    embedding_api_base: str = ""
    embedding_api_key: str = ""
    embedding_model: str = "text-embedding-v3"
    embedding_dim: int = 1024

    # ASR
    asr_api_base: str = ""
    asr_api_key: str = ""
    asr_model: str = "whisper-1"
    # 实时流式 ASR（DashScope paraformer-realtime WebSocket，复用 asr_key）
    asr_stream_model: str = "paraformer-realtime-v2"
    asr_stream_base_url: str = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"

    # 能力开关
    rerank_enabled: bool = True
    rerank_model: str = "BAAI/bge-reranker-base"
    # 重排实现：auto=本地模型优先、不可用时回退API；local=仅本地；api=仅DashScope；none=关闭
    rerank_provider: str = "auto"
    rerank_api_model: str = "gte-rerank-v2"
    rerank_api_key: str = ""             # 缺省复用 embedding/LLM key（同为 DashScope 时）
    rerank_api_base: str = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"
    mcp_enabled: bool = False

    # 意图降本与预算护栏
    intent_rules_enabled: bool = True    # 规则前置短路（高置信指令不耗LLM调用）
    llm_budget_warn: int = 6             # 单请求LLM调用数超过即告警

    # 备用模型（R-17）：主模型整条降级链失败时重试一次，base+model 同时配置才生效
    llm_fallback_api_base: str = ""
    llm_fallback_api_key: str = ""
    llm_fallback_model: str = ""

    # 评测降本：主评/清单判定并发 + 边界双评委抽样 + 极端分复核开关
    eval_pool_size: int = 16             # 评测子调用线程池上限（IO密集，按需调大）
    eval_double_judge_ratio: float = 0.2 # 边界双评委抽样比例，0=关闭，1=全量
    eval_extreme_recheck: bool = True    # 极端分（≤15/≥95）二次校验

    # RAG 结果缓存（按 query+skill+top_k，TTL 秒；索引更新时主动失效）
    rag_cache_enabled: bool = True
    rag_cache_ttl: int = 600

    # 内网安全：Java 业务层调用本服务时携带的共享密钥（生产必填，缺失时仅告警放行）
    internal_token: str = ""
    # CORS 白名单（逗号分隔）；仅浏览器直连调试场景需要，生产走 nginx 同源代理
    cors_allowed_origins: str = "http://localhost:3000,http://localhost:5173"

    # 路径
    skills_dir: str = "./skills"
    storage_path: str = "./data/uploads"

    # RAG 参数（设计文档 §9.1）
    chunk_size: int = 512
    chunk_overlap: int = 50
    recall_top_k: int = 10
    final_top_k: int = 3

    @property
    def embedding_base_url(self) -> str:
        return self.embedding_api_base or self.llm_api_base

    @property
    def embedding_key(self) -> str:
        return self.embedding_api_key or self.llm_api_key

    @property
    def asr_base_url(self) -> str:
        return self.asr_api_base or self.llm_api_base

    @property
    def asr_key(self) -> str:
        return self.asr_api_key or self.llm_api_key

    @property
    def cors_origins(self) -> list[str]:
        return [o.strip() for o in self.cors_allowed_origins.split(",") if o.strip()]

    @property
    def rerank_key(self) -> str:
        return self.rerank_api_key or self.embedding_key


# DB 运行时覆盖项（ai_config 表 payload），空值不进 override 以回落 env 默认
_DB_OVERRIDES: dict = {}


@lru_cache
def _build_settings() -> Settings:
    return Settings(**{k: v for k, v in _DB_OVERRIDES.items() if v not in (None, "")})


def get_settings() -> Settings:
    return _build_settings()


def reload_from_db() -> Settings:
    """从 ai_config 表重载覆盖项并级联清下游缓存（配置改动免重启即时生效）"""
    global _DB_OVERRIDES
    from app.core import ai_config_store

    try:
        ai_config_store.ensure_table()
        _DB_OVERRIDES = ai_config_store.load_payload()
    except Exception as e:  # DB 不可用（启动早期/断连）→ 维持当前配置
        logger.warning("AI配置重载失败，维持现有配置: %s", e)
    _build_settings.cache_clear()
    # 下游客户端缓存级联失效：LLM 实例池 / Embedding client（ASR、rerank 开关本就实时读取）
    from app.llm.factory import _llm_cache
    from app.rag import reranker as _reranker
    from app.rag.embedder import _client as _embed_client
    _llm_cache.clear()
    _embed_client.cache_clear()
    _reranker.reset_state()  # 换 key / 换 provider 后允许重新尝试重排
    logger.info("AI配置已重载，覆盖项: %s", sorted(_DB_OVERRIDES))
    return get_settings()
