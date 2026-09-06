"""MCP 服务端（设计文档 §8.2）

将 app/tools/registry.py 的 8 个标准工具经 MCP 协议对外暴露（SSE 传输）。
- MCP_ENABLED=true：Agent/LangChain 客户端可通过 langchain-mcp-adapters 连接
  http://python-ai:8001/sse 统一调用工具；
- 独立启动：python -m app.mcp.server
"""
from __future__ import annotations

import logging

from app.tools.registry import (
    TOOL_REGISTRY,
    get_candidate_profile,
    get_job_description,
    get_resume,
    save_evaluation,
    search_interview_history,
    search_knowledge_base,
    search_question_bank,
    update_skill_rating,
)

logger = logging.getLogger(__name__)


def create_mcp_server():
    from mcp.server.fastmcp import FastMCP

    mcp = FastMCP("interview-agent-tools", port=8001)

    mcp.tool(name="get_resume",
             description="获取指定简历的结构化解析数据")(get_resume)
    mcp.tool(name="get_job_description",
             description="获取目标JD的岗位要求与技能权重")(get_job_description)
    mcp.tool(name="get_candidate_profile",
             description="获取候选人长期技能画像与历史表现")(get_candidate_profile)
    mcp.tool(name="search_question_bank",
             description="按技能、难度、标签检索题库")(search_question_bank)
    mcp.tool(name="search_knowledge_base",
             description="知识库RAG混合检索")(search_knowledge_base)
    mcp.tool(name="save_evaluation",
             description="保存单轮评测结果")(save_evaluation)
    mcp.tool(name="search_interview_history",
             description="查询历史面试记录")(search_interview_history)
    mcp.tool(name="update_skill_rating",
             description="更新候选人技能评级与薄弱点")(update_skill_rating)

    logger.info("MCP服务端注册工具: %s", list(TOOL_REGISTRY))
    return mcp


if __name__ == "__main__":
    server = create_mcp_server()
    server.run(transport="sse")
