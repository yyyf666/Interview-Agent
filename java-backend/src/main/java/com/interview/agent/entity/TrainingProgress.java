package com.interview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/** 训练任务完成记录：报告训练计划 → 八股练习的回写（R-10） */
@Data
@TableName(value = "training_progress", autoResultMap = true)
public class TrainingProgress {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long candidateId;
    /** 报告来源会话 */
    private Long sourceSessionId;
    private Integer planDay;
    private Integer taskIndex;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object tagIds;

    /** 完成练习的八股会话 id */
    private Long gauntletSessionId;
    /** done（预留其他状态） */
    private String status;

    private OffsetDateTime createdAt;
}
