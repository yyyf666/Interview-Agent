package com.interview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 评测表：每轮回答的六维度结构化评分 */
@Data
@TableName(value = "evaluation", autoResultMap = true)
public class Evaluation {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long turnId;

    private BigDecimal scoreAccuracy;     // 技术准确性 30%
    private BigDecimal scoreCompleteness; // 内容完整度 20%
    private BigDecimal scoreDepth;        // 技术深度 20%
    private BigDecimal scoreLogic;        // 逻辑推理 15%
    private BigDecimal scoreExpression;   // 表达结构 10%
    private BigDecimal scoreTradeoff;     // 选型权衡 5%
    private BigDecimal totalScore;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object basis;                 // 得分依据

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object issues;                // 错误/遗漏点

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object evidence;              // 与issues一一对应的候选人原文片段（证据化评测）

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object checklist;             // 参考要点命中清单（清单聚合评测）

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object missedKnowledgePoints; // 遗漏知识点（回放/刷新后仍可展开）

    private Boolean suspectedRecital;     // 疑似背诵参考答案

    private Boolean shouldFollowUp;
    private String followUpDirection;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object skillTags;

    private String decisionReason;        // Director决策依据
    private OffsetDateTime createdAt;
}
