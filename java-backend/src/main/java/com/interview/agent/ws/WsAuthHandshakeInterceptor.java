package com.interview.agent.ws;

import com.interview.agent.auth.JwtService;
import com.interview.agent.auth.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

/**
 * 实时语音 WS 握手鉴权：浏览器 WebSocket 无法设置请求头，故 token 走 query 参数。
 * 校验通过后把 userId 放入 session attributes 供 relay handler 记录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsAuthHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtService jwtService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = extractToken(request.getURI());
        if (token == null || token.isBlank()) {
            log.warn("WS 握手拒绝：缺少 token");
            return false;
        }
        UserContext.CurrentUser user = jwtService.verify(token);
        if (user == null) {
            log.warn("WS 握手拒绝：token 无效或过期");
            return false;
        }
        attributes.put("userId", user.id());
        attributes.put("username", user.username());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    private static String extractToken(URI uri) {
        String query = uri.getQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "token".equals(pair.substring(0, eq))) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
