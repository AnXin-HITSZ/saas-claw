package com.saasclaw.gateway.filter;

import com.saasclaw.gateway.rewrite.BodyRewriteFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.core.Ordered;
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
