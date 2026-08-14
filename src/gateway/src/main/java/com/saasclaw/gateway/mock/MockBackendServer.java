package com.saasclaw.gateway.mock;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class MockBackendServer {

    private HttpServer server;

    @PostConstruct
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(8859), 0);
        server.createContext("/", this::handle);
        server.start();
        log.info("Mock backend started on port 8859");
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        // 读取请求体
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        log.info("========== Mock Backend Received ==========");
        log.info("Method: {}", exchange.getRequestMethod());
        log.info("URI: {}", exchange.getRequestURI());
        exchange.getRequestHeaders().forEach((key, values) ->
                log.info("Header - {}: {}", key, values));
        log.info("Body: {}", body);
        log.info("===========================================");

        // 返回 200
        String response = "{\"status\":\"ok\"}";
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length());
        exchange.getResponseBody().write(response.getBytes());
        exchange.close();
    }
}
