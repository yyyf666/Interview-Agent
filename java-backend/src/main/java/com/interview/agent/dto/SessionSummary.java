package com.interview.agent.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 历史会话分页视图：只暴露列表与统计所需字段，
 * 不返回 plan/report 大 JSONB（实体级全量返回会让 payload 随轮次膨胀）。
 */
public record SessionSummary(
        Long id,
        String title,
        String status,
        String persona,
        Long candidateId,
        BigDecimal overallScore,
        String createdAt,
        // 面试计划模式（plan.mode）：GAUNTLET=八股；与实体 mode(TEXT/AUDIO) 区分
        String planMode,
        // 报告生成状态（R-16）：NONE/RUNNING/READY/FAILED，历史页据此展示「生成中」徽标
        String reportStatus) {

    /** 统计卡与趋势：仅正式面试（非八股），与分页解耦，避免前端全量派生 */
    public record Stats(int formalCount, double avgScore, List<Double> trendScores) {
    }

    /** 统一分页响应：hasMore 供前端"加载更多" */
    public record Page(List<SessionSummary> records, long total, int pageNo, int pageSize,
                       boolean hasMore, Stats stats) {
    }

    /** 时间统一转 ISO-8601 字符串（JDBC 可能返回 Timestamp/OffsetDateTime，前端按字符串截取日期） */
    public static String iso(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toString();
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant().atOffset(ZoneOffset.UTC).toString();
        }
        return value.toString();
    }
}
