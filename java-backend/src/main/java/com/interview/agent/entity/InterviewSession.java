package com.interview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 面试会话表 */
@Data
@TableName(value = "interview_session", autoResultMap = true)
public class InterviewSession {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 归属用户 */
    private Long userId;
    private Long candidateId;
    private Long resumeId;
    private Long jdId;
    private String title;
    /** INIT / RUNNING / COMPLETED / ABORTED */
    private String status;
    /** TEXT / AUDIO */
    private String mode;
    /** 面试官人格；Panel多人时为逗号分隔CSV（如 pressure,standard） */
    private String persona;

    /** 面试计划：主题顺序/题量/难度权重 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object plan;

    private String currentTopic;
    private Integer currentDifficulty;
    private Integer turnCount;
    private BigDecimal overallScore;

    /** Coach生成的复盘报告 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object report;

    /** 报告状态机：NONE / RUNNING / READY / FAILED（R-16 异步报告） */
    private String reportStatus;
    private String reportError;

    /** 会话级 AI 用量累计：{llm_calls, elapsed_ms, model}（R-17） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object aiMeta;

    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
