package com.interview.agent.controller;

import com.interview.agent.auth.RequireRole;
import com.interview.agent.client.AiServiceClient;
import com.interview.agent.dto.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AI 配置转发：配置的存储与热生效逻辑在 Python AI 层（消费方），
 * Java 仅做鉴权入口 + 透传。key 字段由 AI 层出参掩码。
 *
 * 全局单例配置（所有用户共享模型/密钥），故读与写均要求 ADMIN。
 */
@Tag(name = "AI配置")
@RestController
@RequestMapping("/api/ai-config")
@RequiredArgsConstructor
@RequireRole("ADMIN")
public class AiConfigController {

    private final AiServiceClient aiClient;

    @Operation(summary = "查看当前生效配置（secret 字段掩码）")
    @GetMapping
    public ApiResult<Object> get() {
        return ApiResult.ok(aiClient.get("/ai/config").get("data"));
    }

    @Operation(summary = "保存配置并即时热生效（掩码占位字段视为未修改）")
    @PutMapping
    public ApiResult<Object> save(@RequestBody Map<String, Object> body) {
        return ApiResult.ok(aiClient.putJson("/ai/config", body).get("data"));
    }

    @Operation(summary = "用草稿配置测试 LLM 连通性，不落库")
    @PostMapping("/test")
    public ApiResult<Object> test(@RequestBody Map<String, Object> body) {
        return ApiResult.ok(aiClient.postJson("/ai/config/test", body).get("data"));
    }
}
