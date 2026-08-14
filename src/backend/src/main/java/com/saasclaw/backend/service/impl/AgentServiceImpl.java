package com.saasclaw.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.saasclaw.backend.common.BizException;
import com.saasclaw.backend.dto.AgentCreateRequest;
import com.saasclaw.backend.dto.AgentUpdateRequest;
import com.saasclaw.backend.entity.Agent;
import com.saasclaw.backend.entity.AgentSkill;
import com.saasclaw.backend.entity.Claw;
import com.saasclaw.backend.entity.ModelConfig;
import com.saasclaw.backend.entity.Skill;
import com.saasclaw.backend.mapper.AgentMapper;
import com.saasclaw.backend.mapper.AgentSkillMapper;
import com.saasclaw.backend.mapper.ClawMapper;
import com.saasclaw.backend.mapper.ModelConfigMapper;
import com.saasclaw.backend.mapper.SkillMapper;
import com.saasclaw.backend.service.AgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {

    private final AgentMapper agentMapper;
    private final ClawMapper clawMapper;
    private final ModelConfigMapper modelConfigMapper;
    private final AgentSkillMapper agentSkillMapper;
    private final SkillMapper skillMapper;

    @Override
    public List<Agent> list(Long userId, Long clawId) {
        LambdaQueryWrapper<Agent> wrapper = new LambdaQueryWrapper<Agent>()
                .eq(Agent::getUserId, userId)
                .eq(Agent::getStatus, 1);
        if (clawId != null) {
            wrapper.eq(Agent::getClawId, clawId);
        }
        return agentMapper.selectList(wrapper.orderByDesc(Agent::getId));
    }

    @Override
    public Agent create(Long userId, AgentCreateRequest request) {
        // 1. Claw 归属校验：claw 必须属于当前用户且启用
        Claw claw = clawMapper.selectOne(
                new LambdaQueryWrapper<Claw>()
                        .eq(Claw::getId, request.getClawId())
                        .eq(Claw::getUserId, userId)
                        .eq(Claw::getStatus, 1));
        if (claw == null) {
            throw new BizException(400, "Claw 不存在");
        }

        // 2. base_model 引用校验：必须指向存在的 model_config
        Long modelCount = modelConfigMapper.selectCount(
                new LambdaQueryWrapper<ModelConfig>()
                        .eq(ModelConfig::getName, request.getBaseModel())
                        .eq(ModelConfig::getStatus, 1));
        if (modelCount == 0) {
            throw new BizException(400, "模型配置不存在");
        }

        // 3. alias 用户级唯一校验：(user_id, alias)
        Long aliasCount = agentMapper.selectCount(
                new LambdaQueryWrapper<Agent>()
                        .eq(Agent::getUserId, userId)
                        .eq(Agent::getAlias, request.getAlias()));
        if (aliasCount > 0) {
            throw new BizException(409, "alias 已存在");
        }

        Agent agent = new Agent();
        agent.setClawId(request.getClawId());
        agent.setUserId(userId);
        agent.setName(request.getName());
        agent.setAlias(request.getAlias());
        agent.setDescription(request.getDescription());
        agent.setSystemPrompt(request.getSystemPrompt());
        agent.setBaseModel(request.getBaseModel());
        agent.setTemperature(request.getTemperature() == null ? 0.7 : request.getTemperature());
        agent.setMaxTokens(request.getMaxTokens() == null ? 4096 : request.getMaxTokens());
        agent.setSource("self");
        agent.setVersion("1.0.0");
        agent.setStatus(1);
        agentMapper.insert(agent);
        return agent;
    }

    @Override
    public Agent update(Long userId, Long id, AgentUpdateRequest request) {
        getOwned(userId, id);

        // 改了 base_model 则校验引用存在
        if (request.getBaseModel() != null) {
            Long modelCount = modelConfigMapper.selectCount(
                    new LambdaQueryWrapper<ModelConfig>()
                            .eq(ModelConfig::getName, request.getBaseModel())
                            .eq(ModelConfig::getStatus, 1));
            if (modelCount == 0) {
                throw new BizException(400, "模型配置不存在");
            }
        }

        LambdaUpdateWrapper<Agent> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Agent::getId, id);
        if (request.getName() != null) {
            wrapper.set(Agent::getName, request.getName());
        }
        if (request.getDescription() != null) {
            wrapper.set(Agent::getDescription, request.getDescription());
        }
        if (request.getSystemPrompt() != null) {
            wrapper.set(Agent::getSystemPrompt, request.getSystemPrompt());
        }
        if (request.getBaseModel() != null) {
            wrapper.set(Agent::getBaseModel, request.getBaseModel());
        }
        if (request.getTemperature() != null) {
            wrapper.set(Agent::getTemperature, request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            wrapper.set(Agent::getMaxTokens, request.getMaxTokens());
        }
        agentMapper.update(null, wrapper);

        return agentMapper.selectById(id);
    }

    @Override
    public void delete(Long userId, Long id) {
        getOwned(userId, id);

        agentMapper.update(null,
                new LambdaUpdateWrapper<Agent>()
                        .eq(Agent::getId, id)
                        .set(Agent::getStatus, 0));
    }

    @Override
    public List<Skill> listSkills(Long userId, Long agentId) {
        getOwned(userId, agentId);

        List<Long> skillIds = agentSkillMapper.selectList(
                        new LambdaQueryWrapper<AgentSkill>()
                                .eq(AgentSkill::getAgentId, agentId))
                .stream()
                .map(AgentSkill::getSkillId)
                .toList();
        if (skillIds.isEmpty()) {
            return List.of();
        }
        return skillMapper.selectBatchIds(skillIds);
    }

    @Override
    public void bindSkill(Long userId, Long agentId, Long skillId) {
        getOwned(userId, agentId);

        // skill 可用性：status=1 且 平台默认(0) 或 本人自建
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null || skill.getStatus() == 0
                || (skill.getUserId().longValue() != 0L && !skill.getUserId().equals(userId))) {
            throw new BizException(404, "Skill 不存在");
        }

        // 唯一性：同一 Agent 不能重复绑定同一 Skill
        Long count = agentSkillMapper.selectCount(
                new LambdaQueryWrapper<AgentSkill>()
                        .eq(AgentSkill::getAgentId, agentId)
                        .eq(AgentSkill::getSkillId, skillId));
        if (count > 0) {
            throw new BizException(409, "该 Agent 已绑定此 Skill");
        }

        AgentSkill bind = new AgentSkill();
        bind.setAgentId(agentId);
        bind.setSkillId(skillId);
        agentSkillMapper.insert(bind);
    }

    @Override
    public void unbindSkill(Long userId, Long agentId, Long skillId) {
        getOwned(userId, agentId);

        Long count = agentSkillMapper.selectCount(
                new LambdaQueryWrapper<AgentSkill>()
                        .eq(AgentSkill::getAgentId, agentId)
                        .eq(AgentSkill::getSkillId, skillId));
        if (count == 0) {
            throw new BizException(404, "未绑定此 Skill");
        }
        agentSkillMapper.delete(
                new LambdaQueryWrapper<AgentSkill>()
                        .eq(AgentSkill::getAgentId, agentId)
                        .eq(AgentSkill::getSkillId, skillId));
    }

    /**
     * 归属校验：不存在 / 已软删 / 不属于当前用户，一律视为 404。
     * 故意不区分"不存在"和"别人的"，避免泄露资源存在性。
     */
    private Agent getOwned(Long userId, Long id) {
        Agent agent = agentMapper.selectById(id);
        if (agent == null || agent.getStatus() == 0 || !agent.getUserId().equals(userId)) {
            throw new BizException(404, "Agent 不存在");
        }
        return agent;
    }
}
