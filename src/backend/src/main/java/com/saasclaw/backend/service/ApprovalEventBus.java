package com.saasclaw.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saasclaw.backend.vo.ApprovalResultVO;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalEventBus implements MessageListener {

    /** Redis 广播频道 */
    public static final String CHANNEL = "approval:events";

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final ObjectMapper objectMapper;

    /** 本实例持有的活跃 SSE 连接：requestId → SseEmitter */
    private final Map<String, SseEmitter> connections = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        listenerContainer.addMessageListener(this, new ChannelTopic(CHANNEL));
    }

    /** runtime 挂起 SSE 连接并注册到本地 Map */
    public SseEmitter subscribe(String requestId) {
        SseEmitter emitter = new SseEmitter(0L);
        connections.put(requestId, emitter);
        emitter.onCompletion(() -> connections.remove(requestId));
        emitter.onTimeout(() -> connections.remove(requestId));
        return emitter;
    }

    /** 审批完成：把结果广播到 Redis（所有实例都收到，连接的实例负责推送） */
    public void publish(String requestId, ApprovalResultVO payload) {
        try {
            stringRedisTemplate.convertAndSend(CHANNEL, objectMapper.writeValueAsString(payload));
        }  catch (Exception e) {
            log.error("审批结果序列化失败 requestId={}", requestId, e);
        }
    }

    /** 收到 Redis 广播：若本实例持有对应连接则推送，否则忽略 */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            ApprovalResultVO payload = objectMapper.readValue(message.getBody(), ApprovalResultVO.class);
            SseEmitter emitter = connections.remove(payload.getRequestId());
            if (emitter == null) {
                return;
            }
            emitter.send(SseEmitter.event().name("approval").data(payload));
            emitter.complete();
        } catch (Exception e) {
            log.warn("SSE 推送失败", e);
        }
    }

    /** 心跳：每 30s 向所有活跃连接发注释行，防网关/代理掐断空闲连接 */
    @Scheduled(fixedDelay = 30_000)
    public void heartbeat() {
        connections.forEach((requestId, emitter) -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException e) {
                connections.remove(requestId);
                emitter.complete();
            }
        });
    }
}
