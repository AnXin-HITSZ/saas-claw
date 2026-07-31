package com.minigateway.utils;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class RedisManager {

    private final RedisTemplate<String, String> redisTemplate;
    private final LuaScriptLoader luaScriptLoader;

    public RedisManager(RedisTemplate<String, String> redisTemplate, LuaScriptLoader luaScriptLoader) {
        this.redisTemplate = redisTemplate;
        this.luaScriptLoader = luaScriptLoader;
    }

    public String get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    public void set(String key, String value, long timeout, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, value, timeout, unit);
    }

    public Long increment(String key, long delta) {
        return redisTemplate.opsForValue().increment(key, delta);
    }

    public Long executeLua(List<String> keys, String... args) {
        String script = luaScriptLoader.getRateLimitScript();
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType(Long.class);
        return redisTemplate.execute(redisScript, keys, (Object[]) args);
    }
}
