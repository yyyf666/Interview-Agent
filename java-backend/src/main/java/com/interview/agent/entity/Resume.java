package com.interview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/** 简历表 */
@Data
@TableName(value = "resume", autoResultMap = true)
public class Resume {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long candidateId;
    private String fileName;
    private String filePath;
    private String rawText;

    /** LLM解析后的结构化技能画像 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object parsed;

    private Integer version;
    private OffsetDateTime createdAt;
}
