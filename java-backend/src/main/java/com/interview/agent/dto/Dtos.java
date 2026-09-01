package com.interview.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public class Dtos {

    public record RegisterRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password,
            String nickname) {
    }

    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            String password) {
    }

    public record RefreshRequest(
            @NotBlank(message = "refresh token 不能为空") String refreshToken) {
    }

    public record LogoutRequest(
            String refreshToken) {
    }

    public record StartInterviewRequest(
            Long candidateId,
            Long resumeId,
            Long jdId,
            String mode,
            /** 面试官人格 standard/friendly/pressure/socratic/english（单人时） */
            String persona,
            /** Panel 多面试官人格列表（非空时优先，按轮次轮转发言；存库为CSV） */
            List<String> personas,
            /** 开前缺口分析快照（R-03）：非空时 PLAN 节点直接复用，避免二次 LLM 调用 */
            Map<String, Object> gap,
            /** 高级面试配置（R-08）：题量/时长/难度/代码题/追问强度/语言/重点技能，白名单校验 */
            Map<String, Object> options) {
    }

    public record GapAnalysisRequest(
            Long candidateId,
            Long resumeId,
            Long jdId) {
    }

    public record RenameSessionRequest(
            @Size(max = 256, message = "标题最多256个字符") String title) {
    }

    public record JdUpdateRequest(
            @Size(max = 256, message = "标题最多256个字符") String title,
            @Size(max = 256, message = "公司名最多256个字符") String company,
            /** 白名单字段：title/company/required_skills/skill_weights/focus_points */
            Map<String, Object> parsed) {
    }

    public record StartGauntletRequest(
            /** 预置二级标签 ID，1-6 个（服务端校验并映射 topic/skill） */
            @NotEmpty(message = "请至少选择一个八股标签")
            @Size(max = 6, message = "最多选择6个八股标签")
            List<@NotBlank(message = "标签ID不能为空") String> tagIds,
            /** 起始难度 1-5，缺省 2 */
            Integer difficulty,
            String persona,
            List<String> personas,
            /** 学习模式（R-11）：true 时出题后自动展开评测讲解 */
            Boolean studyMode) {
    }

    public record AnswerRequest(
            @NotBlank(message = "回答不能为空") String answer,
            /** 代码题运行结果（可选）：{language, stdin, stdout, exit_code, results} */
            Map<String, Object> execution,
            /** 幂等键：前端携带当前待答轮次号，不匹配则拒绝（防重复提交/双端并发推进） */
            Integer turnNo) {
    }

    /**
     * 八股主动跳过/换题（R-11）：不产生评测，只标记轮次并出新题。
     * intent=skip 跳过本题；intent=request_question 换一道题（可带 questionSpec 描述）。
     */
    public record InterjectRequest(
            @NotBlank(message = "intent 不能为空") String intent,
            String questionSpec) {
    }

    public record HintRequest(
            /** 提示等级 1=方向 2=关键点 3=揭晓参考思路 */
            Integer level) {
    }

    public record JdTextRequest(
            @NotBlank(message = "JD文本不能为空") String rawText) {
    }

    public record ProfileRequest(
            String nickname,
            String targetRole) {
    }

    /** 收藏一轮问答到错题本（R-09）：tags/note 可缺省，缺省标签取评测技能标签。 */
    public record ReviewCreateRequest(
            Long sessionId,
            Long turnId,
            List<String> tags,
            String note,
            Long candidateId) {
    }

    /** 更新错题复习状态与备注（R-09）：mastered 仅 new/learning/mastered。 */
    public record ReviewUpdateRequest(
            String mastered,
            String note) {
    }

    /** 标记报告训练任务已完成（R-10）：唯一索引冲突即视为已完成。 */
    public record TrainingProgressRequest(
            Long sourceSessionId,
            Integer planDay,
            Integer taskIndex,
            List<String> tagIds,
            Long gauntletSessionId) {
    }
}
