package com.saasclaw.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.saasclaw.backend.common.BizException;
import com.saasclaw.backend.common.RouterConstants;
import com.saasclaw.backend.dto.ModelConfigCreateRequest;
import com.saasclaw.backend.dto.ModelConfigUpdateRequest;
import com.saasclaw.backend.dto.RouterConfigUpdateRequest;
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
                        .ne(ModelConfig::getName, RouterConstants.NAME)  // router 路由专用，不进业务列表
                        .orderByDesc(ModelConfig::getId)
        );
    }

    @Override
    public ModelConfig create(ModelConfigCreateRequest request) {
        if (RouterConstants.NAME.equals(request.getName())) {
            throw new BizException(400, "「router」为系统保留的路由模型名，请使用「配置路由模型」入口");
        }
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

    @Override
    public ModelConfig getRouter() {
        ModelConfig config = modelConfigMapper.selectOne(
                new LambdaQueryWrapper<ModelConfig>().eq(ModelConfig::getName, RouterConstants.NAME));
        if (config == null) {
            throw new BizException(404, "路由模型未配置");
        }
        return config;
    }

    @Override
    public ModelConfig updateRouter(RouterConfigUpdateRequest request) {
        ModelConfig config = modelConfigMapper.selectOne(
                new LambdaQueryWrapper<ModelConfig>().eq(ModelConfig::getName, RouterConstants.NAME));
        if (config == null) {
            // 路由行不存在 → 创建（需完整信息，供管理端首次配置）
            if (isBlank(request.getProvider()) || isBlank(request.getModelName())
                    || isBlank(request.getEndpoint()) || isBlank(request.getApiKey())) {
                throw new BizException(400, "路由模型尚未配置，需完整填写供应商/模型名/Endpoint/API Key");
            }
            config = new ModelConfig();
            config.setName(RouterConstants.NAME);
            config.setProvider(request.getProvider());
            config.setModelName(request.getModelName());
            config.setEndpoint(request.getEndpoint());
            config.setApiKey(request.getApiKey());
            config.setStatus(request.getStatus() == null ? 1 : request.getStatus());
            modelConfigMapper.insert(config);
            return config;
        }

        LambdaUpdateWrapper<ModelConfig> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(ModelConfig::getId, config.getId());
        if (request.getProvider() != null) {
            wrapper.set(ModelConfig::getProvider, request.getProvider());
        }
        if (request.getModelName() != null) {
            wrapper.set(ModelConfig::getModelName, request.getModelName());
        }
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
        return modelConfigMapper.selectById(config.getId());
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private ModelConfig getExisting(Long id) {
        ModelConfig config = modelConfigMapper.selectById(id);
        if (config == null || config.getStatus() == 0) {
            throw new BizException(404, "模型配置不存在");
        }
        return config;
    }
}
