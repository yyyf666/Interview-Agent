package com.interview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/** 错题本条目：一轮问答 + 参考要点/遗漏点/问题，供复习模式使用（R-09） */
@Data
@TableName(value = "review_item", autoResultMap = true)
public class ReviewItem {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long candidateId;
    private Long sessionId;
    private Long turnId;

    private String question;
    private String answer;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object referencePoints;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object missedPoints;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object issues;

    /** 标签（技能/知识点），筛选用 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object tags;

    /** new / learning / mastered */
    private String mastered;
    private String note;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
