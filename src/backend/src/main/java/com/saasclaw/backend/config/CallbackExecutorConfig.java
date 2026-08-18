package com.saasclaw.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 审批回调专用线程池：与 ForkJoinPool.commonPool 隔离，阻塞 I/O 不拖累共享池 */
@Configuration
public class CallbackExecutorConfig {

    @Value("${claw.callback-pool.core:2}")
    private int core;

    @Value("${claw.callback-pool.max:4}")
    private int max;

    @Value("${claw.callback-pool.queue:100}")
    private int queue;

    @Bean(name = "approvalCallbackExecutor")
    public ThreadPoolTaskExecutor approvalCallbackExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix("callback-");
        executor.setWaitForTasksToCompleteOnShutdown(true); // 停机时等收尾，不留半截回调
        executor.initialize();
        return executor;
    }
}