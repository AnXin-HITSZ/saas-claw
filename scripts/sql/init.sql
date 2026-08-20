-- ============================================
-- saas_claw 数据库初始化脚本
-- 生成依据：docs/architecture/database/schema.md（唯一权威来源）
-- 全表不设外键，数据完整性由应用层保证（MyBatis-Plus 惯例）
-- 数据库名: saas_claw
-- ============================================

CREATE DATABASE IF NOT EXISTS saas_claw
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE saas_claw;

-- 删除顺序（无外键，仅保证可重复执行）
DROP TABLE IF EXISTS skill_installation;
DROP TABLE IF EXISTS agent_installation;
DROP TABLE IF EXISTS tool_approval;
DROP TABLE IF EXISTS tool_approval_batch;
DROP TABLE IF EXISTS agent_skill;
DROP TABLE IF EXISTS skill_shop;
DROP TABLE IF EXISTS agent_shop;
DROP TABLE IF EXISTS skill_file;
DROP TABLE IF EXISTS skill;
DROP TABLE IF EXISTS tool;
DROP TABLE IF EXISTS model_config;
DROP TABLE IF EXISTS agent_file;
DROP TABLE IF EXISTS agent;
DROP TABLE IF EXISTS claw;
DROP TABLE IF EXISTS authorization;
DROP TABLE IF EXISTS user;
DROP TABLE IF EXISTS organization;

-- ============================================
-- 1. organization（组织 [预留未启用]）
-- ============================================
CREATE TABLE organization (
    id         BIGINT       PRIMARY KEY AUTO_INCREMENT,
    name       VARCHAR(64)  NOT NULL,
    status     TINYINT      DEFAULT 1,
    created_at DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_org_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织表（预留）';

-- ============================================
-- 2. user（登录主体）
-- ============================================
CREATE TABLE user (
    id            BIGINT       PRIMARY KEY AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    nickname      VARCHAR(64)  DEFAULT NULL,
    email         VARCHAR(128) DEFAULT NULL,
    avatar_url    VARCHAR(255) DEFAULT NULL,
    org_id        BIGINT       DEFAULT NULL,              -- 预留：MVP 为 null，未来组织成员时填
    status        TINYINT      DEFAULT 1,                 -- 1=正常 0=禁用
    role          TINYINT      NOT NULL      DEFAULT 0,   -- 0=普通用户 1=管理员
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP  ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_username (username),
    INDEX idx_org_id (org_id)                             -- 预留索引
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ============================================
-- 3. authorization（API Key 授权）
-- ============================================
CREATE TABLE authorization (
    id         BIGINT       PRIMARY KEY AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,             -- 改为绑定用户，不是组织
    api_key    VARCHAR(128) NOT NULL,
    name       VARCHAR(64)  DEFAULT NULL,          -- 创建时用户起的名称（主标识，可重名）
    key_suffix VARCHAR(8)   DEFAULT NULL,          -- 明文 key 末 6 位（同名时辅助分辨）
    status     TINYINT      DEFAULT 1,
    created_at DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_api_key (api_key),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='API Key授权表';

-- ============================================
-- 4. claw（用户的 Claw 实例）
-- ============================================
CREATE TABLE claw (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    namespace   VARCHAR(64)  NOT NULL,        -- K3s namespace 名（命名规则：claw-{id}）
    status      TINYINT      DEFAULT 1,
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_claw (user_id, name),  -- 同一用户下 Claw 名唯一
    UNIQUE KEY uk_namespace (namespace)       -- namespace 全局唯一
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Claw实例表';

-- ============================================
-- 5. agent（Claw 内的 Agent）
-- ============================================
CREATE TABLE agent (
    id             BIGINT       PRIMARY KEY AUTO_INCREMENT,
    claw_id        BIGINT       NOT NULL,            -- 属于哪个 Claw
    user_id        BIGINT       NOT NULL,            -- 冗余：直接定位用户
    name           VARCHAR(64)  NOT NULL,            -- 显示名（可中文）
    alias          VARCHAR(64)  NOT NULL,            -- API 标识（用户级唯一）
    description    VARCHAR(512) DEFAULT NULL,        -- 路由目录（LLM 路由依据）
    system_prompt  TEXT,                             -- 人设（可编辑）
    base_model     VARCHAR(64)  NOT NULL,            -- 底层大模型（引用 model_config.name）
    temperature    DOUBLE       DEFAULT 0.7,         -- 运行参数（MVP 进表）
    max_tokens     INT          DEFAULT 4096,
    source         VARCHAR(16)  DEFAULT 'self',      -- self=自建 shop=商店安装
    version        VARCHAR(32)  DEFAULT '1.0.0',
    author         VARCHAR(64)  DEFAULT NULL,
    status         TINYINT      DEFAULT 1,
    created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_alias (user_id, alias),       -- 用户级唯一（核心约束）
    INDEX idx_claw_id (claw_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent表';

-- ============================================
-- 6. agent_file（Agent 人格文件）
-- ============================================
CREATE TABLE agent_file (
    id         BIGINT       PRIMARY KEY AUTO_INCREMENT,
    agent_id   BIGINT       NOT NULL,
    file_name  VARCHAR(128) NOT NULL,   -- 约定文件：AGENTS.md / IDENTITY.md / SOUL.md（可扩展）
    file_url   VARCHAR(512) NOT NULL,   -- OSS
    file_type  VARCHAR(16)  DEFAULT NULL,
    file_size  BIGINT       DEFAULT 0,
    file_hash  VARCHAR(64)  DEFAULT NULL,
    created_at DATETIME     DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_file (agent_id, file_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent人格文件表';

-- ============================================
-- 7. model_config（底层大模型配置）
-- ============================================
CREATE TABLE model_config (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
    name        VARCHAR(64)  NOT NULL,        -- 逻辑标识，agent.base_model 引用这个
    provider    VARCHAR(32)  NOT NULL,        -- 供应商：deepseek/openai/qwen...
    model_name  VARCHAR(64)  NOT NULL,        -- 供应商侧真实模型名（调用时用）
    endpoint    VARCHAR(255) NOT NULL,        -- OpenAI 兼容 base_url
    api_key     VARCHAR(255) DEFAULT NULL,    -- 供应商密钥（平台统一持有，不进前端）
    status      TINYINT      DEFAULT 1,
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型配置表';

-- ============================================
-- 8. tool（工具库）
-- ============================================
CREATE TABLE tool (
    id           BIGINT       PRIMARY KEY AUTO_INCREMENT,
    name         VARCHAR(64)  NOT NULL,
    description  VARCHAR(512) DEFAULT NULL,
    schema_json  TEXT,                       -- 工具入参定义（JSON Schema）
    is_sensitive TINYINT      DEFAULT 0,     -- 1=敏感（触发审批）0=普通
    status       TINYINT      DEFAULT 1,
    created_at   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tool_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工具库表';

-- ============================================
-- 9. skill（Skill 资产库）
-- ============================================
CREATE TABLE skill (
    id          BIGINT          PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT NOT NULL DEFAULT 0,        -- 0=平台默认，非0=用户自建
    name        VARCHAR(64)     NOT NULL,
    description VARCHAR(512)    NOT NULL,         -- 路由摘要（必填）
    source      VARCHAR(16)     DEFAULT 'self',   -- self=自建 shop=商店安装
    version     VARCHAR(32)     DEFAULT '1.0.0',
    author      VARCHAR(64)     DEFAULT NULL,
    status      TINYINT         DEFAULT 1,
    created_at  DATETIME        DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_name (user_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Skill资产表';

-- ============================================
-- 10. skill_file（Skill 脚本文件）
-- ============================================
CREATE TABLE skill_file (
    id         BIGINT       PRIMARY KEY AUTO_INCREMENT,
    skill_id   BIGINT       NOT NULL,
    file_name  VARCHAR(128) NOT NULL,                -- 相对路径（含 SKILL.md 标准入口 + 脚本，如 SKILL.md、code/run.py）
    file_url   VARCHAR(512) NOT NULL,                -- OSS URL
    file_type  VARCHAR(16)  DEFAULT NULL,
    file_size  BIGINT       DEFAULT 0,
    file_hash  VARCHAR(64)  DEFAULT NULL,            -- 校验/去重
    created_at DATETIME     DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_skill_file (skill_id, file_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Skill脚本文件表';

-- ============================================
-- 11. agent_shop（Agent 商店）
-- ============================================
CREATE TABLE agent_shop (
    id           BIGINT       PRIMARY KEY AUTO_INCREMENT,
    agent_id     BIGINT       NOT NULL,              -- 引用 agent 表
    publisher_id BIGINT       NOT NULL,              -- 发布者（= agent.user_id）
    installs     INT          DEFAULT 0,
    status       TINYINT      DEFAULT 1,             -- 1=上架 0=下架
    created_at   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shop_agent (agent_id)              -- 一个 Agent 只能上架一次
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent商店表';

-- ============================================
-- 12. skill_shop（Skill 商店）
-- ============================================
CREATE TABLE skill_shop (
    id           BIGINT       PRIMARY KEY AUTO_INCREMENT,
    skill_id     BIGINT       NOT NULL,
    publisher_id BIGINT       NOT NULL,              -- = skill.user_id
    installs     INT          DEFAULT 0,
    status       TINYINT      DEFAULT 1,
    created_at   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_shop_skill (skill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Skill商店表';

-- ============================================
-- 13. agent_skill（Agent - Skill 依赖表）
-- ============================================
CREATE TABLE agent_skill (
    id         BIGINT       PRIMARY KEY AUTO_INCREMENT,
    agent_id   BIGINT       NOT NULL,
    skill_id   BIGINT       NOT NULL,
    created_at DATETIME     DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_skill (agent_id, skill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent-Skill依赖表';

-- ============================================
-- 14. tool_approval（敏感工具审批留痕）
-- ============================================
CREATE TABLE tool_approval (
    id             BIGINT       PRIMARY KEY AUTO_INCREMENT,
    request_id     VARCHAR(64)  NOT NULL,       -- 审批请求 ID（回调关联键，runtime 用它 resume）
    user_id        BIGINT       NOT NULL,       -- 需要确认的用户
    claw_id        BIGINT       DEFAULT NULL,   -- 归属 Claw
    agent_id       BIGINT       NOT NULL,       -- 哪个 Agent 要调
    tool_id        BIGINT       NOT NULL,       -- 哪个敏感工具
    input_summary  VARCHAR(512) DEFAULT NULL,   -- 入参摘要（展示给用户看的关键信息）
    action         TINYINT      DEFAULT NULL,   -- 1=允许 2=拒绝 3=自定义消息
    custom_message VARCHAR(512) DEFAULT NULL,   -- action=3 时用户改写的内容
    status         TINYINT      DEFAULT 0,      -- 0=待审批 1=已处理
    created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    handled_at     DATETIME     DEFAULT NULL,   -- 处理时间（留痕审计用）
    UNIQUE KEY uk_request (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工具审批留痕表';

-- ============================================
-- ============================================
-- 14b. tool_approval_batch（批量敏感工具审批留痕）
-- ============================================
CREATE TABLE tool_approval_batch (
    id            BIGINT       PRIMARY KEY AUTO_INCREMENT,
    request_id    VARCHAR(64)  NOT NULL,       -- 批量审批请求 ID（approval:batch:{spawn_id}）
    user_id       BIGINT       NOT NULL,       -- 需要确认的用户
    claw_id       BIGINT       DEFAULT NULL,   -- 归属 Claw
    agent_id      BIGINT       NOT NULL,       -- 发起 spawn 的父 Agent
    sub_requests  TEXT         NOT NULL,       -- 子请求明细 JSON 数组
    action        TINYINT      DEFAULT NULL,   -- 整体决策 1=允许 2=拒绝 3=自定义消息
    decision_json TEXT         DEFAULT NULL,   -- 逐子请求决策 JSON（null=按整体决策）
    status        TINYINT      DEFAULT 0,      -- 0=待审批 1=已处理
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    handled_at    DATETIME     DEFAULT NULL,   -- 处理时间（留痕审计用）
    UNIQUE KEY uk_batch_request (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='批量工具审批留痕表';

-- 15. agent_installation（Agent 安装记录）
-- ============================================
CREATE TABLE agent_installation (
    id             BIGINT       PRIMARY KEY AUTO_INCREMENT,
    agent_id       BIGINT       NOT NULL,       -- 商店里被装的那个 Agent
    user_id        BIGINT       NOT NULL,       -- 安装者
    claw_id        BIGINT       NOT NULL,       -- 装到哪个 Claw
    local_agent_id BIGINT       DEFAULT NULL,   -- 安装后本地副本（source='shop'）
    status         TINYINT      DEFAULT 1,
    created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent安装记录表';

-- ============================================
-- 16. skill_installation（Skill 安装记录）
-- ============================================
CREATE TABLE skill_installation (
    id             BIGINT       PRIMARY KEY AUTO_INCREMENT,
    skill_id       BIGINT       NOT NULL,       -- 商店里被装的 Skill
    user_id        BIGINT       NOT NULL,       -- 安装者
    local_skill_id BIGINT       DEFAULT NULL,   -- 安装后本地副本
    status         TINYINT      DEFAULT 1,
    created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Skill安装记录表';

-- ============================================
-- 种子数据（测试数据不预置）
-- 说明：以下为平台运行必需配置，需在上线前录入，
-- 可通过 backend 模型配置管理接口添加，或手动执行（替换真实值）。
-- ============================================
-- INSERT INTO model_config (name, provider, model_name, endpoint, api_key) VALUES
--     ('deepseek-v4-flash', 'deepseek', '<供应商侧模型名>', 'https://api.deepseek.com/v1', '<YOUR_API_KEY>'),
--     ('deepseek-v4-pro',   'deepseek', '<供应商侧模型名>', 'https://api.deepseek.com/v1', '<YOUR_API_KEY>');
