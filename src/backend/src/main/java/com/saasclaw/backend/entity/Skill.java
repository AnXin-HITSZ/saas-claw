package com.saasclaw.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("skill")
public class Skill {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 0=平台默认，非0=用户自建 */
    private Long userId;

    /** 用户级唯一（uk_user_name） */
    private String name;

    /** 路由摘要（必填） */
    private String description;

    /** self=自建 shop=商店安装 */
    private String source;

    private String version;

    private String author;

    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}