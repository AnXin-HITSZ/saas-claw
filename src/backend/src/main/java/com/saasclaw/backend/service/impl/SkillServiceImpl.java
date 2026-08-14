package com.saasclaw.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.saasclaw.backend.common.BizException;
import com.saasclaw.backend.dto.SkillCreateRequest;
import com.saasclaw.backend.dto.SkillUpdateRequest;
import com.saasclaw.backend.entity.Skill;
import com.saasclaw.backend.mapper.SkillMapper;
import com.saasclaw.backend.service.SkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SkillServiceImpl implements SkillService {

    private final SkillMapper skillMapper;

    @Override
    public List<Skill> list(Long userId) {
        // 平台默认 skill（user_id=0，全局共享）+ 当前用户自建的
        return skillMapper.selectList(
                new LambdaQueryWrapper<Skill>()
                        .eq(Skill::getStatus, 1)
                        .and(w -> w.eq(Skill::getUserId, 0).or().eq(Skill::getUserId, userId))
                        .orderByDesc(Skill::getId));
    }

    @Override
    public Skill create(Long userId, SkillCreateRequest request) {
        // 用户自建命名空间 (user_id, name) 唯一
        checkNameUnique(userId, request.getName(), null);
        return insert(userId, request);
    }

    @Override
    public Skill update(Long userId, Long id, SkillUpdateRequest request) {
        getOwned(userId, id);
        if (request.getName() != null) {
            checkNameUnique(userId, request.getName(), id);
        }
        return partialUpdate(id, request);
    }

    @Override
    public void delete(Long userId, Long id) {
        getOwned(userId, id);
        softDelete(id);
    }

    @Override
    public Skill createPlatform(SkillCreateRequest request) {
        // 平台命名空间 (0, name) 独立于任何用户
        checkNameUnique(0L, request.getName(), null);
        return insert(0L, request);
    }

    @Override
    public Skill updatePlatform(Long id, SkillUpdateRequest request) {
        getPlatform(id);
        if (request.getName() != null) {
            checkNameUnique(0L, request.getName(), id);
        }
        return partialUpdate(id, request);
    }

    @Override
    public void deletePlatform(Long id) {
        getPlatform(id);
        softDelete(id);
    }

    // ---- helpers ----

    private Skill insert(Long userId, SkillCreateRequest request) {
        Skill skill = new Skill();
        skill.setUserId(userId);
        skill.setName(request.getName());
        skill.setDescription(request.getDescription());
        skill.setSource("self");
        skill.setVersion(request.getVersion() == null ? "1.0.0" : request.getVersion());
        skill.setAuthor(request.getAuthor());
        skill.setStatus(1);
        skillMapper.insert(skill);
        return skill;
    }

    private Skill partialUpdate(Long id, SkillUpdateRequest request) {
        LambdaUpdateWrapper<Skill> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Skill::getId, id);
        if (request.getName() != null) {
            wrapper.set(Skill::getName, request.getName());
        }
        if (request.getDescription() != null) {
            wrapper.set(Skill::getDescription, request.getDescription());
        }
        if (request.getVersion() != null) {
            wrapper.set(Skill::getVersion, request.getVersion());
        }
        if (request.getAuthor() != null) {
            wrapper.set(Skill::getAuthor, request.getAuthor());
        }
        skillMapper.update(null, wrapper);
        return skillMapper.selectById(id);
    }

    private void softDelete(Long id) {
        skillMapper.update(null,
                new LambdaUpdateWrapper<Skill>()
                        .eq(Skill::getId, id)
                        .set(Skill::getStatus, 0));
    }

    private void checkNameUnique(Long userId, String name, Long excludeId) {
        LambdaQueryWrapper<Skill> wrapper = new LambdaQueryWrapper<Skill>()
                .eq(Skill::getUserId, userId)
                .eq(Skill::getName, name);
        if (excludeId != null) {
            wrapper.ne(Skill::getId, excludeId);
        }
        if (skillMapper.selectCount(wrapper) > 0) {
            throw new BizException(409, "Skill 名称已存在");
        }
    }

    /**
     * 归属校验（用户资源）：不存在 / 已软删 / 非本人（含平台 skill）一律 404。
     * 用户改不了平台 skill（user_id=0 != userId）。
     */
    private Skill getOwned(Long userId, Long id) {
        Skill skill = skillMapper.selectById(id);
        if (skill == null || skill.getStatus() == 0 || !skill.getUserId().equals(userId)) {
            throw new BizException(404, "Skill 不存在");
        }
        return skill;
    }

    /** 平台资源定位（admin）：必须 user_id=0 */
    private Skill getPlatform(Long id) {
        Skill skill = skillMapper.selectById(id);
        if (skill == null || skill.getStatus() == 0 || skill.getUserId().longValue() != 0L) {
            throw new BizException(404, "平台 Skill 不存在");
        }
        return skill;
    }
}