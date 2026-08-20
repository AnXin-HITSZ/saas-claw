package com.saasclaw.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** tool_approval_batch：spawn_subagent 聚合子任务敏感操作生成的批量审批留痕表 */
@Data
@TableName("tool_approval_batch")
public class ToolApprovalBatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 批量审批请求 ID（approval:batch:{spawn_id}，回调 resume 关联键） */
    private String requestId;

    /** 需要确认的用户 */
    private Long userId;

    /** 归属 Claw */
    private Long clawId;

    /** 发起 spawn 的父 Agent */
    private Long agentId;

    /** 子请求明细 JSON 数组（[{request_id, agent_id, tool_id, tool_name, input_summary}]） */
    private String subRequests;

    /** 整体决策 1=允许 2=拒绝 3=自定义消息 */
    private Integer action;

    /** 自定义消息（action=3 时必填，作为整体决策 reason 返回 runtime 恢复主图） */
    private String customMessage;

    /** 逐子请求决策 JSON（{child_request_id: {decision, reason}}，null=按整体决策） */
    private String decisionJson;

    /** 0=待审批 1=已处理 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime handledAt;
}
