package com.saasclaw.gateway.filter;

import com.saasclaw.gateway.service.FlowLimitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    @Autowired
    private FlowLimitService flowLimitService;

    @Value("${app.limiter.maxUserQps:50}")
    private long maxUserQps;

    @Value("${app.limiter.rpmLimit:500}")
    private long rpmLimit;

    @Value("${app.limiter.tpmLimit:2000000}")
    private long tpmLimit;

    @Value("${app.limiter.burstLimit:500000}")
    private long burstLimit;

    @Value("${app.limiter.burstTotalThreshold:350000}")
    private long burstTotalThreshold;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 限流针对推理链路(/v1/**，按 modelName 计 QPS/RPM/TPM)；
        // 非 /v1 的认证/管理请求没有 modelName attribute，直接放行，否则会 NPE。
        if (!exchange.getRequest().getPath().value().startsWith("/v1/")) {
            return chain.filter(exchange);
        }

        // 推理链路必经 AuthFilter(-95) 注入 userId；null 时安全兜底放行（理论不会发生）
        Long userId = (Long) exchange.getAttributes().get("userId");
        if (userId == null) {
            log.warn("userId missing in /v1 request, skip rate limit");
            return chain.filter(exchange);
        }

        if (!flowLimitService.isQpsAllowed(userId, maxUserQps)) {
            return reject(exchange, userId, "QPS limit exceeded");
        }

        String modelName = exchange.getAttributes().get("modelName").toString();
        if (!flowLimitService.isRpmAllowed(userId, modelName, rpmLimit)) {
            return reject(exchange, userId, "RPM limit exceeded");
        }

        long estimatedTokens = Long.parseLong(exchange.getAttributes().get("estimatedTokens").toString());
        if (!flowLimitService.isTpmAllowed(userId, modelName, tpmLimit, estimatedTokens)) {
            return reject(exchange, userId, "TPM limit exceeded");
        }

        if (!flowLimitService.isTpmBurstAllowed(userId, modelName, estimatedTokens, burstLimit, burstTotalThreshold)) {
            return reject(exchange, userId, "TPM Burst limit exceeded");
        }

        return chain.filter(exchange);
    }

    public Mono<Void> getMono(ServerWebExchange exchange, HttpStatus status, String body) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        var buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes());
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    public Mono<Void> reject(ServerWebExchange exchange, Long userId, String reason) {
        log.warn("{} for userId={}", reason, userId);
        return getMono(
                exchange,
                HttpStatus.TOO_MANY_REQUESTS,
                "{\"status\":429,\"message\":\"Rate limit exceeded\"}"
        );
    }

    @Override
    public int getOrder() {
        return -80;
    }
}
