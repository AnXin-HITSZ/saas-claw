package com.saasclaw.backend.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.saasclaw.backend.common.BizException;
import com.saasclaw.backend.entity.Agent;
import com.saasclaw.backend.entity.AgentFile;
import com.saasclaw.backend.mapper.AgentFileMapper;
import com.saasclaw.backend.mapper.AgentMapper;
import com.saasclaw.backend.service.AgentFileService;
import com.saasclaw.backend.service.OssService;
import com.saasclaw.backend.vo.AgentFileVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AgentFileServiceImpl implements AgentFileService {

    private final AgentFileMapper agentFileMapper;
    private final AgentMapper agentMapper;
    private final OssService ossService;

    @Override
    public AgentFileVO upload(Long userId, Long agentId, String path, MultipartFile file) {
        getOwnedAgent(userId, agentId);
        validatePath(path);
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "文件内容为空");
        }

        String hash;
        String url;
        try {
            hash = DigestUtils.sha256Hex(file.getBytes());
            url = ossService.upload("agent/" + agentId + "/" + path,
                    file.getInputStream(), file.getSize(), contentType(file, path));
        } catch (IOException e) {
            throw new BizException(500, "读取文件失败");
        }

        AgentFile existing = agentFileMapper.selectOne(
                new LambdaQueryWrapper<AgentFile>()
                        .eq(AgentFile::getAgentId, agentId)
                        .eq(AgentFile::getFileName, path));
        AgentFile saved;
        if (existing == null) {
            AgentFile f = new AgentFile();
            f.setAgentId(agentId);
            f.setFileName(path);
            f.setFileUrl(url);
            f.setFileType(fileTypeOf(path));
            f.setFileSize(file.getSize());
            f.setFileHash(hash);
            agentFileMapper.insert(f);
            saved = f;
        } else {
            existing.setFileUrl(url);
            existing.setFileType(fileTypeOf(path));
            existing.setFileSize(file.getSize());
            existing.setFileHash(hash);
            agentFileMapper.updateById(existing);
            saved = existing;
        }
        return toVO(saved);
    }

    @Override
    public List<AgentFileVO> list(Long userId, Long agentId) {
        getOwnedAgent(userId, agentId);
        return agentFileMapper.selectList(
                        new LambdaQueryWrapper<AgentFile>()
                                .eq(AgentFile::getAgentId, agentId)
                                .orderByAsc(AgentFile::getId))
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public void delete(Long userId, Long agentId, Long fileId) {
        getOwnedAgent(userId, agentId);
        AgentFile file = agentFileMapper.selectOne(
                new LambdaQueryWrapper<AgentFile>()
                        .eq(AgentFile::getId, fileId)
                        .eq(AgentFile::getAgentId, agentId));
        if (file == null) {
            throw new BizException(404, "文件不存在");
        }
        agentFileMapper.deleteById(file.getId());
        ossService.delete("agent/" + agentId + "/" + file.getFileName());
    }

    // ---- helpers ----

    /** 归属校验：agent 必须本人所有且启用 */
    private Agent getOwnedAgent(Long userId, Long agentId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null || agent.getStatus() == 0 || !agent.getUserId().equals(userId)) {
            throw new BizException(404, "Agent 不存在");
        }
        return agent;
    }

    /** path 即 file_name（相对路径），防绝对路径 / 反斜杠 / 目录穿越 */
    private void validatePath(String path) {
        if (path == null || path.isBlank()) {
            throw new BizException(400, "path 不能为空");
        }
        if (path.length() > 128) {
            throw new BizException(400, "path 过长");
        }
        if (path.startsWith("/") || path.contains("\\") || path.contains("..")) {
            throw new BizException(400, "path 不合法");
        }
    }

    /** 优先用前端声明的 contentType，非 octet-stream 时按扩展名兜底推断 */
    private String contentType(MultipartFile file, String path) {
        String declared = file.getContentType();
        if (declared != null && !declared.equalsIgnoreCase("application/octet-stream")) {
            return declared;
        }
        return switch (fileTypeOf(path) == null ? "" : fileTypeOf(path)) {
            case "md" -> "text/markdown; charset=utf-8";
            case "py" -> "text/x-python; charset=utf-8";
            case "json" -> "application/json; charset=utf-8";
            case "yaml", "yml" -> "text/yaml; charset=utf-8";
            case "txt" -> "text/plain; charset=utf-8";
            case "html", "htm" -> "text/html; charset=utf-8";
            default -> "application/octet-stream";
        };
    }

    /** 取相对路径的扩展名（去点小写），如 AGENTS.md → md */
    private String fileTypeOf(String path) {
        int idx = path.lastIndexOf('.');
        if (idx < 0 || idx == path.length() - 1) {
            return null;
        }
        String ext = path.substring(idx + 1).toLowerCase();
        return ext.length() > 16 ? ext.substring(0, 16) : ext;
    }

    private AgentFileVO toVO(AgentFile f) {
        AgentFileVO vo = new AgentFileVO();
        vo.setId(f.getId());
        vo.setFileName(f.getFileName());
        vo.setFileUrl(f.getFileUrl());
        vo.setFileType(f.getFileType());
        vo.setFileSize(f.getFileSize());
        vo.setFileHash(f.getFileHash());
        vo.setCreatedAt(f.getCreatedAt());
        return vo;
    }
}