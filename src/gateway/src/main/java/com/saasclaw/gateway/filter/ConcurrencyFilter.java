package com.saasclaw.gateway.filter;

import com.saasclaw.gateway.service.GoogleConcurrencyService;
import com.saasclaw.gateway.service.DeviceConcurrencyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class ConcurrencyFilter implements GlobalFilter, Ordered {

    @Autowired
    private GoogleConcurrencyService googleConcurrencyService;
    @Autowired
    private DeviceConcurrencyService deviceConcurrencyService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 并发控制针对推理链路(/v1/**)；非 /v1 请求没有 modelName attribute，直接放行，否则 NPE。
        if (!exchange.getRequest().getPath().value().startsWith("/v1/")) {
            return chain.filter(exchange);
        }

        // 推理链路必经 AuthFilter(-95) 注入 userId；null 时安全兜底放行（理论不会发生）
        Long userId = (Long) exchange.getAttributes().get("userId");
        if (userId == null) {
            log.warn("userId missing in /v1 request, skip concurrency control");
            return chain.filter(exchange);
        }

        // modelName 仅由 BodyRewriteFilter(-90) 对 POST /v1 注入；缺失 = 非推理请求（GET /v1/conversations
        // 等会话管理接口），并发控制不适用，直接放行，否则 get("modelName").toString() 会 NPE 500。
        Object modelAttr = exchange.getAttributes().get("modelName");
        if (modelAttr == null) {
            return chain.filter(exchange);
        }
        String modelName = modelAttr.toString();
        String requestId = exchange.getAttributes().get("requestId").toString();

        String productCode = (String) exchange.getAttributes().get("productCode");
        String deviceSn = (String) exchange.getAttributes().get("deviceSn");
        String deviceKey = null;
        if (productCode != null && deviceSn != null) {
            deviceKey = deviceConcurrencyService.buildDeviceKey(productCode, deviceSn);
            if (!deviceConcurrencyService.tryAcquire(productCode, deviceSn, requestId)) {
                log.warn("Device concurrency limit exceeded for userId={}, model={}", userId, modelName);
                return getMono(
                        exchange,
                        HttpStatus.TOO_MANY_REQUESTS,
                        "{\"status\":429,\"message\":\"Device is busy\"}"
                );
            }
            exchange.getAttributes().put("deviceKey", deviceKey);
        }

        boolean acquired = googleConcurrencyService.tryAcquire(userId, modelName);
        if (!acquired) {
            if (deviceKey != null) {
                deviceConcurrencyService.release(deviceKey, requestId);
            }
            log.warn("Google concurrency limit exceeded for userId={}, model={}", userId, modelName);
            return getMono(
                    exchange,
                    HttpStatus.TOO_MANY_REQUESTS,
                    "{\"status\":429,\"message\":\"Google concurrency limit exceeded\"}"
            );
        }
        exchange.getAttributes().put("concurrencyKey", googleConcurrencyService.buildKey(userId, modelName));

        return chain.filter(exchange).doFinally(signal -> {
            if (exchange.getAttributes().get("deviceKey") != null) {
                deviceConcurrencyService.release(
                        (String) exchange.getAttributes().get("deviceKey"),
                        requestId
                );
            }
            if (exchange.getAttributes().get("concurrencyKey") != null) {
                googleConcurrencyService.release(userId, modelName);
            }
        });
    }

    private Mono<Void> getMono(ServerWebExchange exchange, HttpStatus status, String body) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        var buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes());
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -70;
    }
}
