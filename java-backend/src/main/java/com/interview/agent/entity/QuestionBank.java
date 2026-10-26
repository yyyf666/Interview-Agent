package com.interview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/** 题库表 */
@Data
@TableName(value = "question_bank", autoResultMap = true)
public class QuestionBank {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String content;
    private String skillName;
    private Integer difficulty;   // 1-5
    private String qType;         // concept / principle / scenario / project / behavior

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object tags;

    private String referenceAnswer;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object scoringPoints;

    private String source;
    private String status;
    private Integer version;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
