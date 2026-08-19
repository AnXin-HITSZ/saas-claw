package com.saasclaw.gateway.filter;

import com.saasclaw.gateway.rewrite.BodyRewriteFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class BodyRewriteFilter implements GlobalFilter, Ordered {

    @Autowired
    private ModifyRequestBodyGatewayFilterFactory modifyRequestBodyFilter;

    @Autowired
    private BodyRewriteFunction bodyRewriteFunction;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1) 仅 /v1/** 推理链路需要载荷改写（校验/注入 model、requestId 等）。
        //    其它路径（/api/auth/register 等）直接放行：若不加判断，请求体无 model 字段
        //    都会被重写函数拒绝，导致注册/登录等全部 400 "model is required"。
        // 2) 且仅 POST 有请求体可改写：GET /v1/conversations、/v1/conversations/{id}/messages、
        //    /v1/conversations/{id}/trace 等会话管理接口无 body，ModifyRequestBody 会以 null
        //    body 触发重写函数导致 NPE → 网关 500。这些接口根本不需要注入模型字段，直接透传。
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/v1/") || !HttpMethod.POST.equals(exchange.getRequest().getMethod())) {
            return chain.filter(exchange);
        }
        return modifyRequestBodyFilter
                .apply(
                        new ModifyRequestBodyGatewayFilterFactory.Config()
                        .setRewriteFunction(byte[].class, byte[].class, bodyRewriteFunction))
                .filter(exchange, chain);
    }

    @Override
    public int getOrder() {
        return -90;
    }
}
