package com.saasclaw.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saasclaw.backend.common.BizException;
import com.saasclaw.backend.dto.CreateApprovalBatchRequest;
import com.saasclaw.backend.dto.HandleApprovalBatchRequest;
import com.saasclaw.backend.entity.Agent;
import com.saasclaw.backend.entity.Claw;
import com.saasclaw.backend.entity.ToolApprovalBatch;
import com.saasclaw.backend.mapper.AgentMapper;
import com.saasclaw.backend.mapper.ClawMapper;
import com.saasclaw.backend.mapper.ToolApprovalBatchMapper;
import com.saasclaw.backend.service.RuntimeCallbackService;
import com.saasclaw.backend.service.ToolApprovalBatchService;
import com.saasclaw.backend.vo.ApprovalBatchVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ToolApprovalBatchServiceImpl implements ToolApprovalBatchService {

    public static final int ACTION_ALLOW = 1;
    public static final int ACTION_DENY = 2;
    public static final int ACTION_CUSTOM = 3;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_HANDLED = 1;

    private final ToolApprovalBatchMapper batchMapper;
    private final AgentMapper agentMapper;
    private final ClawMapper clawMapper;
    private final RuntimeCallbackService runtimeCallbackService;
    private final ObjectMapper objectMapper;

    @Override
    public ApprovalBatchVO create(Long userId, CreateApprovalBatchRequest request) {
        // 幂等：同 requestId 已存在（含已处理）→ 直接返回已有记录
        ToolApprovalBatch exists = batchMapper.selectOne(
                new LambdaQueryWrapper<ToolApprovalBatch>()
                        .eq(ToolApprovalBatch::getRequestId, request.getRequestId()));
        if (exists != null) {
            return toVO(exists);
        }

        // 防御校验：父 Agent / Claw 必须本人所有且启用（userId 来自 API Key，防跨租户）
        Agent agent = agentMapper.selectById(request.getAgentId());
        if (agent == null || agent.getStatus() == 0 || !agent.getUserId().equals(userId)) {
            throw new BizException(404, "Agent 不存在");
        }
        Claw claw = clawMapper.selectById(request.getClawId());
        if (claw == null || claw.getStatus() == 0 || !claw.getUserId().equals(userId)) {
            throw new BizException(404, "Claw 不存在");
        }

        ToolApprovalBatch record = new ToolApprovalBatch();
        record.setRequestId(request.getRequestId());
        record.setUserId(userId);
        record.setClawId(request.getClawId());
        record.setAgentId(request.getAgentId());
        record.setSubRequests(writeJson(request.getSubRequests()));
        record.setStatus(STATUS_PENDING);
        batchMapper.insert(record);

        return toVO(record);
    }

    @Override
    public void handle(Long userId, Long batchId, HandleApprovalBatchRequest request) {
        doHandle(userId, batchMapper.selectById(batchId), request);
    }

    @Override
    public void handleByRequestId(Long userId, String requestId, HandleApprovalBatchRequest request) {
        ToolApprovalBatch record = batchMapper.selectOne(
                new LambdaQueryWrapper<ToolApprovalBatch>()
                        .eq(ToolApprovalBatch::getRequestId, requestId));
        doHandle(userId, record, request);
    }

    /** 批量审批核心：校验所有权/状态/action，落库 + 回调 runtime 恢复挂起主图 */
    private void doHandle(Long userId, ToolApprovalBatch record, HandleApprovalBatchRequest request) {
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

        Map<String, Map<String, String>> decisionMap = buildDecisionMap(request);
        record.setAction(action);
        record.setCustomMessage(action == ACTION_CUSTOM ? request.getCustomMessage() : null);
        record.setDecisionJson(decisionMap.isEmpty() ? null : writeJson(decisionMap));
        record.setHandledAt(LocalDateTime.now());
        record.setStatus(STATUS_HANDLED);
        batchMapper.updateById(record);

        // 回调 runtime 恢复挂起的主图：result 带整体决策 + 逐子请求覆盖
        runtimeCallbackService.notifyBatchApproval(record.getClawId(), record.getRequestId(),
                buildCallbackResult(request, decisionMap));
    }

    @Override
    public List<ApprovalBatchVO> listPending(Long userId) {
        return batchMapper.selectList(
                        new LambdaQueryWrapper<ToolApprovalBatch>()
                                .eq(ToolApprovalBatch::getUserId, userId)
                                .eq(ToolApprovalBatch::getStatus, STATUS_PENDING)
                                .orderByDesc(ToolApprovalBatch::getId))
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public List<ApprovalBatchVO> listHistory(Long userId) {
        return batchMapper.selectList(
                        new LambdaQueryWrapper<ToolApprovalBatch>()
                                .eq(ToolApprovalBatch::getUserId, userId)
                                .ne(ToolApprovalBatch::getStatus, STATUS_PENDING)
                                .orderByDesc(ToolApprovalBatch::getId)
                                .last("LIMIT 100"))
                .stream()
                .map(this::toVO)
                .toList();
    }

    // ---- helpers ----

    /** action → decision 文本：1=允许 approve，2/3=拒绝 reject（3 带自定义消息） */
    private String decisionOf(Integer action) {
        return action != null && action == ACTION_ALLOW ? "approve" : "reject";
    }

    private Map<String, Map<String, String>> buildDecisionMap(HandleApprovalBatchRequest request) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (request.getDecisions() == null) {
            return out;
        }
        request.getDecisions().forEach((childRequestId, d) -> {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("decision", decisionOf(d.getAction()));
            entry.put("reason", d.getCustomMessage() == null ? "" : d.getCustomMessage());
            out.put(childRequestId, entry);
        });
        return out;
    }

    /** runtime /approvals/callback 的 result 载荷：{decision, reason, decisions?}，与 spawn 容器 _apply_decisions 对齐 */
    private Map<String, Object> buildCallbackResult(
            HandleApprovalBatchRequest request, Map<String, Map<String, String>> decisionMap) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("decision", decisionOf(request.getAction()));
        result.put("reason", request.getCustomMessage() == null ? "" : request.getCustomMessage());
        if (!decisionMap.isEmpty()) {
            result.put("decisions", decisionMap);
        }
        return result;
    }

    private ApprovalBatchVO toVO(ToolApprovalBatch r) {
        ApprovalBatchVO vo = new ApprovalBatchVO();
        vo.setBatchId(r.getId());
        vo.setRequestId(r.getRequestId());
        vo.setAgentId(r.getAgentId());
        Agent agent = agentMapper.selectById(r.getAgentId());
        vo.setAgentName(agent == null ? null : agent.getName());
        vo.setClawId(r.getClawId());
        vo.setSubRequests(readSubRequests(r.getSubRequests()));
        vo.setStatus(r.getStatus());
        vo.setAction(r.getAction());
        vo.setCustomMessage(r.getCustomMessage());
        vo.setCreatedAt(r.getCreatedAt());
        vo.setHandledAt(r.getHandledAt());
        return vo;
    }

    private List<ApprovalBatchVO.SubRequestVO> readSubRequests(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ApprovalBatchVO.SubRequestVO>>() {});
        } catch (Exception e) {
            return List.of();  // 序列化兜底，不影响列表主流程
        }
    }

    private String writeJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new BizException(500, "审批明细序列化失败");
        }
    }
}
