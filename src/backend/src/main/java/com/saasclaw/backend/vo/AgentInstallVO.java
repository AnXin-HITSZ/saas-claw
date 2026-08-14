package com.saasclaw.backend.vo;

import lombok.Data;

import java.util.List;

/** Agent 安装结果：副本已建，附缺失 Skill 清单（供一键安装） */
@Data
public class AgentInstallVO {

    private Long installationId;
    /** 本地副本 Agent id */
    private Long localAgentId;
    /** 副本所属 Claw id */
    private Long clawId;
    /** 源 Agent 依赖但当前用户尚未拥有的 Skill */
    private List<MissingSkillVO> missingSkills;
}
