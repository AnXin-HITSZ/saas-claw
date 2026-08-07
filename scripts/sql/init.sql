-- ============================================
-- mini-gateway 数据库初始化脚本
-- 数据库名: mini_llm
-- ============================================

CREATE DATABASE IF NOT EXISTS mini_llm
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE mini_llm;

-- ============================================
-- 1. 组织表
-- ============================================
DROP TABLE IF EXISTS organization;
CREATE TABLE organization (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
    name        VARCHAR(128) NOT NULL,
    status      TINYINT      DEFAULT 1           COMMENT '1=正常, 0=停用',
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织表';

-- ============================================
-- 2. API Key 授权表
-- ============================================
DROP TABLE IF EXISTS authorization;
CREATE TABLE authorization (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
    org_id      BIGINT       NOT NULL,
    api_key     VARCHAR(128) NOT NULL,
    status      TINYINT      DEFAULT 1           COMMENT '1=正常, 0=停用',
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_api_key (api_key),
    INDEX idx_org_id (org_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='API Key授权表';

-- ============================================
-- 3. 模型配置表
-- ============================================
DROP TABLE IF EXISTS model_config;
CREATE TABLE model_config (
    id            BIGINT       PRIMARY KEY AUTO_INCREMENT,
    model_name    VARCHAR(64)  NOT NULL,
    model_family  VARCHAR(64)  NOT NULL,
    status        TINYINT      DEFAULT 1         COMMENT '1=正常, 0=停用',
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_model_name (model_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型配置表';

-- ============================================
-- 初始数据
-- ============================================
INSERT INTO organization (id, name) VALUES (1, '默认组织');

INSERT INTO authorization (org_id, api_key) VALUES (1, 'sk-test-api-key-001');

INSERT INTO model_config (model_name, model_family) VALUES
    ('DeepSeek-v4-flash', 'DeepSeek'),
    ('DeepSeek-v4-pro', 'DeepSeek');