package com.saasclaw.gateway.filter;

import com.saasclaw.gateway.service.RouteResolverService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * 推理链路动态路由：把请求转发目标改写为请求用户所属的 Claw Pod Service。
 *
 * 只挂在 claw-route（/v1/**）上。顺序取 10001：晚于 RouteToRequestUrlFilter(10000)，
 * 早于 NettyRoutingFilter(MAX)，可安全覆盖 GATEWAY_REQUEST_URL_ATTR。
 */
@Slf4j
@Component
public class ClawRoutingGatewayFilter implements GatewayFilter, Ordered {

    @Autowired
    private RouteResolverService routeResolverService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // CORS 预检直接透传（无身份，无需路由解析）
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequest().getMethod().name())) {
            return chain.filter(exchange);
        }

        Long userId = exchange.getAttribute("userId");
        if (userId == null) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "unauthorized");
        }

        Object modelAttr = exchange.getAttribute("modelName");
        String alias = modelAttr == null ? null : modelAttr.toString();

        URI requestUri = exchange.getRequest().getURI();
        String path = requestUri.getRawPath() != null ? requestUri.getRawPath() : "";
        String query = requestUri.getRawQuery();
        String querySuffix = query == null ? "" : "?" + query;

        // 注意：不可用 resolveTarget(...).flatMap(chain.filter).switchIfEmpty(reject) 写法！
        // chain.filter 返回 Mono<Void>（只 onComplete、不发射元素），switchIfEmpty 会把正常链
        // 完成误判为空流再触发 reject，此刻响应可能已提交 headers 只读，
        // 抛 UnsupportedOperationException 导致 chunked 响应硬断。故用 defaultIfEmpty + flatMap 内分支。
        return routeResolverService.resolveTarget(userId, alias)
                .defaultIfEmpty("")
                .flatMap(base -> {
                    if (base.isEmpty()) {
                        return reject(
                                exchange,
                                HttpStatus.BAD_REQUEST,
                                alias == null ? "claw not found" : "agent " + alias + " not found");
                    }
                    try {
                        URI target = URI.create(base + path + querySuffix);
                        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, target);
                        log.debug("claw route: userId={} alias={} -> {}", userId, alias, target);
                        return chain.filter(exchange);
                    } catch (IllegalArgumentException e) {
                        log.error("illegal claw target uri", e);
                        return reject(exchange, HttpStatus.BAD_GATEWAY, "bad target");
                    }
                });
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        String body = "{\"code\":" + status.value() + ",\"message\":\"" + message + "\"}";
        var buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes());
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return 10001;
    }
}