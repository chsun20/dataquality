---
kind: configuration_system
name: 基于 Nacos 的 Spring Cloud 配置中心与多环境分层配置体系
category: configuration_system
scope:
    - '**'
source_files:
    - ruoyi-gateway/src/main/resources/bootstrap.yml
    - ruoyi-auth/src/main/resources/bootstrap.yml
    - ruoyi-modules/ruoyi-system/src/main/resources/bootstrap.yml
    - ruoyi-modules/ruoyi-dqservice/src/main/resources/bootstrap.yml
    - docker/nacos/conf/application.properties
    - docker/mysql/db/ry_config_20220424.sql
    - ruoyi-ui/.env.development
    - ruoyi-ui/.env.production
    - ruoyi-ui/.env.staging
    - docker/docker-compose.yml
---

## 1. 采用的系统与框架

后端采用 **Spring Cloud + Spring Cloud Alibaba Nacos** 作为统一的配置中心与服务注册发现中心。所有微服务（ruoyi-gateway、ruoyi-auth、ruoyi-system、ruoyi-file、ruoyi-gen、ruoyi-job、ruoyi-dqservice、ruoyi-monitor）通过 `bootstrap.yml` 在应用启动早期连接 Nacos，拉取远程配置。

Nacos 自身以 Docker 镜像形式随项目提供（`docker/nacos/`），其持久化存储指向 MySQL 中的 `ry-config` 库（见 `docker/nacos/conf/application.properties`：`db.url.0=jdbc:mysql://ruoyi-mysql:3306/ry-config?...`），建表脚本位于 `sql/ry_config_20220424.sql`。

前端（ruoyi-ui）使用 Vite/Vue CLI 的 `.env.*` 文件按环境注入构建期变量（`VUE_APP_BASE_API`、`ENV`、`VUE_APP_TITLE`）。

## 2. 关键文件与位置

- 各微服务 `src/main/resources/bootstrap.yml`：统一声明 `spring.application.name`、`spring.profiles.active=dev`、`spring.cloud.nacos.discovery/config.server-addr=127.0.0.1:8848`、`file-extension=yml` 以及共享配置加载规则。
- `docker/nacos/conf/application.properties`：Nacos 服务端配置，包含 MySQL 数据源、鉴权开关（`nacos.core.auth.enabled=false`）、Token 密钥等。
- `docker/mysql/db/ry_config_20220424.sql`：Nacos 配置中心所需的数据库表结构。
- `ruoyi-ui/.env.development` / `.env.production` / `.env.staging`：前端环境变量。
- `docker/docker-compose.yml` 与 `docker/deploy.sh`：编排 Nacos、MySQL、Redis、Nginx 与各微服务 Jar 包。
- `bin/run-*.bat` / `bin/start-backend.ps1`：本地开发时直接启动各服务的批处理脚本。

## 3. 架构与约定

### 3.1 配置加载顺序（Spring Boot + Nacos）

每个微服务的 `bootstrap.yml` 遵循同一模板：

```
spring:
  application:
    name: ruoyi-xxx
  profiles:
    active: dev
  cloud:
    nacos:
      discovery.server-addr: 127.0.0.1:8848
      config:
        server-addr: 127.0.0.1:8848
        file-extension: yml
        shared-configs:
          - application-${spring.profiles.active}.${spring.cloud.nacos.config.file-extension}
```

由此形成如下约定：

1. **应用名即 dataId 前缀**：Nacos 中每个服务对应一个 dataId，形如 `ruoyi-gateway.yml`、`ruoyi-auth.yml` 等，由 `spring.application.name` 决定。
2. **共享配置文件**：通过 `shared-configs` 引入 `application-{profile}.yml`（例如 `application-dev.yml`），用于存放跨服务共享的配置（如公共 Redis、MySQL、Nacos 地址等）。该文件名由 `${spring.profiles.active}` 与 `file-extension=yml` 拼接而成。
3. **Profile 切换**：默认 `active=dev`，可通过 JVM 参数 `-Dspring.profiles.active=prod` 或 Nacos 配置覆盖。
4. **网关额外集成 Sentinel**：`ruoyi-gateway/bootstrap.yml` 额外配置了 `spring.cloud.sentinel.datasource.ds1.nacos.dataId=sentinel-ruoyi-gateway`，将限流规则也托管到 Nacos。

### 3.2 本地 vs 远程配置

- 本地开发：`bootstrap.yml` 中 `server-addr=127.0.0.1:8848`，要求本机运行 Nacos；若 Nacos 不可用，则只能依赖 `resources/` 下已有的 `application.yml`（当前仓库未提交业务模块的 `application.yml`，说明运行时完全依赖 Nacos）。
- 容器部署：`docker/docker-compose.yml` 会先拉起 Nacos，再拉起各微服务，保证配置中心可用。

### 3.3 前端配置

前端通过 `ruoyi-ui/.env.{development,production,staging}` 区分 API 基地址：

| 文件 | `ENV` | `VUE_APP_BASE_API` |
|---|---|---|
| `.env.development` | `'development'` | `/dev-api` |
| `.env.production` | `'production'` | `/prod-api` |
| `.env.staging` | （存在但未列出内容） | — |

这些变量被 `vue.config.js` 与 `src/utils/request.js` 等消费，用于代理转发到后端网关。

### 3.4 Nacos 服务端安全

`docker/nacos/conf/application.properties` 显式关闭认证（`nacos.core.auth.enabled=false`），并设置了一个硬编码的 Token 密钥（`nacos.core.auth.default.token.secret.key=SecretKey...`）。这意味着在 Docker 环境中 Nacos 对客户端不强制鉴权。

## 4. 约定与约束

- **所有微服务必须保留 `bootstrap.yml`**：因为 Nacos 配置在 Spring `Environment` 初始化阶段加载，放在 `application.yml` 中无法生效。仓库中所有子模块均遵循这一模式。
- **Nacos dataId 命名约定**：dataId 必须为 `<spring.application.name>.yml`，group 使用默认的 `DEFAULT_GROUP`（从 gateway 的 sentinel 配置可推断其他配置也沿用默认 group）。
- **共享配置命名约定**：共享配置 dataId 必须为 `application-{profile}.yml`，其中 `{profile}` 来自 `spring.profiles.active`。
- **配置文件格式固定为 yml**：`file-extension=yml` 在所有 bootstrap 中一致，禁止改为 properties 或 yaml。
- **Nacos 地址集中管理**：开发环境统一写死 `127.0.0.1:8848`，生产环境应通过外部方式（如环境变量或 Nacos 自身的配置覆盖）调整，避免修改源码。
- **Sentinel 规则独立 dataId**：网关的限流规则单独存放在 Nacos 中，dataId 为 `sentinel-ruoyi-gateway`，groupId 为 `DEFAULT_GROUP`，type 为 `flow`，格式为 JSON。
- **前端环境变量必须以 `VUE_APP_` 前缀暴露**：这是 Vue CLI 的约定，只有带此前缀的变量才会被编译进浏览器端代码。

## 5. 观察到的局限

- 仓库中没有提交任何业务模块的 `application.yml`，也没有提交 Nacos 中已发布的配置快照——配置完全外置到 Nacos，因此仅凭源码无法还原完整运行时配置。
- Nacos 服务端鉴权关闭且 Token 密钥硬编码在 `application.properties` 中，属于开发环境的简化配置，不应直接用于生产。
- 前端 `.env.staging` 文件存在但未被读取示例，是否实际使用需结合 CI 构建命令确认。