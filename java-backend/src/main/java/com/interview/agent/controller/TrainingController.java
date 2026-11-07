package com.interview.agent.controller;

import com.interview.agent.dto.ApiResult;
import com.interview.agent.dto.Dtos;
import com.interview.agent.entity.TrainingProgress;
import com.interview.agent.service.TrainingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "训练进度")
@RestController
@RequestMapping("/api/training-progress")
@RequiredArgsConstructor
public class TrainingController {

    private final TrainingService trainingService;

    @Operation(summary = "标记训练任务完成（幂等：唯一索引冲突视为已完成）")
    @PostMapping
    public ApiResult<TrainingProgress> markDone(@RequestBody @Valid Dtos.TrainingProgressRequest req) {
        return ApiResult.ok(trainingService.markDone(
                req.sourceSessionId(), req.planDay(), req.taskIndex(), req.tagIds(), req.gauntletSessionId()));
    }

    @Operation(summary = "完成列表与汇总：records/total/completedBySource")
    @GetMapping
    public ApiResult<Map<String, Object>> list(
            @RequestParam(required = false) Long candidateId,
            @RequestParam(required = false) Long sourceSessionId) {
        return ApiResult.ok(trainingService.list(candidateId, sourceSessionId));
    }
}
