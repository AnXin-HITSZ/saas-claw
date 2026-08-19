package com.saasclaw.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 认证相关配置。白名单路径（无需鉴权，直接透传 backend），Ant 风格匹配。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    /** 免鉴权路径，默认登录 / 注册（backend context-path=/api） */
    private List<String> whitelist = new ArrayList<>(List.of("/api/auth/login", "/api/auth/register"));
}