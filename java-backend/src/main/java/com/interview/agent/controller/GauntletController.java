package com.interview.agent.controller;

import com.interview.agent.client.AiServiceClient;
import com.interview.agent.dto.ApiResult;
import com.interview.agent.dto.Dtos;
import com.interview.agent.service.GauntletTagCatalog;
import com.interview.agent.service.IdempotencyService;
import com.interview.agent.service.InterviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Tag(name = "八股拷打")
@RestController
@RequestMapping("/api/gauntlet")
@RequiredArgsConstructor
public class GauntletController {

    /** Skills 清单缓存时长（R-11）：避免每次拉取标签树都穿透到 AI 层 */
    private static final long SKILL_TTL_MS = 60_000L;

    private final InterviewService interviewService;
    private final IdempotencyService idempotency;
    private final AiServiceClient aiClient;

    /** Skills 目录名缓存，与 GauntletTagCatalog 的 skill 字段比对判断是否「通用」 */
    private volatile Set<String> cachedSkills = Set.of();
    private volatile long cachedAt = 0L;

    @Operation(summary = "预置八股标签树（二级标签 -> 主题/Skill 映射，含 hasSkill 覆盖标记）")
    @GetMapping("/tags")
    public ApiResult<Object> tags() {
        Set<String> skills = skills();
        List<Map<String, Object>> groups = new ArrayList<>();
        for (GauntletTagCatalog.Group group : GauntletTagCatalog.GROUPS) {
            List<Map<String, Object>> children = new ArrayList<>();
            for (GauntletTagCatalog.Tag tag : group.children()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", tag.id());
                item.put("label", tag.label());
                item.put("topic", tag.topic());
                item.put("skill", tag.skill());
                item.put("hasSkill", skills.contains(tag.skill()));
                children.add(item);
            }
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("id", group.id());
            g.put("label", group.label());
            g.put("children", children);
            groups.add(g);
        }
        return ApiResult.ok(groups);
    }

    /** AI 层 Skills 清单（60s 缓存）；AI 层不可用时降级为空集，不阻塞标签树展示 */
    @SuppressWarnings("unchecked")
    private Set<String> skills() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < SKILL_TTL_MS) {
            return cachedSkills;
        }
        Set<String> result = new LinkedHashSet<>();
        try {
            Object data = aiClient.get("/ai/skills").get("data");
            if (data instanceof List<?> list) {
                for (Object o : list) {
                    if (o != null) {
                        result.add(String.valueOf(o));
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("拉取 AI 层 Skills 失败，标签覆盖标记降级为空: {}", e.getMessage());
        }
        cachedSkills = result;
        cachedAt = now;
        return result;
    }

    @Operation(summary = "开始八股拷打：按标签构造计划，跳过简历/JD 缺口分析并出首题")
    @PostMapping("/start")
    public ApiResult<Map<String, Object>> start(@RequestBody @Valid Dtos.StartGauntletRequest req,
                                                @RequestHeader(value = "X-Request-Id", required = false)
                                                String requestId) {
        if (!idempotency.claim("start-gauntlet", requestId, Duration.ofSeconds(120))) {
            throw new IllegalStateException("重复提交，请稍候");
        }
        return ApiResult.ok(interviewService.startGauntlet(
                req.tagIds(), req.difficulty(), req.persona(), req.personas(), req.studyMode()));
    }
}
