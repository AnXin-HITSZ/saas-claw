package com.saasclaw.backend.service;

import com.saasclaw.backend.dto.ClawCreateRequest;
import com.saasclaw.backend.entity.Claw;

import java.util.List;

public interface ClawService {

    /** 当前用户的 Claw 列表（数据隔离） */
    List<Claw> list(Long userId);

    /** 创建 Claw：name 用户级唯一 + namespace 两步写库 */
    Claw create(Long userId, ClawCreateRequest request);

    /** 软删：只能删自己的 Claw */
    void delete(Long userId, Long id);
}
