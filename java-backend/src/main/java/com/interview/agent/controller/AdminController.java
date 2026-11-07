package com.interview.agent.controller;

import com.interview.agent.auth.RequireRole;
import com.interview.agent.dto.ApiResult;
import com.interview.agent.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 管理页（R-12 / R-17）：索引健康、Skills 清单与重载、AI 用量，全部要求 ADMIN。 */
@Tag(name = "管理页")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@RequireRole("ADMIN")
public class AdminController {

    private final AdminService adminService;

    @Operation(summary = "索引健康：文档/分块/题目/向量数量、最近索引时间、Embedding 配置齐备度")
    @GetMapping("/index-health")
    public ApiResult<Map<String, Object>> indexHealth() {
        return ApiResult.ok(adminService.indexHealth());
    }

    @Operation(summary = "Skills 清单：名称 / 是否覆盖 / 文件时间")
    @GetMapping("/skills")
    public ApiResult<List<Map<String, Object>>> skills() {
        return ApiResult.ok(adminService.skills());
    }

    @Operation(summary = "重载 Skills（清缓存后重解析 SKILL.md）")
    @PostMapping("/skills/reload")
    public ApiResult<Object> reloadSkills() {
        return ApiResult.ok(adminService.reloadSkills());
    }

    @Operation(summary = "AI 用量：近 N 天按日汇总 + 会话 TOP10 + 全量总计（R-17）")
    @GetMapping("/ai-usage")
    public ApiResult<Map<String, Object>> aiUsage(@RequestParam(defaultValue = "14") int days) {
        return ApiResult.ok(adminService.aiUsage(days));
    }
}
