package com.saasclaw.backend.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;

/**
 * RestClient 统一配置。
 *
 * 背景（审批回调 422 空 body 根因）：RuntimeCallbackServiceImpl 用 Boot 自动配置的
 * RestClient.Builder 回调 runtime（FastAPI/uvicorn）POST /approvals/callback，线上持续返回
 * 422 {"loc":["body"],"msg":"Field required"} —— 请求 body 为空。本应用 classpath 无
 * httpclient5，Boot 自动配置的请求工厂退化为 JDK java.net.http.HttpClient；JDK 客户端默认
 * HTTP/2，对明文 http 请求会附带 Upgrade: h2c / Connection: Upgrade, HTTP2-Settings 头尝试
 * 协议升级，而 uvicorn(h11) 收到带 h2c 升级头的请求会整体丢弃 body，FastAPI 因此判定 body
 * 缺失。已用探针在 pod 内 + 本地 curl 双层复现定位：去掉 h2c 升级头（Content-Length 或
 * chunked 均可）即恢复 200。
 *
 * 修复：强制 JDK HttpClient 走 HTTP/1.1（关闭 h2c 升级），body 即可正常送达。
 * 影响面：全应用仅 RuntimeCallbackServiceImpl 使用 RestClient，强制 HTTP/1.1 无副作用。
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClientCustomizer http11RestClientCustomizer() {
        return builder -> builder.requestFactory(new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()));
    }
}
