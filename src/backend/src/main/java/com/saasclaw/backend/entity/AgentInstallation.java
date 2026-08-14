package com.saasclaw.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** agent_installation：Agent 商店安装记录（Claw 级归属） */
@Data
@TableName("agent_installation")
public class AgentInstallation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 源 Agent id（商店里的源 Agent） */
    private Long agentId;

    /** 安装者 user id */
    private Long userId;

    /** 安装目标 Claw id（副本归属的 Claw，NOT NULL） */
    private Long clawId;

    /** 本地副本 Agent id（快照，source='shop'，归属安装者） */
    private Long localAgentId;

    /** 1=已安装 0=已卸载 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
