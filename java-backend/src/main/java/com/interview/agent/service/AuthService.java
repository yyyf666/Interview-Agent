package com.interview.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.interview.agent.auth.JwtService;
import com.interview.agent.auth.LoginAttemptService;
import com.interview.agent.auth.TokenService;
import com.interview.agent.entity.AppUser;
import com.interview.agent.entity.CandidateProfile;
import com.interview.agent.mapper.AppUserMapper;
import com.interview.agent.mapper.CandidateProfileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户注册/登录。注册时自动创建候选人画像（长期记忆载体），
 * 使"我的画像"与登录账号一一对应，面试会话归属校验以此为锚。
 *
 * 令牌策略：访问令牌 30 分钟（JWT）+ refresh token 7 天（Redis 可吊销，登录/续期时轮换）。
 * 登录防护：5 次失败 / 5 分钟 → 锁定 15 分钟（LoginAttemptService）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AppUserMapper userMapper;
    private final CandidateProfileMapper profileMapper;
    private final JwtService jwtService;
    private final TokenService tokenService;
    private final LoginAttemptService loginAttempts;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public Map<String, Object> register(String username, String password, String nickname) {
        if (username == null || username.isBlank() || password == null || password.length() < 6) {
            throw new IllegalArgumentException("用户名不能为空且密码至少6位");
        }
        Long exists = userMapper.selectCount(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getUsername, username.trim()));
        if (exists != null && exists > 0) {
            throw new IllegalArgumentException("用户名已存在: " + username);
        }
        // 首个注册用户自动成为管理员（避免部署后无人可管理题库/知识库）
        boolean firstUser = userMapper.selectCount(new LambdaQueryWrapper<>()) == 0;
        AppUser user = new AppUser();
        user.setUsername(username.trim());
        user.setPasswordHash(encoder.encode(password));
        user.setNickname(nickname == null || nickname.isBlank() ? username.trim() : nickname);
        user.setRole(firstUser ? AppUser.ROLE_ADMIN : AppUser.ROLE_USER);
        userMapper.insert(user);

        CandidateProfile profile = new CandidateProfile();
        profile.setUserId(user.getId());
        profile.setNickname(user.getNickname());
        profileMapper.insert(profile);
        log.info("新用户注册: {} (id={}, profileId={}, role={})",
                user.getUsername(), user.getId(), profile.getId(), user.getRole());

        return tokenResult(user, profile.getId());
    }

    public Map<String, Object> login(String username, String password, String clientIp) {
        String name = username == null ? "" : username.trim();
        loginAttempts.assertNotLocked(clientIp, name);
        AppUser user = userMapper.selectOne(new LambdaQueryWrapper<AppUser>()
                .eq(AppUser::getUsername, name));
        if (user == null || !encoder.matches(password == null ? "" : password, user.getPasswordHash())) {
            loginAttempts.onFailure(clientIp, name);
            throw new IllegalArgumentException("用户名或密码错误");
        }
        loginAttempts.onSuccess(clientIp, name);
        CandidateProfile profile = profileMapper.selectOne(new LambdaQueryWrapper<CandidateProfile>()
                .eq(CandidateProfile::getUserId, user.getId())
                .orderByAsc(CandidateProfile::getId)
                .last("LIMIT 1"));
        return tokenResult(user, profile == null ? null : profile.getId());
    }

    /**
     * 续期：校验 refresh token → 吊销旧的 → 换发新的访问令牌与 refresh token（轮换防重放）。
     * 失败抛 IllegalArgumentException（400），前端据此回登录门禁。
     */
    public Map<String, Object> refresh(String refreshToken) {
        Long userId = tokenService.resolveUserId(refreshToken);
        if (userId == null) {
            throw new IllegalArgumentException("登录已过期，请重新登录");
        }
        AppUser user = userMapper.selectById(userId);
        if (user == null) {
            tokenService.revoke(refreshToken);
            throw new IllegalArgumentException("用户不存在，请重新登录");
        }
        tokenService.revoke(refreshToken);
        CandidateProfile profile = profileMapper.selectOne(new LambdaQueryWrapper<CandidateProfile>()
                .eq(CandidateProfile::getUserId, user.getId())
                .orderByAsc(CandidateProfile::getId)
                .last("LIMIT 1"));
        return tokenResult(user, profile == null ? null : profile.getId());
    }

    /** 登出：吊销 refresh token（访问令牌靠 30 分钟自然过期） */
    public void logout(String refreshToken) {
        tokenService.revoke(refreshToken);
    }

    public Map<String, Object> me(Long userId) {
        AppUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("user_id", user.getId());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        data.put("role", user.getRole() == null ? AppUser.ROLE_USER : user.getRole());
        return data;
    }

    private Map<String, Object> tokenResult(AppUser user, Long profileId) {
        String role = user.getRole() == null ? AppUser.ROLE_USER : user.getRole();
        Map<String, Object> data = new HashMap<>();
        data.put("token", jwtService.issue(user.getId(), user.getUsername(), role));
        data.put("refresh_token", tokenService.issue(user.getId()));
        data.put("expires_in", jwtService.accessTtlSeconds());
        data.put("user_id", user.getId());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        data.put("role", role);
        data.put("profile_id", profileId);
        return data;
    }
}
