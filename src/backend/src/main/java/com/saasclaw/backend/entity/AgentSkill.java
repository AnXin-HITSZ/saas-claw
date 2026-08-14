package com.saasclaw.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Agent-Skill 绑定：Agent 启用了哪个 Skill（uk_agent_skill 唯一） */
@Data
@TableName("agent_skill")
public class AgentSkill {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long agentId;

    private Long skillId;

    private LocalDateTime createdAt;
}