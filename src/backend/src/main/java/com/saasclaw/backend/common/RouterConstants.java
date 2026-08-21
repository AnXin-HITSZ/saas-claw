package com.saasclaw.backend.common;

/**
 * 路由模型（router）保留名常量。
 *
 * router 是主图路由专用 LLM，独立于业务模型配置：
 * - 不进入 /model-configs 业务列表（Agent 基础模型下拉不可见、不可绑定）
 * - 仅通过 ModelConfigController 的 router 专属端点（GET/PUT /model-configs/router）读写
 *
 * 须与 runtime config.py 的 router_model 默认值保持一致。
 */
public final class RouterConstants {

    /** model_config 表中 router 专用行的 name（保留名，Agent 禁止绑定） */
    public static final String NAME = "router";

    private RouterConstants() {
    }
}
