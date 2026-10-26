package com.interview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 技能字典表 */
@Data
@TableName("skill")
public class Skill {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String category;
    private Long parentId;
    private BigDecimal weight;
    private String description;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
