package com.saasclaw.gateway.service.impl;

import com.saasclaw.gateway.service.FlowLimitService;
import com.saasclaw.gateway.util.RedisManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class FlowLimitServiceImpl implements FlowLimitService {

    @Autowired
    private RedisManager redisManager;

    @Override
    public Long slidingWindow(String hashTag, long limit, long increment) {
        long now = System.currentTimeMillis();
        long windowIndex = now / 10000;
        double weight = 1.0 - (now % 10000) / 10000.0;

        List<String> keys = new ArrayList<>(6);
        for (int i = 0; i < 6; i++) {
            keys.add("sw:{" + hashTag + "}:" + (windowIndex - i));
        }

        return redisManager.executeRateLimitLua(
                keys,
                String.valueOf(increment),
                String.valueOf(limit),
                String.valueOf(weight)
        );
    }

    @Override
    public boolean isQpsAllowed(Long userId, long qpsLimit) {
        String hashTag = "qps:" + userId;
        Long result = slidingWindow(hashTag, qpsLimit * 60, 1L);
        return result != null && result > 0;
    }

    @Override
    public boolean isRpmAllowed(Long userId, String model, long rpmLimit) {
        String hashTag = "rpm:" + model.toLowerCase() + ":" + userId;
        Long result = slidingWindow(hashTag, rpmLimit, 1L);
        return result != null && result > 0;
    }

    @Override
    public boolean isTpmAllowed(Long userId, String model, long tpmLimit, long estimatedTokens) {
        String hashTag = "tpm:" + model.toLowerCase() + ":" + userId;
        Long result = slidingWindow(hashTag, tpmLimit, estimatedTokens);
        return result != null && result > 0;
    }

    @Override
    public boolean isTpmBurstAllowed(Long userId, String model, long estimatedTokens, long burstLimit, long totalThreshold) {
        String hashTag = "burst:" + model.toLowerCase() + ":" + userId;
        long currentSecond = System.currentTimeMillis() / 1000;
        int subWindowIndex = (int) (currentSecond % 10);
        double weight = (System.currentTimeMillis() % 1000) / 1000.0;

        List<String> keys = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            keys.add("burst:sw:" + i + ":{" + hashTag + "}");
        }

        Long result = redisManager.executeBurstLimitLua(
                keys,
                String.valueOf(estimatedTokens),
                String.valueOf(burstLimit),
                String.valueOf(weight),
                String.valueOf(totalThreshold),
                String.valueOf(subWindowIndex),
                String.valueOf(20)
        );
        return result != null && result > 0;
    }
}
