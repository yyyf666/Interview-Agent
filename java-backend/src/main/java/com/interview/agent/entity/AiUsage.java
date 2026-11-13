package com.interview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** AI 用量：每次 LLM 类调用后记一条，供成本看板聚合（R-17） */
@Data
@TableName("ai_usage")
public class AiUsage {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long sessionId;
    /** start / gap / answer / hint / finish / interject */
    private String scene;
    private Integer llmCalls;
    private Long elapsedMs;
    private String model;

    private OffsetDateTime createdAt;
}
