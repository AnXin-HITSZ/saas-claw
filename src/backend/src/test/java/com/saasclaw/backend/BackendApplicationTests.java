package com.saasclaw.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class BackendApplicationTests {

    // 本地无 Redis 可达实例：该容器是 SmartLifecycle，start() 需要实时连接（审批多实例广播用）。
    // Mock 掉后 context 刷新可完成，其余 Bean（含数据源/MyBatis/全部控制器服务）正常装配校验。
    @MockitoBean
    private RedisMessageListenerContainer redisMessageListenerContainer;

    @Test
    void contextLoads() {
    }

}
