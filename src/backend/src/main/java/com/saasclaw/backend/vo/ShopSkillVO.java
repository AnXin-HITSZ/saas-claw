package com.saasclaw.backend.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 商店列表项：skill_shop + skill + user(nickname) 的组装视图 */
@Data
public class ShopSkillVO {

    /** 上架的 Skill id（安装接口入参） */
    private Long skillId;

    private String name;

    private String description;

    private String version;

    private String author;

    private Long publisherId;

    private String publisherNickname;

    /** 累计安装次数 */
    private Integer installs;

    /** 上架时间 */
    private LocalDateTime createdAt;
}