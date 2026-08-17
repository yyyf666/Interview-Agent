package com.interview.agent.config;

import com.interview.agent.auth.AuthInterceptor;
import com.interview.agent.auth.RoleInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final RoleInterceptor roleInterceptor;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 全局鉴权：仅登录/注册/续期/登出匿名可访；题库数据全局共享但管理接口同样需登录
        // WebSocket 握手走 WsAuthHandshakeInterceptor（query token），此处排除
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login", "/api/auth/register",
                        "/api/auth/refresh", "/api/auth/logout", "/api/ws/**")
                .order(0);
        // 角色校验：依赖 AuthInterceptor 已写入 UserContext，故必须排在其后
        registry.addInterceptor(roleInterceptor)
                .addPathPatterns("/api/**")
                .order(1);
    }
}
