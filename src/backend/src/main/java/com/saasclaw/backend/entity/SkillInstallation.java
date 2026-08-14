package com.saasclaw.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Skill 安装记录：用户从商店安装 → 落入用户资源池。
 * skill_id=商店源；local_skill_id=安装时建的本地副本（source='shop'）。
 */
@Data
@TableName("skill_installation")
public class SkillInstallation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 商店里被装的那个 Skill（源） */
    private Long skillId;

    /** 安装者 */
    private Long userId;

    /** 安装后本地副本（源 skill 的快照，归属安装者） */
    private Long localSkillId;

    private Integer status;

    private LocalDateTime createdAt;
}