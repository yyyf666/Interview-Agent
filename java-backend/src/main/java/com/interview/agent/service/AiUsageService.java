package com.interview.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.entity.AiUsage;
import com.interview.agent.entity.InterviewSession;
import com.interview.agent.mapper.AiUsageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 用量归集（R-17）：Python 每个请求回传 meta（llm_calls / llm_elapsed_ms / model），
 * 这里落一条 ai_usage 并把累计值写回 session.ai_meta，供报告页与管理看板展示成本。
 * 统计口径只含 LLM 类调用，不含沙箱执行；meta 缺失或 llm_calls<=0 时直接跳过。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiUsageService {

    private final AiUsageMapper aiUsageMapper;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /** 会话内调用：落 ai_usage 并累加 session.ai_meta（失败只告警，不影响主流程） */
    public void record(InterviewSession session, String scene, Map<String, Object> meta) {
        if (session == null || session.getId() == null) {
            return;
        }
        record(session.getUserId(), session.getId(), scene, meta);
        accumulate(session, meta);
    }

    /** 无会话归属的调用（如开前缺口分析）：只落 ai_usage */
    public void record(Long userId, Long sessionId, String scene, Map<String, Object> meta) {
        int calls = intOf(meta == null ? null : meta.get("llm_calls"));
        if (calls <= 0) {
            return;
        }
        try {
            AiUsage row = new AiUsage();
            row.setUserId(userId);
            row.setSessionId(sessionId);
            row.setScene(scene);
            row.setLlmCalls(calls);
            row.setElapsedMs(longOf(meta.get("llm_elapsed_ms")));
            Object model = meta.get("model");
            row.setModel(model == null || String.valueOf(model).isBlank()
                    ? null : String.valueOf(model));
            aiUsageMapper.insert(row);
        } catch (RuntimeException e) {
            log.warn("ai_usage 落库失败 scene={} session={}: {}", scene, sessionId, e.getMessage());
        }
    }

    /**
     * 会话级累计写入 ai_meta：{llm_calls, elapsed_ms, model}。
     * 只更新 ai_meta 一列（避免整行回写覆盖并发修改），内存对象同步以便本次响应即带上新值。
     */
    @SuppressWarnings("unchecked")
    private void accumulate(InterviewSession session, Map<String, Object> meta) {
        int calls = intOf(meta == null ? null : meta.get("llm_calls"));
        if (calls <= 0) {
            return;
        }
        Map<String, Object> acc = session.getAiMeta() instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        acc.put("llm_calls", intOf(acc.get("llm_calls")) + calls);
        acc.put("elapsed_ms", longOf(acc.get("elapsed_ms")) + longOf(meta.get("llm_elapsed_ms")));
        Object model = meta.get("model");
        if (model != null && !String.valueOf(model).isBlank()) {
            acc.put("model", String.valueOf(model));
        }
        session.setAiMeta(acc);
        try {
            jdbc.update("UPDATE interview_session SET ai_meta = ?::jsonb WHERE id = ?",
                    objectMapper.writeValueAsString(acc), session.getId());
        } catch (Exception e) {
            log.warn("session.ai_meta 累加失败 session={}: {}", session.getId(), e.getMessage());
        }
    }

    private static int intOf(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? 0 : Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long longOf(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? 0L : Math.round(Double.parseDouble(String.valueOf(v).trim()));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
