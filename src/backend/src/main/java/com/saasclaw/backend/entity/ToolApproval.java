package com.saasclaw.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** tool_approval：敏感工具调用前用户审批留痕表 */
@Data
@TableName("tool_approval")
public class ToolApproval {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 审批请求 ID（runtime 传的幂等键，uk_request 唯一，回调关联用） */
    private String requestId;

    /** 需要确认的用户 */
    private Long userId;

    /** 归属 Claw */
    private Long clawId;

    /** 哪个 Agent 要调 */
    private Long agentId;

    /** 哪个敏感工具 */
    private Long toolId;

    /** 入参摘要（展示给用户看的关键信息） */
    private String inputSummary;

    /** 1=允许 2=拒绝 3=自定义消息 */
    private Integer action;

    /** action=3 时用户改写的内容 */
    private String customMessage;

    /** 0=待审批 1=已处理 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime handledAt;
}