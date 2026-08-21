package com.saasclaw.backend.service.impl;

import com.saasclaw.backend.config.RestClientConfig;
import com.sun.net.httpserver.HttpServer;
import lombok.Data;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 回归测试：RestClientConfig 的 http11RestClientCustomizer 是否生效。
 *
 * 背景（审批回调 422 空 body）：Boot 自动配置的 RestClient.Builder 退化为 JDK HttpClient，
 * 默认 HTTP/2 会对明文 http 附带 Upgrade: h2c 升级头，uvicorn/h11 收到后丢弃 body →
 * FastAPI 422 body required。本测试断言自动配置 builder 发出的请求：
 *   1) 不再带 Upgrade: h2c / Connection: Upgrade 升级头（修复点）；
 *   2) body 非空且为 SNAKE_CASE 契约格式。
 */
@SpringBootTest(
        classes = RestClientHttp11ConfigTest.MinimalConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.jackson.property-naming-strategy=SNAKE_CASE")
@ImportAutoConfiguration({
        JacksonAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        RestClientAutoConfiguration.class
})
@Import(RestClientConfig.class)
class RestClientHttp11ConfigTest {

    @Configuration
    static class MinimalConfig {
    }

    @Autowired
    private RestClient.Builder restClientBuilder;

    @Data
    private static class CallbackBody {
        private String requestId;
        private Object result;

        CallbackBody(String requestId, Object result) {
            this.requestId = requestId;
            this.result = result;
        }
    }

    @Test
    void autoConfiguredBuilder_usesHttp11NoH2cUpgrade_andSendsBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> upgradeHeader = new AtomicReference<>("ABSENT");
        AtomicReference<String> connectionHeader = new AtomicReference<>("ABSENT");
        AtomicReference<byte[]> received = new AtomicReference<>();
        server.createContext("/approvals/callback", exchange -> {
            upgradeHeader.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            connectionHeader.set(exchange.getRequestHeaders().getFirst("Connection"));
            received.set(exchange.getRequestBody().readAllBytes());
            byte[] ok = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
        });
        server.start();
        try {
            Map<String, Object> decision = new LinkedHashMap<>();
            decision.put("decision", "approve");
            decision.put("reason", "");
            CallbackBody body = new CallbackBody("approval:http11_repro", decision);

            restClientBuilder.build()
                    .post().uri("http://127.0.0.1:" + server.getAddress().getPort() + "/approvals/callback")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();

            byte[] raw = received.get();
            System.out.println("=== HTTP/1.1 修复验证: Upgrade=" + upgradeHeader.get()
                    + " Connection=" + connectionHeader.get()
                    + " bodyBytes=" + (raw == null ? "NULL" : raw.length) + " ===");
            if (upgradeHeader.get() != null) {
                throw new AssertionError("请求仍带 Upgrade: " + upgradeHeader.get() + " → h2c 升级会丢 body，修复未生效");
            }
            if (raw == null || raw.length == 0) {
                throw new AssertionError("body 为空");
            }
        } finally {
            server.stop(0);
        }
    }
}
