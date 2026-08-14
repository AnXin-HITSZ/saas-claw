# 数据表设计

> 说明：全表不设外键约束，数据完整性由应用层保证（MyBatis-Plus 惯例）。

## organization 表（组织 [预留未启用]）

```sql
CREATE TABLE organization (
    id         BIGINT       PRIMARY KEY AUTO_INCREMENT,
    name       VARCHAR(64)  NOT NULL,
    status     TINYINT      DEFAULT 1,
    created_at DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_org_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织表（预留）';
```

## user 表（登录主体）

```sql
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
```

## authorization 表（鉴权）

```sql
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
```

## claw 表（用户的 Claw 实例）

```sql
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
```

## agent 表（Claw 内的 Agent）

```sql
CREATE TABLE agent (
    id             BIGINT       PRIMARY KEY AUTO_INCREMENT,
    claw_id        BIGINT       NOT NULL,            -- 属于哪个 Claw
    user_id        BIGINT       NOT NULL,            -- 冗余：直接定位用户
    name           VARCHAR(64)  NOT NULL,            -- 显示名（可中文）
    alias          VARCHAR(64)  NOT NULL,            -- API 标识（用户级唯一）
    description    VARCHAR(512) DEFAULT NULL,        -- 路由目录（LLM 路由依据）
    system_prompt  TEXT,                             -- 人设（可编辑）
    base_model     VARCHAR(64)  NOT NULL,            -- 底层大模型（引用 model_config）
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
```

## agent_file 表（Agent 人格文件）

```sql
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
```

## model_config 表（底层大模型配置）

```sql
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
```

## tool 表（工具库）

```sql
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
```

## skill 表（Skill 资产库）

```sql
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
```

## skill_file 表（Skill 脚本文件）

```sql
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
```

> 约定：每个 Skill 必须包含标准入口文件 `SKILL.md`（指令），脚本文件可选（如 `code/run.py`），均以相对路径存入本表。

## agent_shop 表（Agent 商店）

```sql
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
```

## skill_shop 表（Skill 商店）

```sql
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
```

## agent_skill 表（Agent - Skill 依赖表）

```sql
CREATE TABLE agent_skill (
    id         BIGINT       PRIMARY KEY AUTO_INCREMENT,
    agent_id   BIGINT       NOT NULL,
    skill_id   BIGINT       NOT NULL,
    created_at DATETIME     DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_skill (agent_id, skill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent-Skill依赖表';
```

## tool_approval 表（敏感工具审批留痕）

```sql
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
```

## agent_installation 表（Agent 安装记录）

```sql
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
```

## skill_installation 表（Skill 安装记录）

```sql
CREATE TABLE skill_installation (
    id             BIGINT       PRIMARY KEY AUTO_INCREMENT,
    skill_id       BIGINT       NOT NULL,       -- 商店里被装的 Skill
    user_id        BIGINT       NOT NULL,       -- 安装者
    local_skill_id BIGINT       DEFAULT NULL,   -- 安装后本地副本
    status         TINYINT      DEFAULT 1,
    created_at     DATETIME     DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Skill安装记录表';
```