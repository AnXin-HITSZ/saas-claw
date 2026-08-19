package com.saasclaw.gateway.filter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.saasclaw.gateway.config.AuthProperties;
import com.saasclaw.gateway.entity.Authorization;
import com.saasclaw.gateway.mapper.AuthorizationMapper;
import com.saasclaw.gateway.util.ApiKeyUtil;
import com.saasclaw.gateway.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 认证过滤器：双通道解析 userId（JWT 人通道 / API Key 程序通道），
 * 成功后注入 X-User-Id header 并放入 exchange attribute "userId"（限流/并发/路由消费）。
 *
 * 顺序：RequestIdFilter(-100) → AuthFilter(-95) → BodyRewriteFilter(-90) → ...
 */
@Slf4j
@Component
public class AuthFilter implements GlobalFilter, Ordered {

    private static final String USER_ID_ATTR = "userId";
    private static final String X_USER_ID_HEADER = "X-User-Id";

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private AuthorizationMapper authorizationMapper;

    @Autowired
    private AuthProperties authProperties;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String requestId = String.valueOf(exchange.getAttributes().get("requestId"));

        // OPTIONS 预检直接放行（与 backend 行为一致）
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequest().getMethod().name())) {
            return chain.filter(exchange);
        }

        // 白名单（登录 / 注册）直接放行透传 backend
        if (isWhitelisted(path)) {
            return chain.filter(exchange);
        }

        String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (!StringUtils.hasText(auth) || !auth.startsWith("Bearer ")) {
            log.warn("requestId={} path={} 缺少 Bearer token", requestId, path);
            return reject401(exchange, "未认证：缺少 Bearer token");
        }
        String token = auth.substring(7).trim();

        Mono<Long> userMono;
        if (token.startsWith("sk-")) {
            // API Key 通道：SHA-256 等值查 authorization 表（JDBC 阻塞，隔离到 boundedElastic）
            String hash = ApiKeyUtil.hash(token);
            userMono = Mono.fromCallable(() -> {
                Authorization authz = authorizationMapper.selectOne(
                        new LambdaQueryWrapper<Authorization>()
                                .eq(Authorization::getApiKey, hash)
                                .eq(Authorization::getStatus, 1));
                return authz == null ? null : authz.getUserId();
            }).subscribeOn(Schedulers.boundedElastic());
        } else {
            // JWT 通道：HS256 解析
            Long userId = jwtUtil.parseUserId(token);
            userMono = userId == null ? Mono.empty() : Mono.just(userId);
        }

        // 注意：不可用 userMono.flatMap(authorize).switchIfEmpty(reject401) 这种写法！
        // authorize 返回 chain.filter() 即 Mono<Void>（只 onComplete、不发射元素），
        // switchIfEmpty 会把"正常链完成"误判为空流再触发 reject401，而此时响应已提交、
        // headers 只读，抛 UnsupportedOperationException 导致 chunked 响应被硬断（浏览器丢响应体）。
        return userMono
                .defaultIfEmpty(INVALID_USER_ID)
                .flatMap(userId -> {
                    if (userId == null || userId < 0) {
                        return reject401(exchange, "token 无效或已过期");
                    }
                    return authorize(exchange, chain, userId, requestId, path);
                });
    }

    /** 兜底占位：-1 不是合法 userId，用于统一 defaultIfEmpty 分支 */
    private static final long INVALID_USER_ID = -1L;

    private Mono<Void> authorize(ServerWebExchange exchange, GatewayFilterChain chain,
                                 Long userId, String requestId, String path) {
        exchange.getAttributes().put(USER_ID_ATTR, userId);
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header(X_USER_ID_HEADER, String.valueOf(userId))
                .build();
        log.info("requestId={} path={} auth pass userId={}", requestId, path, userId);
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private boolean isWhitelisted(String path) {
        return authProperties.getWhitelist().stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private Mono<Void> reject401(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().add("Content-Type", "application/json;charset=UTF-8");
        String body = "{\"code\":401,\"message\":\"" + message + "\"}";
        var buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes());
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -95;
    }
}