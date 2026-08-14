package com.saasclaw.backend.vo;

import lombok.Data;

/** 缺失 Skill 项：installable=true 时前端可引导一键安装 */
@Data
public class MissingSkillVO {

    private Long skillId;
    private String name;
    /** 该 skill 是否在商店中可安装（已上架且非平台公共 skill） */
    private Boolean installable;
}
