package com.saasclaw.gateway.service;

public interface FlowLimitService {
    public Long slidingWindow(String hashTag, long limit, long increment);
    public boolean isQpsAllowed(Long userId, long qpsLimit);
    public boolean isRpmAllowed(Long userId, String model, long rpmLimit);
    public boolean isTpmAllowed(Long userId, String model, long tpmLimit, long estimatedTokens);
    public boolean isTpmBurstAllowed(Long userId, String model, long estimatedTokens, long burstLimit, long totalThreshold);
}
