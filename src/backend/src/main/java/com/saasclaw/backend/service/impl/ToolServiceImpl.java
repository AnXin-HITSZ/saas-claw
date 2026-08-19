package com.saasclaw.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.saasclaw.backend.common.BizException;
import com.saasclaw.backend.dto.CreateToolRequest;
import com.saasclaw.backend.dto.UpdateToolRequest;
import com.saasclaw.backend.entity.Tool;
import com.saasclaw.backend.mapper.ToolMapper;
import com.saasclaw.backend.service.ToolService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ToolServiceImpl implements ToolService {

    private final ToolMapper toolMapper;

    @Override
    public List<Tool> list() {
        return toolMapper.selectList(
                new LambdaQueryWrapper<Tool>()
                        .eq(Tool::getStatus, 1)
                        .orderByDesc(Tool::getId));
    }

    @Override
    public List<Tool> listAll() {
        return toolMapper.selectList(
                new LambdaQueryWrapper<Tool>().orderByDesc(Tool::getId));
    }

    @Override
    public Tool create(CreateToolRequest request) {
        Long count = toolMapper.selectCount(
                new LambdaQueryWrapper<Tool>().eq(Tool::getName, request.getName()));
        if (count > 0) {
            throw new BizException(409, "工具名已存在");
        }
        Tool tool = new Tool();
        tool.setName(request.getName());
        tool.setDescription(request.getDescription());
        tool.setSchemaJson(request.getSchemaJson());
        tool.setIsSensitive(request.getIsSensitive() == null ? 0 : request.getIsSensitive());
        tool.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        toolMapper.insert(tool);
        return tool;
    }

    @Override
    public Tool update(Long id, UpdateToolRequest request) {
        Tool tool = toolMapper.selectById(id);
        if (tool == null) {
            throw new BizException(404, "工具不存在");
        }
        if (request.getDescription() != null) tool.setDescription(request.getDescription());
        if (request.getSchemaJson() != null) tool.setSchemaJson(request.getSchemaJson());
        if (request.getIsSensitive() != null) tool.setIsSensitive(request.getIsSensitive());
        if (request.getStatus() != null) tool.setStatus(request.getStatus());
        toolMapper.updateById(tool);
        return tool;
    }

    @Override
    public void remove(Long id) {
        Tool tool = toolMapper.selectById(id);
        if (tool == null) {
            throw new BizException(404, "工具不存在");
        }
        toolMapper.deleteById(id);
    }
}