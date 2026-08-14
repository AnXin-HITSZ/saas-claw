package com.saasclaw.backend.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** Agent 文件列表项 */
@Data
public class AgentFileVO {

    private Long id;
    private String fileName;
    private String fileUrl;
    private String fileType;
    private Long fileSize;
    private String fileHash;
    private LocalDateTime createdAt;
}