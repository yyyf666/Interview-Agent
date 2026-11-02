package com.interview.agent.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 面试高级配置（R-08）：白名单校验 + 缺省回落。
 *
 * 前端只负责传原始值，非法值直接拒绝（提示具体字段），保证落库的计划始终合法；
 * 归一化结果随 /ai/interview/start 下发，Python 侧据此约束计划与追问强度。
 */
public final class InterviewOptions {

    public static final Set<Integer> QUESTION_BUDGETS = Set.of(6, 10, 14);
    public static final Set<Integer> DURATIONS = Set.of(20, 40, 60);
    public static final Set<String> CODE_MODES = Set.of("include", "exclude", "auto");
    public static final Set<String> CODE_LANGUAGES = Set.of("java", "python", "javascript");
    public static final Set<String> FOLLOWUP_LEVELS = Set.of("low", "standard", "high");
    public static final Set<String> LANGUAGES = Set.of("zh", "en", "mixed");

    private static final int FOCUS_SKILLS_MAX = 8;
    private static final int FOCUS_SKILL_LEN_MAX = 32;

    private InterviewOptions() {
    }

    /**
     * 归一化配置：始终返回全部键（null 表示自动），并做白名单与长度校验。
     *
     * @param raw 前端原始 options，可为 null
     * @return {question_budget, duration_min, difficulty, code_mode, code_language,
     *          followup_level, interview_language, focus_skills}
     */
    public static Map<String, Object> normalize(Map<String, Object> raw) {
        Map<String, Object> src = raw == null ? Map.of() : raw;
        Map<String, Object> out = new LinkedHashMap<>();

        Integer budget = intOrNull(src.get("question_budget"), "question_budget");
        if (budget != null && !QUESTION_BUDGETS.contains(budget)) {
            throw new IllegalArgumentException("question_budget 仅支持 6/10/14");
        }
        Integer duration = intOrNull(src.get("duration_min"), "duration_min");
        if (duration != null && !DURATIONS.contains(duration)) {
            throw new IllegalArgumentException("duration_min 仅支持 20/40/60");
        }
        Integer difficulty = intOrNull(src.get("difficulty"), "difficulty");
        if (difficulty != null && (difficulty < 1 || difficulty > 5)) {
            throw new IllegalArgumentException("difficulty 取值范围 1-5");
        }
        out.put("question_budget", budget);
        out.put("duration_min", duration);
        out.put("difficulty", difficulty);

        out.put("code_mode", enumOr(src.get("code_mode"), CODE_MODES, "auto", "code_mode"));
        out.put("code_language", enumOr(src.get("code_language"), CODE_LANGUAGES, "java", "code_language"));
        out.put("followup_level", enumOr(src.get("followup_level"), FOLLOWUP_LEVELS, "standard", "followup_level"));
        out.put("interview_language", enumOr(src.get("interview_language"), LANGUAGES, "zh", "interview_language"));
        out.put("focus_skills", focusSkills(src.get("focus_skills")));
        return out;
    }

    private static Integer intOrNull(Object value, String field) {
        if (value == null || "".equals(value)) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " 必须是整数");
        }
    }

    private static String enumOr(Object value, Set<String> allowed, String fallback, String field) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        String v = String.valueOf(value).trim().toLowerCase();
        if (!allowed.contains(v)) {
            throw new IllegalArgumentException(field + " 仅支持 " + allowed);
        }
        return v;
    }

    private static List<String> focusSkills(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("focus_skills 必须是字符串数组");
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String v = String.valueOf(item).trim();
            if (v.isEmpty()) {
                continue;
            }
            if (v.length() > FOCUS_SKILL_LEN_MAX) {
                throw new IllegalArgumentException("focus_skills 单项不超过 " + FOCUS_SKILL_LEN_MAX + " 字");
            }
            if (!out.contains(v)) {
                out.add(v);
            }
        }
        if (out.size() > FOCUS_SKILLS_MAX) {
            throw new IllegalArgumentException("focus_skills 最多 " + FOCUS_SKILLS_MAX + " 项");
        }
        return out;
    }
}
