package com.interview.agent.controller;

import com.interview.agent.dto.ApiResult;
import com.interview.agent.dto.Dtos;
import com.interview.agent.entity.ReviewItem;
import com.interview.agent.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "错题本")
@RestController
@RequestMapping("/api/review-items")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @Operation(summary = "收藏一轮问答到错题本（幂等：同一轮重复收藏返回既有记录）")
    @PostMapping
    public ApiResult<ReviewItem> add(@RequestBody @Valid Dtos.ReviewCreateRequest req) {
        return ApiResult.ok(reviewService.add(
                req.sessionId(), req.turnId(), req.tags(), req.note(), req.candidateId()));
    }

    @Operation(summary = "错题列表：按掌握状态/标签筛选，返回 records/total/tags")
    @GetMapping
    public ApiResult<Map<String, Object>> page(
            @RequestParam(required = false) String mastered,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResult.ok(reviewService.page(mastered, tag, pageNo, pageSize));
    }

    @Operation(summary = "更新复习状态（new/learning/mastered）与备注")
    @PatchMapping("/{id}")
    public ApiResult<ReviewItem> update(@PathVariable Long id,
                                        @RequestBody Dtos.ReviewUpdateRequest req) {
        return ApiResult.ok(reviewService.update(id, req.mastered(), req.note()));
    }

    @Operation(summary = "删除错题记录")
    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        reviewService.delete(id);
        return ApiResult.ok(null);
    }
}
