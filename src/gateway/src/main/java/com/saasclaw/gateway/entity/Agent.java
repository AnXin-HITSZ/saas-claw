package com.saasclaw.gateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent")
public class Agent {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 属于哪个 Claw */
    private Long clawId;

    /** 冗余：直接定位用户 */
    private Long userId;

    private String name;

    /** API 标识（用户级唯一），即请求 model 字段 */
    private String alias;

    private String description;

    private String systemPrompt;

    /** 底层大模型（引用 model_config.name） */
    private String baseModel;

    private Double temperature;

    private Integer maxTokens;

    private String source;

    private String version;

    private String author;

    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}