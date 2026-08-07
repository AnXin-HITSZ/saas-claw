package com.minigateway.service;

public interface GoogleConcurrencyService {
    boolean tryAcquire(Long orgId, String model);
    void release(Long orgId, String model);
    String buildKey(Long orgId, String model);
}
