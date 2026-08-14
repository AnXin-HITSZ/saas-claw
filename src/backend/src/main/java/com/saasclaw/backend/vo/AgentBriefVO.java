package com.saasclaw.backend.vo;

import lombok.Data;

/** 卸载提示里回传的绑定 Agent 摘要 */
@Data
public class AgentBriefVO {

    private Long agentId;

    private String agentName;
}