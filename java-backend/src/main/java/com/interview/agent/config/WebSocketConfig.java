package com.interview.agent.config;

import com.interview.agent.ws.AsrRelayHandler;
import com.interview.agent.ws.WsAuthHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/** 实时语音 WebSocket 端点注册：/api/ws/asr（握手鉴权 + 中转 handler） */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final AsrRelayHandler asrRelayHandler;
    private final WsAuthHandshakeInterceptor wsAuthHandshakeInterceptor;

    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(asrRelayHandler, "/api/ws/asr")
                .addInterceptors(wsAuthHandshakeInterceptor)
                .setAllowedOriginPatterns(allowedOrigins.split(","));
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        // PCM16 帧较小，256KB 缓冲足够并留出余量
        container.setMaxBinaryMessageBufferSize(256 * 1024);
        container.setMaxTextMessageBufferSize(64 * 1024);
        container.setMaxSessionIdleTimeout(120_000L);
        return container;
    }
}
