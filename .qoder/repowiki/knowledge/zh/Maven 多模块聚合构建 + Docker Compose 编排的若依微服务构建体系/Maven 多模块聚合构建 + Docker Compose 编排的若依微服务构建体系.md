---
kind: build_system
name: Maven 多模块聚合构建 + Docker Compose 编排的若依微服务构建体系
category: build_system
scope:
    - '**'
source_files:
    - pom.xml
    - docker/docker-compose.yml
    - docker/deploy.sh
    - docker/copy.sh
    - docker/mysql/dockerfile
    - docker/ruoyi/gateway/dockerfile
    - bin/package.bat
    - bin/start-backend.ps1
    - bin/start-ui.ps1
    - bin/run-auth.bat
    - ruoyi-ui/package.json
    - ruoyi-ui/vue.config.js
---

## 1. 构建系统与工具链

- **后端**：基于 Maven 3.x 的多模块聚合工程（根 `pom.xml`，`packaging=pom`），使用 Spring Boot 2.6.7 + Spring Cloud 2021.0.1 + Spring Cloud Alibaba 2021.0.1.0，Java 8 编译与运行。
- **前端**：Vue 2 + Element UI，通过 `ruoyi-ui/package.json` 与 `vue.config.js` 管理依赖，构建产物输出到 `ruoyi-ui/dist`。
- **容器化**：每个后端子服务在 `docker/ruoyi/<service>/dockerfile` 中独立定义镜像；基础设施（MySQL、Nacos、Redis、Nginx）由 `docker/docker-compose.yml` 统一编排。
- **本地开发脚本**：Windows 批处理（`bin/*.bat`）与 PowerShell（`bin/start-backend.ps1`、`bin/start-ui.ps1`）提供一键打包、单体 Jar 启动、前后端进程化管理。

## 2. 关键文件

- 根构建入口：`pom.xml`（聚合模块、统一版本、插件配置）
- 容器编排：`docker/docker-compose.yml`
- 部署辅助：`docker/deploy.sh`、`docker/copy.sh`
- 各服务 Dockerfile：`docker/ruoyi/{auth,gateway,modules/system,modules/file,modules/job,modules/gen,visual/monitor}/dockerfile`
- MySQL 初始化镜像：`docker/mysql/dockerfile`（将 SQL 注入 `/docker-entrypoint-initdb.d/`）
- Windows 后端打包/运行：`bin/package.bat`、`bin/run-*.bat`、`bin/start-backend.ps1`
- Windows 前端启动：`bin/start-ui.ps1`
- 前端构建配置：`ruoyi-ui/package.json`、`ruoyi-ui/vue.config.js`

## 3. 架构与约定

### 3.1 Maven 聚合与版本管理

- 根 `pom.xml` 通过 `<dependencyManagement>` 集中声明所有第三方依赖版本（Spring Boot、Spring Cloud、Alibaba Nacos、MyBatis、Swagger、Druid、FastJSON、MinIO、POI 等），子模块仅引用 groupId/artifactId 不写 version。
- 项目自身模块（`ruoyi-common-*`、`ruoyi-api-system`）统一使用 `${ruoyi.version}`（当前为 `3.5.0`）。
- 根 POM 的 `<modules>` 列出六大顶层模块：`ruoyi-auth`、`ruoyi-gateway`、`ruoyi-visual`、`ruoyi-modules`、`ruoyi-api`、`ruoyi-common`。
- 编译器插件锁定 JDK 1.8（`source/target=1.8`，UTF-8 编码）；`spring-boot-maven-plugin` 版本通过 `<pluginManagement>` 固定为 `${spring-boot.version}`，避免 Maven 解析到要求 JDK 17+ 的新版插件。

### 3.2 后端打包与运行

- 标准构建命令：`mvn clean package -Dmaven.test.skip=true`（见 `bin/package.bat`）。
- 每个 Spring Boot 子模块通过 `spring-boot-maven-plugin` 打成可执行 Jar，路径遵循 `<module>/target/<artifact>.jar`。
- 本地单 Jar 运行：`bin/run-*.bat` 设置 `-Xms512m -Xmx1024m -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=512m` 并指定 `-Dfile.encoding=utf-8`。
- 多进程后台启动：`bin/start-backend.ps1` 维护一个有序映射（auth→9200、system→9201、dqservice→9205、gateway→8089），按端口检测是否已监听，未监听则用 `Start-Process` 拉起 Java 进程并将 stdout/stderr 重定向到 `logs/<service>.out.log` / `logs/<service>.err.log`，同时等待最多 120s 直到端口就绪。

### 3.3 前端构建与开发

- 前端位于 `ruoyi-ui/`，使用 Vue CLI 4.x（webpack 4），Node 17+ 需 `--openssl-legacy-provider` 以兼容 md4 哈希（见 `bin/start-ui.ps1` 注释与 `$env:NODE_OPTIONS` 设置）。
- 默认开发端口 8081，可通过参数 `-Port` 覆盖；脚本会先检查 `node_modules` 是否存在，不存在时提示执行 `npm install --registry=https://registry.npmmirror.com`。
- 生产构建产物输出到 `ruoyi-ui/dist`，由 `docker/copy.sh` 复制到 `docker/nginx/html/dist` 供 Nginx 静态托管。

### 3.4 Docker 镜像与编排

- 每个后端服务的 `docker/ruoyi/<service>/dockerfile` 采用相同模式：`FROM openjdk:8-jre` → `WORKDIR /home/ruoyi` → `COPY ./jar/<service>.jar` → `ENTRYPOINT java -jar <service>.jar`。
- 镜像构建前需先执行 `docker/copy.sh`，把 Maven 产出的 Jar 和 SQL 脚本拷贝到 `docker/` 对应目录。
- `docker/docker-compose.yml` 定义 10 个 service：`ruoyi-nacos`（v2.0.4）、`ruoyi-mysql`（mysql:5.7，带自定义 command 启用 utf8mb4、时区 +8:00、lower-case-table-names=1、innodb-buffer-pool-size=80M）、`ruoyi-redis`、`ruoyi-nginx`、`ruoyi-gateway`、`ruoyi-auth`、`ruoyi-modules-system`、`ruoyi-modules-gen`、`ruoyi-modules-job`、`ruoyi-modules-file`、`ruoyi-visual-monitor`。
- 依赖关系通过 `depends_on` 与 `links` 表达：Nacos 依赖 MySQL，Nginx 依赖 Gateway，业务模块依赖 Redis/MySQL。
- MySQL 镜像通过 `ADD ./db/*.sql /docker-entrypoint-initdb.d/` 自动初始化数据库（`ry_20210908.sql`、`ry_config_20220424.sql` 等）。

### 3.5 服务器部署脚本

- `docker/deploy.sh` 封装 `port`（firewall-cmd 开放 80/8080/8848/9848/9849/6379/3306/9100/9200~9203/9300）、`base`（启动 mysql/redis/nacos）、`modules`（启动 nginx/gateway/auth/system）、`stop`、`rm` 五个子命令。

## 4. 约定与约束

- **JDK 版本约束**：源码与运行时均为 Java 8。根 POM 显式设置 `java.version=1.8`，并通过 `pluginManagement` 锁定 `spring-boot-maven-plugin` 版本以避免被解析到需要 JDK 17+ 的版本（根 POM 注释明确说明此意图）。
- **依赖版本单一来源**：所有第三方库版本集中在根 `pom.xml` 的 `<properties>` 与 `<dependencyManagement>` 中，子模块不得重复声明版本。
- **Nacos 版本强绑定**：`docker-compose.yml` 中 Nacos 镜像固定为 `nacos/nacos-server:v2.0.4`，注释说明“与项目 nacos-client 2.0.4 及 ry_config 库表结构完全匹配”。
- **MySQL 初始化数据位置**：SQL 脚本必须放在 `docker/mysql/db/*.sql`，由 `docker/mysql/dockerfile` 的 `ADD ./db/*.sql /docker-entrypoint-initdb.d/` 自动加载；`docker/copy.sh` 负责从 `../sql/` 复制。
- **Jar 产物位置约定**：Docker 构建期望 Jar 位于 `docker/ruoyi/<service>/jar/<service>.jar`，由 `docker/copy.sh` 从 Maven `target/` 目录拷贝而来。
- **端口分配约定**：Gateway=8080/8089、Auth=9200、System=9201、Gen=9202、Job=9203、File=9300、Monitor=9100、Nacos=8848/9848/9849、Redis=6379、MySQL=3306、Nginx=80，这些端口在 `deploy.sh` 的 firewall 规则中被硬编码。
- **前端 Node 兼容性**：因 Vue CLI 4.x 使用 webpack 4，Node 17+ 必须设置 `NODE_OPTIONS='--openssl-legacy-provider'`，该要求在 `bin/start-ui.ps1` 中以注释和代码双重体现。
- **日志输出约定**：后端服务日志统一输出到仓库根 `logs/` 目录下，命名格式 `<service>.out.log` / `<service>.err.log`，由 `start-backend.ps1` 创建目录并重定向。
- **测试跳过**：默认打包通过 `-Dmaven.test.skip=true` 跳过单元测试，加速 CI/CD 流程。