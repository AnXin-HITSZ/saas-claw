package com.saasclaw.backend.service;

import com.saasclaw.backend.dto.ModelConfigCreateRequest;
import com.saasclaw.backend.dto.ModelConfigUpdateRequest;
import com.saasclaw.backend.dto.RouterConfigUpdateRequest;
import com.saasclaw.backend.entity.ModelConfig;

import java.util.List;

public interface ModelConfigService {
    List<ModelConfig> list();
    ModelConfig create(ModelConfigCreateRequest request);
    ModelConfig update(Long id, ModelConfigUpdateRequest request);
    void delete(Long id);

    /** 路由模型（router）读取：独立于业务列表，status=0 也返回（便于管理端重新启用） */
    ModelConfig getRouter();

    /** 路由模型（router）更新：行不存在则创建，存在则按非 null 字段更新 */
    ModelConfig updateRouter(RouterConfigUpdateRequest request);
}
