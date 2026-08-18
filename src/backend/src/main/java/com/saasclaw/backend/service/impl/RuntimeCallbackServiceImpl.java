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
        String url = urlTemplate.replace("{id}", String.valueOf(clawId)) + "/approvals/callback";
        RestClient client = restClientBuilder.build();
        try {
            // fire-and-forget：execute 满池时同步抛 RejectedExecutionException，在此兜底，不阻塞请求线程
            approvalCallbackExecutor.execute(() -> callWithRetry(client, url, new CallbackBody(result)));
        } catch (RejectedExecutionException e) {
            // 满池：本回调尽力而为，留痕放弃（审批记录仍在 MySQL，用户可查可重发）
            log.error("审批回调线程池已满，放弃回调 requestId={} url={}", result.getRequestId(), url, e);
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

    /** 回调 body（runtime 契约）：字段与 ApprovalCallbackBody 对齐，全局 SNAKE_CASE 自动转 snake_case */
    @Data
    private static class CallbackBody {
        private String requestId;
        private CallbackResult result;

        CallbackBody(ApprovalResultVO vo) {
            this.requestId = vo.getRequestId();
            this.result = new CallbackResult(vo);
        }
    }

    /** 决策 + 理由：action 1=允许、2/3=拒绝（3 带用户自定义消息） */
    @Data
    private static class CallbackResult {
        private String decision;
        private String reason;

        CallbackResult(ApprovalResultVO vo) {
            String decision;
            switch (vo.getAction()) {
                case 1 -> decision = "approve";
                case 2, 3 -> decision = "reject";
                default -> throw new IllegalArgumentException("未识别 action: " + vo.getAction());
            }
            this.decision = decision;
            this.reason = vo.getCustomMessage() == null ? "" : vo.getCustomMessage();
        }
    }
}