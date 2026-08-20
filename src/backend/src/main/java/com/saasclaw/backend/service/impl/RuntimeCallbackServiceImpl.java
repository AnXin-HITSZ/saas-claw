package com.saasclaw.backend.service.impl;

import com.saasclaw.backend.service.RuntimeCallbackService;
import com.saasclaw.backend.vo.ApprovalResultVO;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** 审批回调 runtime Pod（POST /approvals/callback）：专用线程池异步发，最多 3 次，指数退避。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeCallbackServiceImpl implements RuntimeCallbackService {

    private static final int MAX_ATTEMPTS = 3;

    @Value("${claw.callback-url-template:http://claw-{id}:8000}")
    private String urlTemplate;

    /** Spring Boot 自动配置的 RestClient.Builder：携带应用级 ObjectMapper（全局 SNAKE_CASE 生效） */
    private final RestClient.Builder restClientBuilder;

    /** 审批回调专用线程池（CallbackExecutorConfig）：与 commonPool 隔离 */
    @Qualifier("approvalCallbackExecutor")
    private final Executor approvalCallbackExecutor;

    @Override
    public void notifyApproval(Long clawId, ApprovalResultVO result) {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("decision", toDecision(result.getAction()));
        decision.put("reason", result.getCustomMessage() == null ? "" : result.getCustomMessage());
        notify(clawId, result.getRequestId(), decision);
    }

    @Override
    public void notifyBatchApproval(Long clawId, String requestId, Map<String, Object> result) {
        notify(clawId, requestId, result);
    }

    private void notify(Long clawId, String requestId, Object result) {
        String url = urlTemplate.replace("{id}", String.valueOf(clawId)) + "/approvals/callback";
        RestClient client = restClientBuilder.build();
        try {
            // fire-and-forget：线程池已配 CallerRunsPolicy，满池时不再丢回调，而是在本线程
            // （approve 请求线程）同步执行 callWithRetry——保证回调必达，runtime 主图不会因
            // 回调丢失而永久挂起（修复：放弃回调会让用户已批准的对话无响应）。
            approvalCallbackExecutor.execute(() -> callWithRetry(client, url, new CallbackBody(requestId, result)));
        } catch (RejectedExecutionException e) {
            // 理论不可达（CallerRunsPolicy 不拒绝），仅防御性兜底：显式留痕，不静默放弃
            log.error("审批回调提交异常 requestId={} url={}", requestId, url, e);
        }
    }

    private void callWithRetry(RestClient client, String url, CallbackBody body) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                client.post().uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
                log.info("审批回调成功 requestId={}", body.getRequestId());
                return;
            } catch (Exception e) {
                if (attempt == MAX_ATTEMPTS) {
                    log.error("审批回调失败（重试{}次后放弃）requestId={} url={}", MAX_ATTEMPTS, body.getRequestId(), url, e);
                    return;
                }
                try {
                    Thread.sleep(500L * (1L << attempt)); // 1s、2s 退避
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** action 1=允许 → approve，2/3=拒绝 → reject（3 带自定义消息） */
    private String toDecision(Integer action) {
        return action != null && action == 1 ? "approve" : "reject";
    }

    /** 回调 body（runtime 契约）：字段与 ApprovalCallbackBody 对齐，全局 SNAKE_CASE 自动转 snake_case。
     *  result 用 Object：单条审批放 {decision, reason} Map，批量审批放 {decision, reason, decisions?} Map。 */
    @Data
    private static class CallbackBody {
        private String requestId;
        private Object result;

        CallbackBody(String requestId, Object result) {
            this.requestId = requestId;
            this.result = result;
        }
    }
}
