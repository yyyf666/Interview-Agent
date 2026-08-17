-- =====================================================
-- 迁移 09：用户角色（轻量权限体系）
-- 幂等：可重复执行
-- 角色：USER（默认）/ ADMIN
--   管理类写接口（题库、知识库、AI 配置）要求 ADMIN，由 Java @RequireRole 拦截
-- 存量用户默认 USER；首个注册用户与「存量最早用户」由 Java 侧提升为 ADMIN
-- （见 AuthService.register / AuthBootstrapRunner）
-- =====================================================

ALTER TABLE app_user ADD COLUMN IF NOT EXISTS role VARCHAR(16) NOT NULL DEFAULT 'USER';

-- 仅保留 USER/ADMIN 两值，防止后续误写
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_app_user_role'
    ) THEN
        ALTER TABLE app_user ADD CONSTRAINT ck_app_user_role CHECK (role IN ('USER', 'ADMIN'));
    END IF;
END $$;

COMMENT ON COLUMN app_user.role IS '角色：USER 普通用户 / ADMIN 管理员（管理类写接口要求 ADMIN）';
