package com.saasclaw.backend.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 我安装的 Agent 列表项（本地副本） */
@Data
public class MyAgentInstallationVO {

    private Long installationId;
    /** 本地副本 Agent id */
    private Long agentId;
    private String name;
    private String alias;
    private String description;
    private String version;
    private String author;
    private String baseModel;
    /** 安装目标 Claw id */
    private Long clawId;
    private LocalDateTime installedAt;
}
