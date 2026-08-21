package com.saasclaw.backend.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saasclaw.backend.common.BizException;
import com.saasclaw.backend.dto.HandleApprovalBatchRequest;
import com.saasclaw.backend.entity.ToolApprovalBatch;
import com.saasclaw.backend.mapper.AgentMapper;
import com.saasclaw.backend.mapper.ClawMapper;
import com.saasclaw.backend.mapper.ToolApprovalBatchMapper;
import com.saasclaw.backend.service.RuntimeCallbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * handleBatch / handleBatchByRequest 行为单测（纯 Mockito，不连 DB）。
 * 覆盖新增的按 request_id 直达处理批量审批：解析、校验、整体决策落库、回调 runtime 恢复主图。
 */
class ToolApprovalBatchServiceImplTest {

    private ToolApprovalBatchMapper batchMapper;
    private RuntimeCallbackService runtimeCallbackService;
    private ToolApprovalBatchServiceImpl service;

    @BeforeEach
    void setUp() {
        batchMapper = mock(ToolApprovalBatchMapper.class);
        runtimeCallbackService = mock(RuntimeCallbackService.class);
        service = new ToolApprovalBatchServiceImpl(
                batchMapper,
                mock(AgentMapper.class),
                mock(ClawMapper.class),
                runtimeCallbackService,
                new ObjectMapper());
    }

    private ToolApprovalBatch pendingRecord() {
        ToolApprovalBatch r = new ToolApprovalBatch();
        r.setId(1L);
        r.setRequestId("approval:batch:spawn-1:0");
        r.setUserId(100L);
        r.setClawId(5L);
        r.setSubRequests("[{\"request_id\":\"approval:c1\"}]");
        r.setStatus(ToolApprovalBatchServiceImpl.STATUS_PENDING);
        return r;
    }

    @SuppressWarnings("unchecked")
    private void verifyNotifyBatch(Long clawId, String requestId, String decision, String reason) {
        ArgumentCaptor<Map<String, Object>> result = ArgumentCaptor.forClass(Map.class);
        verify(runtimeCallbackService).notifyBatchApproval(eq(clawId), eq(requestId), result.capture());
        assertEquals(decision, result.getValue().get("decision"));
        assertEquals(reason, result.getValue().get("reason"));
    }

    @Test
    void handleBatchByRequest_resolvesAndHandlesAllow() {
        when(batchMapper.selectOne(any())).thenReturn(pendingRecord());

        HandleApprovalBatchRequest req = new HandleApprovalBatchRequest();
        req.setAction(ToolApprovalBatchServiceImpl.ACTION_ALLOW);
        service.handleByRequestId(100L, "approval:batch:spawn-1:0", req);

        ArgumentCaptor<ToolApprovalBatch> saved = ArgumentCaptor.forClass(ToolApprovalBatch.class);
        verify(batchMapper).updateById(saved.capture());
        assertEquals(ToolApprovalBatchServiceImpl.ACTION_ALLOW, saved.getValue().getAction());
        assertEquals(ToolApprovalBatchServiceImpl.STATUS_HANDLED, saved.getValue().getStatus());
        assertNull(saved.getValue().getCustomMessage());
        assertNull(saved.getValue().getDecisionJson()); // 无逐子覆盖 → 不落 decision_json
        assertNotNull(saved.getValue().getHandledAt());

        verifyNotifyBatch(5L, "approval:batch:spawn-1:0", "approve", "");
    }

    @Test
    void handleBatch_allow_usesSharedPathSameAsByRequest() {
        when(batchMapper.selectById(1L)).thenReturn(pendingRecord());

        HandleApprovalBatchRequest req = new HandleApprovalBatchRequest();
        req.setAction(ToolApprovalBatchServiceImpl.ACTION_ALLOW);
        service.handle(100L, 1L, req);

        verify(batchMapper).updateById(any(ToolApprovalBatch.class));
        verifyNotifyBatch(5L, "approval:batch:spawn-1:0", "approve", "");
    }

    @Test
    void handleBatchByRequest_customWithMessage_savesAndForwardsReason() {
        when(batchMapper.selectOne(any())).thenReturn(pendingRecord());

        HandleApprovalBatchRequest req = new HandleApprovalBatchRequest();
        req.setAction(ToolApprovalBatchServiceImpl.ACTION_CUSTOM);
        req.setCustomMessage("需要人工核对后执行");
        service.handleByRequestId(100L, "approval:batch:spawn-1:0", req);

        ArgumentCaptor<ToolApprovalBatch> saved = ArgumentCaptor.forClass(ToolApprovalBatch.class);
        verify(batchMapper).updateById(saved.capture());
        assertEquals(ToolApprovalBatchServiceImpl.ACTION_CUSTOM, saved.getValue().getAction());
        assertEquals("需要人工核对后执行", saved.getValue().getCustomMessage());

        // 自定义消息（action=3）回调 decision 为 reject + reason
        verifyNotifyBatch(5L, "approval:batch:spawn-1:0", "reject", "需要人工核对后执行");
    }

    @Test
    void handleBatchByRequest_perChildOverrides() {
        when(batchMapper.selectOne(any())).thenReturn(pendingRecord());

        HandleApprovalBatchRequest req = new HandleApprovalBatchRequest();
        req.setAction(ToolApprovalBatchServiceImpl.ACTION_DENY); // 整体拒绝
        HandleApprovalBatchRequest.Decision d = new HandleApprovalBatchRequest.Decision();
        d.setAction(ToolApprovalBatchServiceImpl.ACTION_ALLOW); // 单子请求允许
        d.setCustomMessage("这个子请求放行");
        req.setDecisions(Map.of("approval:c1", d));
        service.handleByRequestId(100L, "approval:batch:spawn-1:0", req);

        ArgumentCaptor<ToolApprovalBatch> saved = ArgumentCaptor.forClass(ToolApprovalBatch.class);
        verify(batchMapper).updateById(saved.capture());
        assertNotNull(saved.getValue().getDecisionJson());
        assertEquals(1, saved.getValue().getDecisionJson().split("approval:c1").length - 1);
        verifyNotifyBatch(5L, "approval:batch:spawn-1:0", "reject", "");
    }

    @Test
    void handleBatchByRequest_notFound_throws404() {
        when(batchMapper.selectOne(any())).thenReturn(null);

        HandleApprovalBatchRequest req = new HandleApprovalBatchRequest();
        req.setAction(ToolApprovalBatchServiceImpl.ACTION_ALLOW);
        BizException ex = assertThrows(BizException.class,
                () -> service.handleByRequestId(100L, "approval:batch:missing", req));
        assertEquals(404, ex.getCode());
        verify(batchMapper, never()).updateById(any(ToolApprovalBatch.class));
    }

    @Test
    void handleBatchByRequest_otherUser_throws404() {
        when(batchMapper.selectOne(any())).thenReturn(pendingRecord());

        HandleApprovalBatchRequest req = new HandleApprovalBatchRequest();
        req.setAction(ToolApprovalBatchServiceImpl.ACTION_ALLOW);
        BizException ex = assertThrows(BizException.class,
                () -> service.handleByRequestId(200L, "approval:batch:spawn-1:0", req));
        assertEquals(404, ex.getCode());
        verify(batchMapper, never()).updateById(any(ToolApprovalBatch.class));
    }

    @Test
    void handleBatchByRequest_alreadyHandled_throws409() {
        ToolApprovalBatch record = pendingRecord();
        record.setStatus(ToolApprovalBatchServiceImpl.STATUS_HANDLED);
        when(batchMapper.selectOne(any())).thenReturn(record);

        HandleApprovalBatchRequest req = new HandleApprovalBatchRequest();
        req.setAction(ToolApprovalBatchServiceImpl.ACTION_ALLOW);
        BizException ex = assertThrows(BizException.class,
                () -> service.handleByRequestId(100L, "approval:batch:spawn-1:0", req));
        assertEquals(409, ex.getCode());
    }

    @Test
    void handleBatchByRequest_customWithoutMessage_throws400() {
        when(batchMapper.selectOne(any())).thenReturn(pendingRecord());

        HandleApprovalBatchRequest req = new HandleApprovalBatchRequest();
        req.setAction(ToolApprovalBatchServiceImpl.ACTION_CUSTOM);
        BizException ex = assertThrows(BizException.class,
                () -> service.handleByRequestId(100L, "approval:batch:spawn-1:0", req));
        assertEquals(400, ex.getCode());
        verify(batchMapper, never()).updateById(any(ToolApprovalBatch.class));
    }
}
