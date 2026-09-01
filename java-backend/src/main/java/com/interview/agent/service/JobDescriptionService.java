package com.interview.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.interview.agent.auth.Ownership;
import com.interview.agent.auth.UserContext;
import com.interview.agent.client.AiServiceClient;
import com.interview.agent.entity.InterviewSession;
import com.interview.agent.entity.JobDescription;
import com.interview.agent.mapper.InterviewSessionMapper;
import com.interview.agent.mapper.JobDescriptionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** JD上传 -> LLM解析岗位能力模型 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobDescriptionService {

    private final JobDescriptionMapper jdMapper;
    private final InterviewSessionMapper sessionMapper;
    private final FileStorageService storageService;
    private final DocumentTextExtractor extractor;
    private final AiServiceClient aiClient;
    private final GapCache gapCache;

    @SuppressWarnings("unchecked")
    public JobDescription upload(MultipartFile file) {
        String filePath = storageService.save(file, "jd");
        String rawText = extractor.extract(file);
        return create(rawText, file.getOriginalFilename(), filePath);
    }

    /** 也支持直接粘贴 JD 文本 */
    @SuppressWarnings("unchecked")
    public JobDescription create(String rawText, String fileName, String filePath) {
        Map<String, Object> resp = aiClient.postJson("/ai/parse/jd", Map.of("rawText", rawText));
        Map<String, Object> parsed = (Map<String, Object>) resp.get("data");

        JobDescription jd = new JobDescription();
        jd.setUserId(UserContext.userId());
        jd.setTitle(parsed != null ? (String) parsed.getOrDefault("title", fileName) : fileName);
        jd.setCompany(parsed != null ? (String) parsed.get("company") : null);
        jd.setFilePath(filePath);
        jd.setRawText(rawText);
        jd.setParsed(parsed);
        if (parsed != null) {
            jd.setSkillWeights(parsed.get("skill_weights"));
            jd.setFocusPoints(parsed.get("focus_points"));
        }
        jdMapper.insert(jd);
        return jd;
    }

    public JobDescription get(Long id) {
        JobDescription jd = jdMapper.selectById(id);
        if (jd != null) {
            Ownership.check(jd.getUserId(), "岗位JD");
        }
        return jd;
    }

    /**
     * 重新解析（R-05）：用已存原文重跑 LLM，回写 parsed/title/company/skillWeights/focusPoints。
     */
    @SuppressWarnings("unchecked")
    public JobDescription reparse(Long id) {
        JobDescription jd = jdMapper.selectById(id);
        if (jd == null) {
            throw new IllegalArgumentException("JD不存在: " + id);
        }
        Ownership.check(jd.getUserId(), "岗位JD");
        String rawText = jd.getRawText();
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("JD原文为空，无法重新解析");
        }
        Map<String, Object> parsed = (Map<String, Object>) aiClient.postJson(
                "/ai/parse/jd", Map.of("rawText", rawText)).get("data");
        applyParsed(jd, parsed);
        jdMapper.updateById(jd);
        gapCache.evict(null, id);
        return jd;
    }

    /**
     * 修正 JD（R-05）：body 允许 title/company/parsed；parsed 白名单裁剪，
     * skillWeights/focusPoints 从 parsed 派生，避免双写不一致。
     */
    public JobDescription update(Long id, String title, String company, Map<String, Object> parsed) {
        JobDescription jd = jdMapper.selectById(id);
        if (jd == null) {
            throw new IllegalArgumentException("JD不存在: " + id);
        }
        Ownership.check(jd.getUserId(), "岗位JD");
        if (title != null && !title.isBlank()) {
            jd.setTitle(title.trim());
        }
        if (company != null) {
            jd.setCompany(company.trim());
        }
        if (parsed != null) {
            applyParsed(jd, sanitizeParsed(parsed));
        }
        jdMapper.updateById(jd);
        gapCache.evict(null, id);
        return jd;
    }

    /** parsed 落库并同步派生字段（title/company 仅在非空时覆盖）。 */
    @SuppressWarnings("unchecked")
    private static void applyParsed(JobDescription jd, Map<String, Object> parsed) {
        if (parsed == null) {
            return;
        }
        jd.setParsed(parsed);
        jd.setSkillWeights(parsed.get("skill_weights"));
        jd.setFocusPoints(parsed.get("focus_points"));
        Object title = parsed.get("title");
        if (title instanceof String t && !t.isBlank()) {
            jd.setTitle(t);
        }
        Object company = parsed.get("company");
        if (company instanceof String c && !c.isBlank()) {
            jd.setCompany(c);
        }
    }

    /** 白名单裁剪：title/company/required_skills/skill_weights/focus_points。 */
    private static Map<String, Object> sanitizeParsed(Map<String, Object> parsed) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (String key : List.of("title", "company", "required_skills", "skill_weights", "focus_points")) {
            if (parsed.containsKey(key)) {
                out.put(key, parsed.get(key));
            }
        }
        return out;
    }

    public List<JobDescription> list() {
        LambdaQueryWrapper<JobDescription> w = new LambdaQueryWrapper<>();
        Long uid = UserContext.userId();
        if (uid != null) {
            w.and(q -> q.isNull(JobDescription::getUserId).or().eq(JobDescription::getUserId, uid));
        }
        return jdMapper.selectList(w.orderByDesc(JobDescription::getId));
    }

    /** 删除JD：历史会话解绑（jd_id 置 NULL）后删记录与磁盘文件（粘贴文本无文件） */
    public void delete(Long id) {
        JobDescription jd = jdMapper.selectById(id);
        if (jd == null) {
            return;
        }
        Ownership.check(jd.getUserId(), "岗位JD");
        sessionMapper.update(null, new LambdaUpdateWrapper<InterviewSession>()
                .set(InterviewSession::getJdId, null)
                .eq(InterviewSession::getJdId, id));
        storageService.delete(jd.getFilePath());
        jdMapper.deleteById(id);
        gapCache.evict(null, id);
    }
}
