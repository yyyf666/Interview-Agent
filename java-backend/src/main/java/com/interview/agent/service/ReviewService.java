package com.interview.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.interview.agent.auth.Ownership;
import com.interview.agent.auth.UserContext;
import com.interview.agent.entity.Evaluation;
import com.interview.agent.entity.InterviewSession;
import com.interview.agent.entity.InterviewTurn;
import com.interview.agent.entity.ReviewItem;
import com.interview.agent.mapper.EvaluationMapper;
import com.interview.agent.mapper.InterviewSessionMapper;
import com.interview.agent.mapper.InterviewTurnMapper;
import com.interview.agent.mapper.ReviewItemMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 错题本（R-09）：把一轮问答 + 评测要点收藏为可复习条目。
 *
 * 幂等：uk_review_user_turn(user_id, turn_id) 唯一索引，重复收藏返回已有记录。
 * 复习状态：new / learning / mastered。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private static final Set<String> MASTERED_STATES = Set.of("new", "learning", "mastered");

    private final ReviewItemMapper reviewMapper;
    private final InterviewSessionMapper sessionMapper;
    private final InterviewTurnMapper turnMapper;
    private final EvaluationMapper evaluationMapper;

    /** 收藏一轮问答（幂等）：同一用户同一轮重复收藏返回既有记录。 */
    public ReviewItem add(Long sessionId, Long turnId, List<String> tags, String note, Long candidateId) {
        if (sessionId == null || turnId == null) {
            throw new IllegalArgumentException("sessionId 与 turnId 不能为空");
        }
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("面试会话不存在: " + sessionId);
        }
        Ownership.check(session.getUserId(), "面试会话");
        InterviewTurn turn = turnMapper.selectById(turnId);
        if (turn == null || !sessionId.equals(turn.getSessionId())) {
            throw new IllegalArgumentException("该轮次不属于当前会话: " + turnId);
        }
        Long userId = UserContext.userId();

        ReviewItem existing = findOwned(userId, turnId);
        if (existing != null) {
            return existing;
        }

        Evaluation eval = evaluationMapper.selectOne(new LambdaQueryWrapper<Evaluation>()
                .eq(Evaluation::getTurnId, turnId).last("limit 1"));

        ReviewItem item = new ReviewItem();
        item.setUserId(userId);
        item.setCandidateId(candidateId != null ? candidateId : session.getCandidateId());
        item.setSessionId(sessionId);
        item.setTurnId(turnId);
        item.setQuestion(turn.getQuestion());
        item.setAnswer(turn.getAnswer());
        item.setReferencePoints(turn.getReferencePoints() == null ? List.of() : turn.getReferencePoints());
        if (eval != null) {
            item.setMissedPoints(eval.getMissedKnowledgePoints() == null ? List.of() : eval.getMissedKnowledgePoints());
            item.setIssues(eval.getIssues() == null ? List.of() : eval.getIssues());
        } else {
            item.setMissedPoints(List.of());
            item.setIssues(List.of());
        }
        item.setTags(tags == null || tags.isEmpty() ? defaultTags(eval) : tags);
        item.setMastered("new");
        item.setNote(note == null ? null : note.trim());
        try {
            reviewMapper.insert(item);
            return item;
        } catch (DuplicateKeyException e) {
            // 并发收藏同一轮：唯一索引兜底，返回已存在记录
            ReviewItem raced = findOwned(userId, turnId);
            if (raced != null) {
                return raced;
            }
            throw e;
        }
    }

    /** 分页查询：按掌握状态 / 标签过滤，附带当前用户全部标签集合。 */
    public Map<String, Object> page(String mastered, String tag, int pageNo, int pageSize) {
        Long userId = UserContext.userId();
        int page = Math.max(1, pageNo);
        int size = Math.max(1, Math.min(100, pageSize));

        LambdaQueryWrapper<ReviewItem> w = ownedQuery(userId);
        if (mastered != null && !mastered.isBlank()) {
            String m = mastered.trim().toLowerCase();
            if (!MASTERED_STATES.contains(m)) {
                throw new IllegalArgumentException("mastered 仅支持 new/learning/mastered");
            }
            w.eq(ReviewItem::getMastered, m);
        }
        if (tag != null && !tag.isBlank()) {
            w.apply("tags @> {0}::jsonb", "[\"" + tag.trim().replace("\"", "") + "\"]");
        }
        w.orderByDesc(ReviewItem::getId);

        Page<ReviewItem> result = reviewMapper.selectPage(new Page<>(page, size), w);

        Set<String> tags = new LinkedHashSet<>();
        for (ReviewItem item : reviewMapper.selectList(ownedQuery(userId).select(ReviewItem::getTags))) {
            if (item.getTags() instanceof List<?> list) {
                for (Object t : list) {
                    if (t != null && !String.valueOf(t).isBlank()) {
                        tags.add(String.valueOf(t));
                    }
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", result.getRecords());
        out.put("total", result.getTotal());
        out.put("tags", new ArrayList<>(tags));
        return out;
    }

    /** 更新复习状态/备注：只能改自己的条目。 */
    public ReviewItem update(Long id, String mastered, String note) {
        ReviewItem item = requireOwned(id);
        if (mastered != null) {
            String m = mastered.trim().toLowerCase();
            if (!MASTERED_STATES.contains(m)) {
                throw new IllegalArgumentException("mastered 仅支持 new/learning/mastered");
            }
            item.setMastered(m);
        }
        if (note != null) {
            String n = note.trim();
            if (n.length() > 512) {
                throw new IllegalArgumentException("备注不能超过 512 个字符");
            }
            item.setNote(n.isEmpty() ? null : n);
        }
        item.setUpdatedAt(OffsetDateTime.now());
        reviewMapper.updateById(item);
        return item;
    }

    public void delete(Long id) {
        ReviewItem item = requireOwned(id);
        reviewMapper.deleteById(item.getId());
    }

    private ReviewItem requireOwned(Long id) {
        ReviewItem item = reviewMapper.selectById(id);
        if (item == null) {
            throw new IllegalArgumentException("错题记录不存在: " + id);
        }
        Ownership.check(item.getUserId(), "错题记录");
        return item;
    }

    private ReviewItem findOwned(Long userId, Long turnId) {
        return reviewMapper.selectOne(ownedQuery(userId).eq(ReviewItem::getTurnId, turnId).last("limit 1"));
    }

    /** 未登录兜底：仅能看 user_id 为空的遗留条目（实际被鉴权拦截器挡在前面）。 */
    private static LambdaQueryWrapper<ReviewItem> ownedQuery(Long userId) {
        LambdaQueryWrapper<ReviewItem> w = new LambdaQueryWrapper<>();
        if (userId == null) {
            w.isNull(ReviewItem::getUserId);
        } else {
            w.eq(ReviewItem::getUserId, userId);
        }
        return w;
    }

    /** 未显式传标签时用评测技能标签兜底，保证标签筛选可用。 */
    private static List<String> defaultTags(Evaluation eval) {
        if (eval == null || !(eval.getSkillTags() instanceof List<?> list)) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (Object t : list) {
            if (t != null && !String.valueOf(t).isBlank()) {
                tags.add(String.valueOf(t));
            }
        }
        return tags;
    }
}
