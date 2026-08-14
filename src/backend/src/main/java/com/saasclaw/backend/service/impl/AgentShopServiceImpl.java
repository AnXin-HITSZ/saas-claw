package com.saasclaw.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.saasclaw.backend.common.BizException;
import com.saasclaw.backend.entity.*;
import com.saasclaw.backend.mapper.AgentFileMapper;
import com.saasclaw.backend.mapper.AgentInstallationMapper;
import com.saasclaw.backend.mapper.AgentMapper;
import com.saasclaw.backend.mapper.AgentShopMapper;
import com.saasclaw.backend.mapper.AgentSkillMapper;
import com.saasclaw.backend.mapper.ClawMapper;
import com.saasclaw.backend.mapper.SkillFileMapper;
import com.saasclaw.backend.mapper.SkillMapper;
import com.saasclaw.backend.mapper.SkillShopMapper;
import com.saasclaw.backend.mapper.UserMapper;
import com.saasclaw.backend.service.AgentShopService;
import com.saasclaw.backend.service.OssService;
import com.saasclaw.backend.vo.AgentInstallVO;
import com.saasclaw.backend.vo.MissingSkillVO;
import com.saasclaw.backend.vo.MyAgentInstallationVO;
import com.saasclaw.backend.vo.ShopAgentVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AgentShopServiceImpl implements AgentShopService {

    private final AgentShopMapper agentShopMapper;
    private final AgentInstallationMapper agentInstallationMapper;
    private final AgentMapper agentMapper;
    private final ClawMapper clawMapper;
    private final AgentSkillMapper agentSkillMapper;
    private final SkillMapper skillMapper;
    private final SkillFileMapper skillFileMapper;
    private final SkillShopMapper skillShopMapper;
    private final AgentFileMapper agentFileMapper;
    private final UserMapper userMapper;
    private final OssService ossService;

    @Override
    public void publish(Long userId, Long agentId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null || agent.getStatus() == 0 || !agent.getUserId().equals(userId)) {
            throw new BizException(404, "Agent 不存在");
        }
        if (!"self".equals(agent.getSource())) {
            throw new BizException(400, "仅自建 Agent 可上架");
        }

        Long count = agentShopMapper.selectCount(
                new LambdaQueryWrapper<AgentShop>().eq(AgentShop::getAgentId, agentId));
        if (count > 0) {
            throw new BizException(409, "该 Agent 已上架");
        }

        AgentShop shop = new AgentShop();
        shop.setAgentId(agentId);
        shop.setPublisherId(userId);
        shop.setInstalls(0);
        shop.setStatus(1);
        agentShopMapper.insert(shop);
    }

    @Override
    public void unpublish(Long userId, Long agentId) {
        AgentShop shop = agentShopMapper.selectOne(
                new LambdaQueryWrapper<AgentShop>().eq(AgentShop::getAgentId, agentId));
        if (shop == null || shop.getStatus() == 0 || !shop.getPublisherId().equals(userId)) {
            throw new BizException(404, "该 Agent 未上架或不属于你");
        }
        agentShopMapper.update(null,
                new LambdaUpdateWrapper<AgentShop>()
                        .eq(AgentShop::getId, shop.getId())
                        .set(AgentShop::getStatus, 0));
    }

    @Override
    public List<ShopAgentVO> listShop() {
        List<AgentShop> shops = agentShopMapper.selectList(
                new LambdaQueryWrapper<AgentShop>()
                        .eq(AgentShop::getStatus, 1)
                        .orderByDesc(AgentShop::getId));
        if (shops.isEmpty()) {
            return List.of();
        }

        List<Long> agentIds = shops.stream().map(AgentShop::getAgentId).toList();
        Map<Long, Agent> agentMap = agentMapper.selectBatchIds(agentIds).stream()
                .collect(Collectors.toMap(Agent::getId, a -> a));
        List<Long> publisherIds = shops.stream().map(AgentShop::getPublisherId).distinct().toList();
        Map<Long, User> userMap = userMapper.selectBatchIds(publisherIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        return shops.stream()
                // 发布者已软删该 agent 的商品不再展示
                .filter(shop -> {
                    Agent a = agentMap.get(shop.getAgentId());
                    return a != null && a.getStatus() == 1;
                })
                .map(shop -> {
                    ShopAgentVO vo = new ShopAgentVO();
                    vo.setAgentId(shop.getAgentId());
                    Agent a = agentMap.get(shop.getAgentId());
                    vo.setName(a.getName());
                    vo.setAlias(a.getAlias());
                    vo.setDescription(a.getDescription());
                    vo.setVersion(a.getVersion());
                    vo.setAuthor(a.getAuthor());
                    vo.setBaseModel(a.getBaseModel());
                    vo.setPublisherId(shop.getPublisherId());
                    User publisher = userMap.get(shop.getPublisherId());
                    vo.setPublisherNickname(publisher == null ? null : publisher.getNickname());
                    vo.setInstalls(shop.getInstalls());
                    vo.setCreatedAt(shop.getCreatedAt());
                    return vo;
                })
                .toList();
    }

    @Override
    @Transactional
    public AgentInstallVO install(Long userId, Long agentId, Long clawId) {
        AgentShop shop = agentShopMapper.selectOne(
                new LambdaQueryWrapper<AgentShop>()
                        .eq(AgentShop::getAgentId, agentId)
                        .eq(AgentShop::getStatus, 1));
        if (shop == null) {
            throw new BizException(404, "该 Agent 未上架");
        }
        Agent source = agentMapper.selectById(agentId);
        if (source == null || source.getStatus() == 0) {
            throw new BizException(404, "Agent 不存在");
        }

        // 安装目标 Claw 必须本人所有且启用
        Claw claw = clawMapper.selectOne(
                new LambdaQueryWrapper<Claw>()
                        .eq(Claw::getId, clawId)
                        .eq(Claw::getUserId, userId)
                        .eq(Claw::getStatus, 1));
        if (claw == null) {
            throw new BizException(400, "Claw 不存在或不可用");
        }

        // 副本命名空间唯一：同一用户下 alias 已存在（自建或之前副本）→ 409
        Long exists = agentMapper.selectCount(
                new LambdaQueryWrapper<Agent>()
                        .eq(Agent::getUserId, userId)
                        .eq(Agent::getAlias, source.getAlias()));
        if (exists > 0) {
            throw new BizException(409, "你已有同名 Agent（alias 冲突），无法安装");
        }

        // 1. 建本地副本（快照，归属安装者，绑定目标 Claw）
        Agent copy = new Agent();
        copy.setUserId(userId);
        copy.setClawId(clawId);
        copy.setName(source.getName());
        copy.setAlias(source.getAlias());
        copy.setDescription(source.getDescription());
        copy.setSystemPrompt(source.getSystemPrompt());
        copy.setBaseModel(source.getBaseModel());
        copy.setTemperature(source.getTemperature());
        copy.setMaxTokens(source.getMaxTokens());
        copy.setSource("shop");
        copy.setVersion(source.getVersion());
        copy.setAuthor(source.getAuthor());
        copy.setStatus(1);
        agentMapper.insert(copy);

        // 2. 写安装记录（agent_id=源, local_agent_id=副本）
        AgentInstallation inst = new AgentInstallation();
        inst.setAgentId(agentId);
        inst.setUserId(userId);
        inst.setClawId(clawId);
        inst.setLocalAgentId(copy.getId());
        inst.setStatus(1);
        agentInstallationMapper.insert(inst);

        // 3. 安装数自增
        agentShopMapper.update(null,
                new LambdaUpdateWrapper<AgentShop>()
                        .eq(AgentShop::getId, shop.getId())
                        .setSql("installs = installs + 1"));

        // 4. 复制源 Agent 的文件到副本（快照：OSS copyObject + 插副本文件记录）
        copyAgentFiles(agentId, copy.getId());

        // 5. 比对缺失 Skill（供前端一键安装引导）
        AgentInstallVO vo = new AgentInstallVO();
        vo.setInstallationId(inst.getId());
        vo.setLocalAgentId(copy.getId());
        vo.setClawId(clawId);
        vo.setMissingSkills(findMissingSkills(userId, agentId));
        return vo;
    }

    @Override
    public List<MyAgentInstallationVO> listMyInstallations(Long userId) {
        List<AgentInstallation> insts = agentInstallationMapper.selectList(
                new LambdaQueryWrapper<AgentInstallation>()
                        .eq(AgentInstallation::getUserId, userId)
                        .eq(AgentInstallation::getStatus, 1)
                        .orderByDesc(AgentInstallation::getId));
        if (insts.isEmpty()) {
            return List.of();
        }

        List<Long> localIds = insts.stream().map(AgentInstallation::getLocalAgentId).toList();
        Map<Long, Agent> agentMap = agentMapper.selectBatchIds(localIds).stream()
                .collect(Collectors.toMap(Agent::getId, a -> a));

        return insts.stream().map(inst -> {
            MyAgentInstallationVO vo = new MyAgentInstallationVO();
            vo.setInstallationId(inst.getId());
            vo.setClawId(inst.getClawId());
            vo.setAgentId(inst.getLocalAgentId());
            Agent a = agentMap.get(inst.getLocalAgentId());
            if (a != null) {
                vo.setName(a.getName());
                vo.setAlias(a.getAlias());
                vo.setDescription(a.getDescription());
                vo.setVersion(a.getVersion());
                vo.setAuthor(a.getAuthor());
                vo.setBaseModel(a.getBaseModel());
            }
            vo.setInstalledAt(inst.getCreatedAt());
            return vo;
        }).toList();
    }

    @Override
    public void uninstall(Long userId, Long installationId) {
        AgentInstallation inst = agentInstallationMapper.selectById(installationId);
        if (inst == null || inst.getStatus() == 0 || !inst.getUserId().equals(userId)) {
            throw new BizException(404, "安装记录不存在");
        }

        // 双软删：安装记录 + 本地副本
        agentInstallationMapper.update(null,
                new LambdaUpdateWrapper<AgentInstallation>()
                        .eq(AgentInstallation::getId, installationId)
                        .set(AgentInstallation::getStatus, 0));
        agentMapper.update(null,
                new LambdaUpdateWrapper<Agent>()
                        .eq(Agent::getId, inst.getLocalAgentId())
                        .set(Agent::getStatus, 0));
    }

    /** 复制源 Agent 的文件到副本（OSS copyObject + agent_file 插记录） */
    private void copyAgentFiles(Long sourceAgentId, Long copyAgentId) {
        List<AgentFile> srcFiles = agentFileMapper.selectList(
                new LambdaQueryWrapper<AgentFile>().eq(AgentFile::getAgentId, sourceAgentId));
        for (AgentFile f : srcFiles) {
            String destKey = "agent/" + copyAgentId + "/" + f.getFileName();
            String url = ossService.copy("agent/" + sourceAgentId + "/" + f.getFileName(), destKey);
            AgentFile cf = new AgentFile();
            cf.setAgentId(copyAgentId);
            cf.setFileName(f.getFileName());
            cf.setFileUrl(url);
            cf.setFileType(f.getFileType());
            cf.setFileSize(f.getFileSize());
            cf.setFileHash(f.getFileHash());
            agentFileMapper.insert(cf);
        }
    }

    /**
     * 比对源 Agent 依赖的 Skill 与当前用户已拥有的 Skill：
     * 优先用 SKILL.md 的 file_hash 严格比对内容；源 Skill 无 SKILL.md 时 fallback 按 name 匹配；
     * 平台公共 skill（user_id=0）视为全局可用，跳过。
     */
    private List<MissingSkillVO> findMissingSkills(Long userId, Long sourceAgentId) {
        List<Long> boundIds = agentSkillMapper.selectList(
                        new LambdaQueryWrapper<AgentSkill>()
                                .eq(AgentSkill::getAgentId, sourceAgentId))
                .stream()
                .map(AgentSkill::getSkillId)
                .toList();
        if (boundIds.isEmpty()) {
            return List.of();
        }

        List<Skill> boundSkills = skillMapper.selectBatchIds(boundIds);
        List<Skill> mySkills = skillMapper.selectList(
                new LambdaQueryWrapper<Skill>()
                        .eq(Skill::getUserId, userId)
                        .eq(Skill::getStatus, 1));

        // 我拥有的 skill 的 SKILL.md hash 集合
        Set<String> myHashes = new HashSet<>();
        for (Skill s : mySkills) {
            SkillFile f = skillFileMapper.selectOne(
                    new LambdaQueryWrapper<SkillFile>()
                            .eq(SkillFile::getSkillId, s.getId())
                            .eq(SkillFile::getFileName, "SKILL.md"));
            if (f != null && f.getFileHash() != null) {
                myHashes.add(f.getFileHash());
            }
        }

        List<MissingSkillVO> missing = new ArrayList<>();
        for (Skill bound : boundSkills) {
            if (bound == null || bound.getStatus() == 0) {
                continue;
            }
            // 平台公共 skill 全局可用，不算缺失
            if (bound.getUserId() != null && bound.getUserId() == 0L) {
                continue;
            }
            SkillFile src = skillFileMapper.selectOne(
                    new LambdaQueryWrapper<SkillFile>()
                            .eq(SkillFile::getSkillId, bound.getId())
                            .eq(SkillFile::getFileName, "SKILL.md"));
            boolean owned;
            if (src != null && src.getFileHash() != null) {
                owned = myHashes.contains(src.getFileHash());
            } else {
                owned = mySkills.stream().anyMatch(s -> s.getName().equals(bound.getName()));
            }
            if (!owned) {
                boolean installable = skillShopMapper.selectCount(
                        new LambdaQueryWrapper<SkillShop>()
                                .eq(SkillShop::getSkillId, bound.getId())
                                .eq(SkillShop::getStatus, 1)) > 0;
                MissingSkillVO m = new MissingSkillVO();
                m.setSkillId(bound.getId());
                m.setName(bound.getName());
                m.setInstallable(installable);
                missing.add(m);
            }
        }
        return missing;
    }
}
