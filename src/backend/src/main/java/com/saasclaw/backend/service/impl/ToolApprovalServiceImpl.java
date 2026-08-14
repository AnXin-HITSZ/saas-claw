package com.saasclaw.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.saasclaw.backend.common.BizException;
import com.saasclaw.backend.dto.CreateApprovalRequest;
import com.saasclaw.backend.dto.HandleApprovalRequest;
import com.saasclaw.backend.entity.Agent;
import com.saasclaw.backend.entity.Claw;
import com.saasclaw.backend.entity.Tool;
import com.saasclaw.backend.entity.ToolApproval;
import com.saasclaw.backend.mapper.AgentMapper;
import com.saasclaw.backend.mapper.ClawMapper;
import com.saasclaw.backend.mapper.ToolApprovalMapper;
import com.saasclaw.backend.mapper.ToolMapper;
import com.saasclaw.backend.service.ApprovalEventBus;
import com.saasclaw.backend.service.ToolApprovalService;
import com.saasclaw.backend.vo.ApprovalRequestVO;
import com.saasclaw.backend.vo.ApprovalResultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ToolApprovalServiceImpl implements ToolApprovalService {

    /** action：1=允许 2=拒绝 3=自定义消息 */
    public static final int ACTION_ALLOW = 1;
    public static final int ACTION_DENY = 2;
    public static final int ACTION_CUSTOM = 3;

    /** status：0=待审批 1=已处理 */
    public static final int STATUS_PENDING = 0;
    public static final int STATUS_HANDLED = 1;

    private final ToolApprovalMapper toolApprovalMapper;
    private final ToolMapper toolMapper;
    private final AgentMapper agentMapper;
    private final ClawMapper clawMapper;
    private final ApprovalEventBus eventBus;

    @Override
    public ApprovalRequestVO create(Long userId, CreateApprovalRequest request) {
        // 幂等：同 requestId 已存在（含已处理）→ 直接返回已有状态
        ToolApproval exists = toolApprovalMapper.selectOne(
                new LambdaQueryWrapper<ToolApproval>()
                        .eq(ToolApproval::getRequestId, request.getRequestId()));
        if (exists != null) {
            return toVO(exists);
        }

        // 防御校验：仅敏感工具需要审批；agent/claw 必须本人所有且启用
        Tool tool = toolMapper.selectById(request.getToolId());
        if (tool == null || tool.getStatus() == 0) {
            throw new BizException(404, "Tool 不存在");
        }
        if (tool.getIsSensitive() == null || tool.getIsSensitive() != 1) {
            throw new BizException(400, "仅敏感工具需要审批");
        }
        Agent agent = agentMapper.selectById(request.getAgentId());
        if (agent == null || agent.getStatus() == 0 || !agent.getUserId().equals(userId)) {
            throw new BizException(404, "Agent 不存在");
        }
        Claw claw = clawMapper.selectById(request.getClawId());
        if (claw == null || claw.getStatus() == 0 || !claw.getUserId().equals(userId)) {
            throw new BizException(404, "Claw 不存在");
        }

        ToolApproval record = new ToolApproval();
        record.setRequestId(request.getRequestId());
        record.setUserId(userId);
        record.setClawId(request.getClawId());
        record.setAgentId(request.getAgentId());
        record.setToolId(request.getToolId());
        record.setInputSummary(request.getInputSummary());
        record.setStatus(STATUS_PENDING);
        toolApprovalMapper.insert(record);

        return toVO(record);
    }

    @Override
    public ApprovalResultVO getResult(Long userId, String requestId) {
        ToolApproval record = toolApprovalMapper.selectOne(
                new LambdaQueryWrapper<ToolApproval>()
                        .eq(ToolApproval::getRequestId, requestId));
        if (record == null || !record.getUserId().equals(userId)) {
            throw new BizException(404, "审批请求不存在");
        }
        return toResultVO(record);
    }

    @Override
    public List<ApprovalRequestVO> listPending(Long userId) {
        return toolApprovalMapper.selectList(
                        new LambdaQueryWrapper<ToolApproval>()
                                .eq(ToolApproval::getUserId, userId)
                                .eq(ToolApproval::getStatus, STATUS_PENDING)
                                .orderByDesc(ToolApproval::getId))
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public List<ApprovalRequestVO> listHistory(Long userId) {
        return toolApprovalMapper.selectList(
                        new LambdaQueryWrapper<ToolApproval>()
                                .eq(ToolApproval::getUserId, userId)
                                .ne(ToolApproval::getStatus, STATUS_PENDING)
                                .orderByDesc(ToolApproval::getId)
                                .last("LIMIT 100"))
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public void handle(Long userId, Long approvalId, HandleApprovalRequest request) {
        ToolApproval record = toolApprovalMapper.selectById(approvalId);
        if (record == null || !record.getUserId().equals(userId)) {
            throw new BizException(404, "审批请求不存在");
        }
        if (record.getStatus() == STATUS_HANDLED) {
            throw new BizException(409, "该审批已处理");
        }
        Integer action = request.getAction();
        if (action == null
                || (action != ACTION_ALLOW && action != ACTION_DENY && action != ACTION_CUSTOM)) {
            throw new BizException(400, "action 不合法");
        }
        if (action == ACTION_CUSTOM
                && (request.getCustomMessage() == null || request.getCustomMessage().isBlank())) {
            throw new BizException(400, "action=3 时 customMessage 必填");
        }

        record.setAction(action);
        record.setCustomMessage(action == ACTION_CUSTOM ? request.getCustomMessage() : null);
        record.setHandledAt(LocalDateTime.now());
        record.setStatus(STATUS_HANDLED);
        toolApprovalMapper.updateById(record);

        // Redis 广播：所有实例收到，连接所在实例推送 SSE
        eventBus.publish(record.getRequestId(), toResultVO(record));
    }

    // ---- helpers ----

    private ApprovalRequestVO toVO(ToolApproval r) {
        ApprovalRequestVO vo = new ApprovalRequestVO();
        vo.setApprovalId(r.getId());
        vo.setRequestId(r.getRequestId());
        vo.setAgentId(r.getAgentId());
        Agent agent = agentMapper.selectById(r.getAgentId());
        vo.setAgentName(agent == null ? null : agent.getName());
        vo.setClawId(r.getClawId());
        vo.setToolId(r.getToolId());
        Tool tool = toolMapper.selectById(r.getToolId());
        vo.setToolName(tool == null ? null : tool.getName());
        vo.setInputSummary(r.getInputSummary());
        vo.setStatus(r.getStatus());
        vo.setAction(r.getAction());
        vo.setCustomMessage(r.getCustomMessage());
        vo.setCreatedAt(r.getCreatedAt());
        vo.setHandledAt(r.getHandledAt());
        return vo;
    }

    private ApprovalResultVO toResultVO(ToolApproval r) {
        ApprovalResultVO vo = new ApprovalResultVO();
        vo.setApprovalId(r.getId());
        vo.setRequestId(r.getRequestId());
        vo.setStatus(r.getStatus());
        vo.setAction(r.getAction());
        vo.setCustomMessage(r.getCustomMessage());
        vo.setHandledAt(r.getHandledAt());
        return vo;
    }
}
