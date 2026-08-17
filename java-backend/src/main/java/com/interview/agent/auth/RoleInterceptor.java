package com.interview.agent.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.dto.ApiResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

/**
 * 角色校验拦截器：注册在 AuthInterceptor 之后（依赖其已写入 UserContext）。
 * 仅对标注 {@link RequireRole} 的方法/类生效，未标注的接口行为不变。
 * 无权限返回 HTTP 403 + errorCode=FORBIDDEN，前端可据此隐藏/提示。
 */
@Component
@RequiredArgsConstructor
public class RoleInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || !(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequireRole required = method.getMethodAnnotation(RequireRole.class);
        if (required == null) {
            required = method.getBeanType().getAnnotation(RequireRole.class);
        }
        if (required == null) {
            return true;
        }
        UserContext.CurrentUser user = UserContext.get();
        if (user == null) {
            write(response, HttpServletResponse.SC_UNAUTHORIZED,
                    ApiResult.error(401, "UNAUTHORIZED", "未登录或登录已过期"));
            return false;
        }
        boolean allowed = Arrays.stream(required.value())
                .anyMatch(role -> role.equalsIgnoreCase(user.role()));
        if (!allowed) {
            write(response, HttpServletResponse.SC_FORBIDDEN,
                    ApiResult.error(403, "FORBIDDEN", "无权限执行该操作（需要 " + String.join("/", required.value()) + "）"));
            return false;
        }
        return true;
    }

    private void write(HttpServletResponse response, int status, Object body) throws Exception {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
