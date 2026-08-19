package com.saasclaw.gateway.service;

public interface GoogleConcurrencyService {
    boolean tryAcquire(Long userId, String model);
    void release(Long userId, String model);
    String buildKey(Long userId, String model);
}
