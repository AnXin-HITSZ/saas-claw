package com.saasclaw.gateway.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.saasclaw.gateway.entity.Agent;
import com.saasclaw.gateway.entity.Claw;
import com.saasclaw.gateway.mapper.AgentMapper;
import com.saasclaw.gateway.mapper.ClawMapper;
import com.saasclaw.gateway.service.RouteResolverService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 目标 Claw 解析 + 本地缓存。
 *
 * 缓存策略：与模型白名单同节奏 —— 每 3 分钟全量重建 (userId:alias) → Claw 基址 映射，
 * like 默认值、缓存读写都在调用线程（filter 已切 boundedElastic），命中即纯内存。
 * agent/claw 变更最多 3 分钟后路由生效。
 */
@Slf4j
@Service
public class RouteResolverServiceImpl implements RouteResolverService {

    /** (userId:alias) → Claw 基址，例如 http://claw-3.claw-3.svc.cluster.local:8000 */
    private final Map<String, String> targetCache = new ConcurrentHashMap<>();

    @Autowired
    private AgentMapper agentMapper;

    @Autowired
    private ClawMapper clawMapper;

    /** 集群内 Service 后缀，K8s 默认 .svc.cluster.local */
    @Value("${app.claw.service-suffix:.svc.cluster.local}")
    private String serviceSuffix;

    /** Claw Pod 服务端口（runtime 监听端口） */
    @Value("${app.claw.port:8000}")
    private int clawPort;

    /** 本地联调直连地址（如 http://localhost:8000），配置后忽略缓存/查库 */
    @Value("${app.claw.local-target:}")
    private String localTarget;

    @Override
    public Mono<String> resolveTarget(Long userId, String alias) {
        return Mono.fromCallable(() -> resolveBlocking(userId, alias))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private String resolveBlocking(Long userId, String alias) {
        if (StringUtils.hasText(localTarget)) {
            return localTarget;
        }
        String cached = targetCache.get(cacheKey(userId, alias));
        if (cached != null) {
            return cached;
        }
        return resolveAndCache(userId, alias);
    }

    private String resolveAndCache(Long userId, String alias) {
        Claw claw;
        if (alias != null) {
            Agent agent = agentMapper.selectOne(
                    new LambdaQueryWrapper<Agent>()
                            .eq(Agent::getUserId, userId)
                            .eq(Agent::getAlias, alias)
                            .eq(Agent::getStatus, 1));
            if (agent == null || agent.getClawId() == null) {
                log.warn("route resolve: agent not found userId={} alias={}", userId, alias);
                return null;
            }
            claw = clawMapper.selectById(agent.getClawId());
        } else {
            // 会话管理类接口无 alias，回落用户默认 Claw（启用状态、id 最小）
            claw = clawMapper.selectOne(
                    new LambdaQueryWrapper<Claw>()
                            .eq(Claw::getUserId, userId)
                            .eq(Claw::getStatus, 1)
                            .orderByAsc(Claw::getId)
                            .last("LIMIT 1"));
        }
        if (claw == null || !Integer.valueOf(1).equals(claw.getStatus())) {
            log.warn("route resolve: claw not found userId={} alias={}", userId, alias);
            return null;
        }
        String base = buildBaseOf(claw);
        targetCache.put(cacheKey(userId, alias), base);
        return base;
    }

    private String buildBaseOf(Claw claw) {
        String ns = claw.getNamespace();
        return "http://" + ns + "." + ns + serviceSuffix + ":" + clawPort;
    }

    private static String cacheKey(Long userId, String alias) {
        return userId + ":" + alias;
    }

    /** 每 3 分钟全量重建缓存（增量场景下 agent=0 条也允许：清空即可） */
    @Scheduled(cron = "0 0/3 * * * ?")
    public void refreshCache() {
        List<Claw> claws = clawMapper.selectList(
                new LambdaQueryWrapper<Claw>().eq(Claw::getStatus, 1));
        Map<Long, Claw> clawById = new HashMap<>();
        claws.forEach(c -> clawById.put(c.getId(), c));

        List<Agent> agents = agentMapper.selectList(
                new LambdaQueryWrapper<Agent>().eq(Agent::getStatus, 1));

        Map<String, String> fresh = new HashMap<>();
        for (Agent a : agents) {
            if (a.getUserId() == null || !StringUtils.hasText(a.getAlias()) || a.getClawId() == null) {
                continue;
            }
            Claw c = clawById.get(a.getClawId());
            if (c == null) {
                continue;
            }
            fresh.put(cacheKey(a.getUserId(), a.getAlias()), buildBaseOf(c));
        }
        targetCache.clear();
        targetCache.putAll(fresh);
        log.info("route cache refreshed, size={}", fresh.size());
    }
}