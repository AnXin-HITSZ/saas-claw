package com.saasclaw.backend.service;

import com.saasclaw.backend.dto.CreateToolRequest;
import com.saasclaw.backend.dto.UpdateToolRequest;
import com.saasclaw.backend.entity.Tool;

import java.util.List;

public interface ToolService {

    /** 启用工具清单（登录用户可见 / LLM 候选） */
    List<Tool> list();

    /** 全部工具（含停用，管理员配置用） */
    List<Tool> listAll();

    /** 创建工具（name 全局唯一） */
    Tool create(CreateToolRequest request);

    /** 更新元数据 / 敏感度 / 启停；name 不可改 */
    Tool update(Long id, UpdateToolRequest request);

    /** 删除工具（物理删除） */
    void remove(Long id);
}