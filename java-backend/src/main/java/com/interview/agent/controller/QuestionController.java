package com.interview.agent.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.interview.agent.auth.RequireRole;
import com.interview.agent.client.AiServiceClient;
import com.interview.agent.dto.ApiResult;
import com.interview.agent.entity.QuestionBank;
import com.interview.agent.service.QuestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "题库管理")
@RestController
@RequestMapping("/api/questions")
@RequiredArgsConstructor
public class QuestionController {

    private final QuestionService questionService;
    private final AiServiceClient aiClient;

    @GetMapping
    public ApiResult<Page<QuestionBank>> page(@RequestParam(defaultValue = "1") int pageNo,
                                              @RequestParam(defaultValue = "20") int pageSize,
                                              @RequestParam(required = false) String skill,
                                              @RequestParam(required = false) Integer difficulty,
                                              @RequestParam(required = false) String qType,
                                              @RequestParam(required = false) String kw) {
        return ApiResult.ok(questionService.page(pageNo, pageSize, skill, difficulty, qType, kw));
    }

    @Operation(summary = "批量导入题目（管理员，按题干去重，R-12）")
    @PostMapping("/import")
    @RequireRole("ADMIN")
    public ApiResult<Map<String, Object>> importQuestions(@RequestBody List<QuestionBank> questions) {
        return ApiResult.ok(questionService.importAll(questions));
    }

    @Operation(summary = "新增题目（管理员）")
    @PostMapping
    @RequireRole("ADMIN")
    public ApiResult<QuestionBank> create(@RequestBody QuestionBank question) {
        return ApiResult.ok(questionService.create(question));
    }

    @Operation(summary = "修改题目（管理员）")
    @PutMapping("/{id}")
    @RequireRole("ADMIN")
    public ApiResult<QuestionBank> update(@PathVariable Long id, @RequestBody QuestionBank question) {
        question.setId(id);
        return ApiResult.ok(questionService.update(question));
    }

    @Operation(summary = "删除题目（管理员）")
    @DeleteMapping("/{id}")
    @RequireRole("ADMIN")
    public ApiResult<Void> delete(@PathVariable Long id) {
        questionService.delete(id);
        return ApiResult.ok(null);
    }

    @Operation(summary = "全量重建题库向量索引（管理员）")
    @PostMapping("/reindex")
    @RequireRole("ADMIN")
    public ApiResult<Map<String, Object>> reindex() {
        return ApiResult.ok(questionService.reindexAll());
    }

    @Operation(summary = "语义检索题库（代理AI层向量检索）")
    @PostMapping("/search")
    @SuppressWarnings("unchecked")
    public ApiResult<Object> search(@RequestBody Map<String, Object> req) {
        Map<String, Object> body = new HashMap<>(req);
        return ApiResult.ok(aiClient.postJson("/ai/questions/search", body).get("data"));
    }
}
