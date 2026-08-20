package com.saasclaw.backend.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/** 处理批量审批（人工通道）：整体决策 + 可选逐子请求覆盖 */
@Data
public class HandleApprovalBatchRequest {

    /** 整体决策 1=允许 2=拒绝 3=自定义消息 */
    @NotNull(message = "action 不能为空")
    private Integer action;

    /** action=3 时必填（Service 层校验） */
    @Size(max = 512, message = "customMessage 过长")
    private String customMessage;

    /** 逐子请求覆盖：{child_request_id: {action, custom_message}}，缺省按整体决策 */
    private Map<String, Decision> decisions;

    @Data
    public static class Decision {
        /** 1=允许 2=拒绝 */
        @NotNull(message = "子请求 action 不能为空")
        private Integer action;
        @Size(max = 512, message = "customMessage 过长")
        private String customMessage;
    }
}
