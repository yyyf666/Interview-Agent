package com.interview.agent.auth;

/**
 * 数据归属校验：owner_id 为 NULL 视为遗留共享数据放行；
 * 非 NULL 时必须与当前登录用户一致。
 */
public final class Ownership {

    private Ownership() {
    }

    public static void check(Long ownerId, String what) {
        Long current = UserContext.userId();
        if (current == null || ownerId == null || ownerId.equals(current)) {
            return;
        }
        throw new IllegalArgumentException("无权访问该" + what);
    }
}
