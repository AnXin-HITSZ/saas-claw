package com.saasclaw.gateway.service;

public interface DeviceConcurrencyService {
    boolean tryAcquire(String productCode, String deviceSn, String requestId);
    void release(String deviceKey, String requestId);
    String buildDeviceKey(String productCode, String deviceSn);
}
