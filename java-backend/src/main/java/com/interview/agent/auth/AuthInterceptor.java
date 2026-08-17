package com.interview.agent.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.dto.ApiResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Bearer JWT 鉴权拦截器：拦截 /api/**（/api/auth/login|register 白名单在 WebConfig 排除）。
 * 浏览器 WebSocket 无法设 header，握手走 query 参数 token（由 WsAuthHandshakeInterceptor 校验）。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : null;
        UserContext.CurrentUser user = token == null ? null : jwtService.verify(token);
        if (user == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    objectMapper.writeValueAsString(ApiResult.error(401, "未登录或登录已过期")));
            return false;
        }
        UserContext.set(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }
}
