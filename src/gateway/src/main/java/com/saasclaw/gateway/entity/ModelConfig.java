package com.saasclaw.gateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 网关侧只读 model_config 的校验子集。
 * 权威 schema 见 backend 实体 / deploy/k8s/schema.sql：
 * 逻辑标识是 name（agent.base_model、runtime 路由、请求体 model 均引用 name），
 * 表中不存在 model_family 列，供应商真实名为 model_name。
 */
@Data
@TableName("model_config")
public class ModelConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 逻辑标识：agent.base_model 与请求体 model 字段引用这个 */
    private String name;

    /** 1=启用 0=禁用/软删 */
    private Integer status;
}