# Claw SaaS 阶段一：骨架搭建 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将当前 pyclaw 单体项目重构为 claw-saas 多模块架构空壳 — 8 个 Spring Boot 微服务 + 2 个 FastAPI 服务 + 部署目录重组，全部可编译/启动验证通过。不迁移业务代码。

**Architecture:** 骨架先行策略。新建 `backend/`（Maven 多模块）、`runtime/`（Python）、`deploy/`（从 helm/ 迁移）、`scripts/` 目录，与旧代码并存在项目根目录下。旧代码（spring-backend/、openclaw/、sandbox-runner/）作为阶段二参考保留。

**Tech Stack:** Spring Boot 3.3.7, Spring Cloud 2023.0.5, Java 17, Maven, FastAPI (Python 3.11+), Alibaba Cloud OSS SDK 3.18.1

## Global Constraints

- groupId: `com.clawsaas`（不在代码中使用 `com.anxin.pyclaw`）
- Java 版本: 17
- Spring Boot 版本: 3.3.7
- gateway 使用 WebFlux，不依赖 spring-boot-starter-web
- 每个服务只配 `application.name` + `server.port`，不配数据源
- 每个服务有独立的 `exception/GlobalExceptionHandler.java`
- OSS 配置以占位符形式存在，不填实际凭据
- 不建 common 共享模块
- FastAPI 服务只在 main.py 挂载 health router
- 所有目录内的 `__init__.py` 为空文件

---

### Task 1: Root directory restructuring

**Files:**
- Create: `backend/` (directory)
- Create: `runtime/` (directory)
- Create: `deploy/` (directory)
- Create: `scripts/` (directory)
- Create: `scripts/.gitkeep`
- Move: `pyclaw-web/` → `frontend/`
- Move: `helm/` → `deploy/helm/`
- Modify: `.gitignore`

**Produces:** Target monorepo directory layout alongside old code.

- [ ] **Step 1: Create target directories**

```bash
mkdir -p backend
mkdir -p runtime
mkdir -p deploy
mkdir -p scripts
```

- [ ] **Step 2: Move frontend from pyclaw-web/ to frontend/**

```bash
git mv pyclaw-web frontend
```

- [ ] **Step 3: Move helm/ to deploy/helm/**

```bash
git mv helm deploy/helm
```

- [ ] **Step 4: Create scripts/.gitkeep placeholder**

```bash
touch scripts/.gitkeep
```

- [ ] **Step 5: Update .gitignore for new directory layout**

Replace `.gitignore` content:

```gitignore
.env
.venv/
__pycache__/
*.py[cod]
.pytest_cache/
dist/
build/
*.egg-info/
chatdata/*
!chatdata/.gitkeep

# Java backend
backend/**/target/
backend/**/data/
backend/maven-repository/

# Frontend
frontend/node_modules/
frontend/dist/

# Python runtime
runtime/**/__pycache__/
runtime/**/.venv/
runtime/**/.pytest_cache/

# K3s production values and secrets. Commit *.example.yaml templates instead.
pyclaw-values-k3s.yaml
spring-values-k3s.yaml
values-k3s.yaml
values-k3s.yaml.bak
*.secret.yaml
certs/

# Host SSH material
known_hosts
pyclaw_host_ops_ed25519*
```

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "chore: restructure root directories for claw-saas monorepo

Create backend/, runtime/, deploy/, scripts/.
Move pyclaw-web/ -> frontend/, helm/ -> deploy/helm/.
Update .gitignore for new layout.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: Backend parent POM

**Files:**
- Create: `backend/pom.xml`

**Produces:** Maven multi-module parent POM that all 8 services inherit from. Defines `<dependencyManagement>` for Spring Boot, Spring Cloud, and OSS SDK.

- [ ] **Step 1: Write backend/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.clawsaas</groupId>
  <artifactId>claw-saas-backend</artifactId>
  <version>0.1.0</version>
  <packaging>pom</packaging>
  <name>claw-saas-backend</name>
  <description>Claw SaaS backend monorepo — Maven multi-module parent</description>

  <modules>
    <module>gateway</module>
    <module>backend-for-frontend</module>
    <module>claw-service</module>
    <module>runtime-service</module>
    <module>agent-marketplace-service</module>
    <module>billing-service</module>
    <module>skill-marketplace-service</module>
  </modules>

  <properties>
    <java.version>17</java.version>
    <spring-boot.version>3.3.7</spring-boot.version>
    <spring-cloud.version>2023.0.5</spring-cloud.version>
    <aliyun-oss.version>3.18.1</aliyun-oss.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>${spring-boot.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-dependencies</artifactId>
        <version>${spring-cloud.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>com.aliyun.oss</groupId>
        <artifactId>aliyun-sdk-oss</artifactId>
        <version>${aliyun-oss.version}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>org.springframework.boot</groupId>
          <artifactId>spring-boot-maven-plugin</artifactId>
          <version>${spring-boot.version}</version>
        </plugin>
      </plugins>
    </pluginManagement>
  </build>
</project>
```

- [ ] **Step 2: Verify parent POM is valid**

```bash
cd backend && mvn validate
```

Expected: BUILD SUCCESS（会警告无子模块，正常，后续补齐）

- [ ] **Step 3: Commit**

```bash
git add backend/pom.xml
git commit -m "chore: add Maven multi-module parent POM

groupId: com.clawsaas, Spring Boot 3.3.7, Spring Cloud 2023.0.5.
Declares all 8 submodules. Manages OSS SDK 3.18.1.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: Gateway service shell

**Files:**
- Create: `backend/gateway/pom.xml`
- Create: `backend/gateway/src/main/java/com/clawsaas/gateway/GatewayApplication.java`
- Create: `backend/gateway/src/main/resources/application.yml`
- Create: `backend/gateway/src/main/java/com/clawsaas/gateway/config/package-info.java`
- Create: `backend/gateway/src/main/java/com/clawsaas/gateway/exception/GlobalExceptionHandler.java`

**Consumes:** `backend/pom.xml` (parent POM)
**Produces:** Gateway service compiles as a Spring Cloud Gateway WebFlux application on port 8080.

- [ ] **Step 1: Write gateway/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.clawsaas</groupId>
    <artifactId>claw-saas-backend</artifactId>
    <version>0.1.0</version>
    <relativePath>../pom.xml</relativePath>
  </parent>

  <artifactId>gateway</artifactId>
  <name>claw-saas-gateway</name>
  <description>API Gateway — unified entry, auth, routing, rate limiting</description>

  <dependencies>
    <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-starter-gateway</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Write GatewayApplication.java**

Create directories first:
```bash
mkdir -p backend/gateway/src/main/java/com/clawsaas/gateway/config
mkdir -p backend/gateway/src/main/java/com/clawsaas/gateway/exception
mkdir -p backend/gateway/src/main/resources
```

```java
package com.clawsaas.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
```

- [ ] **Step 3: Write application.yml**

```yaml
spring:
  application:
    name: gateway
server:
  port: 8080
```

- [ ] **Step 4: Write config/package-info.java**

```java
/**
 * Gateway configuration classes.
 *
 * Phase 2 will add: WebSecurityConfig, CorsConfig, RateLimitConfig, RouteConfig.
 */
package com.clawsaas.gateway.config;
```

- [ ] **Step 5: Write GlobalExceptionHandler.java**

```java
package com.clawsaas.gateway.exception;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "code", 500,
                "message", "Internal server error",
                "requestId", ""
        ));
    }
}
```

- [ ] **Step 6: Verify gateway compiles**

```bash
cd backend && mvn -pl gateway validate
```

Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add backend/gateway/
git commit -m "chore: add gateway service shell (8080, WebFlux)

Spring Cloud Gateway, no spring-boot-starter-web.
Minimal Application + GlobalExceptionHandler.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 4: Backend-for-frontend service shell

**Files:**
- Create: `backend/backend-for-frontend/pom.xml`
- Create: `backend/backend-for-frontend/src/main/java/com/clawsaas/bff/BffApplication.java`
- Create: `backend/backend-for-frontend/src/main/resources/application.yml`
- Create: `backend/backend-for-frontend/src/main/java/com/clawsaas/bff/config/package-info.java`
- Create: `backend/backend-for-frontend/src/main/java/com/clawsaas/bff/exception/GlobalExceptionHandler.java`

**Consumes:** `backend/pom.xml` (parent POM)
**Produces:** BFF service compiles as a Spring Web MVC application on port 8081.

- [ ] **Step 1: Write pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.clawsaas</groupId>
    <artifactId>claw-saas-backend</artifactId>
    <version>0.1.0</version>
    <relativePath>../pom.xml</relativePath>
  </parent>

  <artifactId>backend-for-frontend</artifactId>
  <name>claw-saas-bff</name>
  <description>Backend For Frontend — page-level data aggregation and adaptation</description>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Write BffApplication.java**

```bash
mkdir -p backend/backend-for-frontend/src/main/java/com/clawsaas/bff/config
mkdir -p backend/backend-for-frontend/src/main/java/com/clawsaas/bff/exception
mkdir -p backend/backend-for-frontend/src/main/resources
```

```java
package com.clawsaas.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BffApplication {

    public static void main(String[] args) {
        SpringApplication.run(BffApplication.class, args);
    }
}
```

- [ ] **Step 3: Write application.yml**

```yaml
spring:
  application:
    name: backend-for-frontend
server:
  port: 8081
```

- [ ] **Step 4: Write config/package-info.java**

```java
/**
 * BFF configuration classes.
 *
 * Phase 2 will add: HttpClientConfig, WebMvcConfig.
 */
package com.clawsaas.bff.config;
```

- [ ] **Step 5: Write GlobalExceptionHandler.java**

(Same structure as gateway — see Task 3 Step 5. Change package to `com.clawsaas.bff.exception`.)

```java
package com.clawsaas.bff.exception;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "code", 500,
                "message", "Internal server error",
                "requestId", ""
        ));
    }
}
```

- [ ] **Step 6: Verify compiles**

```bash
cd backend && mvn -pl backend-for-frontend validate
```

Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add backend/backend-for-frontend/
git commit -m "chore: add backend-for-frontend service shell (8081, Web MVC)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 5: Claw-service shell

**Files:**
- Create: `backend/claw-service/pom.xml`
- Create: `backend/claw-service/src/main/java/com/clawsaas/claw/ClawServiceApplication.java`
- Create: `backend/claw-service/src/main/resources/application.yml`
- Create: `backend/claw-service/src/main/java/com/clawsaas/claw/config/package-info.java`
- Create: `backend/claw-service/src/main/java/com/clawsaas/claw/exception/GlobalExceptionHandler.java`

**Consumes:** `backend/pom.xml` (parent POM)
**Produces:** Claw service compiles as a Spring Web MVC + JPA application on port 8082. Manages Claw instances, conversations, messages, Agent install/config — pure DB CRUD, no OSS or file operations.

- [ ] **Step 1: Create directories and write pom.xml**

```bash
mkdir -p backend/claw-service/src/main/java/com/clawsaas/claw/config
mkdir -p backend/claw-service/src/main/java/com/clawsaas/claw/exception
mkdir -p backend/claw-service/src/main/resources
```

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.clawsaas</groupId>
    <artifactId>claw-saas-backend</artifactId>
    <version>0.1.0</version>
    <relativePath>../pom.xml</relativePath>
  </parent>

  <artifactId>claw-service</artifactId>
  <name>claw-saas-claw-service</name>
  <description>Claw service — Claw instances, conversations, messages, Agent install/config (DB CRUD only)</description>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-mysql</artifactId>
    </dependency>
    <dependency>
      <groupId>com.h2database</groupId>
      <artifactId>h2</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>com.mysql</groupId>
      <artifactId>mysql-connector-j</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Write ClawServiceApplication.java**

```java
package com.clawsaas.claw;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ClawServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClawServiceApplication.class, args);
    }
}
```

- [ ] **Step 3: Write application.yml**

```yaml
spring:
  application:
    name: claw-service
server:
  port: 8082
```

- [ ] **Step 4: Write config/package-info.java**

```java
/**
 * Claw service configuration.
 *
 * Phase 2 will add: DataSourceConfig, JpaConfig, FlywayConfig.
 */
package com.clawsaas.claw.config;
```

- [ ] **Step 5: Write GlobalExceptionHandler.java**

Same structure as gateway's handler (see Task 3 Step 5). Change package to `com.clawsaas.claw.exception`.

- [ ] **Step 6: Verify compiles**

```bash
cd backend && mvn -pl claw-service validate
```

Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add backend/claw-service/
git commit -m "chore: add claw-service shell (8082, Web MVC + JPA)

Claw instances, conversations, Agent installs — DB CRUD only, no OSS.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 6: Runtime-service shell

**Files:**
- Create: `backend/runtime-service/pom.xml`
- Create: `backend/runtime-service/src/main/java/com/clawsaas/runtime/RuntimeServiceApplication.java`
- Create: `backend/runtime-service/src/main/resources/application.yml`
- Create: `backend/runtime-service/src/main/java/com/clawsaas/runtime/config/package-info.java`
- Create: `backend/runtime-service/src/main/java/com/clawsaas/runtime/exception/GlobalExceptionHandler.java`

**Consumes:** `backend/pom.xml` (parent POM)
**Produces:** Runtime service compiles as Spring Web MVC + JPA + HTTP client on port 8083.

- [ ] **Step 1: Create directories**

```bash
mkdir -p backend/runtime-service/src/main/java/com/clawsaas/runtime/config
mkdir -p backend/runtime-service/src/main/java/com/clawsaas/runtime/exception
mkdir -p backend/runtime-service/src/main/resources
```

- [ ] **Step 2: Write pom.xml**

Same dependencies as claw-service (Web, JPA, Validation, Actuator, Flyway, H2, PostgreSQL, MySQL). The HTTP client for FastAPI calls uses Spring's `RestTemplate` from `spring-boot-starter-web` — no extra dependency needed in the skeleton.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.clawsaas</groupId>
    <artifactId>claw-saas-backend</artifactId>
    <version>0.1.0</version>
    <relativePath>../pom.xml</relativePath>
  </parent>

  <artifactId>runtime-service</artifactId>
  <name>claw-saas-runtime-service</name>
  <description>Runtime service — orchestration, approval, FastAPI bridge, Provider, Secret</description>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-mysql</artifactId>
    </dependency>
    <dependency>
      <groupId>com.h2database</groupId>
      <artifactId>h2</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>com.mysql</groupId>
      <artifactId>mysql-connector-j</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: Write RuntimeServiceApplication.java**

```java
package com.clawsaas.runtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RuntimeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RuntimeServiceApplication.class, args);
    }
}
```

- [ ] **Step 4: Write application.yml**

```yaml
spring:
  application:
    name: runtime-service
server:
  port: 8083
```

- [ ] **Step 5: Write config/package-info.java**

```java
/**
 * Runtime service configuration.
 *
 * Phase 2 will add: DataSourceConfig, RestTemplateConfig, PyclawRuntimeApiConfig,
 * SecretEncryptionConfig, SandboxProperties.
 */
package com.clawsaas.runtime.config;
```

- [ ] **Step 6: Write GlobalExceptionHandler.java**

Same structure. Change package to `com.clawsaas.runtime.exception`.

- [ ] **Step 7: Verify compiles**

```bash
cd backend && mvn -pl runtime-service validate
```

Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add backend/runtime-service/
git commit -m "chore: add runtime-service shell (8083, Web MVC + JPA)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 7: Agent-marketplace-service shell (with OSS config)

**Files:**
- Create: `backend/agent-marketplace-service/pom.xml`
- Create: `backend/agent-marketplace-service/src/main/java/com/clawsaas/agentmarketplace/AgentMarketplaceServiceApplication.java`
- Create: `backend/agent-marketplace-service/src/main/resources/application.yml`
- Create: `backend/agent-marketplace-service/src/main/java/com/clawsaas/agentmarketplace/config/package-info.java`
- Create: `backend/agent-marketplace-service/src/main/java/com/clawsaas/agentmarketplace/config/OssConfig.java`
- Create: `backend/agent-marketplace-service/src/main/java/com/clawsaas/agentmarketplace/exception/GlobalExceptionHandler.java`

**Consumes:** `backend/pom.xml` (parent POM)
**Produces:** Agent marketplace compiles with OSS client config placeholder on port 8084.

- [ ] **Step 1: Create directories**

```bash
mkdir -p backend/agent-marketplace-service/src/main/java/com/clawsaas/agentmarketplace/config
mkdir -p backend/agent-marketplace-service/src/main/java/com/clawsaas/agentmarketplace/exception
mkdir -p backend/agent-marketplace-service/src/main/resources
```

- [ ] **Step 2: Write pom.xml**

Same JPA dependencies as claw-service, plus OSS SDK:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.clawsaas</groupId>
    <artifactId>claw-saas-backend</artifactId>
    <version>0.1.0</version>
    <relativePath>../pom.xml</relativePath>
  </parent>

  <artifactId>agent-marketplace-service</artifactId>
  <name>claw-saas-agent-marketplace-service</name>
  <description>Agent marketplace — agent publishing, versioning, OSS artifact storage</description>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-mysql</artifactId>
    </dependency>
    <dependency>
      <groupId>com.aliyun.oss</groupId>
      <artifactId>aliyun-sdk-oss</artifactId>
    </dependency>
    <dependency>
      <groupId>com.h2database</groupId>
      <artifactId>h2</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>com.mysql</groupId>
      <artifactId>mysql-connector-j</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: Write AgentMarketplaceServiceApplication.java**

```java
package com.clawsaas.agentmarketplace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AgentMarketplaceServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentMarketplaceServiceApplication.class, args);
    }
}
```

- [ ] **Step 4: Write application.yml with OSS placeholder**

```yaml
spring:
  application:
    name: agent-marketplace-service
server:
  port: 8084

clawsaas:
  oss:
    endpoint: ${OSS_ENDPOINT:}
    access-key-id: ${OSS_ACCESS_KEY_ID:}
    access-key-secret: ${OSS_ACCESS_KEY_SECRET:}
    bucket: ${OSS_BUCKET:claw-saas-artifacts}
```

- [ ] **Step 5: Write OssConfig.java**

```java
package com.clawsaas.agentmarketplace.config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OssConfig {

    private static final Logger log = LoggerFactory.getLogger(OssConfig.class);

    @Bean
    @ConfigurationProperties(prefix = "clawsaas.oss")
    public OssProperties ossProperties() {
        return new OssProperties();
    }

    @Bean
    public OSS ossClient(OssProperties properties) {
        if (properties.getEndpoint() == null || properties.getEndpoint().isBlank()) {
            log.warn("OSS endpoint not configured — OSS client will not be available");
            return null;
        }
        log.info("Creating OSS client: endpoint={} bucket={}", properties.getEndpoint(), properties.getBucket());
        return new OSSClientBuilder().build(
                properties.getEndpoint(),
                properties.getAccessKeyId(),
                properties.getAccessKeySecret());
    }

    public static class OssProperties {
        private String endpoint;
        private String accessKeyId;
        private String accessKeySecret;
        private String bucket = "claw-saas-artifacts";

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getAccessKeyId() { return accessKeyId; }
        public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }
        public String getAccessKeySecret() { return accessKeySecret; }
        public void setAccessKeySecret(String accessKeySecret) { this.accessKeySecret = accessKeySecret; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
    }
}
```

- [ ] **Step 6: Write config/package-info.java**

```java
/**
 * Agent marketplace service configuration.
 *
 * Phase 2 will add: DataSourceConfig, JpaConfig, FlywayConfig.
 */
package com.clawsaas.agentmarketplace.config;
```

- [ ] **Step 7: Write GlobalExceptionHandler.java**

Same structure. Change package to `com.clawsaas.agentmarketplace.exception`.

- [ ] **Step 8: Verify compiles**

```bash
cd backend && mvn -pl agent-marketplace-service validate
```

Expected: BUILD SUCCESS

- [ ] **Step 9: Commit**

```bash
git add backend/agent-marketplace-service/
git commit -m "chore: add agent-marketplace-service shell (8084, Web MVC + JPA + OSS)

OSS config placeholder with Alibaba Cloud OSS SDK 3.18.1.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 8: Billing-service shell

**Files:**
- Create: `backend/billing-service/pom.xml`
- Create: `backend/billing-service/src/main/java/com/clawsaas/billing/BillingServiceApplication.java`
- Create: `backend/billing-service/src/main/resources/application.yml`
- Create: `backend/billing-service/src/main/java/com/clawsaas/billing/config/package-info.java`
- Create: `backend/billing-service/src/main/java/com/clawsaas/billing/exception/GlobalExceptionHandler.java`

**Consumes:** `backend/pom.xml` (parent POM)
**Produces:** Billing service compiles as Spring Web MVC + JPA on port 8085.

- [ ] **Step 1: Create directories and write pom.xml**

```bash
mkdir -p backend/billing-service/src/main/java/com/clawsaas/billing/config
mkdir -p backend/billing-service/src/main/java/com/clawsaas/billing/exception
mkdir -p backend/billing-service/src/main/resources
```

Pom dependencies: same JPA stack as claw-service (Web, JPA, Validation, Actuator, Flyway, H2, PostgreSQL, MySQL). No OSS needed.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.clawsaas</groupId>
    <artifactId>claw-saas-backend</artifactId>
    <version>0.1.0</version>
    <relativePath>../pom.xml</relativePath>
  </parent>

  <artifactId>billing-service</artifactId>
  <name>claw-saas-billing-service</name>
  <description>Billing service — usage, quotas, plans, invoicing</description>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-mysql</artifactId>
    </dependency>
    <dependency>
      <groupId>com.h2database</groupId>
      <artifactId>h2</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>com.mysql</groupId>
      <artifactId>mysql-connector-j</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: Write BillingServiceApplication.java**

```java
package com.clawsaas.billing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BillingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillingServiceApplication.class, args);
    }
}
```

- [ ] **Step 3: Write application.yml**

```yaml
spring:
  application:
    name: billing-service
server:
  port: 8085
```

- [ ] **Step 4: Write config/package-info.java**

```java
/**
 * Billing service configuration.
 *
 * Phase 2 will add: DataSourceConfig, JpaConfig, FlywayConfig.
 */
package com.clawsaas.billing.config;
```

- [ ] **Step 5: Write GlobalExceptionHandler.java**

Same structure. Change package to `com.clawsaas.billing.exception`.

- [ ] **Step 6: Verify compiles**

```bash
cd backend && mvn -pl billing-service validate
```

Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add backend/billing-service/
git commit -m "chore: add billing-service shell (8085, Web MVC + JPA)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 9: Skill-marketplace-service shell (with OSS config)

**Files:**
- Create: `backend/skill-marketplace-service/pom.xml`
- Create: `backend/skill-marketplace-service/src/main/java/com/clawsaas/skillmarketplace/SkillMarketplaceServiceApplication.java`
- Create: `backend/skill-marketplace-service/src/main/resources/application.yml`
- Create: `backend/skill-marketplace-service/src/main/java/com/clawsaas/skillmarketplace/config/package-info.java`
- Create: `backend/skill-marketplace-service/src/main/java/com/clawsaas/skillmarketplace/config/OssConfig.java`
- Create: `backend/skill-marketplace-service/src/main/java/com/clawsaas/skillmarketplace/exception/GlobalExceptionHandler.java`

**Consumes:** `backend/pom.xml` (parent POM)
**Produces:** Skill marketplace compiles with OSS client config on port 8086. Mirrors agent-marketplace-service structure.

- [ ] **Step 1: Create directories**

```bash
mkdir -p backend/skill-marketplace-service/src/main/java/com/clawsaas/skillmarketplace/config
mkdir -p backend/skill-marketplace-service/src/main/java/com/clawsaas/skillmarketplace/exception
mkdir -p backend/skill-marketplace-service/src/main/resources
```

- [ ] **Step 2: Write pom.xml**

Identical to agent-marketplace-service pom (Web, JPA, Validation, Actuator, Flyway, OSS SDK, H2, PostgreSQL, MySQL). Change `artifactId` to `skill-marketplace-service`, `name` to `claw-saas-skill-marketplace-service`, `description` to `Skill marketplace — skill publishing, search, installation, OSS artifact storage`.

- [ ] **Step 3: Write SkillMarketplaceServiceApplication.java**

```java
package com.clawsaas.skillmarketplace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SkillMarketplaceServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SkillMarketplaceServiceApplication.class, args);
    }
}
```

- [ ] **Step 4: Write application.yml with OSS placeholder**

```yaml
spring:
  application:
    name: skill-marketplace-service
server:
  port: 8086

clawsaas:
  oss:
    endpoint: ${OSS_ENDPOINT:}
    access-key-id: ${OSS_ACCESS_KEY_ID:}
    access-key-secret: ${OSS_ACCESS_KEY_SECRET:}
    bucket: ${OSS_BUCKET:claw-saas-artifacts}
```

- [ ] **Step 5: Write OssConfig.java**

Copy from agent-marketplace-service Task 7 Step 5, change package to `com.clawsaas.skillmarketplace.config`.

- [ ] **Step 6: Write config/package-info.java** and **GlobalExceptionHandler.java** (same pattern as prior services, with `skillmarketplace` package).

- [ ] **Step 7: Verify compiles**

```bash
cd backend && mvn -pl skill-marketplace-service validate
```

Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add backend/skill-marketplace-service/
git commit -m "chore: add skill-marketplace-service shell (8086, Web MVC + JPA + OSS)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 10: Full Maven build verification

**Files:**
- None (verification only)

**Consumes:** Tasks 2–9 (all backend service shells + parent POM)
**Produces:** Confirmation that all 8 modules compile together.

- [ ] **Step 1: Compile the entire backend**

```bash
cd backend && mvn compile
```

Expected: all 8 modules compile successfully.

- [ ] **Step 2: Run tests (should be empty/no-op)**

```bash
cd backend && mvn test
```

Expected: BUILD SUCCESS (0 tests run, since no test classes yet).

- [ ] **Step 3: Commit any fixes if needed, or note "BUILD SUCCESS"**

```bash
echo "Backend skeleton: all 8 modules compile. BUILD SUCCESS."
```

---

### Task 11: Python pyclaw-runtime-api shell

**Files:**
- Create: `runtime/pyclaw-runtime-api/pyproject.toml`
- Create: `runtime/pyclaw-runtime-api/app/main.py`
- Create: `runtime/pyclaw-runtime-api/app/__init__.py`
- Create: `runtime/pyclaw-runtime-api/app/api/__init__.py`
- Create: `runtime/pyclaw-runtime-api/app/api/health.py`
- Create: `runtime/pyclaw-runtime-api/app/runtime/__init__.py`
- Create: `runtime/pyclaw-runtime-api/app/schemas/__init__.py`
- Create: `runtime/pyclaw-runtime-api/app/config/__init__.py`
- Create: `runtime/pyclaw-runtime-api/app/config/settings.py`
- Create: `runtime/pyclaw-runtime-api/app/config/logging.py`

**Produces:** FastAPI control plane service starts and responds to `/healthz`.

- [ ] **Step 1: Create directory structure**

```bash
mkdir -p runtime/pyclaw-runtime-api/app/api
mkdir -p runtime/pyclaw-runtime-api/app/runtime
mkdir -p runtime/pyclaw-runtime-api/app/schemas
mkdir -p runtime/pyclaw-runtime-api/app/config
```

- [ ] **Step 2: Create empty __init__.py files**

```bash
touch runtime/pyclaw-runtime-api/app/__init__.py
touch runtime/pyclaw-runtime-api/app/api/__init__.py
touch runtime/pyclaw-runtime-api/app/runtime/__init__.py
touch runtime/pyclaw-runtime-api/app/schemas/__init__.py
touch runtime/pyclaw-runtime-api/app/config/__init__.py
```

- [ ] **Step 3: Write pyproject.toml**

```toml
[project]
name = "pyclaw-runtime-api"
version = "0.1.0"
description = "Claw SaaS Runtime control plane — execution engine"
requires-python = ">=3.11"
dependencies = [
    "fastapi>=0.115.0",
    "uvicorn[standard]>=0.32.0",
    "pydantic>=2.0",
    "pydantic-settings>=2.0",
    "httpx>=0.28.0",
]

[project.optional-dependencies]
dev = [
    "pytest>=8.0",
    "pytest-asyncio>=0.24.0",
    "httpx>=0.28.0",
]

[tool.pytest.ini_options]
asyncio_mode = "auto"
testpaths = ["tests"]
```

- [ ] **Step 4: Write app/main.py**

```python
"""PyClaw Runtime API — FastAPI control plane (execution engine)."""

from fastapi import FastAPI

from app.api.health import router as health_router

app = FastAPI(title="PyClaw Runtime API", version="0.1.0")

app.include_router(health_router)

# Phase 2 will include:
# from app.api.runs import router as runs_router
# from app.api.approvals import router as approvals_router
# from app.api.tools import router as tools_router
```

- [ ] **Step 5: Write app/api/health.py**

```python
"""Health check endpoint."""

from fastapi import APIRouter

router = APIRouter(tags=["health"])


@router.get("/healthz")
def healthz():
    return {"status": "ok", "service": "pyclaw-runtime-api", "version": "0.1.0"}
```

- [ ] **Step 6: Write app/config/settings.py**

```python
"""Application settings."""

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    model_config = {"env_prefix": "PYCLAW_", "case_sensitive": False}

    host: str = "0.0.0.0"
    port: int = 8090
    log_level: str = "INFO"


settings = Settings()
```

- [ ] **Step 7: Write app/config/logging.py**

```python
"""Logging configuration."""

import logging

from app.config.settings import settings

logging.basicConfig(
    level=getattr(logging, settings.log_level.upper(), logging.INFO),
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
```

- [ ] **Step 8: Verify it starts**

```bash
cd runtime/pyclaw-runtime-api
pip install -e ".[dev]"
python -c "from app.main import app; print('FastAPI app created OK')"
```

Expected: prints "FastAPI app created OK" without errors.

- [ ] **Step 9: Commit**

```bash
git add runtime/pyclaw-runtime-api/
git commit -m "chore: add pyclaw-runtime-api shell (8090, FastAPI control plane)

Health endpoint only. No scheduler/ channel directory.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 12: Python claw-runner shell

**Files:**
- Create: `runtime/claw-runner/pyproject.toml`
- Create: `runtime/claw-runner/app/main.py`
- Create: `runtime/claw-runner/app/__init__.py`
- Create: `runtime/claw-runner/app/api/__init__.py`
- Create: `runtime/claw-runner/app/api/health.py`
- Create: `runtime/claw-runner/app/workspace/__init__.py`
- Create: `runtime/claw-runner/app/tools/__init__.py`
- Create: `runtime/claw-runner/app/sandbox/__init__.py`
- Create: `runtime/claw-runner/app/schemas/__init__.py`
- Create: `runtime/claw-runner/app/config/__init__.py`
- Create: `runtime/claw-runner/app/config/settings.py`
- Create: `runtime/claw-runner/app/config/logging.py`

**Produces:** FastAPI data plane service starts and responds to `/healthz`.

- [ ] **Step 1: Create directory structure**

```bash
mkdir -p runtime/claw-runner/app/api
mkdir -p runtime/claw-runner/app/workspace
mkdir -p runtime/claw-runner/app/tools
mkdir -p runtime/claw-runner/app/sandbox
mkdir -p runtime/claw-runner/app/schemas
mkdir -p runtime/claw-runner/app/config
```

- [ ] **Step 2: Create empty __init__.py files**

```bash
touch runtime/claw-runner/app/__init__.py
touch runtime/claw-runner/app/api/__init__.py
touch runtime/claw-runner/app/workspace/__init__.py
touch runtime/claw-runner/app/tools/__init__.py
touch runtime/claw-runner/app/sandbox/__init__.py
touch runtime/claw-runner/app/schemas/__init__.py
touch runtime/claw-runner/app/config/__init__.py
```

- [ ] **Step 3: Write pyproject.toml**

```toml
[project]
name = "claw-runner"
version = "0.1.0"
description = "Claw Runner — isolated execution environment data plane"
requires-python = ">=3.11"
dependencies = [
    "fastapi>=0.115.0",
    "uvicorn[standard]>=0.32.0",
    "pydantic>=2.0",
    "pydantic-settings>=2.0",
]

[project.optional-dependencies]
dev = [
    "pytest>=8.0",
    "pytest-asyncio>=0.24.0",
    "httpx>=0.28.0",
]

[tool.pytest.ini_options]
asyncio_mode = "auto"
testpaths = ["tests"]
```

- [ ] **Step 4: Write app/main.py**

```python
"""Claw Runner — FastAPI data plane (isolated execution environment)."""

from fastapi import FastAPI

from app.api.health import router as health_router

app = FastAPI(title="Claw Runner", version="0.1.0")

app.include_router(health_router)

# Phase 2 will include:
# from app.api.workspace import router as workspace_router
# from app.api.tools import router as tools_router
# from app.api.commands import router as commands_router
```

- [ ] **Step 5: Write app/api/health.py**

```python
"""Health check endpoint."""

import os
from fastapi import APIRouter

router = APIRouter(tags=["health"])

CLAW_ID = os.getenv("PYCLAW_CLAW_ID", "")
OWNER_USER_ID = os.getenv("PYCLAW_OWNER_USER_ID", "")


@router.get("/healthz")
def healthz():
    return {
        "status": "ok",
        "service": "claw-runner",
        "version": "0.1.0",
        "clawId": CLAW_ID,
        "ownerUserId": OWNER_USER_ID,
    }
```

- [ ] **Step 6: Write app/config/settings.py**

```python
"""Application settings."""

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    model_config = {"env_prefix": "PYCLAW_", "case_sensitive": False}

    host: str = "0.0.0.0"
    port: int = 8091
    log_level: str = "INFO"


settings = Settings()
```

- [ ] **Step 7: Write app/config/logging.py** (same as pyclaw-runtime-api, see Task 11 Step 7).

- [ ] **Step 8: Verify it starts**

```bash
cd runtime/claw-runner
pip install -e ".[dev]"
python -c "from app.main import app; print('FastAPI app created OK')"
```

Expected: prints "FastAPI app created OK" without errors.

- [ ] **Step 9: Commit**

```bash
git add runtime/claw-runner/
git commit -m "chore: add claw-runner shell (8091, FastAPI data plane)

Health endpoint with clawId/ownerUserId from env. workspace/ path_guard.py
placeholder — highest priority for Phase 2 migration.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 13: Deploy directory structure + Helm rename

**Files:**
- Modify: `deploy/helm/pyclaw/Chart.yaml` (rename)
- Modify: `deploy/helm/pyclaw/values.yaml` (namespace rename)
- Create: `deploy/docker-compose.yml` (placeholder)
- Create: `deploy/env/.gitkeep`

**Consumes:** Task 1 (directory restructuring)
**Produces:** Deploy directory with renamed Helm charts and Docker Compose placeholder.

- [ ] **Step 1: Rename Helm chart from pyclaw to claw-saas**

Modify `deploy/helm/pyclaw/Chart.yaml`:

```yaml
apiVersion: v2
name: claw-saas
description: Helm chart for Claw SaaS platform
type: application
version: 0.1.0
appVersion: "0.1.0"
```

- [ ] **Step 2: Update Helm values namespace references**

Modify `deploy/helm/pyclaw/values.yaml` — change `pyclaw` references to `claw-saas` in any namespace or label values. If the file is small, read it first and replace all occurrences.

- [ ] **Step 3: Update Helm _helpers.tpl labels**

Modify `deploy/helm/pyclaw/templates/_helpers.tpl` — change `app.kubernetes.io/part-of: pyclaw` to `app.kubernetes.io/part-of: claw-saas`.

- [ ] **Step 4: Rename Helm chart directory**

```bash
git mv deploy/helm/pyclaw deploy/helm/claw-saas
```

- [ ] **Step 5: Create deploy/docker-compose.yml placeholder**

```yaml
# Claw SaaS local development compose file.
# Phase 2 will add all 8 backend services + 2 runtime services + DB + Redis.
version: "3.9"

services:
  # Placeholder — services added in Phase 2
```

- [ ] **Step 6: Create deploy/env/ placeholder**

```bash
mkdir -p deploy/env
touch deploy/env/.gitkeep
```

- [ ] **Step 7: Create deploy/k8s/ placeholder (for raw K8s manifests)**

```bash
mkdir -p deploy/k8s
touch deploy/k8s/.gitkeep
```

- [ ] **Step 8: Commit**

```bash
git add deploy/
git commit -m "chore: set up deploy/ directory with renamed Helm charts

Rename helm/pyclaw -> deploy/helm/claw-saas.
Update Chart.yaml, values.yaml, _helpers.tpl for claw-saas naming.
Add docker-compose.yml, env/, k8s/ placeholders.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 14: Scripts directory + CLAUDE.md update

**Files:**
- Modify: `CLAUDE.md`
- Create: `scripts/.gitkeep` (already done in Task 1, verify)

**Consumes:** All prior tasks
**Produces:** Updated CLAUDE.md reflecting new project structure; scripts/ ready for Phase 2.

- [ ] **Step 1: Update CLAUDE.md**

Replace current content with:

```markdown
# Claude Code 工作规约

## 项目概述

claw-saas 是 Claw SaaS 平台的 monorepo。用户创建 Claw（独立执行环境），每个 Claw 可运行多个 Agent。

## 目录结构

```text
backend/     Java / Spring Boot 微服务（8 个）
frontend/    前端应用
runtime/     Python / FastAPI Runtime（控制面 + 数据面）
deploy/      本地与生产部署配置
scripts/     工程脚本
docs/        架构、设计、计划文档
```

## 关键文档

重构和开发前，先阅读：

```text
docs/architecture/ARCHITECTURE.md          最终目标架构
docs/architecture/backend-coding-standards.md  Java 编码规约
docs/architecture/runtime-coding-standards.md  Python 编码规约
docs/superpowers/specs/2026-07-19-claw-saas-refactoring-design.md  重构设计
docs/superpowers/plans/2026-07-19-claw-saas-skeleton-plan.md       阶段一计划
```

## 工作要求

1. 当前阶段：**阶段一（骨架搭建）** 或 **阶段二（业务迁移）**，以实施计划为准。
2. Spring Boot Controller 不写业务逻辑，业务逻辑进入 service/impl。
3. FastAPI router 不写业务编排，编排在 Spring runtime-service。
4. Claw Runner 必须保持执行环境隔离（path_guard, sandbox limits）。
5. 不直接把 Entity 暴露给前端或跨服务接口。
6. 不建 common 共享模块，各服务自持工具类（异常处理、加密等）。
7. 先拆服务边界，再迁移业务代码。每次只迁移一个服务。
8. 改动后运行对应模块的校验或测试。

## 服务速查

| 服务 | 端口 | 技术 |
|------|------|------|
| gateway | 8080 | Spring Cloud Gateway (WebFlux) |
| backend-for-frontend | 8081 | Spring Web MVC |
| claw-service | 8082 | Spring Web MVC + JPA |
| runtime-service | 8083 | Spring Web MVC + JPA |
| agent-marketplace-service | 8084 | Spring Web MVC + JPA + OSS |
| billing-service | 8085 | Spring Web MVC + JPA |
| skill-marketplace-service | 8086 | Spring Web MVC + JPA + OSS |
| pyclaw-runtime-api | 8090 | FastAPI (控制面) |
| claw-runner | 8091 | FastAPI (数据面) |

## 当前状态

阶段一骨架已搭建完毕。阶段二按以下顺序迁移业务代码：

```text
1. claw-service
2. runtime-service
3. agent-marketplace-service
4. skill-marketplace-service
5. billing-service
6. gateway + backend-for-frontend
7. Python Runtime: pyclaw-runtime-api → claw-runner
```
```

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md for claw-saas skeleton structure

Reflect 8 microservices + 2 FastAPI services + deploy layout.
Reference design spec and implementation plan.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 15: Final verification

**Files:**
- None (verification only)

**Consumes:** Tasks 1–14 (all skeleton files)
**Produces:** Confirmation that the entire skeleton is complete and clean.

- [ ] **Step 1: Full Maven compile**

```bash
cd backend && mvn compile
```

Expected: BUILD SUCCESS (all 8 modules)

- [ ] **Step 2: Verify Python services import**

```bash
cd runtime/pyclaw-runtime-api && python -c "from app.main import app; print('pyclaw-runtime-api OK')"
cd ../claw-runner && python -c "from app.main import app; print('claw-runner OK')"
```

Expected: Both print OK without errors.

- [ ] **Step 3: Verify project structure matches design**

```bash
echo "=== backend modules ===" && ls -d backend/*/
echo "=== runtime services ===" && ls -d runtime/*/
echo "=== deploy layout ===" && ls deploy/
echo "=== frontend exists ===" && test -f frontend/package.json && echo "yes"
echo "=== scripts exists ===" && test -d scripts && echo "yes"
```

- [ ] **Step 4: Verify no com.anxin.pyclaw references in new code**

```bash
grep -r "com.anxin.pyclaw" backend/ runtime/ frontend/ deploy/ scripts/ --include="*.java" --include="*.xml" --include="*.py" --include="*.toml" --include="*.json" --include="*.yaml" --include="*.yml" || echo "No old package references found — OK"
```

- [ ] **Step 5: Check git status is clean of unexpected files**

```bash
git status
```

Expected: only the files we intentionally created/modified.

- [ ] **Step 6: Final commit**

```bash
git add -A
git commit -m "chore: finalize Phase 1 skeleton verification

All 8 Java modules compile. Both FastAPI services import OK.
Project structure matches design spec.

Co-Authored-By: Claude <noreply@anthropic.com>"
```
