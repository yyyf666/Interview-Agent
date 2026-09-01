package com.interview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/** 岗位JD表 */
@Data
@TableName(value = "job_description", autoResultMap = true)
public class JobDescription {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 归属用户（NULL=遗留数据） */
    private Long userId;
    private String title;
    private String company;
    private String filePath;
    private String rawText;

    /** LLM解析后的岗位能力模型 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object parsed;

    /** 技能权重 {"java":0.3} */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object skillWeights;

    /** 考察重点 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object focusPoints;

    private OffsetDateTime createdAt;
}
