package com.saasclaw.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.saasclaw.backend.common.BizException;
import com.saasclaw.backend.vo.AgentBriefVO;
import com.saasclaw.backend.vo.BatchFailItemVO;
import com.saasclaw.backend.vo.InstallBatchResultVO;
import com.saasclaw.backend.vo.MySkillInstallationVO;
import com.saasclaw.backend.vo.ShopSkillVO;
import com.saasclaw.backend.entity.AgentSkill;
import com.saasclaw.backend.entity.Skill;
import com.saasclaw.backend.entity.SkillFile;
import com.saasclaw.backend.entity.SkillInstallation;
import com.saasclaw.backend.entity.SkillShop;
import com.saasclaw.backend.entity.User;
import com.saasclaw.backend.mapper.AgentMapper;
import com.saasclaw.backend.mapper.AgentSkillMapper;
import com.saasclaw.backend.mapper.SkillFileMapper;
import com.saasclaw.backend.mapper.SkillInstallationMapper;
import com.saasclaw.backend.mapper.SkillMapper;
import com.saasclaw.backend.mapper.SkillShopMapper;
import com.saasclaw.backend.mapper.UserMapper;
import com.saasclaw.backend.service.OssService;
import com.saasclaw.backend.service.SkillShopService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SkillShopServiceImpl implements SkillShopService {

    private final SkillShopMapper skillShopMapper;
    private final SkillInstallationMapper skillInstallationMapper;
    private final SkillMapper skillMapper;
    private final AgentSkillMapper agentSkillMapper;
    private final AgentMapper agentMapper;
    private final UserMapper userMapper;
    private final SkillFileMapper skillFileMapper;
    private final OssService ossService;
    private final TransactionTemplate transactionTemplate;

    @Override
    public void publish(Long userId, Long skillId) {
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null || skill.getStatus() == 0 || !skill.getUserId().equals(userId)) {
            throw new BizException(404, "Skill 不存在");
        }
        if (!"self".equals(skill.getSource())) {
            throw new BizException(400, "仅自建 Skill 可上架");
        }

        Long count = skillShopMapper.selectCount(
                new LambdaQueryWrapper<SkillShop>().eq(SkillShop::getSkillId, skillId));
        if (count > 0) {
            throw new BizException(409, "该 Skill 已上架");
        }

        SkillShop shop = new SkillShop();
        shop.setSkillId(skillId);
        shop.setPublisherId(userId);
        shop.setInstalls(0);
        shop.setStatus(1);
        skillShopMapper.insert(shop);
    }

    @Override
    public void unpublish(Long userId, Long skillId) {
        SkillShop shop = skillShopMapper.selectOne(
                new LambdaQueryWrapper<SkillShop>().eq(SkillShop::getSkillId, skillId));
        if (shop == null || shop.getStatus() == 0 || !shop.getPublisherId().equals(userId)) {
            throw new BizException(404, "该 Skill 未上架或不属于你");
        }
        skillShopMapper.update(null,
                new LambdaUpdateWrapper<SkillShop>()
                        .eq(SkillShop::getId, shop.getId())
                        .set(SkillShop::getStatus, 0));
    }

    @Override
    public List<ShopSkillVO> listShop() {
        List<SkillShop> shops = skillShopMapper.selectList(
                new LambdaQueryWrapper<SkillShop>()
                        .eq(SkillShop::getStatus, 1)
                        .orderByDesc(SkillShop::getId));
        if (shops.isEmpty()) {
            return List.of();
        }

        List<Long> skillIds = shops.stream().map(SkillShop::getSkillId).toList();
        Map<Long, Skill> skillMap = skillMapper.selectBatchIds(skillIds).stream()
                .collect(Collectors.toMap(Skill::getId, s -> s));
        List<Long> publisherIds = shops.stream().map(SkillShop::getPublisherId).distinct().toList();
        Map<Long, User> userMap = userMapper.selectBatchIds(publisherIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        return shops.stream()
                // 发布者已软删该 skill 的商品不再展示
                .filter(shop -> {
                    Skill s = skillMap.get(shop.getSkillId());
                    return s != null && s.getStatus() == 1;
                })
                .map(shop -> {
                    ShopSkillVO vo = new ShopSkillVO();
                    vo.setSkillId(shop.getSkillId());
                    Skill s = skillMap.get(shop.getSkillId());
                    vo.setName(s.getName());
                    vo.setDescription(s.getDescription());
                    vo.setVersion(s.getVersion());
                    vo.setAuthor(s.getAuthor());
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
    public SkillInstallation install(Long userId, Long skillId) {
        return installInternal(userId, skillId);
    }

    /** 单个安装的实际逻辑（无事务，由 install / installBatch 各自的事务边界包裹） */
    private SkillInstallation installInternal(Long userId, Long skillId) {
        SkillShop shop = skillShopMapper.selectOne(
                new LambdaQueryWrapper<SkillShop>()
                        .eq(SkillShop::getSkillId, skillId)
                        .eq(SkillShop::getStatus, 1));
        if (shop == null) {
            throw new BizException(404, "该 Skill 未上架");
        }
        Skill source = skillMapper.selectById(skillId);
        if (source == null || source.getStatus() == 0) {
            throw new BizException(404, "Skill 不存在");
        }

        // 副本命名空间唯一：同一用户下 (user_id, name) 已存在（自建或之前副本）→ 409
        Long exists = skillMapper.selectCount(
                new LambdaQueryWrapper<Skill>()
                        .eq(Skill::getUserId, userId)
                        .eq(Skill::getName, source.getName()));
        if (exists > 0) {
            throw new BizException(409, "你已有同名 Skill，无法安装");
        }

        // 1. 建本地副本（快照，归属安装者）
        Skill copy = new Skill();
        copy.setUserId(userId);
        copy.setName(source.getName());
        copy.setDescription(source.getDescription());
        copy.setSource("shop");
        copy.setVersion(source.getVersion());
        copy.setAuthor(source.getAuthor());
        copy.setStatus(1);
        skillMapper.insert(copy);

        // 2. 写安装记录（skill_id=源, local_skill_id=副本）
        SkillInstallation inst = new SkillInstallation();
        inst.setSkillId(skillId);
        inst.setUserId(userId);
        inst.setLocalSkillId(copy.getId());
        inst.setStatus(1);
        skillInstallationMapper.insert(inst);

        // 3. 安装数自增
        skillShopMapper.update(null,
                new LambdaUpdateWrapper<SkillShop>()
                        .eq(SkillShop::getId, shop.getId())
                        .setSql("installs = installs + 1"));

        // 4. 复制源 skill 的文件到副本（快照：OSS copyObject + 插副本文件记录）
        copyFiles(skillId, copy.getId());

        return inst;
    }

    @Override
    public InstallBatchResultVO installBatch(Long userId, List<Long> skillIds) {
        List<SkillInstallation> succeeded = new ArrayList<>();
        List<BatchFailItemVO> failed = new ArrayList<>();
        for (Long skillId : skillIds) {
            try {
                // 每个 skill 独立事务：单个失败只回滚自身，不拖垮整批
                SkillInstallation inst = transactionTemplate.execute(status -> installInternal(userId, skillId));
                if (inst != null) {
                    succeeded.add(inst);
                }
            } catch (BizException e) {
                BatchFailItemVO item = new BatchFailItemVO();
                item.setSkillId(skillId);
                item.setReason(e.getMessage());
                failed.add(item);
            }
        }
        InstallBatchResultVO vo = new InstallBatchResultVO();
        vo.setSucceeded(succeeded);
        vo.setFailed(failed);
        return vo;
    }

    @Override
    public List<MySkillInstallationVO> listMyInstallations(Long userId) {
        List<SkillInstallation> insts = skillInstallationMapper.selectList(
                new LambdaQueryWrapper<SkillInstallation>()
                        .eq(SkillInstallation::getUserId, userId)
                        .eq(SkillInstallation::getStatus, 1)
                        .orderByDesc(SkillInstallation::getId));
        if (insts.isEmpty()) {
            return List.of();
        }

        List<Long> localIds = insts.stream().map(SkillInstallation::getLocalSkillId).toList();
        Map<Long, Skill> skillMap = skillMapper.selectBatchIds(localIds).stream()
                .collect(Collectors.toMap(Skill::getId, s -> s));
        // 被 Agent 绑定计数
        Map<Long, Long> bindCount = agentSkillMapper.selectList(
                        new LambdaQueryWrapper<AgentSkill>().in(AgentSkill::getSkillId, localIds))
                .stream()
                .collect(Collectors.groupingBy(AgentSkill::getSkillId, Collectors.counting()));

        return insts.stream().map(inst -> {
            MySkillInstallationVO vo = new MySkillInstallationVO();
            vo.setInstallationId(inst.getId());
            vo.setSkillId(inst.getLocalSkillId());
            Skill s = skillMap.get(inst.getLocalSkillId());
            if (s != null) {
                vo.setName(s.getName());
                vo.setDescription(s.getDescription());
                vo.setVersion(s.getVersion());
                vo.setAuthor(s.getAuthor());
            }
            vo.setBoundAgentCount(bindCount.getOrDefault(inst.getLocalSkillId(), 0L).intValue());
            vo.setInstalledAt(inst.getCreatedAt());
            return vo;
        }).toList();
    }

    @Override
    public void uninstall(Long userId, Long installationId, boolean force) {
        SkillInstallation inst = skillInstallationMapper.selectById(installationId);
        if (inst == null || inst.getStatus() == 0 || !inst.getUserId().equals(userId)) {
            throw new BizException(404, "安装记录不存在");
        }
        Long localSkillId = inst.getLocalSkillId();

        // 副本正被 Agent 绑定：默认拦截，提示绑定列表；用户确认后 force 才删
        List<AgentSkill> binds = agentSkillMapper.selectList(
                new LambdaQueryWrapper<AgentSkill>().eq(AgentSkill::getSkillId, localSkillId));
        if (!binds.isEmpty() && !force) {
            List<AgentBriefVO> agents = agentMapper.selectBatchIds(
                            binds.stream().map(AgentSkill::getAgentId).toList())
                    .stream()
                    .map(a -> {
                        AgentBriefVO vo = new AgentBriefVO();
                        vo.setAgentId(a.getId());
                        vo.setAgentName(a.getName());
                        return vo;
                    })
                    .toList();
            throw new BizException(409, "该 Skill 正被 " + agents.size() + " 个 Agent 使用，请确认后强制卸载", agents);
        }

        // 双软删：安装记录 + 本地副本
        skillInstallationMapper.update(null,
                new LambdaUpdateWrapper<SkillInstallation>()
                        .eq(SkillInstallation::getId, installationId)
                        .set(SkillInstallation::getStatus, 0));
        skillMapper.update(null,
                new LambdaUpdateWrapper<Skill>()
                        .eq(Skill::getId, localSkillId)
                        .set(Skill::getStatus, 0));
    }

    /** 复制源 skill 的文件到副本（OSS copyObject + skill_file 插记录） */
    private void copyFiles(Long sourceSkillId, Long copySkillId) {
        List<SkillFile> srcFiles = skillFileMapper.selectList(
                new LambdaQueryWrapper<SkillFile>().eq(SkillFile::getSkillId, sourceSkillId));
        for (SkillFile f : srcFiles) {
            String destKey = "skill/" + copySkillId + "/" + f.getFileName();
            String url = ossService.copy("skill/" + sourceSkillId + "/" + f.getFileName(), destKey);
            SkillFile cf = new SkillFile();
            cf.setSkillId(copySkillId);
            cf.setFileName(f.getFileName());
            cf.setFileUrl(url);
            cf.setFileType(f.getFileType());
            cf.setFileSize(f.getFileSize());
            cf.setFileHash(f.getFileHash());
            skillFileMapper.insert(cf);
        }
    }
}