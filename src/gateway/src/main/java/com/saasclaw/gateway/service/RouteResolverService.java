package com.saasclaw.gateway.service;

import reactor.core.publisher.Mono;

/**
 * 推理链路目标 Claw Pod 地址解析：agent.claw_id + claw.namespace → 集群内 Service 地址。
 */
public interface RouteResolverService {

    /**
     * 根据用户与 alias 解析目标 Claw Pod 基址（如 http://claw-3.claw-3.svc.cluster.local:8000）。
     *
     * @param alias 请求 model 字段（agent.alias）；null 时回落用户默认 Claw（会话管理类接口）
     * @return 解析失败（alias 不存在 / Claw 被禁用）返回 empty
     */
    Mono<String> resolveTarget(Long userId, String alias);
}