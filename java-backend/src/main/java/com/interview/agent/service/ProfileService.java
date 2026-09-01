package com.interview.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.auth.Ownership;
import com.interview.agent.auth.UserContext;
import com.interview.agent.entity.CandidateProfile;
import com.interview.agent.entity.InterviewSession;
import com.interview.agent.entity.JobDescription;
import com.interview.agent.entity.Resume;
import com.interview.agent.mapper.CandidateProfileMapper;
import com.interview.agent.mapper.InterviewSessionMapper;
import com.interview.agent.mapper.JobDescriptionMapper;
import com.interview.agent.mapper.ResumeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 候选人画像与成长追踪（长期记忆） */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final CandidateProfileMapper profileMapper;
    private final InterviewSessionMapper sessionMapper;
    private final ResumeMapper resumeMapper;
    private final JobDescriptionMapper jdMapper;
    private final FileStorageService storageService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CandidateProfile get(Long id) {
        CandidateProfile p = profileMapper.selectById(id);
        if (p != null) {
            Ownership.check(p.getUserId(), "候选人画像");
            applyDerivedStats(p);
        }
        return p;
    }

    /**
     * 统计字段派生（R-18）：按 candidate_id 统计 COMPLETED 且非八股拷打的会话，
     * 返回体覆盖存储值（库内旧值仅作历史兼容，不再作为真源）。
     */
    private void applyDerivedStats(CandidateProfile p) {
        if (p.getId() == null) {
            return;
        }
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT COUNT(*) AS total, AVG(overall_score) AS avg_score FROM interview_session"
                            + " WHERE candidate_id = ? AND status = 'COMPLETED'"
                            + " AND (plan->>'mode') IS DISTINCT FROM 'GAUNTLET'", p.getId());
            Object total = row.get("total");
            p.setTotalInterviews(total instanceof Number n ? n.intValue() : 0);
            Object avg = row.get("avg_score");
            p.setAvgScore(avg instanceof Number n
                    ? BigDecimal.valueOf(n.doubleValue()).setScale(1, RoundingMode.HALF_UP) : null);
        } catch (RuntimeException e) {
            log.warn("画像统计派生失败 profile={}: {}", p.getId(), e.getMessage());
        }
    }

    public List<CandidateProfile> list() {
        LambdaQueryWrapper<CandidateProfile> w = new LambdaQueryWrapper<>();
        Long uid = UserContext.userId();
        if (uid != null) {
            w.and(q -> q.isNull(CandidateProfile::getUserId).or().eq(CandidateProfile::getUserId, uid));
        }
        return profileMapper.selectList(w.orderByAsc(CandidateProfile::getId));
    }

    public CandidateProfile create(CandidateProfile profile) {
        profile.setUserId(UserContext.userId());
        profileMapper.insert(profile);
        return profile;
    }

    public CandidateProfile update(CandidateProfile profile) {
        CandidateProfile db = profileMapper.selectById(profile.getId());
        if (db != null) {
            Ownership.check(db.getUserId(), "候选人画像");
        }
        profile.setUserId(db == null ? null : db.getUserId()); // 不允许跨用户转移归属
        // 统计字段只读（R-18）：置空后 MyBatis-Plus 忽略该列，避免前端覆盖派生值
        profile.setTotalInterviews(null);
        profile.setAvgScore(null);
        profileMapper.updateById(profile);
        return get(profile.getId());
    }

    /** 成长追踪：历次面试成绩趋势 */
    public List<Map<String, Object>> growthTrend(Long candidateId) {
        CandidateProfile p = profileMapper.selectById(candidateId);
        if (p != null) {
            Ownership.check(p.getUserId(), "候选人画像");
        }
        List<InterviewSession> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<InterviewSession>()
                        .eq(InterviewSession::getCandidateId, candidateId)
                        .eq(InterviewSession::getStatus, "COMPLETED")
                        .orderByAsc(InterviewSession::getId));
        List<Map<String, Object>> trend = new ArrayList<>();
        for (InterviewSession s : sessions) {
            Map<String, Object> item = new HashMap<>();
            item.put("session_id", s.getId());
            item.put("title", s.getTitle());
            item.put("overall_score", s.getOverallScore());
            item.put("finished_at", s.getFinishedAt());
            trend.add(item);
        }
        return trend;
    }

    // ------------------------------------------------- 画像洞察（R-18）

    /**
     * 画像洞察：薄弱点下钻（关联场次/证据原话/最近出现）+ 技能掌握度（含 30 天未练提示）。
     * 数据只读聚合，不改任何存储。
     */
    public Map<String, Object> insights(Long id) {
        CandidateProfile p = profileMapper.selectById(id);
        if (p == null) {
            throw new IllegalArgumentException("候选人画像不存在: " + id);
        }
        Ownership.check(p.getUserId(), "候选人画像");

        List<Map<String, Object>> rows = completedTurns(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profileId", id);
        out.put("weakPoints", weakDrilldown(p, rows));
        out.put("skills", skillMastery(p, rows));
        out.put("turnCount", rows.size());
        return out;
    }

    /** 该候选人所有已完成（非八股）场次的轮次 + 评测原文（jsonb 转 text 供本地解析） */
    private List<Map<String, Object>> completedTurns(Long candidateId) {
        return jdbc.queryForList(
                "SELECT t.id AS turn_id, t.session_id, t.question, t.skill_tags::text AS skill_tags,"
                        + " s.title, s.overall_score, s.finished_at,"
                        + " e.missed_knowledge_points::text AS missed, e.issues::text AS issues,"
                        + " e.evidence::text AS evidence, e.total_score AS turn_score"
                        + " FROM interview_turn t"
                        + " JOIN interview_session s ON s.id = t.session_id"
                        + " LEFT JOIN evaluation e ON e.turn_id = t.id"
                        + " WHERE s.candidate_id = ? AND s.status = 'COMPLETED'"
                        + " AND (s.plan->>'mode') IS DISTINCT FROM 'GAUNTLET'"
                        + " ORDER BY t.id ASC", candidateId);
    }

    /** 薄弱点下钻：每个弱点给出根因、最近出现时间、关联场次与证据原话 */
    private List<Map<String, Object>> weakDrilldown(CandidateProfile p, List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : asList(p.getWeakPoints())) {
            String skill = weakSkillOf(item);
            if (skill == null || skill.isBlank()) {
                continue;
            }
            String rootCause = weakReasonOf(item);
            Set<Long> sessionIds = new LinkedHashSet<>();
            List<Map<String, Object>> sessions = new ArrayList<>();
            List<Map<String, Object>> evidences = new ArrayList<>();
            OffsetDateTime lastSeen = null;
            for (Map<String, Object> row : rows) {
                if (!matchesSkill(skill, row)) {
                    continue;
                }
                Long sid = asLong(row.get("session_id"));
                if (sid != null && sessionIds.add(sid)) {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("id", sid);
                    s.put("title", row.get("title"));
                    s.put("date", timeText(row.get("finished_at")));
                    s.put("score", row.get("overall_score"));
                    sessions.add(s);
                }
                OffsetDateTime seen = asTime(row.get("finished_at"));
                if (seen != null && (lastSeen == null || seen.isAfter(lastSeen))) {
                    lastSeen = seen;
                }
                collectEvidence(row, evidences);
            }
            if (sessions.isEmpty() && evidences.isEmpty()) {
                continue; // 弱点尚未在正式面试中命中：不造假数据
            }
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("skill", skill);
            w.put("rootCause", rootCause);
            w.put("lastSeen", timeText(lastSeen));
            w.put("sessionCount", sessions.size());
            w.put("sessions", sessions.size() > 8 ? sessions.subList(0, 8) : sessions);
            w.put("evidence", evidences.size() > 10 ? evidences.subList(0, 10) : evidences);
            out.add(w);
        }
        return out;
    }

    /** 从评测行提取遗漏点/错因作为证据（问题 + 原话 + 遗漏描述） */
    private void collectEvidence(Map<String, Object> row, List<Map<String, Object>> sink) {
        List<Map<String, Object>> issues = jsonMaps(row.get("issues"));
        List<String> quotes = jsonStrings(row.get("evidence"));
        List<String> missed = jsonStrings(row.get("missed"));
        int n = Math.max(issues.size(), Math.max(quotes.size(), missed.size()));
        for (int i = 0; i < n && sink.size() < 40; i++) {
            Map<String, Object> issue = i < issues.size() ? issues.get(i) : Map.of();
            String missedText = i < missed.size() ? missed.get(i) : str(issue.get("issue"));
            String quote = i < quotes.size() ? quotes.get(i) : str(issue.get("quote"));
            if ((missedText == null || missedText.isBlank()) && (quote == null || quote.isBlank())) {
                continue;
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("question", truncate(row.get("question"), 120));
            e.put("quote", truncate(quote, 200));
            e.put("missed", truncate(missedText, 160));
            e.put("sessionId", row.get("session_id"));
            e.put("date", timeText(row.get("finished_at")));
            sink.add(e);
        }
    }

    /** 技能掌握度：以 skillRatings 为基底，叠加最近练习时间与 30 天未练天数 */
    private List<Map<String, Object>> skillMastery(CandidateProfile p, List<Map<String, Object>> rows) {
        Map<String, Object> ratings = asMap(p.getSkillRatings());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Object> entry : ratings.entrySet()) {
            String skill = entry.getKey();
            Map<String, Object> rating = asMap(entry.getValue());
            Double score = asDouble(rating.get("score"));
            if (score == null) {
                score = asDouble(rating.get("value"));
            }
            OffsetDateTime last = lastPracticed(skill, rows);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("skill", skill);
            s.put("score", score);
            s.put("level", rating.get("level"));
            s.put("mastery", masteryOf(score));
            s.put("lastPracticed", timeText(last));
            Long stale = last == null ? null : ChronoUnit.DAYS.between(last, OffsetDateTime.now());
            s.put("staleDays", stale);
            s.put("stale", stale != null && stale >= 30);
            out.add(s);
        }
        return out;
    }

    private OffsetDateTime lastPracticed(String skill, List<Map<String, Object>> rows) {
        OffsetDateTime last = null;
        for (Map<String, Object> row : rows) {
            if (!matchesSkill(skill, row)) {
                continue;
            }
            OffsetDateTime t = asTime(row.get("finished_at"));
            if (t != null && (last == null || t.isAfter(last))) {
                last = t;
            }
        }
        return last;
    }

    private static String masteryOf(Double score) {
        if (score == null) {
            return "未掌握";
        }
        if (score >= 75) {
            return "已掌握";
        }
        return score >= 50 ? "练习中" : "未掌握";
    }

    /** 技能 ⇄ 轮次匹配：skill_tags 命中或题干包含技能名（不区分大小写） */
    private static boolean matchesSkill(String skill, Map<String, Object> row) {
        String s = skill.toLowerCase();
        String tags = str(row.get("skill_tags"));
        if (tags != null && tags.toLowerCase().contains(s)) {
            return true;
        }
        String q = str(row.get("question"));
        return q != null && q.toLowerCase().contains(s);
    }

    // ------------------------------------------------- 数据资产：导出 / 导入 / 清空（R-18）

    /** 导出画像 + 简历 + JD（不含会话与密钥），供个人备份与迁移 */
    public Map<String, Object> exportData(Long id) {
        CandidateProfile p = get(id);
        if (p == null) {
            throw new IllegalArgumentException("候选人画像不存在: " + id);
        }
        List<Map<String, Object>> resumes = new ArrayList<>();
        for (Resume r : resumeMapper.selectList(new LambdaQueryWrapper<Resume>()
                .eq(Resume::getCandidateId, id).orderByAsc(Resume::getId))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fileName", r.getFileName());
            row.put("rawText", r.getRawText());
            row.put("parsed", r.getParsed());
            row.put("version", r.getVersion());
            resumes.add(row);
        }
        List<Map<String, Object>> jds = new ArrayList<>();
        Long uid = UserContext.userId();
        LambdaQueryWrapper<JobDescription> jw = new LambdaQueryWrapper<>();
        if (uid != null) {
            jw.and(q -> q.isNull(JobDescription::getUserId).or().eq(JobDescription::getUserId, uid));
        }
        for (JobDescription jd : jdMapper.selectList(jw.orderByAsc(JobDescription::getId))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", jd.getTitle());
            row.put("company", jd.getCompany());
            row.put("rawText", jd.getRawText());
            row.put("parsed", jd.getParsed());
            row.put("skillWeights", jd.getSkillWeights());
            row.put("focusPoints", jd.getFocusPoints());
            jds.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", 1);
        out.put("exportedAt", OffsetDateTime.now().toString());
        out.put("profile", p);
        out.put("resumes", resumes);
        out.put("jds", jds);
        return out;
    }

    /** 导入备份：新建画像 + 简历 + JD（不导入会话，避免跨账号串数据） */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> importData(Map<String, Object> body) {
        Object rawProfile = body == null ? null : body.get("profile");
        if (!(rawProfile instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("导入文件缺少 profile 字段");
        }
        CandidateProfile p = objectMapper.convertValue(rawProfile, CandidateProfile.class);
        p.setId(null);
        p.setUserId(UserContext.userId());
        p.setTotalInterviews(null);
        p.setAvgScore(null);
        profileMapper.insert(p);

        int resumeCount = 0;
        for (Object item : asList(body.get("resumes"))) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            Resume r = objectMapper.convertValue(item, Resume.class);
            r.setId(null);
            r.setUserId(UserContext.userId());
            r.setCandidateId(p.getId());
            r.setFilePath(null); // 备份不含文件，仅保留文本与结构化解析
            resumeMapper.insert(r);
            resumeCount++;
        }
        int jdCount = 0;
        for (Object item : asList(body.get("jds"))) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            JobDescription jd = objectMapper.convertValue(item, JobDescription.class);
            jd.setId(null);
            jd.setUserId(UserContext.userId());
            jd.setFilePath(null);
            jdMapper.insert(jd);
            jdCount++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profileId", p.getId());
        out.put("resumes", resumeCount);
        out.put("jds", jdCount);
        return out;
    }

    /** 清空当前用户数据（保留账号）：会话→轮次→评测→笔记/训练/用量→简历→JD→画像，需确认词 DELETE */
    @Transactional
    public Map<String, Object> purge(String confirm) {
        if (!"DELETE".equals(confirm == null ? null : confirm.trim())) {
            throw new IllegalArgumentException("确认词不正确，请输入 DELETE");
        }
        Long uid = UserContext.userId();
        if (uid == null) {
            throw new IllegalStateException("未登录，无法清空数据");
        }
        // 简历文件先删（后续行删除不再能定位文件路径）
        for (Resume r : resumeMapper.selectList(new LambdaQueryWrapper<Resume>().eq(Resume::getUserId, uid))) {
            storageService.delete(r.getFilePath());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("evaluations", jdbc.update("DELETE FROM evaluation WHERE session_id IN"
                + " (SELECT id FROM interview_session WHERE user_id = ?)", uid));
        out.put("turns", jdbc.update("DELETE FROM interview_turn WHERE session_id IN"
                + " (SELECT id FROM interview_session WHERE user_id = ?)", uid));
        out.put("reviewItems", jdbc.update("DELETE FROM review_item WHERE user_id = ?", uid));
        out.put("training", jdbc.update("DELETE FROM training_progress WHERE user_id = ?", uid));
        out.put("aiUsage", jdbc.update("DELETE FROM ai_usage WHERE user_id = ?", uid));
        out.put("sessions", jdbc.update("DELETE FROM interview_session WHERE user_id = ?", uid));
        out.put("resumes", jdbc.update("DELETE FROM resume WHERE user_id = ?", uid));
        out.put("jds", jdbc.update("DELETE FROM job_description WHERE user_id = ?", uid));
        out.put("profiles", jdbc.update("DELETE FROM candidate_profile WHERE user_id = ?", uid));
        return out;
    }

    // ------------------------------------------------- JSON 工具

    private static List<?> asList(Object v) {
        return v instanceof List<?> l ? l : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private String weakSkillOf(Object item) {
        if (item instanceof Map<?, ?> m) {
            return str(m.get("skill"));
        }
        return str(item);
    }

    private static String weakReasonOf(Object item) {
        if (item instanceof Map<?, ?> m) {
            Object r = m.containsKey("rootCause") ? m.get("rootCause") : m.get("reason");
            return str(r);
        }
        return null;
    }

    private List<String> jsonStrings(Object raw) {
        String text = str(raw);
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            Object v = objectMapper.readValue(text, Object.class);
            List<String> out = new ArrayList<>();
            for (Object x : asList(v)) {
                String s = x instanceof Map<?, ?> m ? str(m.get("issue")) : str(x);
                if (s != null && !s.isBlank()) {
                    out.add(s);
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Map<String, Object>> jsonMaps(Object raw) {
        String text = str(raw);
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            Object v = objectMapper.readValue(text, Object.class);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object x : asList(v)) {
                if (x instanceof Map<?, ?>) {
                    out.add(asMap(x));
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Long asLong(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    private static Double asDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }

    private static OffsetDateTime asTime(Object v) {
        if (v instanceof OffsetDateTime t) {
            return t;
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        }
        return null;
    }

    private static String timeText(Object v) {
        OffsetDateTime t = asTime(v);
        return t == null ? null : t.toString();
    }

    private static String truncate(Object v, int max) {
        String s = str(v);
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 删除候选人画像：级联删除名下简历（会话引用解绑 + 删文件），历史会话 candidate_id 置 NULL */
    @Transactional
    public void delete(Long id) {
        CandidateProfile p = profileMapper.selectById(id);
        if (p == null) {
            return;
        }
        Ownership.check(p.getUserId(), "候选人画像");
        List<Resume> resumes = resumeMapper.selectList(
                new LambdaQueryWrapper<Resume>().eq(Resume::getCandidateId, id));
        for (Resume r : resumes) {
            sessionMapper.update(null, new LambdaUpdateWrapper<InterviewSession>()
                    .set(InterviewSession::getResumeId, null)
                    .eq(InterviewSession::getResumeId, r.getId()));
            storageService.delete(r.getFilePath());
            resumeMapper.deleteById(r.getId());
        }
        sessionMapper.update(null, new LambdaUpdateWrapper<InterviewSession>()
                .set(InterviewSession::getCandidateId, null)
                .eq(InterviewSession::getCandidateId, id));
        profileMapper.deleteById(id);
    }
}
