package com.interview.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.interview.agent.auth.Ownership;
import com.interview.agent.auth.UserContext;
import com.interview.agent.client.AiServiceClient;
import com.interview.agent.entity.CandidateProfile;
import com.interview.agent.entity.InterviewSession;
import com.interview.agent.entity.Resume;
import com.interview.agent.mapper.CandidateProfileMapper;
import com.interview.agent.mapper.InterviewSessionMapper;
import com.interview.agent.mapper.ResumeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 简历上传 -> 文本提取 -> LLM结构化解析 -> 更新候选人画像 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumeService {

    private final ResumeMapper resumeMapper;
    private final CandidateProfileMapper profileMapper;
    private final InterviewSessionMapper sessionMapper;
    private final FileStorageService storageService;
    private final DocumentTextExtractor extractor;
    private final AiServiceClient aiClient;
    private final GapCache gapCache;

    @SuppressWarnings("unchecked")
    public Resume upload(MultipartFile file, Long candidateId) {
        String filePath = storageService.save(file, "resume");
        String rawText = extractor.extract(file);

        Resume resume = new Resume();
        resume.setUserId(UserContext.userId());
        resume.setCandidateId(candidateId);
        resume.setFileName(file.getOriginalFilename());
        resume.setFilePath(filePath);
        resume.setRawText(rawText);
        resume.setVersion(1);
        resumeMapper.insert(resume);

        // 调用 AI 层做 LLM 结构化解析（Java 不直接调 LLM）
        Map<String, Object> resp = aiClient.postJson("/ai/parse/resume",
                Map.of("rawText", rawText));
        Map<String, Object> parsed = (Map<String, Object>) resp.get("data");
        resume.setParsed(parsed);
        resumeMapper.updateById(resume);

        // 解析结果同步进候选人长期画像
        syncProfile(candidateId, parsed);
        return resume;
    }

    /** 解析结果同步进候选人长期画像（上传与重新解析共用）。 */
    private void syncProfile(Long candidateId, Map<String, Object> parsed) {
        if (candidateId == null || parsed == null) {
            return;
        }
        CandidateProfile profile = profileMapper.selectById(candidateId);
        if (profile != null) {
            profile.setSkillTags(parsed.get("skills"));
            Object years = parsed.get("years_of_experience");
            if (years != null) {
                profile.setYearsOfExperience(new BigDecimal(String.valueOf(years)));
            }
            profileMapper.updateById(profile);
        }
    }

    /**
     * 重新解析（R-05）：用已存原文重跑 LLM 结构化解析，version+1。
     * writeProfile=true 时才同步长期画像（预览场景默认不动画像）。
     */
    @SuppressWarnings("unchecked")
    public Resume reparse(Long id, boolean writeProfile) {
        Resume resume = resumeMapper.selectById(id);
        if (resume == null) {
            throw new IllegalArgumentException("简历不存在: " + id);
        }
        Ownership.check(resume.getUserId(), "简历");
        String rawText = resume.getRawText();
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("简历原文为空，无法重新解析");
        }
        Map<String, Object> parsed = (Map<String, Object>) aiClient.postJson(
                "/ai/parse/resume", Map.of("rawText", rawText)).get("data");
        resume.setParsed(parsed);
        bumpVersion(resume);
        resumeMapper.updateById(resume);
        if (writeProfile) {
            syncProfile(resume.getCandidateId(), parsed);
        }
        gapCache.evict(id, null);
        return resume;
    }

    /**
     * 修正解析结果（R-05）：只接受白名单字段，skills 每项必须含 skill 与 level(1-5)。
     */
    public Resume updateParsed(Long id, Map<String, Object> parsed) {
        Resume resume = resumeMapper.selectById(id);
        if (resume == null) {
            throw new IllegalArgumentException("简历不存在: " + id);
        }
        Ownership.check(resume.getUserId(), "简历");
        Map<String, Object> sanitized = sanitizeParsed(parsed);
        resume.setParsed(sanitized);
        bumpVersion(resume);
        resumeMapper.updateById(resume);
        gapCache.evict(id, null);
        return resume;
    }

    private static void bumpVersion(Resume resume) {
        resume.setVersion(resume.getVersion() == null ? 1 : resume.getVersion() + 1);
    }

    /** 白名单裁剪：name/years_of_experience/skills/projects/summary，其余键丢弃。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> sanitizeParsed(Map<String, Object> parsed) {
        if (parsed == null) {
            throw new IllegalArgumentException("解析结果不能为空");
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (String key : List.of("name", "years_of_experience", "skills", "projects", "summary")) {
            if (parsed.containsKey(key)) {
                out.put(key, parsed.get(key));
            }
        }
        Object skills = out.get("skills");
        if (skills != null) {
            if (!(skills instanceof List<?> skillList)) {
                throw new IllegalArgumentException("skills 必须是数组");
            }
            for (Object item : skillList) {
                if (!(item instanceof Map<?, ?> skill) || !(skill.get("skill") instanceof String s) || s.isBlank()) {
                    throw new IllegalArgumentException("skills 每项必须包含非空的 skill 字段");
                }
                Object level = skill.get("level");
                if (!(level instanceof Number n) || n.intValue() < 1 || n.intValue() > 5) {
                    throw new IllegalArgumentException("skills 的 level 必须是 1-5 的整数");
                }
            }
        }
        return out;
    }

    public Resume get(Long id) {
        Resume r = resumeMapper.selectById(id);
        if (r != null) {
            Ownership.check(r.getUserId(), "简历");
        }
        return r;
    }

    public List<Resume> list(Long candidateId) {
        LambdaQueryWrapper<Resume> w = new LambdaQueryWrapper<>();
        Long uid = UserContext.userId();
        if (uid != null) {
            w.and(q -> q.isNull(Resume::getUserId).or().eq(Resume::getUserId, uid));
        }
        if (candidateId != null) {
            w.eq(Resume::getCandidateId, candidateId);
        }
        w.orderByDesc(Resume::getId);
        return resumeMapper.selectList(w);
    }

    /** 删除简历：历史会话解绑（resume_id 置 NULL）后删记录与磁盘文件 */
    public void delete(Long id) {
        Resume r = resumeMapper.selectById(id);
        if (r == null) {
            return;
        }
        Ownership.check(r.getUserId(), "简历");
        sessionMapper.update(null, new LambdaUpdateWrapper<InterviewSession>()
                .set(InterviewSession::getResumeId, null)
                .eq(InterviewSession::getResumeId, id));
        storageService.delete(r.getFilePath());
        resumeMapper.deleteById(id);
        gapCache.evict(id, null);
    }
}
