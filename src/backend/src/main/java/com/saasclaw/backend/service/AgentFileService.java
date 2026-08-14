package com.saasclaw.backend.service;

import com.saasclaw.backend.vo.AgentFileVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface AgentFileService {

    /** 上传（同名覆盖），校验 agent 归属 */
    AgentFileVO upload(Long userId, Long agentId, String path, MultipartFile file);

    List<AgentFileVO> list(Long userId, Long agentId);

    void delete(Long userId, Long agentId, Long fileId);
}