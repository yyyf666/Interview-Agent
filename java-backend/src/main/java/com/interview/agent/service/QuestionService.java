package com.interview.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.interview.agent.client.AiServiceClient;
import com.interview.agent.entity.QuestionBank;
import com.interview.agent.mapper.QuestionBankMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 题库管理：CRUD + 向量索引同步（向量写入由 AI 层完成） */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionService {

    /** 单次导入上限（防止一次大文件导入长时间占用请求线程） */
    private static final int IMPORT_LIMIT = 2000;

    private final QuestionBankMapper questionMapper;
    private final AiServiceClient aiClient;

    public Page<QuestionBank> page(int pageNo, int pageSize, String skill, Integer difficulty,
                                   String qType, String kw) {
        LambdaQueryWrapper<QuestionBank> w = new LambdaQueryWrapper<>();
        if (skill != null && !skill.isBlank()) {
            w.eq(QuestionBank::getSkillName, skill);
        }
        if (difficulty != null) {
            w.eq(QuestionBank::getDifficulty, difficulty);
        }
        if (qType != null && !qType.isBlank()) {
            w.eq(QuestionBank::getQType, qType);
        }
        if (kw != null && !kw.isBlank()) {
            w.like(QuestionBank::getContent, kw.trim());
        }
        return questionMapper.selectPage(new Page<>(pageNo, pageSize), w.orderByAsc(QuestionBank::getId));
    }

    public QuestionBank create(QuestionBank question) {
        if (question.getStatus() == null) {
            question.setStatus("ACTIVE");
        }
        questionMapper.insert(question);
        syncEmbedding(question);
        return question;
    }

    public QuestionBank update(QuestionBank question) {
        question.setVersion(question.getVersion() == null ? 1 : question.getVersion() + 1);
        questionMapper.updateById(question);
        syncEmbedding(question);
        return question;
    }

    public void delete(Long id) {
        questionMapper.deleteById(id);
    }

    /**
     * 批量导入（R-12）：按题干 content 去重（本次内 + 库内），逐条记录跳过原因。
     * 仅做内容去重，不做 JSON Schema 全量校验；缺字段按默认值补齐。
     */
    public Map<String, Object> importAll(List<QuestionBank> list) {
        if (list == null || list.isEmpty()) {
            throw new IllegalArgumentException("导入内容为空");
        }
        if (list.size() > IMPORT_LIMIT) {
            throw new IllegalArgumentException("单次最多导入 " + IMPORT_LIMIT + " 题");
        }
        int imported = 0;
        int skipped = 0;
        Set<String> seen = new HashSet<>();
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            QuestionBank q = list.get(i);
            String content = q == null || q.getContent() == null ? "" : q.getContent().trim();
            if (content.isEmpty()) {
                skipped++;
                errors.add("第" + (i + 1) + "条：题干为空");
                continue;
            }
            if (!seen.add(content)) {
                skipped++;
                continue;
            }
            long exists = questionMapper.selectCount(new LambdaQueryWrapper<QuestionBank>()
                    .eq(QuestionBank::getContent, content));
            if (exists > 0) {
                skipped++;
                continue;
            }
            q.setId(null);
            q.setContent(content);
            if (q.getStatus() == null) {
                q.setStatus("ACTIVE");
            }
            if (q.getDifficulty() == null) {
                q.setDifficulty(2);
            }
            if (q.getVersion() == null) {
                q.setVersion(1);
            }
            questionMapper.insert(q);
            syncEmbedding(q);
            imported++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", list.size());
        out.put("imported", imported);
        out.put("skipped", skipped);
        out.put("errors", errors.size() > 20 ? errors.subList(0, 20) : errors);
        return out;
    }

    /** 触发全量题库向量重建 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> reindexAll() {
        List<QuestionBank> all = questionMapper.selectList(new LambdaQueryWrapper<QuestionBank>()
                .eq(QuestionBank::getStatus, "ACTIVE"));
        for (QuestionBank q : all) {
            syncEmbedding(q);
        }
        return Map.of("indexed", all.size());
    }

    private void syncEmbedding(QuestionBank q) {
        if (q.getId() == null || q.getContent() == null) {
            return;
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("question_id", q.getId());
            body.put("content", q.getContent());
            body.put("metadata", Map.of(
                    "skill", q.getSkillName() == null ? "" : q.getSkillName(),
                    "difficulty", q.getDifficulty() == null ? 2 : q.getDifficulty(),
                    "q_type", q.getQType() == null ? "" : q.getQType()));
            aiClient.postJson("/ai/questions/embed", body);
        } catch (Exception e) {
            // 向量索引失败不阻塞题库管理，可后续 reindex 补偿
            log.warn("题目向量同步失败 questionId={}, err={}", q.getId(), e.getMessage());
        }
    }
}
