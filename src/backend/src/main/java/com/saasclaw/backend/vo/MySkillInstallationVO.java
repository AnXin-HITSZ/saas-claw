package com.saasclaw.backend.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 我的资源池项：安装记录 + 本地副本 + 被 Agent 绑定计数 */
@Data
public class MySkillInstallationVO {

    /** 安装记录 id（卸载接口入参） */
    private Long installationId;

    /** 本地副本 skill id */
    private Long skillId;

    private String name;

    private String description;

    private String version;

    private String author;

    /** 正被多少 Agent 绑定使用 */
    private Integer boundAgentCount;

    private LocalDateTime installedAt;
}