package com.saasclaw.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.saasclaw.backend.common.BizException;
import com.saasclaw.backend.dto.ModelConfigCreateRequest;
import com.saasclaw.backend.dto.ModelConfigUpdateRequest;
import com.saasclaw.backend.entity.ModelConfig;
import com.saasclaw.backend.mapper.ModelConfigMapper;
import com.saasclaw.backend.service.ModelConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ModelConfigServiceImpl implements ModelConfigService {

    private final ModelConfigMapper modelConfigMapper;

    @Override
    public List<ModelConfig> list() {
        return modelConfigMapper.selectList(
                new LambdaQueryWrapper<ModelConfig>()
                        .eq(ModelConfig::getStatus, 1)
                        .orderByDesc(ModelConfig::getId)
        );
    }

    @Override
    public ModelConfig create(ModelConfigCreateRequest request) {
        Long count = modelConfigMapper.selectCount(
                new LambdaQueryWrapper<ModelConfig>().eq(ModelConfig::getName, request.getName())
        );
        if (count > 0) {
            throw new BizException(409, "模型配置名称已存在");
        }
        ModelConfig config = new ModelConfig();
        config.setName(request.getName());
        config.setProvider(request.getProvider());
        config.setModelName(request.getModelName());
        config.setEndpoint(request.getEndpoint());
        config.setApiKey(request.getApiKey());
        config.setStatus(1);
        modelConfigMapper.insert(config);
        return config;
    }

    @Override
    public ModelConfig update(Long id, ModelConfigUpdateRequest request) {
        getExisting(id);

        LambdaUpdateWrapper<ModelConfig> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(ModelConfig::getId, id);
        if (request.getEndpoint() != null) {
            wrapper.set(ModelConfig::getEndpoint, request.getEndpoint());
        }
        if (request.getApiKey() != null) {
            wrapper.set(ModelConfig::getApiKey, request.getApiKey());
        }
        if (request.getStatus() != null) {
            wrapper.set(ModelConfig::getStatus, request.getStatus());
        }
        modelConfigMapper.update(null, wrapper);

        return modelConfigMapper.selectById(id);
    }

    @Override
    public void delete(Long id) {
        getExisting(id);

        modelConfigMapper.update(null,
                new LambdaUpdateWrapper<ModelConfig>()
                        .eq(ModelConfig::getId, id)
                        .set(ModelConfig::getStatus, 0)
        );
    }

    private ModelConfig getExisting(Long id) {
        ModelConfig config = modelConfigMapper.selectById(id);
        if (config == null || config.getStatus() == 0) {
            throw new BizException(404, "模型配置不存在");
        }
        return config;
    }
}
