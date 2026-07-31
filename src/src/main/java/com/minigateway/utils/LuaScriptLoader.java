package com.minigateway.utils;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class LuaScriptLoader {

    private String rateLimitScript;

    @PostConstruct
    public void init() throws IOException {
        rateLimitScript = loadScript("lua/rate_limit.lua");
    }

    private String loadScript(String path) throws IOException {
        var resource = new ClassPathResource(path);
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    public String getRateLimitScript() {
        return rateLimitScript;
    }
}
