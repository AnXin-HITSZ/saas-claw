package com.saasclaw.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.saasclaw.backend.dto.ToolSyncItem;
import com.saasclaw.backend.entity.Tool;
import com.saasclaw.backend.mapper.ToolMapper;
import com.saasclaw.backend.service.ToolService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ToolServiceImpl implements ToolService {

    private final ToolMapper toolMapper;

    @Override
    @Transactional
    public void sync(List<ToolSyncItem> items) {
        for (ToolSyncItem item : items) {
            Tool existing = toolMapper.selectOne(
                    new LambdaQueryWrapper<Tool>().eq(Tool::getName, item.getName()));
            if (existing != null) {
                // 代码权威：已存在的工具只更新元数据，status 由平台控制保持不变
                existing.setDescription(item.getDescription());
                existing.setSchemaJson(item.getSchemaJson());
                existing.setIsSensitive(item.getIsSensitive());
                toolMapper.updateById(existing);
            } else {
                Tool tool = new Tool();
                tool.setName(item.getName());
                tool.setDescription(item.getDescription());
                tool.setSchemaJson(item.getSchemaJson());
                tool.setIsSensitive(item.getIsSensitive() == null ? 0 : item.getIsSensitive());
                tool.setStatus(1);
                toolMapper.insert(tool);
            }
        }
    }

    @Override
    public List<Tool> list() {
        return toolMapper.selectList(
                new LambdaQueryWrapper<Tool>()
                        .eq(Tool::getStatus, 1)
                        .orderByDesc(Tool::getId)
        );
    }
}
