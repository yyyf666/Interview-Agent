package com.interview.agent.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法/类级角色校验：由 RoleInterceptor 读取。
 * 轻量实现（不引入完整 Spring Security）：角色来自 JWT claim，命中其一即放行。
 *
 * <pre>
 * &#64;RequireRole("ADMIN")
 * &#64;PostMapping
 * public ApiResult<...> create(...) { ... }
 * </pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /** 允许访问的角色，默认仅管理员 */
    String[] value() default {"ADMIN"};
}
