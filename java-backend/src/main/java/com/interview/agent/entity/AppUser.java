package com.interview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 系统用户表：登录主体（06-migration-auth 建表，09-migration-role 加角色） */
@Data
@TableName("app_user")
public class AppUser {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    /** BCrypt 哈希，绝不存明文 */
    private String passwordHash;
    private String nickname;
    /** 角色：USER / ADMIN（默认 USER；题库、知识库、AI 配置写接口要求 ADMIN） */
    private String role;
    private OffsetDateTime createdAt;
}
