package com.interview.agent.controller;

import com.interview.agent.auth.UserContext;
import com.interview.agent.dto.ApiResult;
import com.interview.agent.dto.Dtos;
import com.interview.agent.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "用户鉴权")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "注册（自动创建候选人画像），成功即返回访问令牌 + refresh token")
    @PostMapping("/register")
    public ApiResult<Map<String, Object>> register(@RequestBody @Valid Dtos.RegisterRequest req) {
        return ApiResult.ok(authService.register(req.username(), req.password(), req.nickname()));
    }

    @Operation(summary = "登录：返回访问令牌（30分钟）+ refresh token（7天）与默认画像 id")
    @PostMapping("/login")
    public ApiResult<Map<String, Object>> login(@RequestBody @Valid Dtos.LoginRequest req,
                                                HttpServletRequest request) {
        return ApiResult.ok(authService.login(req.username(), req.password(), clientIp(request)));
    }

    @Operation(summary = "续期：用 refresh token 换发新的访问令牌（旧的 refresh token 立即失效）")
    @PostMapping("/refresh")
    public ApiResult<Map<String, Object>> refresh(@RequestBody @Valid Dtos.RefreshRequest req) {
        return ApiResult.ok(authService.refresh(req.refreshToken()));
    }

    @Operation(summary = "登出：吊销 refresh token")
    @PostMapping("/logout")
    public ApiResult<Void> logout(@RequestBody(required = false) Dtos.LogoutRequest req) {
        authService.logout(req == null ? null : req.refreshToken());
        return ApiResult.ok(null);
    }

    @Operation(summary = "当前登录用户信息")
    @GetMapping("/me")
    public ApiResult<Map<String, Object>> me() {
        return ApiResult.ok(authService.me(UserContext.userId()));
    }

    /**
     * 客户端 IP：优先 X-Real-IP（nginx 用 $remote_addr 覆盖写入，客户端无法伪造），
     * 其次 X-Forwarded-For 首段，最后回落到 socket 远端地址。用于登录失败计数作用域。
     */
    private static String clientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
