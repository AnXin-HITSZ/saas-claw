package com.saasclaw.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Slf4j
@Component
public class RequestIdFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 22);
        long startTime = System.currentTimeMillis();

        exchange.getAttributes().put("requestId", requestId);
        exchange.getAttributes().put("startTime", startTime);

        log.info("request start: requestId={}, path={}, method={}",
                requestId,
                exchange.getRequest().getPath().value(),
                exchange.getRequest().getMethod().name());

        String clientIp = getClientIp(exchange.getRequest());

        String productCode = exchange.getRequest().getHeaders().getFirst("Product-Code");
        String deviceSn = exchange.getRequest().getHeaders().getFirst("Device-Sn");
        if (productCode != null && !productCode.isEmpty()) {
            exchange.getAttributes().put("productCode", productCode);
        }
        if (deviceSn != null && !deviceSn.isEmpty()) {
            exchange.getAttributes().put("deviceSn", deviceSn);
        }

        ServerHttpRequest modifiedRequest = exchange.getRequest().mutate()
                .header("X-Forwarded-For", clientIp)
                .header("X-Request-Id", requestId)
                .build();

        ServerWebExchange modifiedExchange = exchange.mutate()
                .request(modifiedRequest)
                .build();

        return chain.filter(modifiedExchange);
    }

    private String getClientIp(ServerHttpRequest request) {
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }

        if (request.getRemoteAddress() != null) {
            return request.getRemoteAddress().getAddress().getHostAddress();
        }

        return "unknown";
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
