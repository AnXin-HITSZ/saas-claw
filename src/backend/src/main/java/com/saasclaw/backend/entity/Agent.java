package com.saasclaw.backend.entity;

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

    /** 显示名（可中文） */
    private String name;

    /** API 标识（用户级唯一） */
    private String alias;

    /** 路由目录 */
    private String description;

    /** 人设（可编辑） */
    private String systemPrompt;

    /** 底层大模型（引用 model_config.name） */
    private String baseModel;

    /** 运行参数 */
    private Double temperature;

    private Integer maxTokens;

    /** self=自建 shop=商店安装 */
    private String source;

    private String version;

    private String author;

    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
