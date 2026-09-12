package com.interview.agent.ws;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实时语音中转：浏览器 /api/ws/asr ↔ python-ai /ai/asr/stream 双向透传。
 * 浏览器上行二进制 PCM16 帧转发到 Python，Python 下行转写 JSON 转发回浏览器。
 */
@Slf4j
@Component
public class AsrRelayHandler extends AbstractWebSocketHandler {

    private final String upstreamUrl;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Map<String, WebSocket> upstreams = new ConcurrentHashMap<>();

    public AsrRelayHandler(@Value("${app.ai-base-url}") String aiBaseUrl,
                          @Value("${app.ai.internal-token:}") String internalToken) {
        String wsBase = aiBaseUrl.replaceFirst("^http", "ws");
        // websocket scope 不受 HTTP 中间件保护，用 query 参数携带内部密钥做握手校验
        String query = (internalToken == null || internalToken.isBlank())
                ? "" : "?internal_token=" + URLEncoder.encode(internalToken, StandardCharsets.UTF_8);
        this.upstreamUrl = wsBase + "/ai/asr/stream" + query;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        WebSocket.Listener listener = new RelayListener(session);
        WebSocket upstream = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(upstreamUrl), listener).join();
        upstreams.put(session.getId(), upstream);
        log.info("实时语音中转已建立：browser={} -> {}", session.getId(), upstreamUrl);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        WebSocket upstream = upstreams.get(session.getId());
        if (upstream != null) {
            ByteBuffer payload = message.getPayload();
            byte[] bytes = new byte[payload.remaining()];
            payload.get(bytes);
            upstream.sendBinary(ByteBuffer.wrap(bytes), true);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        WebSocket upstream = upstreams.get(session.getId());
        if (upstream != null) {
            upstream.sendText(message.getPayload(), true);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        WebSocket upstream = upstreams.remove(session.getId());
        if (upstream != null) {
            try {
                upstream.sendClose(WebSocket.NORMAL_CLOSURE, "client closed");
            } catch (Exception e) {
                upstream.abort();
            }
        }
        log.info("实时语音中转结束：browser={} status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        WebSocket upstream = upstreams.remove(session.getId());
        if (upstream != null) {
            upstream.abort();
        }
        log.warn("实时语音中转传输错误：browser={} err={}", session.getId(), exception.getMessage());
    }

    /** 把上游（Python）的转写事件回推给浏览器；文本可能分片，按 last 聚合成一条 */
    private static final class RelayListener implements WebSocket.Listener {

        private final WebSocketSession browser;
        private final StringBuilder textBuf = new StringBuilder();

        RelayListener(WebSocketSession browser) {
            this.browser = browser;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket,
                                                               CharSequence data, boolean last) {
            textBuf.append(data);
            if (last) {
                String payload = textBuf.toString();
                textBuf.setLength(0);
                sendToBrowser(new TextMessage(payload));
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onBinary(WebSocket webSocket,
                                                                 ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            sendToBrowser(new BinaryMessage(bytes));
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("实时语音上游错误: {}", error.getMessage());
            sendToBrowser(new TextMessage("{\"type\":\"error\",\"text\":\"语音链路中断\"}"));
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket,
                                                               int statusCode, String reason) {
            try {
                if (browser.isOpen()) {
                    browser.close(new CloseStatus(statusCode, reason));
                }
            } catch (Exception e) {
                log.debug("关闭浏览器会话异常: {}", e.getMessage());
            }
            return null;
        }

        private void sendToBrowser(org.springframework.web.socket.WebSocketMessage<?> msg) {
            try {
                synchronized (browser) {
                    if (browser.isOpen()) {
                        browser.sendMessage(msg);
                    }
                }
            } catch (Exception e) {
                log.warn("回推转写到浏览器失败: {}", e.getMessage());
            }
        }
    }
}
