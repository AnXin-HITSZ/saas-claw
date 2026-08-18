package com.saasclaw.gateway.config;

import com.saasclaw.gateway.filter.ClawRoutingGatewayFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 路由表（Java DSL 替代 YAML 单条 catch-all）：
 *
 * | 路径                     | 目标             | 鉴权       | 说明                        |
 * |--------------------------|------------------|------------|-----------------------------|
 * | /auth/login、/auth/register | backend        | 白名单放行  | AuthFilter 直接透传         |
 * | /api/**                  | backend          | AuthFilter  | 管理接口                    |
 * | /v1/**                   | Claw Pod（动态） | AuthFilter  | 推理/会话链路，ClawRouting 改写目标 |
 *
 * 未匹配路径由网关返回 404，不再无脑 catch-all 转发。
 */
@Configuration
public class GatewayRouteConfig {

    @Value("${app.backend.uri:http://backend:8080}")
    private String backendUri;

    /** claw-route 占位地址，真实目标由 ClawRoutingGatewayFilter 覆盖 */
    @Value("${app.claw.placeholder-uri:http://localhost:8000}")
    private String clawPlaceholderUri;

    @Autowired
    private ClawRoutingGatewayFilter clawRoutingGatewayFilter;

    @Bean
    public RouteLocator gatewayRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("backend-auth", r -> r
                        .path("/api/auth/login", "/api/auth/register")
                        .uri(backendUri))
                .route("backend-api", r -> r
                        .path("/api/**")
                        .uri(backendUri))
                .route("claw-route", r -> r
                        .path("/v1/**")
                        .filters(f -> f.filter(clawRoutingGatewayFilter))
                        .uri(clawPlaceholderUri))
                .build();
    }
}