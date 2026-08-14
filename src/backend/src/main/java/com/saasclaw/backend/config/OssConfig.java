package com.saasclaw.backend.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 注入 OSS 客户端（凭据来自 OssProperties） */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(OssProperties.class)
public class OssConfig {

    private final OssProperties props;

    @Bean
    public OSS ossClient() {
        return new OSSClientBuilder()
                .build(props.getEndpoint(), props.getAccessKeyId(), props.getAccessKeySecret());
    }
}