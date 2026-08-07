package com.minigateway.filter;

import com.minigateway.service.GoogleConcurrencyService;
import com.minigateway.service.DeviceConcurrencyService;
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
        Long orgId = 1L;
        String modelName = exchange.getAttributes().get("modelName").toString();
        String requestId = exchange.getAttributes().get("requestId").toString();

        String productCode = (String) exchange.getAttributes().get("productCode");
        String deviceSn = (String) exchange.getAttributes().get("deviceSn");
        String deviceKey = null;
        if (productCode != null && deviceSn != null) {
            deviceKey = deviceConcurrencyService.buildDeviceKey(productCode, deviceSn);
            if (!deviceConcurrencyService.tryAcquire(productCode, deviceSn, requestId)) {
                log.warn("Device concurrency limit exceeded for orgId={}, model={}", orgId, modelName);
                return getMono(
                        exchange,
                        HttpStatus.TOO_MANY_REQUESTS,
                        "{\"status\":429,\"message\":\"Device is busy\"}"
                );
            }
            exchange.getAttributes().put("deviceKey", deviceKey);
        }

        boolean acquired = googleConcurrencyService.tryAcquire(orgId, modelName);
        if (!acquired) {
            if (deviceKey != null) {
                deviceConcurrencyService.release(deviceKey, requestId);
            }
            log.warn("Google concurrency limit exceeded for orgId={}, model={}", orgId, modelName);
            return getMono(
                    exchange,
                    HttpStatus.TOO_MANY_REQUESTS,
                    "{\"status\":429,\"message\":\"Google concurrency limit exceeded\"}"
            );
        }
        exchange.getAttributes().put("concurrencyKey", googleConcurrencyService.buildKey(orgId, modelName));

        return chain.filter(exchange).doFinally(signal -> {
            if (exchange.getAttributes().get("deviceKey") != null) {
                deviceConcurrencyService.release(
                        (String) exchange.getAttributes().get("deviceKey"),
                        requestId
                );
            }
            if (exchange.getAttributes().get("concurrencyKey") != null) {
                googleConcurrencyService.release(orgId, modelName);
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
