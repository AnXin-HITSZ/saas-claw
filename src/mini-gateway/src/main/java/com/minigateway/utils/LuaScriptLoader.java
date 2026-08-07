package com.minigateway.utils;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Data
@Component
public class LuaScriptLoader {

    private String rateLimitScript;
    private String burstLimitScript;
    private String acquireScript;
    private String releaseScript;
    private String compareAndDeleteScript;

    @PostConstruct
    public void init() throws IOException {
        rateLimitScript = loadScript("lua/rate_limit.lua");
        burstLimitScript = loadScript("lua/burst_limit.lua");
        acquireScript = loadScript("lua/acquire.lua");
        releaseScript = loadScript("lua/release.lua");
        compareAndDeleteScript = loadScript("lua/compare_and_delete.lua");
    }

    private String loadScript(String path) throws IOException {
        var resource = new ClassPathResource(path);
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
