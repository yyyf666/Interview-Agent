package com.interview.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.OffsetDateTime;

/** 面试轮次表：每轮问答记录 */
@Data
@TableName(value = "interview_turn", autoResultMap = true)
public class InterviewTurn {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Integer turnNo;
    private String topic;
    private Integer difficulty;
    private Boolean isFollowUp;
    private String question;
    /** rag / question_bank / generated */
    private String questionSource;
    /** concept/principle/scenario/project/behavior/code */
    private String questionType;
    /** 代码题元数据 {starter_code, language, test_cases} */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object coding;
    /** 面试官对该轮回答的现场回应 */
    private String interviewerReaction;
    /** 参考答题要点（回放时并排对照 + 分级提示依据） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object referencePoints;
    /** 本轮使用分级提示次数（评测降档依据） */
    private Integer hintCount;
    /** 插话意图（answer_type=INTERJECTION 时有效）：request_question/skip/hint/clarify/end/off_topic */
    private String interjectionIntent;
    /** 当轮发言人设（Panel轮转结果，单人时与会话 persona 一致） */
    private String speakerPersona;
    private String answer;
    /** TEXT / ASR / CODE / INTERJECTION */
    private String answerType;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object skillTags;

    private OffsetDateTime createdAt;
}
