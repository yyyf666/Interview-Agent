package com.interview.agent.service;

import com.interview.agent.client.AiServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理页后台能力（R-12 / R-17）：索引健康度、Skills 清单与重载、AI 用量看板。
 * 向量与配置的真源分别在 pgvector 与 AI 层；本服务只做聚合读与转发，不写入业务数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final JdbcTemplate jdbc;
    private final AiServiceClient aiClient;

    /** 索引健康：文档/分块/题目/向量数量 + 最近更新时间 + Embedding 配置齐备度 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> indexHealth() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("docCount", count("SELECT COUNT(*) FROM knowledge_doc"));
        out.put("docIndexed", count("SELECT COUNT(*) FROM knowledge_doc WHERE chunk_status = 'INDEXED'"));
        out.put("docFailed", count("SELECT COUNT(*) FROM knowledge_doc WHERE chunk_status = 'FAILED'"));
        out.put("chunkCount", count("SELECT COUNT(*) FROM knowledge_vector"));
        out.put("questionCount", count("SELECT COUNT(*) FROM question_bank"));
        out.put("questionIndexed", count("SELECT COUNT(*) FROM question_vector"));
        out.put("docVectorUpdatedAt", time("SELECT MAX(updated_at) FROM knowledge_doc WHERE chunk_status = 'INDEXED'"));
        out.put("questionVectorUpdatedAt", time("SELECT MAX(updated_at) FROM question_bank"));

        // Embedding 配置在 AI 层为真源：key 为掩码回显，非空即视为已配置
        try {
            Map<String, Object> cfg = (Map<String, Object>) aiClient.get("/ai/config").get("data");
            boolean ready = notBlank(cfg.get("embedding_api_base"))
                    && notBlank(cfg.get("embedding_model"))
                    && notBlank(cfg.get("embedding_api_key"));
            out.put("embeddingConfigured", ready);
            out.put("embeddingModel", cfg.getOrDefault("embedding_model", ""));
        } catch (RuntimeException e) {
            log.warn("读取 AI 配置失败: {}", e.getMessage());
            out.put("embeddingConfigured", false);
            out.put("embeddingModel", "");
            out.put("configError", e.getMessage());
        }
        return out;
    }

    /** Skills 清单（名称 / 是否覆盖 / 文件时间）；AI 层不可用时降级为空列表 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> skills() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Object data = aiClient.get("/ai/skills/detail").get("data");
            if (data instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("name", m.get("name"));
                        item.put("mtime", m.get("mtime"));
                        item.put("hasSkill", true);
                        out.add(item);
                    }
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        } catch (RuntimeException e) {
            log.warn("拉取 Skills 详情失败，降级为名称清单: {}", e.getMessage());
        }
        try {
            Object data = aiClient.get("/ai/skills").get("data");
            if (data instanceof List<?> list) {
                for (Object o : list) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", String.valueOf(o));
                    item.put("mtime", null);
                    item.put("hasSkill", true);
                    out.add(item);
                }
            }
        } catch (RuntimeException e) {
            log.warn("拉取 Skills 清单失败: {}", e.getMessage());
        }
        return out;
    }

    /** 重载 Skills 缓存，返回重载后的目录清单 */
    public Object reloadSkills() {
        return aiClient.postJson("/ai/skills/reload", Map.of()).get("data");
    }

    /** AI 用量（R-17）：近 N 天按日汇总 + 会话 TOP10 + 全量总计 */
    public Map<String, Object> aiUsage(int days) {
        int d = Math.max(1, Math.min(90, days));
        List<Map<String, Object>> daily = jdbc.queryForList(
                "SELECT to_char(date_trunc('day', created_at), 'YYYY-MM-DD') AS day,"
                        + " COUNT(*) AS records,"
                        + " COALESCE(SUM(llm_calls),0) AS llm_calls,"
                        + " COALESCE(SUM(elapsed_ms),0) AS elapsed_ms"
                        + " FROM ai_usage WHERE created_at >= now() - make_interval(days => ?)"
                        + " GROUP BY 1 ORDER BY 1", d);
        List<Map<String, Object>> topSessions = jdbc.queryForList(
                "SELECT u.session_id,"
                        + " COALESCE(MAX(s.title), '') AS title,"
                        + " COUNT(*) AS records,"
                        + " COALESCE(SUM(u.llm_calls),0) AS llm_calls,"
                        + " COALESCE(SUM(u.elapsed_ms),0) AS elapsed_ms"
                        + " FROM ai_usage u LEFT JOIN interview_session s ON s.id = u.session_id"
                        + " WHERE u.created_at >= now() - make_interval(days => ?)"
                        + " GROUP BY u.session_id ORDER BY llm_calls DESC LIMIT 10", d);
        Map<String, Object> total = jdbc.queryForMap(
                "SELECT COUNT(*) AS records, COALESCE(SUM(llm_calls),0) AS llm_calls,"
                        + " COALESCE(SUM(elapsed_ms),0) AS elapsed_ms"
                        + " FROM ai_usage WHERE created_at >= now() - make_interval(days => ?)", d);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", d);
        out.put("daily", daily);
        out.put("topSessions", topSessions);
        out.put("total", total);
        return out;
    }

    private long count(String sql) {
        Long v = jdbc.queryForObject(sql, Long.class);
        return v == null ? 0L : v;
    }

    private Object time(String sql) {
        return jdbc.queryForObject(sql, Object.class);
    }

    private static boolean notBlank(Object o) {
        return o != null && !String.valueOf(o).isBlank();
    }
}
