package com.interview.agent.controller;

import com.interview.agent.dto.ApiResult;
import com.interview.agent.dto.Dtos;
import com.interview.agent.dto.SessionSummary;
import com.interview.agent.entity.Evaluation;
import com.interview.agent.entity.InterviewSession;
import com.interview.agent.entity.InterviewTurn;
import com.interview.agent.service.IdempotencyService;
import com.interview.agent.service.InterviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Tag(name = "面试会话")
@RestController
@RequestMapping("/api/interviews")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewService interviewService;
    private final IdempotencyService idempotency;

    @Operation(summary = "开始面试：缺口分析 -> 生成计划 -> 首题（persona 单人 / personas 多面试官 Panel 轮转）")
    @PostMapping
    public ApiResult<Map<String, Object>> start(@RequestBody @Valid Dtos.StartInterviewRequest req,
                                                @RequestHeader(value = "X-Request-Id", required = false)
                                                String requestId) {
        if (!idempotency.claim("start-interview", requestId, Duration.ofSeconds(120))) {
            throw new IllegalStateException("重复提交，请稍候");
        }
        return ApiResult.ok(interviewService.start(
                req.candidateId(), req.resumeId(), req.jdId(), req.mode(),
                req.persona(), req.personas(), req.gap(), req.options()));
    }

    @Operation(summary = "开前缺口分析（R-03）：简历 × JD 匹配度/优势/薄弱/重点考察，Redis 缓存 24h")
    @PostMapping("/gap-analysis")
    public ApiResult<Map<String, Object>> gapAnalysis(@RequestBody Dtos.GapAnalysisRequest req) {
        if (req.resumeId() == null || req.jdId() == null) {
            throw new IllegalArgumentException("resumeId 与 jdId 不能为空");
        }
        return ApiResult.ok(interviewService.gapAnalysis(
                req.candidateId(), req.resumeId(), req.jdId()));
    }

    @Operation(summary = "提交回答（文字或代码+运行结果）：评测 -> Director决策 -> 面试官回应 -> 下一题/报告")
    @PostMapping("/{id}/answers")
    public ApiResult<Map<String, Object>> answer(@PathVariable Long id,
                                                 @RequestBody @Valid Dtos.AnswerRequest req) {
        return ApiResult.ok(interviewService.submitAnswer(
                id, req.answer(), "TEXT", req.execution(), req.turnNo()));
    }

    @Operation(summary = "流式提交回答（R-16）：节点级 SSE，stage* -> done；不支持时前端回退 /answers")
    @PostMapping(value = "/{id}/answers/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter answerStream(@PathVariable Long id, @RequestBody @Valid Dtos.AnswerRequest req) {
        SseEmitter emitter = new SseEmitter(300_000L);
        interviewService.submitAnswerStream(
                id, req.answer(), "TEXT", req.execution(), req.turnNo(), emitter);
        return emitter;
    }

    @Operation(summary = "八股主动跳过/换题（R-11）：不产生评测，标记轮次后出新题，消耗 1 题预算")
    @PostMapping("/{id}/interject")
    public ApiResult<Map<String, Object>> interject(@PathVariable Long id,
                                                    @RequestBody @Valid Dtos.InterjectRequest req) {
        return ApiResult.ok(interviewService.interject(id, req.intent(), req.questionSpec()));
    }

    @Operation(summary = "分级提示：1=方向 2=关键点 3=揭晓思路（计入评测降档）")
    @PostMapping("/{id}/hint")
    public ApiResult<Map<String, Object>> hint(@PathVariable Long id,
                                               @RequestBody Dtos.HintRequest req) {
        return ApiResult.ok(interviewService.requestHint(id, req.level() == null ? 1 : req.level()));
    }

    @Operation(summary = "提交语音回答（上传音频，AI层ASR转写后走相同链路）")
    @PostMapping(value = "/{id}/audio-answers", consumes = "multipart/form-data")
    public ApiResult<Map<String, Object>> audioAnswer(@PathVariable Long id,
                                                      @RequestParam("file") MultipartFile file) {
        return ApiResult.ok(interviewService.submitAudioAnswer(id, file));
    }

    @Operation(summary = "提前结束面试并生成复盘报告")
    @PostMapping("/{id}/finish")
    public ApiResult<Map<String, Object>> finish(@PathVariable Long id) {
        return ApiResult.ok(interviewService.finish(id));
    }

    @Operation(summary = "报告状态轮询（R-16）：NONE/RUNNING/READY/FAILED")
    @GetMapping("/{id}/report-status")
    public ApiResult<Map<String, Object>> reportStatus(@PathVariable Long id) {
        return ApiResult.ok(interviewService.reportStatus(id));
    }

    @GetMapping("/{id}")
    public ApiResult<InterviewSession> get(@PathVariable Long id) {
        return ApiResult.ok(interviewService.get(id));
    }

    @Operation(summary = "会话状态快照（R-01）：session/turns/evaluations/开放轮次，用于断线同步")
    @GetMapping("/{id}/state")
    public ApiResult<Map<String, Object>> state(@PathVariable Long id) {
        return ApiResult.ok(interviewService.state(id));
    }

    @Operation(summary = "重试评测（R-01）：已评测/已推进时幂等返回状态，不重复调用 LLM")
    @PostMapping("/{id}/turns/{turnNo}/retry-evaluation")
    public ApiResult<Map<String, Object>> retryEvaluation(@PathVariable Long id,
                                                          @PathVariable int turnNo) {
        return ApiResult.ok(interviewService.retryEvaluation(id, turnNo));
    }

    @Operation(summary = "重命名会话（R-06）")
    @PatchMapping("/{id}")
    public ApiResult<InterviewSession> rename(@PathVariable Long id,
                                              @RequestBody @Valid Dtos.RenameSessionRequest req) {
        return ApiResult.ok(interviewService.rename(id, req.title()));
    }

    @Operation(summary = "删除会话（R-06）：硬删除轮次与评测，同时清理缓存")
    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        interviewService.delete(id);
        return ApiResult.ok(null);
    }

    @GetMapping("/{id}/turns")
    public ApiResult<List<InterviewTurn>> turns(@PathVariable Long id) {
        return ApiResult.ok(interviewService.listTurns(id));
    }

    @GetMapping("/{id}/evaluations")
    public ApiResult<List<Evaluation>> evaluations(@PathVariable Long id) {
        return ApiResult.ok(interviewService.listEvaluations(id));
    }

    @Operation(summary = "与上一场对比（R-14）：同候选人+同JD的更早一场已完成会话，含六维均值")
    @GetMapping("/{id}/compare")
    public ApiResult<Map<String, Object>> compare(@PathVariable Long id) {
        return ApiResult.ok(interviewService.compare(id));
    }

    @GetMapping
    public ApiResult<List<InterviewSession>> list(@RequestParam(required = false) Long candidateId) {
        return ApiResult.ok(interviewService.listSessions(candidateId));
    }

    @Operation(summary = "历史会话分页：服务端过滤（scope/keyword/status） + 摘要投影（不含 plan/report）")
    @GetMapping("/page")
    public ApiResult<SessionSummary.Page> page(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(required = false) Long candidateId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return ApiResult.ok(interviewService.pageSessions(pageNo, pageSize, scope, candidateId, keyword, status));
    }
}
