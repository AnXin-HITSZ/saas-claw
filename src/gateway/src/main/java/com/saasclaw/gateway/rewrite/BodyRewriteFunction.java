package com.saasclaw.gateway.rewrite;

import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.factory.rewrite.RewriteFunction;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class BodyRewriteFunction implements RewriteFunction<byte[], byte[]> {

    @Override
    public Publisher<byte[]> apply(ServerWebExchange exchange, byte[] bytes) {
        // 空请求体（GET 会话/消息/trace 等）防御：ModifyRequestBody 对无 body 请求以 null 传入，
        // 直接 new String(null) 会 NPE 导致网关 500。这里透传空数组，让重写链自然走空 body 分支。
        if (bytes == null || bytes.length == 0) {
            return Mono.just(new byte[0]);
        }
        String body = new String(bytes, StandardCharsets.UTF_8);

        String modelName = extractModel(body);
        if (modelName == null || modelName.isEmpty()) {
            log.error("model is required");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "model is required");
        }
        exchange.getAttributes().put("modelName", modelName);

        long eTokens = estimateTokens(body);
        exchange.getAttributes().put("estimatedTokens", eTokens);

        // 注意：请求体 model 字段承载的是 agent alias（saas-claw 语义，前端 chat.ts / ClawRouting 均按
        // alias 消费），不是 model_config.name 模型逻辑名——两者是不同命名空间，故此处不做白名单校验。
        // alias 合法性由 ClawRouting 解析（alias 无效 / 未启用 Claw 时返回 400 agent not found）。
        String requestId = exchange.getAttributes().get("requestId").toString();
        long startTime = Long.parseLong(exchange.getAttributes().get("startTime").toString());

        String newBody = injectFields(body, requestId, startTime, modelName, eTokens);

        return Mono.just(newBody.getBytes(StandardCharsets.UTF_8));
    }

    private String extractModel(String bodyStr) {
        Pattern p = Pattern.compile("\"model\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(bodyStr);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private long estimateTokens(String bodyStr) {
        // 简化版：按字符数 × 0.5 估算
        return (long) (bodyStr.length() * 0.5);
    }

    private String injectFields(String bodyStr, String requestId, long startTime, String modelName, long tokens) {
        int lastBrace = bodyStr.lastIndexOf('}');
        if (lastBrace == -1) return bodyStr;

        return new StringBuilder(bodyStr.length() + 200)
                .append(bodyStr, 0, lastBrace)
                .append(",\"requestId\":\"").append(requestId).append("\"")
                .append(",\"requestTime\":").append(startTime)
                .append(",\"modelName\":\"").append(modelName).append("\"")
                .append(",\"estimatedTokens\":").append(tokens)
                .append('}')
                .toString();
    }
}
