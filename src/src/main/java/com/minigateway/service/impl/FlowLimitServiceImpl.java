package com.minigateway.service.impl;

import com.minigateway.utils.RedisManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class FlowLimitServiceImpl {

    @Autowired
    private RedisManager redisManager;

    public Long slidingWindow(String hashTag, long limit, long increment) {

    }
}
