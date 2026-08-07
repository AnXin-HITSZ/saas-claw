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

    public Boolean setIfAbsent(String key, String value, long timeout, TimeUnit unit) {
        return redisTemplate.opsForValue().setIfAbsent(key, value, timeout, unit);
    }

    public Long increment(String key, long delta) {
        return redisTemplate.opsForValue().increment(key, delta);
    }

    public Boolean delete(String key) {
        return redisTemplate.delete(key);
    }

    public Long executeRateLimitLua(List<String> keys, String... args) {
        return executeScriptLua(luaScriptLoader.getRateLimitScript(), keys, args);
    }

    public Long executeBurstLimitLua(List<String> keys, String... args) {
        return executeScriptLua(luaScriptLoader.getBurstLimitScript(), keys, args);
    }

    public Long executeAcquireLua(List<String> keys, String... args) {
        return executeScriptLua(luaScriptLoader.getAcquireScript(), keys, args);
    }

    public Long executeReleaseLua(List<String> keys, String... args) {
        return executeScriptLua(luaScriptLoader.getReleaseScript(), keys, args);
    }

    public Long executeCompareAndDeleteLua(List<String> keys, String... args) {
        return executeScriptLua(luaScriptLoader.getCompareAndDeleteScript(), keys, args);
    }

    public Long executeScriptLua(String script, List<String> keys, String... args) {
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType(Long.class);
        return redisTemplate.execute(redisScript, keys, (Object[]) args);
    }
}
