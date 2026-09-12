package com.interview.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.auth.Ownership;
import com.interview.agent.auth.UserContext;
import com.interview.agent.client.AiServiceClient;
import com.interview.agent.dto.SessionSummary;
import com.interview.agent.entity.*;
import com.interview.agent.exception.AiTimeoutException;
import com.interview.agent.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * 面试生命周期编排：
 * 会话由 Java 层持有数据真源（PostgreSQL + Redis 状态缓存），
 * 提问/评测/决策/复盘等智能能力全部委托 Python AI 层（LangGraph 多Agent）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewService {

    private static final String SESSION_CACHE_PREFIX = "interview:session:";

    private final InterviewSessionMapper sessionMapper;
    private final InterviewTurnMapper turnMapper;
    private final EvaluationMapper evaluationMapper;
    private final ResumeMapper resumeMapper;
    private final JobDescriptionMapper jdMapper;
    private final CandidateProfileMapper profileMapper;
    private final AiServiceClient aiClient;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final FileStorageService storageService;
    private final GapCache gapCache;
    private final ReviewItemMapper reviewItemMapper;
    private final TrainingProgressMapper trainingProgressMapper;
    private final AiUsageMapper aiUsageMapper;
    private final AiUsageService aiUsageService;

    /** 上下文压缩：最近 N 轮问答保留全文，更早轮次只留评分摘要（长会话不再线性膨胀） */
    @Value("${app.context.full-history-turns:5}")
    private int fullHistoryTurns = 5;

    /** 报告异步生成（R-16）：单线程串行，避免多场同时结束把 LLM 打满 */
    @Autowired
    @Qualifier("reportExecutor")
    private ThreadPoolTaskExecutor reportExecutor;

    /** SSE 转发（R-16）：请求线程只负责挂起，长连接工作交给独立线程 */
    @Autowired
    @Qualifier("streamExecutor")
    private ThreadPoolTaskExecutor streamExecutor;

    // ------------------------------------------------- 开始面试

    @SuppressWarnings("unchecked")
    public Map<String, Object> start(Long candidateId, Long resumeId, Long jdId, String mode, String persona) {
        return start(candidateId, resumeId, jdId, mode, persona, null);
    }

    public Map<String, Object> start(Long candidateId, Long resumeId, Long jdId, String mode,
                                     String persona, List<String> personas) {
        return start(candidateId, resumeId, jdId, mode, persona, personas, null);
    }

    public Map<String, Object> start(Long candidateId, Long resumeId, Long jdId, String mode,
                                     String persona, List<String> personas, Map<String, Object> gap) {
        return start(candidateId, resumeId, jdId, mode, persona, personas, gap, null);
    }

    /**
     * 开始面试（R-08）：options 为前端高级配置，白名单归一化后随计划下发。
     * 非法配置直接拒绝（IllegalArgumentException → 400）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> start(Long candidateId, Long resumeId, Long jdId, String mode,
                                     String persona, List<String> personas, Map<String, Object> gap,
                                     Map<String, Object> options) {
        Map<String, Object> normalizedOptions = InterviewOptions.normalize(options);
        Resume resume = resumeMapper.selectById(resumeId);
        if (resume == null) {
            throw new IllegalArgumentException("简历不存在: " + resumeId);
        }
        Ownership.check(resume.getUserId(), "简历");
        JobDescription jd = jdMapper.selectById(jdId);
        if (jd == null) {
            throw new IllegalArgumentException("JD不存在: " + jdId);
        }
        Ownership.check(jd.getUserId(), "岗位JD");
        CandidateProfile profile = candidateId != null ? profileMapper.selectById(candidateId) : null;
        if (profile != null) {
            Ownership.check(profile.getUserId(), "候选人画像");
        }

        InterviewSession session = new InterviewSession();
        session.setUserId(UserContext.userId());
        session.setCandidateId(candidateId);
        session.setResumeId(resumeId);
        session.setJdId(jdId);
        session.setTitle("模拟面试 - " + jd.getTitle());
        session.setStatus("INIT");
        session.setMode(mode == null ? "TEXT" : mode);
        // Panel：personas 非空则存 CSV（多面试官轮转），否则回落单值 persona
        session.setPersona(resolvePersonaCsv(persona, personas));
        session.setTurnCount(0);
        session.setCurrentDifficulty(2);
        sessionMapper.insert(session);

        // 技能缺口分析 + 面试计划 + 首题（AI层：Director/Interviewer Agent）
        Map<String, Object> body = new HashMap<>();
        body.put("resume", Map.of(
                "parsed", Optional.ofNullable(resume.getParsed()).orElse(Map.of()),
                "raw_text", truncate(resume.getRawText(), 4000)));
        body.put("jd", Map.of("parsed", Optional.ofNullable(jd.getParsed()).orElse(Map.of())));
        body.put("profile", profile == null ? Map.of() : Map.of(
                "skill_ratings", Optional.ofNullable(profile.getSkillRatings()).orElse(Map.of()),
                "weak_points", Optional.ofNullable(profile.getWeakPoints()).orElse(List.of())));
        // 开前缺口分析（R-03）：携带已确认的 gap，PLAN 节点直接复用以避免二次 LLM 调用
        if (gap != null && !gap.isEmpty()) {
            body.put("gap", gap);
        }
        // 高级配置（R-08）：Python 侧据此约束题量/难度/代码题/追问强度与语言
        body.put("options", normalizedOptions);
        return beginSession(session, body, normalizedOptions);
    }

    // ------------------------------------------------- 开前缺口分析（R-03）

    /**
     * 开前缺口分析：简历 × JD 的匹配度/优势/薄弱/重点考察。
     * 结果按 interview:gap:{resumeId}:{jdId} 缓存 24h，简历/JD 变化时由 GapCache 清理。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> gapAnalysis(Long candidateId, Long resumeId, Long jdId) {
        Resume resume = resumeMapper.selectById(resumeId);
        if (resume == null) {
            throw new IllegalArgumentException("简历不存在: " + resumeId);
        }
        Ownership.check(resume.getUserId(), "简历");
        JobDescription jd = jdMapper.selectById(jdId);
        if (jd == null) {
            throw new IllegalArgumentException("JD不存在: " + jdId);
        }
        Ownership.check(jd.getUserId(), "岗位JD");
        if (candidateId != null) {
            CandidateProfile profile = profileMapper.selectById(candidateId);
            if (profile != null) {
                Ownership.check(profile.getUserId(), "候选人画像");
            }
        }

        String key = gapCache.key(resumeId, jdId);
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, Map.class);
            } catch (JsonProcessingException e) {
                log.warn("缺口分析缓存反序列化失败，回源重算: {}", e.getMessage());
            }
        }

        Map<String, Object> body = new HashMap<>();
        body.put("resume", Map.of("parsed", Optional.ofNullable(resume.getParsed()).orElse(Map.of())));
        body.put("jd", Map.of("parsed", Optional.ofNullable(jd.getParsed()).orElse(Map.of())));
        Map<String, Object> data = (Map<String, Object>) aiClient.postJson("/ai/interview/gap", body).get("data");
        // 用量口径（R-17）：缺口分析无会话归属，只落 ai_usage
        aiUsageService.record(UserContext.userId(), null, "gap", metaOf(data));
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(data), Duration.ofHours(24));
        } catch (JsonProcessingException e) {
            log.warn("缺口分析缓存写入失败: {}", e.getMessage());
        }
        return data;
    }

    // ------------------------------------------------- 开始八股拷打

    /**
     * 八股拷打：不依赖简历/JD/候选人，直接由预置标签生成计划并出首题。
     * 计划标记 plan.mode=GAUNTLET，Python 侧据此跳过 PLAN 节点并切换八股出题提示词。
     */
    public Map<String, Object> startGauntlet(List<String> tagIds, Integer difficulty,
                                             String persona, List<String> personas) {
        return startGauntlet(tagIds, difficulty, persona, personas, null);
    }

    /** 八股拷打开始（R-11）：studyMode=true 时前端自动展开评测讲解，计划内标记供刷新后保留。 */
    public Map<String, Object> startGauntlet(List<String> tagIds, Integer difficulty,
                                             String persona, List<String> personas, Boolean studyMode) {
        List<GauntletTagCatalog.Tag> tags = GauntletTagCatalog.resolve(tagIds);
        int base = difficulty == null ? 2 : Math.max(1, Math.min(5, difficulty));

        InterviewSession session = new InterviewSession();
        session.setUserId(UserContext.userId());
        session.setTitle(gauntletTitle(tags));
        session.setStatus("INIT");
        session.setMode("TEXT");
        session.setPersona(resolvePersonaCsv(persona, personas));
        session.setTurnCount(0);
        session.setCurrentDifficulty(base);
        sessionMapper.insert(session);

        Map<String, Object> body = new HashMap<>();
        body.put("plan", buildGauntletPlan(tags, base, Boolean.TRUE.equals(studyMode)));
        return beginSession(session, body);
    }

    /** 八股计划：每个标签一个主题；30 题软上限复用 total_question_budget 收尾。 */
    private static Map<String, Object> buildGauntletPlan(List<GauntletTagCatalog.Tag> tags, int base,
                                                        boolean studyMode) {
        List<String> labels = tags.stream().map(GauntletTagCatalog.Tag::label).toList();
        List<Map<String, Object>> planTags = new ArrayList<>();
        List<Map<String, Object>> topics = new ArrayList<>();
        for (GauntletTagCatalog.Tag tag : tags) {
            planTags.add(Map.of("id", tag.id(), "label", tag.label(), "skill", tag.skill()));
            topics.add(Map.of(
                    "topic", tag.topic(),
                    "skill", tag.skill(),
                    "question_count", 30,
                    "base_difficulty", base,
                    "reason", "候选人选择的八股标签「" + tag.label() + "」",
                    "coding", false));
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("mode", "GAUNTLET");
        plan.put("tags", planTags);
        plan.put("opening_remark", "八股拷打开场：本场围绕「" + String.join("、", labels)
                + "」连续出题，起始难度 " + base + " 级，随时可点「结束拷打并复盘」。");
        plan.put("total_question_budget", 30);
        plan.put("topics", topics);
        plan.put("options", Map.of("study_mode", studyMode));
        return plan;
    }

    /** 首题落库 + 会话启动的统一收尾（正式面试与八股共用）。 */
    private Map<String, Object> beginSession(InterviewSession session, Map<String, Object> body) {
        return beginSession(session, body, null);
    }

    /** options（R-08）非空时合并进 session.plan.options（Python 也会回传，双保险）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> beginSession(InterviewSession session, Map<String, Object> body,
                                             Map<String, Object> options) {
        body.put("session_id", session.getId());
        body.put("persona", session.getPersona());
        Map<String, Object> data = (Map<String, Object>) aiClient.postJson("/ai/interview/start", body).get("data");
        // 首题计划的 LLM 用量（R-17）
        aiUsageService.record(session, "start", metaOf(data));

        Object plan = data.get("plan") != null ? data.get("plan") : body.get("plan");
        if (options != null && !options.isEmpty()) {
            plan = mergePlanOptions(plan, options);
        }
        session.setPlan(plan);
        session.setStatus("RUNNING");
        session.setCurrentTopic((String) data.get("topic"));
        session.setCurrentDifficulty((Integer) data.get("difficulty"));
        session.setTurnCount(1);
        session.setStartedAt(OffsetDateTime.now());
        sessionMapper.updateById(session);

        saveTurnQuestion(session.getId(), 1, data, session.getPersona());
        cacheSession(session.getId());

        Map<String, Object> result = new HashMap<>(data);
        result.put("session_id", session.getId());
        result.put("speaker_persona", speakerPersona(session.getPersona(), 1));
        return result;
    }

    private static String gauntletTitle(List<GauntletTagCatalog.Tag> tags) {
        List<String> labels = tags.stream().map(GauntletTagCatalog.Tag::label).limit(3).toList();
        return "八股拷打 · " + String.join(" / ", labels) + (tags.size() > 3 ? " 等" : "");
    }

    /** 把归一化后的高级配置写回计划（Python 回传与新计划重写都不会丢失 options）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mergePlanOptions(Object plan, Map<String, Object> options) {
        Map<String, Object> merged = plan instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        Map<String, Object> existing = merged.get("options") instanceof Map<?, ?> o
                ? new LinkedHashMap<>((Map<String, Object>) o) : new LinkedHashMap<>();
        existing.putAll(options);
        merged.put("options", existing);
        return merged;
    }

    // ------------------------------------------------- 提交回答（文字/代码）

    @SuppressWarnings("unchecked")
    public Map<String, Object> submitAnswer(Long sessionId, String answer, String answerType) {
        return submitAnswer(sessionId, answer, answerType, null, null);
    }

    /**
     * 提交回答。代码题携带 execution（{language, stdin, stdout, exit_code}）时，
     * 将运行结果拼接进回答文本，供 Evaluator 作为代码正确性证据。
     * turnNo 为幂等键：与当前开放轮次不一致时拒绝（LLM超时重试/双端并发不产生重复轮次）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> submitAnswer(Long sessionId, String answer, String answerType,
                                            Map<String, Object> execution, Integer turnNo) {
        AnswerPrep prep = prepareAnswer(sessionId, answer, answerType, execution, turnNo);
        if (prep.early() != null) {
            return prep.early();
        }
        Map<String, Object> data = (Map<String, Object>) aiClient
                .postJson("/ai/interview/answer", prep.body()).get("data");
        return applyAiDecision(prep.session(), prep.current(), data);
    }

    /** 作答前置：幂等校验 + 回答落库 + 组装 AI 请求体（同步/流式共用） */
    @SuppressWarnings("unchecked")
    private AnswerPrep prepareAnswer(Long sessionId, String answer, String answerType,
                                     Map<String, Object> execution, Integer turnNo) {
        InterviewSession session = requireRunning(sessionId);
        InterviewTurn current = requireOpenTurnOrNull(sessionId);

        // 已作答但评测失败（AI 超时/断网）：同一 turnNo 重发时直接重试评测，天然幂等
        if (current == null && turnNo != null) {
            InterviewTurn answered = findTurn(sessionId, turnNo);
            if (answered != null && answered.getAnswer() != null) {
                if (findEvaluation(answered.getId()) != null) {
                    Map<String, Object> st = state(sessionId);
                    st.put("alreadyEvaluated", true);
                    st.put("session_status", "RUNNING");
                    return new AnswerPrep(null, null, null, st);
                }
                if (findTurn(sessionId, turnNo + 1) == null) {
                    return new AnswerPrep(null, null, null, retryEvaluationInternal(session, answered));
                }
                Map<String, Object> st = state(sessionId);
                st.put("alreadyAdvanced", true);
                st.put("session_status", "RUNNING");
                return new AnswerPrep(null, null, null, st);
            }
        }
        if (current == null) {
            throw new IllegalStateException("当前没有待回答的问题，会话数据异常");
        }
        if (turnNo != null && !turnNo.equals(current.getTurnNo())) {
            throw new IllegalStateException("轮次不匹配（当前应为第" + current.getTurnNo()
                    + "轮），该回答可能已提交过，请勿重复提交");
        }
        String fullAnswer = answer;
        if (execution != null && !execution.isEmpty()) {
            fullAnswer = answer + "\n\n【代码运行结果】\n"
                    + "输入: " + String.valueOf(execution.getOrDefault("stdin", "")).trim() + "\n"
                    + "输出: " + String.valueOf(execution.getOrDefault("stdout", "")).trim() + "\n"
                    + "退出码: " + execution.getOrDefault("exit_code", "-")
                    + (Boolean.TRUE.equals(execution.get("timed_out")) ? "（运行超时）" : "")
                    + sampleSummary(execution)
                    + (execution.get("stderr") != null && !String.valueOf(execution.get("stderr")).isBlank()
                       ? "\n错误输出: " + String.valueOf(execution.get("stderr")).trim() : "");
            answerType = "CODE";
        }
        current.setAnswer(fullAnswer);
        current.setAnswerType(answerType);
        turnMapper.updateById(current);

        Map<String, Object> body = new HashMap<>();
        body.put("session_id", sessionId);
        body.put("answer", fullAnswer);
        body.put("context", buildContext(session, current));
        return new AnswerPrep(session, current, body, null);
    }

    /** early 非空表示幂等短路结果（已评测/已推进），直接返回给调用方 */
    private record AnswerPrep(InterviewSession session, InterviewTurn current,
                              Map<String, Object> body, Map<String, Object> early) {
    }

    /** SSE 帧累加器：event + data 拼完一个空行才派发 */
    private static final class StreamState {
        String event;
        final StringBuilder data = new StringBuilder();
    }

    /**
     * 流式作答（R-16）：转发 Python 节点级 SSE，stage 事件原样下发；
     * 收到 result 后走与同步链路一致的 applyAiDecision 落库，再以 done 事件下发最终结果。
     */
    public void submitAnswerStream(Long sessionId, String answer, String answerType,
                                   Map<String, Object> execution, Integer turnNo, SseEmitter emitter) {
        UserContext.CurrentUser user = UserContext.get();
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> log.debug("SSE 连接异常 session={}: {}", sessionId, e.getMessage()));
        streamExecutor.execute(() -> {
            if (user != null) {
                UserContext.set(user);
            }
            try {
                AnswerPrep prep = prepareAnswer(sessionId, answer, answerType, execution, turnNo);
                if (prep.early() != null) {
                    sendEvent(emitter, "done", prep.early());
                    emitter.complete();
                    return;
                }
                StreamState state = new StreamState();
                boolean[] finished = {false};
                aiClient.streamPost("/ai/interview/answer/stream", prep.body(), line -> {
                    try {
                        onStreamLine(line, state, emitter, prep, finished);
                    } catch (IOException e) {
                        // 客户端断开：抛出让上游读取循环终止，尽早释放连接
                        throw new IllegalStateException("SSE 客户端已断开", e);
                    }
                });
                if (!finished[0]) {
                    sendEvent(emitter, "done", Map.of("session_status", "RUNNING", "stream_aborted", true));
                    emitter.complete();
                }
            } catch (Exception e) {
                log.error("流式作答失败 session={}", sessionId, e);
                try {
                    sendEvent(emitter, "error", Map.of(
                            "message", e.getMessage() == null ? "流式作答失败" : e.getMessage(),
                            "errorCode", e instanceof AiTimeoutException ? "AI_TIMEOUT" : "STREAM_ERROR"));
                    emitter.complete();
                } catch (Exception ignore) {
                    emitter.completeWithError(e);
                }
            } finally {
                UserContext.clear();
            }
        });
    }

    private void onStreamLine(String line, StreamState state, SseEmitter emitter,
                              AnswerPrep prep, boolean[] finished) throws IOException {
        if (line.isEmpty()) {
            flushStreamFrame(state, emitter, prep, finished);
            state.event = null;
            state.data.setLength(0);
            return;
        }
        if (line.startsWith("event:")) {
            state.event = line.substring(6).trim();
        } else if (line.startsWith("data:")) {
            state.data.append(line.substring(5).trim());
        }
    }

    @SuppressWarnings("unchecked")
    private void flushStreamFrame(StreamState state, SseEmitter emitter,
                                  AnswerPrep prep, boolean[] finished) throws IOException {
        if (state.event == null || state.data.length() == 0 || finished[0]) {
            return;
        }
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(state.data.toString(), Map.class);
        } catch (JsonProcessingException e) {
            log.warn("流式事件解析失败: {}", e.getMessage());
            return;
        }
        switch (state.event) {
            case "stage" -> sendEvent(emitter, "stage", payload);
            case "result" -> {
                Map<String, Object> done = applyAiDecision(prep.session(), prep.current(), payload);
                sendEvent(emitter, "done", done);
                finished[0] = true;
                emitter.complete();
            }
            case "error" -> {
                sendEvent(emitter, "error", payload);
                finished[0] = true;
                emitter.complete();
            }
            default -> { }
        }
    }

    private static void sendEvent(SseEmitter emitter, String name, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
    }


    /** 全样例运行汇总（R-13）：execution.results 存在时拼接「样例通过 n/m」供评测引用 */
    @SuppressWarnings("unchecked")
    private static String sampleSummary(Map<String, Object> execution) {
        Object results = execution.get("results");
        if (!(results instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        int passed = 0;
        for (Object o : list) {
            if (o instanceof Map<?, ?> row && Boolean.TRUE.equals(row.get("pass"))) {
                passed++;
            }
        }
        return "\n样例通过: " + passed + "/" + list.size();
    }

    /**
     * 八股主动跳过/换题（R-11）：不产生评测，只标记轮次并出新题。
     *
     * 将开放轮 answer 置占位文本 + answerType=INTERJECTION，再以 forced_intent 调用 AI 层；
     * Python node_intent 命中 forced_intent 时短路（不消耗 LLM 意图识别）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> interject(Long sessionId, String intent, String questionSpec) {
        String normalized = intent == null ? "" : intent.trim().toLowerCase();
        if (!"skip".equals(normalized) && !"request_question".equals(normalized)) {
            throw new IllegalArgumentException("intent 仅支持 skip / request_question");
        }
        InterviewSession session = requireRunning(sessionId);
        InterviewTurn current = requireOpenTurn(sessionId);

        String placeholder = "skip".equals(normalized) ? "[跳过本题]" : "[换一题]";
        current.setAnswer(placeholder);
        current.setAnswerType("INTERJECTION");
        current.setInterjectionIntent(normalized);
        turnMapper.updateById(current);

        Map<String, Object> body = new HashMap<>();
        body.put("session_id", sessionId);
        body.put("answer", placeholder);
        body.put("forced_intent", normalized);
        if (questionSpec != null && !questionSpec.isBlank()) {
            body.put("question_spec", questionSpec.trim());
        }
        body.put("context", buildContext(session, current));

        Map<String, Object> data = (Map<String, Object>) aiClient.postJson("/ai/interview/answer", body).get("data");
        Map<String, Object> result = applyAiDecision(session, current, data, "interject");
        result.put("interjected", normalized);
        return result;
    }

    /** 语音回答：上传音频 -> AI层ASR转写 -> 走文字回答链路 */
    public Map<String, Object> submitAudioAnswer(Long sessionId, MultipartFile audio) {
        String savedPath = storageService.save(audio, "audio");
        Map<String, Object> asrResp = aiClient.postMultipart("/ai/asr", "file",
                audio.getOriginalFilename(), storageService.readBytes(savedPath));
        String text = (String) asrResp.get("data");
        return submitAnswer(sessionId, text, "ASR");
    }

    /**
     * 重试评测（R-01）：用已落库的 answer 重新走「评测 -> 决策 -> 下一题」。
     * 幂等：已有 evaluation 或已推进出下一轮时只返回状态，不再消耗 LLM。
     */
    public Map<String, Object> retryEvaluation(Long sessionId, int turnNo) {
        InterviewSession session = requireRunning(sessionId);
        InterviewTurn turn = findTurn(sessionId, turnNo);
        if (turn == null || turn.getAnswer() == null) {
            throw new IllegalArgumentException("该轮次没有可重试的作答");
        }
        if (findEvaluation(turn.getId()) != null) {
            Map<String, Object> st = state(sessionId);
            st.put("alreadyEvaluated", true);
            return st;
        }
        if (findTurn(sessionId, turnNo + 1) != null) {
            Map<String, Object> st = state(sessionId);
            st.put("alreadyAdvanced", true);
            return st;
        }
        return retryEvaluationInternal(session, turn);
    }

    // ------------------------------------------------- 分级提示

    /**
     * 当前开放题的分级提示：1=方向 2=关键点 3=揭晓参考思路。
     * 不推进状态机，但次数落到 turn，评测时作为深度/完整度降档依据。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> requestHint(Long sessionId, int level) {
        InterviewSession session = requireRunning(sessionId);
        InterviewTurn current = requireOpenTurn(sessionId);
        int askedBefore = current.getHintCount() == null ? 0 : current.getHintCount();

        Map<String, Object> body = new HashMap<>();
        body.put("session_id", sessionId);
        body.put("question", current.getQuestion());
        body.put("reference_points", current.getReferencePoints() == null
                ? List.of() : (List<Object>) current.getReferencePoints());
        body.put("level", Math.max(1, Math.min(3, level)));
        body.put("topic", current.getTopic());
        body.put("asked_before", askedBefore);
        // 提示面向当前开放题的发言人（Panel 轮转后的单值，与本题 speaker_persona 一致）
        body.put("persona", speakerPersona(session.getPersona(), current.getTurnNo()));
        Map<String, Object> data = (Map<String, Object>) aiClient.postJson("/ai/interview/hint", body).get("data");
        aiUsageService.record(session, "hint", metaOf(data));

        current.setHintCount(askedBefore + 1);
        turnMapper.updateById(current);

        Map<String, Object> result = new HashMap<>(data);
        result.put("hint_count", current.getHintCount());
        result.put("turn_no", current.getTurnNo());
        return result;
    }

    // ------------------------------------------------- 主动结束并生成报告

    /**
     * 结束面试（R-16）：报告改为后台异步生成，接口立即返回 report_status。
     * 幂等：已 READY 直接回放；RUNNING 只返回状态不再重复排队；FAILED 可重试。
     */
    public Map<String, Object> finish(Long sessionId) {
        InterviewSession session = requireOwned(sessionId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (session.getReport() instanceof Map<?, ?> existing && !existing.isEmpty()
                && "READY".equals(session.getReportStatus())) {
            out.put("report_status", "READY");
            out.put("report", existing);
            return out;
        }
        if ("RUNNING".equals(session.getReportStatus())) {
            out.put("report_status", "RUNNING");
            return out;
        }
        if (!"RUNNING".equals(session.getStatus()) && !"COMPLETED".equals(session.getStatus())) {
            throw new IllegalStateException("面试会话状态不可用: " + session.getStatus());
        }
        // 若当前有未回答的问题，删除该空轮次（保持历史与报告一致）
        InterviewTurn open = requireOpenTurnOrNull(sessionId);
        if (open != null && open.getAnswer() == null) {
            turnMapper.deleteById(open.getId());
        }
        session.setStatus("COMPLETED");
        session.setReportStatus("RUNNING");
        session.setReportError(null);
        session.setFinishedAt(OffsetDateTime.now());
        sessionMapper.updateById(session);

        UserContext.CurrentUser user = UserContext.get();
        reportExecutor.execute(() -> generateReport(sessionId, user));
        out.put("report_status", "RUNNING");
        return out;
    }

    /** 后台报告任务：成功 READY，失败 FAILED + report_error（前端可轮询重试） */
    @SuppressWarnings("unchecked")
    private void generateReport(Long sessionId, UserContext.CurrentUser user) {
        try {
            if (user != null) {
                UserContext.set(user);
            }
            InterviewSession session = sessionMapper.selectById(sessionId);
            if (session == null) {
                return;
            }
            InterviewTurn open = requireOpenTurnOrNull(sessionId);
            Map<String, Object> body = new HashMap<>();
            body.put("session_id", sessionId);
            body.put("context", buildContext(session, open));
            Map<String, Object> data = (Map<String, Object>) aiClient
                    .postJson("/ai/interview/end", body).get("data");
            // 复盘报告的 LLM 用量（R-17）：异步线程内落库，失败只告警
            aiUsageService.record(session, "finish", metaOf(data));
            Map<String, Object> report = (Map<String, Object>) data.getOrDefault("report", data);
            persistReport(session, report);
        } catch (Exception e) {
            log.error("异步报告生成失败 session={}", sessionId, e);
            recordReportFailure(sessionId, e.getMessage());
        } finally {
            UserContext.clear();
        }
    }

    /** 失败落库：状态置 FAILED 并记录原因（截断到 512，避免超列宽） */
    private void recordReportFailure(Long sessionId, String message) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            return;
        }
        session.setReportStatus("FAILED");
        String err = message == null || message.isBlank() ? "报告生成失败" : message;
        session.setReportError(err.length() > 512 ? err.substring(0, 512) : err);
        sessionMapper.updateById(session);
        cacheSession(sessionId);
    }

    /** 报告状态轮询（R-16）：{reportStatus, reportError, overallScore, status} */
    public Map<String, Object> reportStatus(Long sessionId) {
        InterviewSession session = requireOwned(sessionId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reportStatus", session.getReportStatus() == null ? "NONE" : session.getReportStatus());
        out.put("reportError", session.getReportError());
        out.put("overallScore", session.getOverallScore());
        out.put("status", session.getStatus());
        return out;
    }

    // ------------------------------------------------- 查询

    public InterviewSession get(Long id) {
        InterviewSession session = sessionMapper.selectById(id);
        if (session != null) {
            Ownership.check(session.getUserId(), "面试会话");
        }
        return session;
    }

    /** 会话状态一次性同步（R-01）：session + turns + evaluations + 当前开放轮次。 */
    public Map<String, Object> state(Long sessionId) {
        InterviewSession session = get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("面试会话不存在: " + sessionId);
        }
        InterviewTurn open = requireOpenTurnOrNull(sessionId);
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("session", session);
        st.put("turns", listTurns(sessionId));
        st.put("evaluations", listEvaluations(sessionId));
        st.put("openTurnNo", open == null ? null : open.getTurnNo());
        return st;
    }

    /** 重命名会话（R-06）：仅改标题，不限会话状态。 */
    public InterviewSession rename(Long id, String title) {
        InterviewSession session = requireOwned(id);
        String trimmed = title == null ? "" : title.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        if (trimmed.length() > 256) {
            throw new IllegalArgumentException("标题不能超过 256 个字符");
        }
        session.setTitle(trimmed);
        sessionMapper.updateById(session);
        return session;
    }

    /** 删除会话（R-06）：按 evaluation -> turn -> session 顺序硬删除，避免外键约束；R-09 级联清除错题/训练进度/AI 用量。 */
    @Transactional
    public void delete(Long id) {
        InterviewSession session = sessionMapper.selectById(id);
        if (session == null) {
            return;
        }
        Ownership.check(session.getUserId(), "面试会话");
        evaluationMapper.delete(new LambdaQueryWrapper<Evaluation>()
                .eq(Evaluation::getSessionId, id));
        turnMapper.delete(new LambdaQueryWrapper<InterviewTurn>()
                .eq(InterviewTurn::getSessionId, id));
        reviewItemMapper.delete(new LambdaQueryWrapper<ReviewItem>()
                .eq(ReviewItem::getSessionId, id));
        trainingProgressMapper.delete(new LambdaQueryWrapper<TrainingProgress>()
                .eq(TrainingProgress::getGauntletSessionId, id));
        aiUsageMapper.delete(new LambdaQueryWrapper<AiUsage>()
                .eq(AiUsage::getSessionId, id));
        sessionMapper.deleteById(id);
        redis.delete(SESSION_CACHE_PREFIX + id);
    }

    public List<InterviewSession> listSessions(Long candidateId) {
        LambdaQueryWrapper<InterviewSession> w = new LambdaQueryWrapper<>();
        Long uid = UserContext.userId();
        if (uid != null) {
            w.and(q -> q.isNull(InterviewSession::getUserId).or().eq(InterviewSession::getUserId, uid));
        }
        if (candidateId != null) {
            w.eq(InterviewSession::getCandidateId, candidateId);
        }
        return sessionMapper.selectList(w.orderByDesc(InterviewSession::getId));
    }

    public SessionSummary.Page pageSessions(int pageNo, int pageSize, String scope, Long candidateId) {
        return pageSessions(pageNo, pageSize, scope, candidateId, null, null);
    }

    /**
     * 历史会话分页：服务端过滤 + 摘要投影（不拉 plan/report 大 JSONB）。
     *
     * @param scope all=全部 / formal=仅正式面试 / gauntlet=仅八股
     * @param candidateId 非空时只留该候选人的正式面试；八股记录无候选人，始终保留（与旧版展示规则一致）
     * @param keyword 标题模糊匹配或 #id 精确匹配
     * @param status INIT/RUNNING/COMPLETED/ABORTED，空=全部
     */
    public SessionSummary.Page pageSessions(int pageNo, int pageSize, String scope, Long candidateId,
                                            String keyword, String status) {
        int page = Math.max(1, pageNo);
        int size = Math.max(1, Math.min(100, pageSize));

        QueryWrapper<InterviewSession> w = visibleSessions();
        applyScope(w, scope);
        if (candidateId != null) {
            w.and(q -> q.apply("plan->>'mode' = 'GAUNTLET'").or().eq("candidate_id", candidateId));
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            w.and(q -> q.apply("(title ILIKE {0} OR CAST(id AS TEXT) = {1})", "%" + kw + "%", kw));
        }
        if (status != null && !status.isBlank()) {
            String st = status.trim().toUpperCase();
            if (!Set.of("INIT", "RUNNING", "COMPLETED", "ABORTED").contains(st)) {
                throw new IllegalArgumentException("status 不合法: " + status);
            }
            w.eq("status", st);
        }
        w.select("id", "title", "status", "persona", "candidate_id", "overall_score",
                "created_at", "report_status", "plan->>'mode' AS plan_mode");

        Page<Map<String, Object>> result = sessionMapper.selectMapsPage(
                new Page<>(page, size), w.orderByDesc("id"));
        List<SessionSummary> records = new ArrayList<>();
        for (Map<String, Object> row : result.getRecords()) {
            records.add(toSummary(row));
        }
        return new SessionSummary.Page(records, result.getTotal(), page, size,
                (long) page * size < result.getTotal(), sessionStats());
    }

    /** 统计卡与趋势：仅正式面试，与分页解耦（避免前端为了统计全量拉取） */
    private SessionSummary.Stats sessionStats() {
        QueryWrapper<InterviewSession> agg = visibleSessions();
        agg.apply("COALESCE(plan->>'mode', '') <> 'GAUNTLET'")
                .select("COUNT(*) AS cnt", "AVG(overall_score) AS avg_score");
        List<Map<String, Object>> aggRows = sessionMapper.selectMaps(agg);
        Long cnt = aggRows.isEmpty() ? null : toLong(aggRows.get(0).get("cnt"));
        int count = cnt == null ? 0 : cnt.intValue();
        Object avgRaw = aggRows.isEmpty() ? null : aggRows.get(0).get("avg_score");
        double avg = avgRaw instanceof Number n ? n.doubleValue() : 0.0;

        QueryWrapper<InterviewSession> trend = visibleSessions();
        trend.apply("COALESCE(plan->>'mode', '') <> 'GAUNTLET'")
                .select("overall_score").orderByDesc("id").last("LIMIT 20");
        List<Double> scores = new ArrayList<>();
        for (Map<String, Object> row : sessionMapper.selectMaps(trend)) {
            // 整行全 NULL（如未评分的会话）时 MyBatis 默认返回 null 元素，需跳过
            if (row == null) {
                continue;
            }
            Object v = row.get("overall_score");
            if (v instanceof Number n) {
                scores.add(n.doubleValue());
            }
        }
        Collections.reverse(scores); // 时间正序，直接喂趋势图
        return new SessionSummary.Stats(count, Math.round(avg * 10) / 10.0, scores);
    }

    /** 可见性：遗留数据（user_id NULL）放行，否则仅归属用户可见 */
    private QueryWrapper<InterviewSession> visibleSessions() {
        QueryWrapper<InterviewSession> w = new QueryWrapper<>();
        Long uid = UserContext.userId();
        if (uid != null) {
            w.and(q -> q.isNull("user_id").or().eq("user_id", uid));
        }
        return w;
    }

    private static void applyScope(QueryWrapper<InterviewSession> w, String scope) {
        String s = scope == null ? "" : scope.trim().toLowerCase();
        switch (s) {
            case "formal" -> w.apply("COALESCE(plan->>'mode', '') <> 'GAUNTLET'");
            case "gauntlet" -> w.apply("plan->>'mode' = 'GAUNTLET'");
            default -> { }
        }
    }

    private static SessionSummary toSummary(Map<String, Object> row) {
        Map<String, Object> r = new HashMap<>();
        row.forEach((k, v) -> r.put(k.toLowerCase(), v));
        return new SessionSummary(
                toLong(r.get("id")),
                (String) r.get("title"),
                (String) r.get("status"),
                (String) r.get("persona"),
                toLong(r.get("candidate_id")),
                toDecimal(r.get("overall_score")),
                SessionSummary.iso(r.get("created_at")),
                (String) r.get("plan_mode"),
                (String) r.get("report_status"));
    }

    private static Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        return v instanceof Number n ? n.longValue() : Long.valueOf(v.toString());
    }

    public List<InterviewTurn> listTurns(Long sessionId) {
        requireVisible(sessionId);
        return turnMapper.selectList(new LambdaQueryWrapper<InterviewTurn>()
                .eq(InterviewTurn::getSessionId, sessionId).orderByAsc(InterviewTurn::getTurnNo));
    }

    public List<Evaluation> listEvaluations(Long sessionId) {
        requireVisible(sessionId);
        return evaluationMapper.selectList(new LambdaQueryWrapper<Evaluation>()
                .eq(Evaluation::getSessionId, sessionId).orderByAsc(Evaluation::getId));
    }

    /**
     * 与上一场对比（R-14）：同候选人 + 同 JD 的更早一场已完成正式面试。
     * 返回 {current, previous}；previous 为 null 表示无可对比场次。
     * 六维分取 evaluation 均值（报告只存 module_scores，不含六维）。
     */
    public Map<String, Object> compare(Long sessionId) {
        InterviewSession current = sessionMapper.selectById(sessionId);
        if (current == null) {
            throw new IllegalArgumentException("面试会话不存在: " + sessionId);
        }
        Ownership.check(current.getUserId(), "面试会话");

        Map<String, Object> mine = new LinkedHashMap<>();
        mine.put("id", current.getId());
        mine.put("overallScore", current.getOverallScore());
        mine.put("report", current.getReport());
        mine.put("dimensions", dimensionAverages(current.getId()));

        LambdaQueryWrapper<InterviewSession> w = new LambdaQueryWrapper<InterviewSession>()
                .lt(InterviewSession::getId, sessionId)
                .eq(InterviewSession::getStatus, "COMPLETED")
                .isNotNull(InterviewSession::getReport)
                .apply("(plan->>'mode') IS DISTINCT FROM 'GAUNTLET'")
                .orderByDesc(InterviewSession::getId)
                .last("LIMIT 1");
        if (current.getCandidateId() == null) {
            w.isNull(InterviewSession::getCandidateId);
        } else {
            w.eq(InterviewSession::getCandidateId, current.getCandidateId());
        }
        if (current.getJdId() == null) {
            w.isNull(InterviewSession::getJdId);
        } else {
            w.eq(InterviewSession::getJdId, current.getJdId());
        }
        Long uid = UserContext.userId();
        if (uid != null) {
            w.eq(InterviewSession::getUserId, uid);
        }
        InterviewSession prev = sessionMapper.selectOne(w);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("current", mine);
        if (prev == null) {
            out.put("previous", null);
            return out;
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("id", prev.getId());
        before.put("title", prev.getTitle());
        before.put("createdAt", prev.getCreatedAt());
        before.put("overallScore", prev.getOverallScore());
        before.put("report", prev.getReport());
        before.put("dimensions", dimensionAverages(prev.getId()));
        out.put("previous", before);
        return out;
    }

    /** 六维均值（accuracy/completeness/depth/logic/expression/tradeoff），无评测时返回空表 */
    private Map<String, Object> dimensionAverages(Long sessionId) {
        List<Evaluation> evals = evaluationMapper.selectList(new LambdaQueryWrapper<Evaluation>()
                .eq(Evaluation::getSessionId, sessionId));
        Map<String, Object> out = new LinkedHashMap<>();
        if (evals.isEmpty()) {
            return out;
        }
        String[] keys = {"accuracy", "completeness", "depth", "logic", "expression", "tradeoff"};
        double[] sums = new double[keys.length];
        for (Evaluation e : evals) {
            sums[0] += scoreOf(e.getScoreAccuracy());
            sums[1] += scoreOf(e.getScoreCompleteness());
            sums[2] += scoreOf(e.getScoreDepth());
            sums[3] += scoreOf(e.getScoreLogic());
            sums[4] += scoreOf(e.getScoreExpression());
            sums[5] += scoreOf(e.getScoreTradeoff());
        }
        for (int i = 0; i < keys.length; i++) {
            out.put(keys[i], BigDecimal.valueOf(sums[i] / evals.size()).setScale(1, RoundingMode.HALF_UP));
        }
        return out;
    }

    private static double scoreOf(BigDecimal v) {
        return v == null ? 0d : v.doubleValue();
    }

    /** 会话可见性：遗留数据（user_id NULL）放行，否则仅归属用户可访 */
    private void requireVisible(Long sessionId) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session != null) {
            Ownership.check(session.getUserId(), "面试会话");
        }
    }

    // ------------------------------------------------- 内部逻辑

    /** 从 AI 响应体取用量 meta（R-17）：{llm_calls, llm_elapsed_ms, model}，缺失返回 null */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> metaOf(Map<String, Object> data) {
        Object meta = data == null ? null : data.get("meta");
        return meta instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private Map<String, Object> applyAiDecision(InterviewSession session, InterviewTurn answered,
                                                Map<String, Object> data) {
        return applyAiDecision(session, answered, data, "answer");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applyAiDecision(InterviewSession session, InterviewTurn answered,
                                                Map<String, Object> data, String scene) {
        // 0. AI 用量（R-17）：作答/插话/重试评测共用此入口，计数落 ai_usage 并累加 ai_meta
        aiUsageService.record(session, scene, metaOf(data));
        // 1. 保存本轮评测结果（Evaluator 输出）
        Map<String, Object> evaluation = (Map<String, Object>) data.get("evaluation");
        if (evaluation != null) {
            Evaluation eval = new Evaluation();
            eval.setSessionId(session.getId());
            eval.setTurnId(answered.getId());
            eval.setScoreAccuracy(toDecimal(evaluation.get("accuracy")));
            eval.setScoreCompleteness(toDecimal(evaluation.get("completeness")));
            eval.setScoreDepth(toDecimal(evaluation.get("depth")));
            eval.setScoreLogic(toDecimal(evaluation.get("logic")));
            eval.setScoreExpression(toDecimal(evaluation.get("expression")));
            eval.setScoreTradeoff(toDecimal(evaluation.get("tradeoff")));
            eval.setTotalScore(toDecimal(evaluation.get("total")));
            eval.setBasis(evaluation.getOrDefault("basis", List.of()));
            eval.setIssues(evaluation.getOrDefault("issues", List.of()));
            eval.setEvidence(evaluation.getOrDefault("evidence", List.of()));
            eval.setChecklist(evaluation.getOrDefault("checklist", List.of()));
            eval.setMissedKnowledgePoints(evaluation.getOrDefault("missed_knowledge_points", List.of()));
            eval.setSuspectedRecital(Boolean.TRUE.equals(evaluation.get("suspected_recital")));
            eval.setShouldFollowUp((Boolean) evaluation.getOrDefault("should_follow_up", false));
            eval.setFollowUpDirection((String) evaluation.get("follow_up_direction"));
            eval.setSkillTags(evaluation.getOrDefault("skill_tags", List.of()));
            eval.setDecisionReason((String) data.get("decision_reason"));
            // 幂等：同一轮重试/并发重发时不再插入重复评测
            long evaluated = evaluationMapper.selectCount(new LambdaQueryWrapper<Evaluation>()
                    .eq(Evaluation::getTurnId, answered.getId()));
            if (evaluated == 0) {
                evaluationMapper.insert(eval);
            }

            answered.setSkillTags(evaluation.getOrDefault("skill_tags", List.of()));
            turnMapper.updateById(answered);
        }

        // 1.5 面试官现场回应（REACT 节点输出）落到已回答轮次
        Object reaction = data.get("interviewer_reaction");
        if (reaction != null && !String.valueOf(reaction).isBlank()) {
            answered.setInterviewerReaction(String.valueOf(reaction));
            turnMapper.updateById(answered);
        }

        // 1.8 插话（元请求）：本轮不产生评测记录，标记 INTERJECTION 供回放与统计区分
        Map<String, Object> interjection = (Map<String, Object>) data.get("interjection");
        if (interjection != null) {
            Object intent = interjection.get("intent");
            answered.setAnswerType("INTERJECTION");
            answered.setInterjectionIntent(intent == null ? null : String.valueOf(intent));
            turnMapper.updateById(answered);
            log.info("session={} turn={} 插话意图: {}", session.getId(), answered.getTurnNo(), intent);
        }

        String action = (String) data.get("action");

        // 2. 面试结束：生成复盘报告
        if ("FINAL_REPORT".equals(action)) {
            persistReport(session, (Map<String, Object>) data.get("report"));
            Map<String, Object> result = new HashMap<>(data);
            result.put("session_status", "COMPLETED");
            result.put("turn_id", answered.getId());
            return result;
        }

        // 2.5 并发护栏：评测期间用户已点「结束」，会话已 COMPLETED，只回写本轮评测，不再推进轮次
        InterviewSession fresh = sessionMapper.selectById(session.getId());
        if (fresh == null || !"RUNNING".equals(fresh.getStatus())) {
            Map<String, Object> result = new HashMap<>(data);
            result.put("session_status", fresh == null ? "UNKNOWN" : fresh.getStatus());
            result.put("turn_id", answered.getId());
            return result;
        }

        // 3. 继续提问（NEXT_QUESTION / FOLLOW_UP）；有界重规划发生时回写新计划（真源仍在Java）
        Object newPlan = data.get("plan");
        if (newPlan != null) {
            session.setPlan(newPlan);
            log.info("session={} 采纳 Director 重规划", session.getId());
        }
        session.setCurrentTopic((String) data.get("topic"));
        session.setCurrentDifficulty((Integer) data.getOrDefault("difficulty", 2));
        int nextTurnNo = answered.getTurnNo() + 1;
        session.setTurnCount(nextTurnNo);
        sessionMapper.updateById(session);
        saveTurnQuestion(session.getId(), nextTurnNo, data, session.getPersona());
        cacheSession(session.getId());

        Map<String, Object> result = new HashMap<>(data);
        result.put("session_status", "RUNNING");
        // 本轮回应人设 = 刚回答轮次发言人；下一题人设 = 新轮次发言人（前端展示与落库保持一致）
        result.put("reaction_speaker", speakerPersona(session.getPersona(), answered.getTurnNo()));
        result.put("speaker_persona", speakerPersona(session.getPersona(), nextTurnNo));
        // 已回答轮次 id（R-09）：前端据此定位「加入错题本」目标
        result.put("turn_id", answered.getId());
        return result;
    }

    @SuppressWarnings("unchecked")
    private void persistReport(InterviewSession session, Map<String, Object> report) {
        if (report == null) {
            report = Map.of();
        }
        session.setReport(report);
        session.setStatus("COMPLETED");
        session.setFinishedAt(OffsetDateTime.now());
        session.setReportStatus("READY");
        session.setReportError(null);
        Object overall = report.get("overall_score");
        if (overall != null) {
            session.setOverallScore(toDecimal(overall));
        }
        sessionMapper.updateById(session);

        // 更新候选人长期画像统计（skill_ratings/weak_points 由 AI 层 Coach 工具直接回写）
        // 八股练习不计入正式面试场次/均分，避免污染画像
        if (session.getCandidateId() != null && !isGauntlet(session)) {
            CandidateProfile profile = profileMapper.selectById(session.getCandidateId());
            if (profile != null) {
                int total = profile.getTotalInterviews() == null ? 0 : profile.getTotalInterviews();
                BigDecimal avg = profile.getAvgScore() == null ? BigDecimal.ZERO : profile.getAvgScore();
                BigDecimal newAvg = session.getOverallScore() == null ? avg : avg.multiply(BigDecimal.valueOf(total))
                        .add(session.getOverallScore())
                        .divide(BigDecimal.valueOf(total + 1L), 2, RoundingMode.HALF_UP);
                profile.setTotalInterviews(total + 1);
                profile.setAvgScore(newAvg);
                profileMapper.updateById(profile);
            }
        }
        cacheSession(session.getId());
    }

    /** 八股会话标记：plan.mode = GAUNTLET（无独立列，避免数据库迁移）。 */
    private static boolean isGauntlet(InterviewSession session) {
        return session.getPlan() instanceof Map<?, ?> plan && "GAUNTLET".equals(plan.get("mode"));
    }

    private void saveTurnQuestion(Long sessionId, int turnNo, Map<String, Object> data, String personaCsv) {
        InterviewTurn turn = new InterviewTurn();
        turn.setSessionId(sessionId);
        turn.setTurnNo(turnNo);
        turn.setSpeakerPersona(speakerPersona(personaCsv, turnNo));
        turn.setTopic((String) data.get("topic"));
        turn.setDifficulty((Integer) data.getOrDefault("difficulty", 2));
        turn.setIsFollowUp(Boolean.TRUE.equals(data.get("is_follow_up")));
        turn.setQuestion((String) data.get("question"));
        turn.setQuestionSource((String) data.getOrDefault("source", "generated"));
        turn.setQuestionType((String) data.get("question_type"));
        turn.setReferencePoints(data.getOrDefault("reference_points", List.of()));
        turn.setHintCount(0);
        if ("code".equals(data.get("question_type"))) {
            Map<String, Object> coding = new HashMap<>();
            coding.put("starter_code", data.getOrDefault("starter_code", ""));
            // R-13：三语言初始模板，前端切语言时直接覆盖编辑器
            coding.put("starter_codes", data.getOrDefault("starter_codes", Map.of()));
            coding.put("language", data.getOrDefault("language", "java"));
            coding.put("test_cases", data.getOrDefault("test_cases", List.of()));
            turn.setCoding(coding);
        }
        turnMapper.insert(turn);
    }

    /** 构建传递给 AI 层的会话上下文（Java 是数据真源，AI 层无状态重建状态机） */
    private Map<String, Object> buildContext(InterviewSession session, InterviewTurn current) {
        List<InterviewTurn> turns = listTurns(session.getId());
        List<Evaluation> evals = listEvaluations(session.getId());
        Map<Long, Evaluation> evalByTurn = new HashMap<>();
        evals.forEach(e -> evalByTurn.put(e.getTurnId(), e));

        List<Map<String, Object>> history = new ArrayList<>();
        List<InterviewTurn> answered = new ArrayList<>();
        for (InterviewTurn t : turns) {
            if (t.getAnswer() != null) {
                answered.add(t);
            }
        }
        // 末 N 轮保留全文；更早轮次只留可复现摘要（director 的轮转/难度只依赖 topic 与分数）
        int firstFull = Math.max(0, answered.size() - Math.max(1, fullHistoryTurns));
        for (int i = 0; i < answered.size(); i++) {
            InterviewTurn t = answered.get(i);
            boolean full = i >= firstFull;
            Evaluation e = evalByTurn.get(t.getId());
            Map<String, Object> item = new HashMap<>();
            item.put("turn_no", t.getTurnNo());
            item.put("topic", t.getTopic());
            item.put("difficulty", t.getDifficulty());
            // question 截断保留（interviewer 依此避重），answer 仅近 N 轮携带
            item.put("question", full ? t.getQuestion() : truncate(t.getQuestion(), 120));
            item.put("is_follow_up", t.getIsFollowUp());
            if (full) {
                item.put("answer", truncate(t.getAnswer(), 2000));
            } else if ("INTERJECTION".equals(t.getAnswerType())) {
                // 插话原文很短且复盘报告会引用，压缩轮次也保留
                item.put("answer", truncate(t.getAnswer(), 500));
            } else {
                item.put("compressed", true);
            }
            if ("INTERJECTION".equals(t.getAnswerType())) {
                item.put("interjection", true);
                item.put("interjection_intent", t.getInterjectionIntent());
            }
            item.put("speaker", t.getSpeakerPersona() != null
                    ? t.getSpeakerPersona() : speakerPersona(session.getPersona(), t.getTurnNo()));
            if (e != null) {
                item.put("total_score", e.getTotalScore());
                item.put("issues", e.getIssues());
                item.put("skill_tags", e.getSkillTags());
            }
            history.add(item);
        }

        Map<String, Object> cur = new HashMap<>();
        if (current != null) {
            cur.put("turn_no", current.getTurnNo());
            cur.put("topic", current.getTopic());
            cur.put("difficulty", current.getDifficulty());
            cur.put("question", current.getQuestion());
            cur.put("is_follow_up", current.getIsFollowUp());
            cur.put("reference_points", current.getReferencePoints() == null
                    ? List.of() : current.getReferencePoints());
            cur.put("hint_count", current.getHintCount() == null ? 0 : current.getHintCount());
            cur.put("question_type", current.getQuestionType());
            cur.put("source", current.getQuestionSource());
            cur.put("speaker", current.getSpeakerPersona() != null
                    ? current.getSpeakerPersona() : speakerPersona(session.getPersona(), current.getTurnNo()));
            cur.put("coding", current.getCoding() == null ? Map.of() : current.getCoding());
        }

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("plan", session.getPlan());
        ctx.put("current", cur);
        ctx.put("history", history);
        ctx.put("persona", session.getPersona() == null ? "standard" : session.getPersona());
        // 训练计划可执行（R-10）：传标签清单，AI 只能按 id 引用，前端据此跳转八股
        ctx.put("tag_catalog", GauntletTagCatalog.flatten());
        return ctx;
    }

    private InterviewSession requireRunning(Long sessionId) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("面试会话不存在: " + sessionId);
        }
        Ownership.check(session.getUserId(), "面试会话");
        if (!"RUNNING".equals(session.getStatus())) {
            throw new IllegalStateException("面试会话状态不可用: " + session.getStatus());
        }
        return session;
    }

    private InterviewTurn requireOpenTurn(Long sessionId) {
        InterviewTurn turn = requireOpenTurnOrNull(sessionId);
        if (turn == null) {
            throw new IllegalStateException("当前没有待回答的问题，会话数据异常");
        }
        return turn;
    }

    private InterviewTurn requireOpenTurnOrNull(Long sessionId) {
        return turnMapper.selectOne(new LambdaQueryWrapper<InterviewTurn>()
                .eq(InterviewTurn::getSessionId, sessionId)
                .isNull(InterviewTurn::getAnswer)
                .orderByDesc(InterviewTurn::getTurnNo)
                .last("limit 1"));
    }

    private InterviewTurn findTurn(Long sessionId, int turnNo) {
        return turnMapper.selectOne(new LambdaQueryWrapper<InterviewTurn>()
                .eq(InterviewTurn::getSessionId, sessionId)
                .eq(InterviewTurn::getTurnNo, turnNo)
                .last("limit 1"));
    }

    private Evaluation findEvaluation(Long turnId) {
        return evaluationMapper.selectOne(new LambdaQueryWrapper<Evaluation>()
                .eq(Evaluation::getTurnId, turnId)
                .last("limit 1"));
    }

    private InterviewSession requireOwned(Long id) {
        InterviewSession session = sessionMapper.selectById(id);
        if (session == null) {
            throw new IllegalArgumentException("面试会话不存在: " + id);
        }
        Ownership.check(session.getUserId(), "面试会话");
        return session;
    }

    /** 已作答轮的重新评测：复用同一份 answer 与上下文，避免前端重复提交。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> retryEvaluationInternal(InterviewSession session, InterviewTurn answered) {
        Map<String, Object> body = new HashMap<>();
        body.put("session_id", session.getId());
        body.put("answer", answered.getAnswer());
        body.put("context", buildContext(session, answered));
        Map<String, Object> data = (Map<String, Object>) aiClient.postJson("/ai/interview/answer", body).get("data");
        Map<String, Object> result = applyAiDecision(session, answered, data);
        result.put("retried", true);
        return result;
    }

    /** 会话状态写入 Redis，供跨服务读取（设计文档 §2.3） */
    private void cacheSession(Long sessionId) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        try {
            redis.opsForValue().set(SESSION_CACHE_PREFIX + sessionId,
                    objectMapper.writeValueAsString(session),
                    Duration.ofHours(8));
        } catch (JsonProcessingException e) {
            log.warn("会话状态缓存失败: {}", e.getMessage());
        }
    }

    /** 组装 session.persona：personas 非空存 CSV（去空去重、保留顺序），否则回落单值 persona */
    private static String resolvePersonaCsv(String persona, List<String> personas) {
        if (personas != null && !personas.isEmpty()) {
            List<String> cleaned = new ArrayList<>();
            for (String p : personas) {
                if (p == null) {
                    continue;
                }
                String v = p.trim().toLowerCase();
                if (!v.isEmpty() && !cleaned.contains(v)) {
                    cleaned.add(v);
                }
            }
            if (!cleaned.isEmpty()) {
                return String.join(",", cleaned);
            }
        }
        return persona == null || persona.isBlank() ? "standard" : persona.trim().toLowerCase();
    }

    /** Panel 轮转发言人（与 Python templates.select_persona 同一公式：personas[turnNo % size]） */
    private static String speakerPersona(String personaCsv, int turnNo) {
        List<String> personas = new ArrayList<>();
        if (personaCsv != null) {
            for (String p : personaCsv.split(",")) {
                String v = p.trim().toLowerCase();
                if (!v.isEmpty()) {
                    personas.add(v);
                }
            }
        }
        if (personas.isEmpty()) {
            return "standard";
        }
        if (personas.size() == 1) {
            return personas.get(0);
        }
        return personas.get(Math.floorMod(turnNo, personas.size()));
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal b) {
            return b;
        }
        return new BigDecimal(String.valueOf(value));
    }
}
