package com.saasclaw.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Skill 商店上架记录（uk_shop_skill：一个 skill 只能上架一次） */
@Data
@TableName("skill_shop")
public class SkillShop {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 上架的 Skill（引用 skill 表） */
    private Long skillId;

    /** 发布者（= skill.user_id） */
    private Long publisherId;

    /** 累计安装次数 */
    private Integer installs;

    /** 1=上架 0=下架 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}