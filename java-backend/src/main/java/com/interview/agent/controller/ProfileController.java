package com.interview.agent.controller;

import com.interview.agent.dto.ApiResult;
import com.interview.agent.entity.CandidateProfile;
import com.interview.agent.entity.Skill;
import com.interview.agent.mapper.SkillMapper;
import com.interview.agent.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "候选人画像与技能字典")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;
    private final SkillMapper skillMapper;

    @GetMapping("/profiles")
    public ApiResult<List<CandidateProfile>> list() {
        return ApiResult.ok(profileService.list());
    }

    @GetMapping("/profiles/{id}")
    public ApiResult<CandidateProfile> get(@PathVariable Long id) {
        return ApiResult.ok(profileService.get(id));
    }

    @PostMapping("/profiles")
    public ApiResult<CandidateProfile> create(@RequestBody CandidateProfile profile) {
        return ApiResult.ok(profileService.create(profile));
    }

    @PutMapping("/profiles/{id}")
    public ApiResult<CandidateProfile> update(@PathVariable Long id, @RequestBody CandidateProfile profile) {
        profile.setId(id);
        return ApiResult.ok(profileService.update(profile));
    }

    @Operation(summary = "成长追踪：历次面试成绩趋势")
    @GetMapping("/profiles/{id}/growth")
    public ApiResult<List<Map<String, Object>>> growth(@PathVariable Long id) {
        return ApiResult.ok(profileService.growthTrend(id));
    }

    @Operation(summary = "画像洞察（R-18）：薄弱点下钻（场次/证据原话）+ 技能掌握度")
    @GetMapping("/profiles/{id}/insights")
    public ApiResult<Map<String, Object>> insights(@PathVariable Long id) {
        return ApiResult.ok(profileService.insights(id));
    }

    @Operation(summary = "导出画像数据资产（R-18）：profile + resumes + jds，不含会话与密钥")
    @GetMapping("/profiles/{id}/export")
    public ApiResult<Map<String, Object>> exportData(@PathVariable Long id) {
        return ApiResult.ok(profileService.exportData(id));
    }

    @Operation(summary = "导入画像备份（R-18）：新建画像 + 简历 + JD，不导入会话")
    @PostMapping("/profiles/import")
    public ApiResult<Map<String, Object>> importData(@RequestBody Map<String, Object> body) {
        return ApiResult.ok(profileService.importData(body));
    }

    @Operation(summary = "清空当前用户全部业务数据（R-18）：需确认词 DELETE，保留账号")
    @PostMapping("/account/purge")
    public ApiResult<Map<String, Object>> purge(@RequestBody Map<String, Object> body) {
        Object confirm = body == null ? null : body.get("confirm");
        return ApiResult.ok(profileService.purge(confirm == null ? null : String.valueOf(confirm)));
    }

    @Operation(summary = "删除候选人画像（级联清理名下简历，历史会话解绑）")
    @DeleteMapping("/profiles/{id}")
    public ApiResult<Void> deleteProfile(@PathVariable Long id) {
        profileService.delete(id);
        return ApiResult.ok(null);
    }

    @GetMapping("/skills")
    public ApiResult<List<Skill>> skills() {
        return ApiResult.ok(skillMapper.selectList(null));
    }
}
