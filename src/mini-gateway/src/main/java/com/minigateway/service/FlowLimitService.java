package com.minigateway.service;

public interface FlowLimitService {
    public Long slidingWindow(String hashTag, long limit, long increment);
    public boolean isQpsAllowed(Long orgId, long qpsLimit);
    public boolean isRpmAllowed(Long orgId, String model, long rpmLimit);
    public boolean isTpmAllowed(Long orgId, String model, long tpmLimit, long estimatedTokens);
    public boolean isTpmBurstAllowed(Long orgId, String model, long estimatedTokens, long burstLimit, long totalThreshold);
}
