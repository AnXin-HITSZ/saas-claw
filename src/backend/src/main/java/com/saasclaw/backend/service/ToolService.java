package com.saasclaw.backend.service;

import com.saasclaw.backend.dto.ToolSyncItem;
import com.saasclaw.backend.entity.Tool;

import java.util.List;

public interface ToolService {

    /** 同步工具清单（代码权威：runtime 上报，upsert，不删除） */
    void sync(List<ToolSyncItem> items);

    /** 工具列表（登录用户可看） */
    List<Tool> list();
}
