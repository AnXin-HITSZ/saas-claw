package com.saasclaw.backend.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final ApiKeyInterceptor apiKeyInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 人工通道（JWT）：排除登录、注册、错误页，以及程序通道 /tools/approval-requests/**
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/auth/register",
                        "/auth/login",
                        "/tools/approval-requests/**",
                        "/error"
                );
        // 程序通道（API Key）：敏感工具审批请求
        registry.addInterceptor(apiKeyInterceptor)
                .addPathPatterns("/tools/approval-requests/**");
    }
}
