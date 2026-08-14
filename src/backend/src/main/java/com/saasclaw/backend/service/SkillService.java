package com.saasclaw.backend.service;

import com.saasclaw.backend.dto.SkillCreateRequest;
import com.saasclaw.backend.dto.SkillUpdateRequest;
import com.saasclaw.backend.entity.Skill;

import java.util.List;

public interface SkillService {

    /** 列表：平台默认（user_id=0）+ 我自建的，status=1 */
    List<Skill> list(Long userId);

    /** 用户自建（user_id=userId） */
    Skill create(Long userId, SkillCreateRequest request);

    /** 编辑自己的 */
    Skill update(Long userId, Long id, SkillUpdateRequest request);

    /** 软删自己的 */
    void delete(Long userId, Long id);

    /** 管理员：创建平台 Skill（user_id=0） */
    Skill createPlatform(SkillCreateRequest request);

    /** 管理员：编辑平台 Skill */
    Skill updatePlatform(Long id, SkillUpdateRequest request);

    /** 管理员：软删平台 Skill */
    void deletePlatform(Long id);
}