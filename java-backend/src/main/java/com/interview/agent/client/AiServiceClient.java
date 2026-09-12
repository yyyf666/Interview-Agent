package com.interview.agent.client;

import com.interview.agent.config.TraceIdFilter;
import com.interview.agent.exception.AiTimeoutException;
import com.interview.agent.exception.AiUpstreamException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Python AI 能力层 HTTP 客户端。
 * 设计约束：Java 层不直接调用 LLM API，所有大模型能力统一由 AI 层封装。
 * 安全约束：每个请求携带 X-Internal-Token（共享密钥），AI 层据此拒绝非内网调用。
 * 异常语义：上游 4xx/5xx → AiUpstreamException(502)；连接/读超时 → AiTimeoutException(504)。
 */
@Slf4j
@Component
public class AiServiceClient {

    /** 与 python-ai 的 internal_token_middleware 约定的头名 */
    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final RestClient restClient;
    private final String internalToken;

    public AiServiceClient(@Value("${app.ai-base-url}") String baseUrl,
                           @Value("${app.ai.timeout-seconds:120}") long timeoutSeconds,
                           @Value("${app.ai.internal-token:}") String internalToken) {
        this.internalToken = internalToken;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        // LLM 调用耗时较长，读超时放宽
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> postJson(String path, Object body) {
        log.info("AI-> POST {}", path);
        return call(path, () -> restClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(this::applyHeaders)
                .body(body)
                .retrieve()
                .body(Map.class));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> putJson(String path, Object body) {
        log.info("AI-> PUT {}", path);
        return call(path, () -> restClient.put()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(this::applyHeaders)
                .body(body)
                .retrieve()
                .body(Map.class));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> postMultipart(String path, String fieldName, String fileName, byte[] content) {
        log.info("AI-> POST(multipart) {} file={}", path, fileName);
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part(fieldName, new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);
        return call(path, () -> restClient.post()
                .uri(path)
                .headers(this::applyHeaders)
                .body(builder.build())
                .retrieve()
                .body(Map.class));
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> get(String path) {
        return call(path, () -> restClient.get().uri(path)
                .headers(this::applyHeaders)
                .retrieve().body(Map.class));
    }

    /**
     * 流式 POST（R-16）：逐行读取上游 SSE（text/event-stream），每读到一行回调 onLine。
     * 回调内抛出的 RuntimeException 会中断读取（用于客户端断开时释放上游连接）。
     */
    public void streamPost(String path, Object body, Consumer<String> onLine) {
        log.info("AI-> POST(stream) {}", path);
        try {
            restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .headers(this::applyHeaders)
                    .body(body)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new AiUpstreamException("AI 能力层返回异常("
                                    + response.getStatusCode().value() + ")");
                        }
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                onLine.accept(line);
                            }
                        }
                        return Boolean.TRUE;
                    });
        } catch (RestClientResponseException e) {
            log.error("AI 能力层流式返回异常 {} status={} body={}", path, e.getStatusCode().value(),
                    abbreviate(e.getResponseBodyAsString()));
            throw new AiUpstreamException("AI 能力层返回异常(" + e.getStatusCode().value() + ")", e);
        } catch (ResourceAccessException e) {
            if (hasTimeoutCause(e)) {
                throw new AiTimeoutException("AI 能力层流式调用超时: " + path, e);
            }
            throw new AiUpstreamException("AI 能力层不可达: " + e.getMessage(), e);
        } catch (AiUpstreamException e) {
            throw e;
        } catch (RestClientException e) {
            throw new AiUpstreamException("AI 能力层调用失败: " + e.getMessage(), e);
        }
    }

    /** 统一注入 TraceId 与内部密钥 */
    private void applyHeaders(HttpHeaders headers) {
        headers.set(TraceIdFilter.TRACE_HEADER, currentTraceId());
        if (internalToken != null && !internalToken.isBlank()) {
            headers.set(INTERNAL_TOKEN_HEADER, internalToken);
        }
    }

    /** 统一异常映射：让上层与前端拿到 502/504，而不是裸 500 */
    private Map<String, Object> call(String path, Supplier<Map<String, Object>> action) {
        try {
            return action.get();
        } catch (RestClientResponseException e) {
            log.error("AI 能力层返回异常 {} status={} body={}", path, e.getStatusCode().value(),
                    abbreviate(e.getResponseBodyAsString()));
            throw new AiUpstreamException("AI 能力层返回异常(" + e.getStatusCode().value() + ")", e);
        } catch (ResourceAccessException e) {
            if (hasTimeoutCause(e)) {
                throw new AiTimeoutException("AI 能力层调用超时: " + path, e);
            }
            throw new AiUpstreamException("AI 能力层不可达: " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new AiUpstreamException("AI 能力层调用失败: " + e.getMessage(), e);
        }
    }

    private static boolean hasTimeoutCause(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    private static String currentTraceId() {
        String tid = MDC.get(TraceIdFilter.MDC_KEY);
        return tid == null ? "" : tid;
    }
}
