package com.saasclaw.gateway.service.impl;

import com.saasclaw.gateway.service.DeviceConcurrencyService;
import com.saasclaw.gateway.util.RedisManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class DeviceConcurrencyServiceImpl implements DeviceConcurrencyService {

    @Autowired
    private RedisManager redisManager;

    @Value("${app.device-concurrency.lock-timeout-seconds:300}")
    private int lockTimeoutSeconds;

    @Override
    public boolean tryAcquire(String productCode, String deviceSn, String requestId) {
        if (productCode == null || deviceSn == null || requestId == null) {
            return true;
        }
        String deviceKey = buildDeviceKey(productCode, deviceSn);
        try {
            Boolean acquired = redisManager.setIfAbsent(deviceKey, requestId, lockTimeoutSeconds, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            return true;
        }
    }

    @Override
    public void release(String deviceKey, String requestId) {
        try {
            redisManager.executeCompareAndDeleteLua(
                    Collections.singletonList(deviceKey),
                    requestId
            );
        } catch (Exception e) {
            log.warn("Device concurrency release failed", e);
        }
    }

    @Override
    public String buildDeviceKey(String productCode, String deviceSn) {
        return "device:concurrency:" + productCode + ":" + deviceSn;
    }
}
