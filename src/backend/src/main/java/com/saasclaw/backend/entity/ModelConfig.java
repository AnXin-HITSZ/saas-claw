package com.saasclaw.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("model_config")
public class ModelConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 逻辑标识，agent.base_model 引用这个，创建后不可改 */
    private String name;

    /** 供应商：deepseek/openai/qwen... */
    private String provider;

    /** 供应商侧真实模型名（调用时用） */
    private String modelName;

    /** OpenAI 兼容 base_url */
    private String endpoint;

    /** 供应商密钥：仅接收写入，永不回显 */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String apiKey;

    /** 1=启用 0=禁用/软删 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
