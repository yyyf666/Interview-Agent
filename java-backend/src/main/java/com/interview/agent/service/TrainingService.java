package com.interview.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.interview.agent.auth.Ownership;
import com.interview.agent.auth.UserContext;
import com.interview.agent.entity.InterviewSession;
import com.interview.agent.entity.TrainingProgress;
import com.interview.agent.mapper.InterviewSessionMapper;
import com.interview.agent.mapper.TrainingProgressMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 训练进度（R-10）：报告训练任务 → 八股练习 → 回写完成态。
 *
 * 幂等：uk_training_task(source_session_id, plan_day, task_index) 唯一索引，重复提交返回既有行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingService {

    private final TrainingProgressMapper progressMapper;
    private final InterviewSessionMapper sessionMapper;

    /** 标记任务完成（幂等）：冲突视为已完成，返回既有行。 */
    public TrainingProgress markDone(Long sourceSessionId, Integer planDay, Integer taskIndex,
                                     List<String> tagIds, Long gauntletSessionId) {
        if (sourceSessionId == null || planDay == null || taskIndex == null) {
            throw new IllegalArgumentException("sourceSessionId / planDay / taskIndex 不能为空");
        }
        InterviewSession source = sessionMapper.selectById(sourceSessionId);
        if (source == null) {
            throw new IllegalArgumentException("来源会话不存在: " + sourceSessionId);
        }
        Ownership.check(source.getUserId(), "来源会话");

        TrainingProgress existing = find(sourceSessionId, planDay, taskIndex);
        if (existing != null) {
            return existing;
        }
        TrainingProgress row = new TrainingProgress();
        row.setUserId(UserContext.userId());
        row.setCandidateId(source.getCandidateId());
        row.setSourceSessionId(sourceSessionId);
        row.setPlanDay(planDay);
        row.setTaskIndex(taskIndex);
        row.setTagIds(tagIds == null ? List.of() : tagIds);
        row.setGauntletSessionId(gauntletSessionId);
        row.setStatus("done");
        try {
            progressMapper.insert(row);
            return row;
        } catch (DuplicateKeyException e) {
            TrainingProgress raced = find(sourceSessionId, planDay, taskIndex);
            if (raced != null) {
                return raced;
            }
            throw e;
        }
    }

    /** 完成列表 + 汇总：按来源会话分组，前端据此渲染「已完成 x/y」。 */
    public Map<String, Object> list(Long candidateId, Long sourceSessionId) {
        LambdaQueryWrapper<TrainingProgress> w = new LambdaQueryWrapper<>();
        Long uid = UserContext.userId();
        if (uid != null) {
            w.and(q -> q.isNull(TrainingProgress::getUserId).or().eq(TrainingProgress::getUserId, uid));
        }
        if (candidateId != null) {
            w.eq(TrainingProgress::getCandidateId, candidateId);
        }
        if (sourceSessionId != null) {
            w.eq(TrainingProgress::getSourceSessionId, sourceSessionId);
        }
        List<TrainingProgress> rows = progressMapper.selectList(w.orderByDesc(TrainingProgress::getId));

        Map<String, Integer> bySource = new LinkedHashMap<>();
        List<Map<String, Object>> items = new ArrayList<>();
        for (TrainingProgress row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", row.getId());
            m.put("sourceSessionId", row.getSourceSessionId());
            m.put("planDay", row.getPlanDay());
            m.put("taskIndex", row.getTaskIndex());
            m.put("tagIds", row.getTagIds());
            m.put("gauntletSessionId", row.getGauntletSessionId());
            m.put("status", row.getStatus());
            m.put("createdAt", row.getCreatedAt());
            items.add(m);
            bySource.merge(String.valueOf(row.getSourceSessionId()), 1, Integer::sum);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", items);
        out.put("total", items.size());
        out.put("completedBySource", bySource);
        return out;
    }

    private TrainingProgress find(Long sourceSessionId, Integer planDay, Integer taskIndex) {
        return progressMapper.selectOne(new LambdaQueryWrapper<TrainingProgress>()
                .eq(TrainingProgress::getSourceSessionId, sourceSessionId)
                .eq(TrainingProgress::getPlanDay, planDay)
                .eq(TrainingProgress::getTaskIndex, taskIndex)
                .last("limit 1"));
    }
}
