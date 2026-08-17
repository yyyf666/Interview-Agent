package com.interview.agent.auth;

/**
 * 当前请求的登录用户上下文。
 * 由 AuthInterceptor 在 preHandle 写入、afterCompletion 清理（虚拟线程每请求独立，ThreadLocal 安全）。
 */
public final class UserContext {

    /** role：USER / ADMIN，随 JWT claim 下发，改角色后需重新登录生效 */
    public record CurrentUser(Long id, String username, String role) {

        public boolean isAdmin() {
            return "ADMIN".equalsIgnoreCase(role);
        }
    }

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    public static Long userId() {
        CurrentUser u = HOLDER.get();
        return u == null ? null : u.id();
    }

    public static String role() {
        CurrentUser u = HOLDER.get();
        return u == null ? "USER" : u.role();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
