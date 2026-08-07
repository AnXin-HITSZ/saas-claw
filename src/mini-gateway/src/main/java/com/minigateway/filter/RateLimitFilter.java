package com.minigateway.filter;

import com.minigateway.service.FlowLimitService;
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

    @Value("${app.limiter.maxOrgQps:50}")
    private long maxOrgQps;

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
        Long orgId  = 1L;

        if (!flowLimitService.isQpsAllowed(orgId, maxOrgQps)) {
            return reject(exchange, orgId, "QPS limit exceeded");
        }

        String modelName = exchange.getAttributes().get("modelName").toString();
        if (!flowLimitService.isRpmAllowed(orgId, modelName, rpmLimit)) {
            return reject(exchange, orgId, "RPM limit exceeded");
        }

        long estimatedTokens = Long.parseLong(exchange.getAttributes().get("estimatedTokens").toString());
        if (!flowLimitService.isTpmAllowed(orgId, modelName, tpmLimit, estimatedTokens)) {
            return reject(exchange, orgId, "TPM limit exceeded");
        }

        if (!flowLimitService.isTpmBurstAllowed(orgId, modelName, estimatedTokens, burstLimit, burstTotalThreshold)) {
            return reject(exchange, orgId, "TPM Burst limit exceeded");
        }

        return chain.filter(exchange);
    }

    public Mono<Void> getMono(ServerWebExchange exchange, HttpStatus status, String body) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        var buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes());
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    public Mono<Void> reject(ServerWebExchange exchange, Long orgId, String reason) {
        log.warn("{} for orgId={}", reason, orgId);
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
