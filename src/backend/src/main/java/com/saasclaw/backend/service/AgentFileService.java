package com.saasclaw.backend.service;

import com.saasclaw.backend.vo.AgentFileVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface AgentFileService {

    /** 上传（同名覆盖），校验 agent 归属 */
    AgentFileVO upload(Long userId, Long agentId, String path, MultipartFile file);

    List<AgentFileVO> list(Long userId, Long agentId);

    void delete(Long userId, Long agentId, Long fileId);

    /** 人格文件全量读取：返回文件全文（UTF-8）；仅限三份人格文件；不存在抛 404 */
    String readContent(Long userId, Long agentId, String fileName);

    /** 人格文件全量写入：JSON {content} 全量覆盖，不存在的文件自动创建；仅限三份人格文件 */
    AgentFileVO writeContent(Long userId, Long agentId, String fileName, String content);
}