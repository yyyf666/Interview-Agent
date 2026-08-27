package com.interview.agent.controller;

import com.interview.agent.auth.RequireRole;
import com.interview.agent.dto.ApiResult;
import com.interview.agent.entity.KnowledgeDoc;
import com.interview.agent.service.KnowledgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Tag(name = "知识库管理")
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    @Operation(summary = "上传知识文档（Markdown/TXT/PDF/Word），触发RAG索引（管理员）")
    @PostMapping
    @RequireRole("ADMIN")
    public ApiResult<KnowledgeDoc> upload(@RequestParam("file") MultipartFile file,
                                          @RequestParam(required = false) String docType,
                                          @RequestParam(required = false) List<String> tags) {
        return ApiResult.ok(knowledgeService.upload(file, docType, tags));
    }

    @Operation(summary = "重新索引（失败补偿/版本更新，管理员）")
    @PostMapping("/{id}/reindex")
    @RequireRole("ADMIN")
    public ApiResult<KnowledgeDoc> reindex(@PathVariable Long id) {
        return ApiResult.ok(knowledgeService.reindex(id));
    }

    @GetMapping
    public ApiResult<List<KnowledgeDoc>> list() {
        return ApiResult.ok(knowledgeService.list());
    }

    @Operation(summary = "知识文档详情：元信息 + 原文预览（R-12）")
    @GetMapping("/{id}")
    public ApiResult<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResult.ok(knowledgeService.detail(id));
    }

    @Operation(summary = "下载知识文档原文（R-12）")
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable Long id) {
        KnowledgeService.FilePayload payload = knowledgeService.file(id);
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(payload.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(payload.bytes());
    }

    @Operation(summary = "RAG 检索预览：召回片段与分数（R-12）")
    @PostMapping("/search")
    public ApiResult<Object> search(@RequestBody Map<String, Object> req) {
        return ApiResult.ok(knowledgeService.search(req));
    }

    @Operation(summary = "删除知识文档（管理员）")
    @DeleteMapping("/{id}")
    @RequireRole("ADMIN")
    public ApiResult<Void> delete(@PathVariable Long id) {
        knowledgeService.delete(id);
        return ApiResult.ok(null);
    }
}
