package com.interview.agent.controller;

import com.interview.agent.dto.ApiResult;
import com.interview.agent.entity.Resume;
import com.interview.agent.service.ResumeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Tag(name = "简历管理")
@RestController
@RequestMapping("/api/resumes")
@RequiredArgsConstructor
public class ResumeController {

    private final ResumeService resumeService;

    @Operation(summary = "上传简历并触发LLM结构化解析")
    @PostMapping
    public ApiResult<Resume> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam(required = false) Long candidateId) {
        return ApiResult.ok(resumeService.upload(file, candidateId));
    }

    @Operation(summary = "简历详情（含解析结果）")
    @GetMapping("/{id}")
    public ApiResult<Resume> get(@PathVariable Long id) {
        return ApiResult.ok(resumeService.get(id));
    }

    @Operation(summary = "重新解析简历（R-05）：用已存原文重跑 LLM，writeProfile=true 时同步画像")
    @PostMapping("/{id}/reparse")
    public ApiResult<Resume> reparse(@PathVariable Long id,
                                     @RequestParam(defaultValue = "false") boolean writeProfile) {
        return ApiResult.ok(resumeService.reparse(id, writeProfile));
    }

    @Operation(summary = "修正简历解析结果（R-05）：白名单字段 + skills 需含 skill/level")
    @PutMapping("/{id}/parsed")
    public ApiResult<Resume> updateParsed(@PathVariable Long id,
                                          @RequestBody Map<String, Object> parsed) {
        return ApiResult.ok(resumeService.updateParsed(id, parsed));
    }

    @GetMapping
    public ApiResult<List<Resume>> list(@RequestParam(required = false) Long candidateId) {
        return ApiResult.ok(resumeService.list(candidateId));
    }

    @Operation(summary = "删除简历（历史会话解绑 + 删磁盘文件）")
    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        resumeService.delete(id);
        return ApiResult.ok(null);
    }
}
