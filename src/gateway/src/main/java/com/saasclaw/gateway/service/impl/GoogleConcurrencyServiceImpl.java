package com.saasclaw.gateway.service.impl;

import com.saasclaw.gateway.service.GoogleConcurrencyService;
import com.saasclaw.gateway.util.RedisManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Slf4j
@Service
public class GoogleConcurrencyServiceImpl implements GoogleConcurrencyService {

    @Autowired
    private RedisManager redisManager;

    @Value("${app.concurrency.max-concurrent:5}")
    private int maxConcurrent;

    @Value("${app.concurrency.key-timeout-seconds:300}")
    private int keyTimeoutSeconds;

    @Override
    public boolean tryAcquire(Long userId, String model) {
        String key = buildKey(userId, model);
        try {
            Long result = redisManager.executeAcquireLua(
                    Collections.singletonList(key),
                    String.valueOf(maxConcurrent),
                    String.valueOf(keyTimeoutSeconds)
            );
            return result != null && result == 1L;
        } catch (Exception e) {
            log.error("Google concurrency acquire failed, fail open", e);
            return true;
        }

    }

    @Override
    public void release(Long userId, String model) {
        String key = buildKey(userId, model);
        try {
            redisManager.executeReleaseLua(
                    Collections.singletonList(key)
            );
        } catch (Exception e) {
            log.warn("Google concurrency release failed", e);
        }
    }

    @Override
    public String buildKey(Long userId, String model) {
        String modelLower = model != null ? model.toLowerCase() : "";
        return "concurrency:" + userId + ":" + modelLower;
    }
}
