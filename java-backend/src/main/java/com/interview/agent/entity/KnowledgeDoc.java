package com.interview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/** 知识库文档表 */
@Data
@TableName(value = "knowledge_doc", autoResultMap = true)
public class KnowledgeDoc {

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 归属用户 */
    private Long userId;
    private String title;
    private String filePath;
    /** question_bank / article / note / experience */
    private String docType;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object tags;

    /** PENDING / INDEXED / FAILED */
    private String chunkStatus;
    private Integer chunkCount;
    private Integer version;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
