package com.saasclaw.backend.service.impl;

import com.saasclaw.backend.common.BizException;
import com.saasclaw.backend.dto.HandleApprovalRequest;
import com.saasclaw.backend.entity.ToolApproval;
import com.saasclaw.backend.mapper.AgentMapper;
import com.saasclaw.backend.mapper.ClawMapper;
import com.saasclaw.backend.mapper.ToolApprovalMapper;
import com.saasclaw.backend.mapper.ToolMapper;
import com.saasclaw.backend.service.ApprovalEventBus;
import com.saasclaw.backend.service.RuntimeCallbackService;
import com.saasclaw.backend.vo.ApprovalResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
 * handle / handleByRequestId 行为单测（纯 Mockito，不连 DB）。
 * 覆盖新增的按 request_id 直达处理：request_id 解析、所有权/状态/action 校验、落库、Redis 广播、回调 runtime。
 */
class ToolApprovalServiceImplTest {

    private ToolApprovalMapper toolApprovalMapper;
    private ApprovalEventBus eventBus;
    private RuntimeCallbackService runtimeCallbackService;
    private ToolApprovalServiceImpl service;

    @BeforeEach
    void setUp() {
        toolApprovalMapper = mock(ToolApprovalMapper.class);
        eventBus = mock(ApprovalEventBus.class);
        runtimeCallbackService = mock(RuntimeCallbackService.class);
        service = new ToolApprovalServiceImpl(
                toolApprovalMapper,
                mock(ToolMapper.class),
                mock(AgentMapper.class),
                mock(ClawMapper.class),
                eventBus,
                runtimeCallbackService);
    }

    private ToolApproval pendingRecord() {
        ToolApproval r = new ToolApproval();
        r.setId(1L);
        r.setRequestId("approval:test-call");
        r.setUserId(100L);
        r.setClawId(5L);
        r.setStatus(ToolApprovalServiceImpl.STATUS_PENDING);
        return r;
    }

    @Test
    void handleByRequestId_resolvesByRequestIdAndHandlesAllow() {
        when(toolApprovalMapper.selectOne(any())).thenReturn(pendingRecord());

        HandleApprovalRequest req = new HandleApprovalRequest();
        req.setAction(ToolApprovalServiceImpl.ACTION_ALLOW);
        service.handleByRequestId(100L, "approval:test-call", req);

        ArgumentCaptor<ToolApproval> saved = ArgumentCaptor.forClass(ToolApproval.class);
        verify(toolApprovalMapper).updateById(saved.capture());
        assertEquals(ToolApprovalServiceImpl.ACTION_ALLOW, saved.getValue().getAction());
        assertEquals(ToolApprovalServiceImpl.STATUS_HANDLED, saved.getValue().getStatus());
        assertNull(saved.getValue().getCustomMessage());
        assertNotNull(saved.getValue().getHandledAt());

        // Redis 广播 + 回调 runtime 恢复（携 requestId 与结果）
        ArgumentCaptor<ApprovalResultVO> result = ArgumentCaptor.forClass(ApprovalResultVO.class);
        verify(eventBus).publish(eq("approval:test-call"), result.capture());
        verify(runtimeCallbackService).notifyApproval(eq(5L), result.capture());
        assertEquals("approval:test-call", result.getAllValues().get(0).getRequestId());
        assertEquals(ToolApprovalServiceImpl.ACTION_ALLOW, result.getAllValues().get(0).getAction());
    }

    @Test
    void handle_allow_usesSharedPathSameAsByRequest() {
        // 原有按 DB id 处理与按 request_id 处理共用 doHandle：行为一致
        when(toolApprovalMapper.selectById(1L)).thenReturn(pendingRecord());

        HandleApprovalRequest req = new HandleApprovalRequest();
        req.setAction(ToolApprovalServiceImpl.ACTION_ALLOW);
        service.handle(100L, 1L, req);

        verify(toolApprovalMapper).updateById(any(ToolApproval.class));
        verify(runtimeCallbackService).notifyApproval(eq(5L), any());
    }

    @Test
    void handleByRequestId_notFound_throws404() {
        when(toolApprovalMapper.selectOne(any())).thenReturn(null);

        HandleApprovalRequest req = new HandleApprovalRequest();
        req.setAction(ToolApprovalServiceImpl.ACTION_ALLOW);
        BizException ex = assertThrows(BizException.class,
                () -> service.handleByRequestId(100L, "approval:missing", req));
        assertEquals(404, ex.getCode());
        verify(toolApprovalMapper, never()).updateById(any(ToolApproval.class));
    }

    @Test
    void handleByRequestId_otherUser_throws404() {
        when(toolApprovalMapper.selectOne(any())).thenReturn(pendingRecord());

        HandleApprovalRequest req = new HandleApprovalRequest();
        req.setAction(ToolApprovalServiceImpl.ACTION_ALLOW);
        BizException ex = assertThrows(BizException.class,
                () -> service.handleByRequestId(200L, "approval:test-call", req));
        assertEquals(404, ex.getCode());
        verify(toolApprovalMapper, never()).updateById(any(ToolApproval.class));
    }

    @Test
    void handleByRequestId_alreadyHandled_throws409() {
        ToolApproval record = pendingRecord();
        record.setStatus(ToolApprovalServiceImpl.STATUS_HANDLED);
        when(toolApprovalMapper.selectOne(any())).thenReturn(record);

        HandleApprovalRequest req = new HandleApprovalRequest();
        req.setAction(ToolApprovalServiceImpl.ACTION_ALLOW);
        BizException ex = assertThrows(BizException.class,
                () -> service.handleByRequestId(100L, "approval:test-call", req));
        assertEquals(409, ex.getCode());
    }

    @Test
    void handleByRequestId_invalidAction_throws400() {
        when(toolApprovalMapper.selectOne(any())).thenReturn(pendingRecord());

        HandleApprovalRequest req = new HandleApprovalRequest();
        req.setAction(99);
        BizException ex = assertThrows(BizException.class,
                () -> service.handleByRequestId(100L, "approval:test-call", req));
        assertEquals(400, ex.getCode());
    }

    @Test
    void handleByRequestId_customWithoutMessage_throws400() {
        when(toolApprovalMapper.selectOne(any())).thenReturn(pendingRecord());

        HandleApprovalRequest req = new HandleApprovalRequest();
        req.setAction(ToolApprovalServiceImpl.ACTION_CUSTOM);
        BizException ex = assertThrows(BizException.class,
                () -> service.handleByRequestId(100L, "approval:test-call", req));
        assertEquals(400, ex.getCode());
        verify(toolApprovalMapper, never()).updateById(any(ToolApproval.class));
    }

    @Test
    void handleByRequestId_customWithMessage_persistsMessage() {
        when(toolApprovalMapper.selectOne(any())).thenReturn(pendingRecord());

        HandleApprovalRequest req = new HandleApprovalRequest();
        req.setAction(ToolApprovalServiceImpl.ACTION_CUSTOM);
        req.setCustomMessage("请先确认数据");
        service.handleByRequestId(100L, "approval:test-call", req);

        ArgumentCaptor<ToolApproval> saved = ArgumentCaptor.forClass(ToolApproval.class);
        verify(toolApprovalMapper).updateById(saved.capture());
        assertEquals(ToolApprovalServiceImpl.ACTION_CUSTOM, saved.getValue().getAction());
        assertEquals("请先确认数据", saved.getValue().getCustomMessage());
        // 自定义消息作为 reason 回传 runtime
        ArgumentCaptor<ApprovalResultVO> result = ArgumentCaptor.forClass(ApprovalResultVO.class);
        verify(runtimeCallbackService).notifyApproval(eq(5L), result.capture());
        assertEquals("请先确认数据", result.getValue().getCustomMessage());
    }

    @Test
    void handleByRequestId_deny_notAllow_isRejected() {
        when(toolApprovalMapper.selectOne(any())).thenReturn(pendingRecord());

        HandleApprovalRequest req = new HandleApprovalRequest();
        req.setAction(ToolApprovalServiceImpl.ACTION_DENY);
        service.handleByRequestId(100L, "approval:test-call", req);

        ArgumentCaptor<ToolApproval> saved = ArgumentCaptor.forClass(ToolApproval.class);
        verify(toolApprovalMapper).updateById(saved.capture());
        assertEquals(ToolApprovalServiceImpl.ACTION_DENY, saved.getValue().getAction());
    }
}
