package com.saasclaw.backend.service;

import com.saasclaw.backend.dto.ModelConfigCreateRequest;
import com.saasclaw.backend.dto.ModelConfigUpdateRequest;
import com.saasclaw.backend.entity.ModelConfig;

import java.util.List;

public interface ModelConfigService {
    List<ModelConfig> list();
    ModelConfig create(ModelConfigCreateRequest request);
    ModelConfig update(Long id, ModelConfigUpdateRequest request);
    void delete(Long id);
}
