package com.saasclaw.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 阿里云 OSS 配置。
 * 密钥类值走环境变量 / application-local.yaml（gitignored），不进公共配置。
 */
@Data
@ConfigurationProperties(prefix = "oss")
public class OssProperties {

    /** 地域节点，如 oss-cn-hangzhou.aliyuncs.com */
    private String endpoint;

    private String accessKeyId;

    private String accessKeySecret;

    /** bucket 名（公开读） */
    private String bucket;
}