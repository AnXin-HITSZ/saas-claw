package com.saasclaw.backend.service;

import com.saasclaw.backend.dto.AgentCreateRequest;
import com.saasclaw.backend.dto.AgentUpdateRequest;
import com.saasclaw.backend.entity.Agent;
import com.saasclaw.backend.entity.Skill;

import java.util.List;

public interface AgentService {

    /** 当前用户的 Agent 列表（可选按 claw 过滤） */
    List<Agent> list(Long userId, Long clawId);

    /** 创建：claw 归属 + base_model 引用 + alias 用户级唯一校验 */
    Agent create(Long userId, AgentCreateRequest request);

    /** 部分更新：alias / clawId 不可改 */
    Agent update(Long userId, Long id, AgentUpdateRequest request);

    /** 软删：只能删自己的 Agent */
    void delete(Long userId, Long id);

    /** 该 Agent 已启用的 Skill 列表 */
    List<Skill> listSkills(Long userId, Long agentId);

    /** 绑定 Skill 到 Agent（校验 agent 归属 + skill 可用性 + 唯一） */
    void bindSkill(Long userId, Long agentId, Long skillId);

    /** 解绑 */
    void unbindSkill(Long userId, Long agentId, Long skillId);
}
