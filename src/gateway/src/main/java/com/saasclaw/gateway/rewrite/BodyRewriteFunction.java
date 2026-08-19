package com.saasclaw.gateway.rewrite;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.saasclaw.gateway.entity.ModelConfig;
import com.saasclaw.gateway.mapper.ModelConfigMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.factory.rewrite.RewriteFunction;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class BodyRewriteFunction implements RewriteFunction<byte[], byte[]> {
    @Autowired
    private ModelConfigMapper modelConfigMapper;

    private Set<String> supportedModels = ConcurrentHashMap.newKeySet();

    @PostConstruct
    public void init() {
        refreshModels();
    }

    @Scheduled(cron = "0 0/3 * * * ?")
    public void refreshModels() {
        List<ModelConfig> models = modelConfigMapper.selectList(
                new LambdaQueryWrapper<ModelConfig>().eq(ModelConfig::getStatus, 1)
        );
        supportedModels.clear();
        // 校验集合用逻辑标识 name（agent.base_model / 请求体 model 引用它），不是供应商侧 model_name
        models.forEach(m -> supportedModels.add(m.getName()));
        log.info("refreshed model list: {}", supportedModels);
    }

    @Override
    public Publisher<byte[]> apply(ServerWebExchange exchange, byte[] bytes) {
        String body = new String(bytes, StandardCharsets.UTF_8);

        String modelName = extractModel(body);
        if (modelName == null || modelName.isEmpty()) {
            log.error("model is required");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "model is required");
        }
        exchange.getAttributes().put("modelName", modelName);

        long eTokens = estimateTokens(body);
        exchange.getAttributes().put("estimatedTokens", eTokens);

        if (!supportedModels.contains(modelName)) {
            log.error("model {} is not supported", modelName);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "model " + modelName + " is not supported");
        }

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
