package com.saasclaw.backend.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** Agent 商店列表项 */
@Data
public class ShopAgentVO {

    private Long agentId;
    private String name;
    private String alias;
    private String description;
    private String version;
    private String author;
    private String baseModel;
    private Long publisherId;
    private String publisherNickname;
    private Integer installs;
    private LocalDateTime createdAt;
}
