package com.interview.agent.controller;

import com.interview.agent.dto.ApiResult;
import com.interview.agent.dto.Dtos;
import com.interview.agent.entity.JobDescription;
import com.interview.agent.service.JobDescriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "岗位JD管理")
@RestController
@RequestMapping("/api/job-descriptions")
@RequiredArgsConstructor
public class JobDescriptionController {

    private final JobDescriptionService jdService;

    @Operation(summary = "上传JD文档并解析岗位能力模型")
    @PostMapping
    public ApiResult<JobDescription> upload(@RequestParam("file") MultipartFile file) {
        return ApiResult.ok(jdService.upload(file));
    }

    @Operation(summary = "直接粘贴JD文本解析")
    @PostMapping("/text")
    public ApiResult<JobDescription> createFromText(@RequestBody @jakarta.validation.Valid Dtos.JdTextRequest req) {
        return ApiResult.ok(jdService.create(req.rawText(), "粘贴文本", null));
    }

    @GetMapping("/{id}")
    public ApiResult<JobDescription> get(@PathVariable Long id) {
        return ApiResult.ok(jdService.get(id));
    }

    @Operation(summary = "重新解析JD（R-05）：用已存原文重跑 LLM，回写 parsed/派生字段")
    @PostMapping("/{id}/reparse")
    public ApiResult<JobDescription> reparse(@PathVariable Long id) {
        return ApiResult.ok(jdService.reparse(id));
    }

    @Operation(summary = "修正JD（R-05）：title/company/parsed 白名单字段")
    @PutMapping("/{id}")
    public ApiResult<JobDescription> update(@PathVariable Long id,
                                            @RequestBody @jakarta.validation.Valid Dtos.JdUpdateRequest req) {
        return ApiResult.ok(jdService.update(id, req.title(), req.company(), req.parsed()));
    }

    @GetMapping
    public ApiResult<List<JobDescription>> list() {
        return ApiResult.ok(jdService.list());
    }

    @Operation(summary = "删除JD（历史会话解绑 + 删磁盘文件）")
    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        jdService.delete(id);
        return ApiResult.ok(null);
    }
}
