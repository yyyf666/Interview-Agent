package com.interview.agent.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

/**
 * 自管 JWT（HS256）：短期访问令牌签发与校验。
 * - 有效期 30 分钟（原 7 天 → 缩短以收敛泄露窗口），续期靠 refresh token（见 TokenService）
 * - 角色随 claim 下发，避免每请求查库；改角色后需重新登录生效
 * 密钥来自 APP_JWT_SECRET（≥32字节；缺省为本地开发值，生产必须覆盖）。
 */
@Slf4j
@Component
public class JwtService {

    /** 访问令牌有效期：30 分钟 */
    private static final Duration ACCESS_TTL = Duration.ofMinutes(30);
    /** 令牌类型，防止 refresh token 冒充访问令牌 */
    public static final String TYPE_ACCESS = "access";

    private final SecretKey key;

    public JwtService(@Value("${app.jwt.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            // HS256 弱密钥兜底：右侧补零到32字节，仅可能发生在开发环境
            byte[] padded = new byte[32];
            System.arraycopy(bytes, 0, padded, 0, bytes.length);
            bytes = padded;
            log.warn("APP_JWT_SECRET 短于32字节，已补齐（生产环境请配置足够长度的密钥）");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    public String issue(Long userId, String username, String role) {
        Date now = new Date();
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role == null ? "USER" : role)
                .claim("typ", TYPE_ACCESS)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + ACCESS_TTL.toMillis()))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /** 访问令牌有效秒数（前端可据此提前静默续期） */
    public long accessTtlSeconds() {
        return ACCESS_TTL.toSeconds();
    }

    /** 校验并解析；非法/过期/类型不符返回 null */
    public UserContext.CurrentUser verify(String token) {
        try {
            Claims claims = Jwts.parserBuilder().setSigningKey(key).build()
                    .parseClaimsJws(token).getBody();
            if (!TYPE_ACCESS.equals(claims.get("typ", String.class))) {
                return null;
            }
            String role = claims.get("role", String.class);
            return new UserContext.CurrentUser(Long.parseLong(claims.getSubject()),
                    claims.get("username", String.class),
                    role == null ? "USER" : role);
        } catch (Exception e) {
            log.debug("JWT校验失败: {}", e.getMessage());
            return null;
        }
    }
}
