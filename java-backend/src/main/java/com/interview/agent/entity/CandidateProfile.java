package com.interview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 候选人画像表：长期技能画像与面试统计（长期记忆载体） */
@Data
@TableName(value = "candidate_profile", autoResultMap = true)
public class CandidateProfile {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 归属用户（NULL=遗留数据，对所有人可见） */
    private Long userId;
    private String nickname;
    private String targetRole;
    private BigDecimal yearsOfExperience;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object skillTags;      // ["java","redis"]

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object skillRatings;   // {"java":{"score":72,"level":"B"}}

    private Integer totalInterviews;
    private BigDecimal avgScore;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object weakPoints;     // 高频失分点

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
