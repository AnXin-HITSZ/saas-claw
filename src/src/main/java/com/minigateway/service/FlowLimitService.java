package com.minigateway.service;

public interface FlowLimitService {
    public Long slidingWindow(String hashTag, long limit, long increment);
}
