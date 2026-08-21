package com.saasclaw.backend.controller;

import com.saasclaw.backend.common.Result;
import com.saasclaw.backend.config.RequireAdmin;
import com.saasclaw.backend.dto.ModelConfigCreateRequest;
import com.saasclaw.backend.dto.ModelConfigUpdateRequest;
import com.saasclaw.backend.dto.RouterConfigUpdateRequest;
import com.saasclaw.backend.entity.ModelConfig;
import com.saasclaw.backend.service.ModelConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/model-configs")
@RequiredArgsConstructor
public class ModelConfigController {

    private final ModelConfigService modelConfigService;

    @GetMapping
    public Result<List<ModelConfig>> list() {
        return Result.ok(modelConfigService.list());
    }

    @PostMapping
    @RequireAdmin
    public Result<ModelConfig> create(@Valid @RequestBody ModelConfigCreateRequest request) {
        return Result.ok(modelConfigService.create(request));
    }

    @PutMapping("/{id}")
    @RequireAdmin
    public Result<ModelConfig> update(@PathVariable Long id,
                                      @Valid @RequestBody ModelConfigUpdateRequest request) {
        return Result.ok(modelConfigService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @RequireAdmin
    public Result<Void> delete(@PathVariable Long id) {
        modelConfigService.delete(id);
        return Result.ok();
    }

    /** 路由模型（router）：主图路由专用，独立于业务模型，仅管理员读写 */
    @GetMapping("/router")
    @RequireAdmin
    public Result<ModelConfig> getRouter() {
        return Result.ok(modelConfigService.getRouter());
    }

    @PutMapping("/router")
    @RequireAdmin
    public Result<ModelConfig> updateRouter(@Valid @RequestBody RouterConfigUpdateRequest request) {
        return Result.ok(modelConfigService.updateRouter(request));
    }
}
