---
kind: dependency_management
name: Maven 多模块聚合工程依赖管理（Spring Cloud Alibaba + Vue CLI）
category: dependency_management
scope:
    - '**'
---

## 1. 使用的系统/工具

- **后端**：Maven 3.x，采用 `pom` 打包的**多模块聚合工程**。
- **前端**：Vue CLI (`vue-cli-service`)，通过 `ruoyi-ui/package.json` 声明依赖。
- **构建产物**：每个 Spring Boot 子模块使用 `spring-boot-maven-plugin` 的 `repackage` goal 生成可执行 Jar。
- **无锁文件**：仓库中未发现 `pom.xml.lock`、`dependency-lock.xml`、`node_modules/`、`yarn.lock`、`pnpm-lock.yaml` 等锁定文件；也未发现 `vendor/`、`lib/` 等第三方库 vendoring 目录。依赖解析完全依赖远程仓库（默认 Maven Central）。

## 2. 关键文件

- `pom.xml`：根聚合 POM，定义所有公共版本与 `dependencyManagement`。
- `ruoyi-common/pom.xml`：通用能力模块聚合（core、security、log、redis、swagger、datascope、datasource）。
- `ruoyi-modules/pom.xml`：业务模块聚合（system、gen、job、file、dqservice）。
- `ruoyi-api/pom.xml`：Feign API 聚合（ruoyi-api-system）。
- `ruoyi-auth/pom.xml`、`ruoyi-modules/ruoyi-dqservice/pom.xml` 等：具体子模块依赖声明。
- `ruoyi-ui/package.json`：前端依赖清单。
- `ruoyi-modules/ruoyi-dqservice/.mvn/wrapper/maven-wrapper.properties`、`mvnw`：该子模块自带 Maven Wrapper。

## 3. 架构与约定

### 3.1 根 POM 集中管控版本

根 `pom.xml` 在 `<properties>` 中统一声明所有第三方库版本，例如：

| 属性 | 值 |
|---|---|
| `java.version` | `1.8` |
| `spring-boot.version` | `2.6.7` |
| `spring-cloud.version` | `2021.0.1` |
| `spring-cloud-alibaba.version` | `2021.0.1.0` |
| `alibaba.nacos.version` | `2.0.4` |
| `spring-boot-admin.version` | `2.6.6` |
| `druid.version` | `1.2.8` |
| `fastjson.version` | `1.2.80` |
| `minio.version` | `8.2.2` |
| `poi.version` | `4.1.2` |
| `transmittable-thread-local.version` | `2.12.2` |
| `ruoyi.version` | `3.5.0` |

并通过 `<dependencyManagement>` 引入以下 BOM：

- `org.springframework.cloud:spring-cloud-dependencies:${spring-cloud.version}`（import scope）
- `com.alibaba.cloud:spring-cloud-alibaba-dependencies:${spring-cloud-alibaba.version}`（import scope）
- `org.springframework.boot:spring-boot-dependencies:${spring-boot.version}`（import scope）

同时在此处显式声明 Nacos Client、MyBatis、Swagger、FastDFS、PageHelper、Druid、Velocity、Fastjson、JJWT、TransmittableThreadLocal、MinIO、POI、commons-io/fileupload/collections、Kaptcha 等第三方依赖的版本。

### 3.2 自研模块版本继承

所有 `com.ruoyi:*` 自研模块（`ruoyi-common-*`、`ruoyi-api-system`）在根 POM 的 `dependencyManagement` 中统一以 `${ruoyi.version}` 声明，子模块引用时不写版本号，由父 POM 统一管理。

### 3.3 子模块仅声明依赖坐标

典型子模块（如 `ruoyi-auth/pom.xml`）只写 `<groupId>/<artifactId>`，不写 `<version>`，全部从父 POM 继承。这保证了同一仓库内所有服务对同一组第三方库版本的强一致性。

### 3.4 冲突解决策略：exclusions

`ruoyi-modules/ruoyi-dqservice/pom.xml` 中对 `hadoop-client:2.7.1`、`hive-metastore:2.2.0`、`hive-jdbc:2.2.0` 等重型依赖大量使用 `<exclusions>` 剔除 `servlet-api`、`slf4j-log4j12`、`log4j`、`guava`、`curator-client`、`zookeeper`、`hadoop-common/hdfs/mapreduce/yarn-*` 等传递依赖，以避免与 Spring Boot / Spring Cloud 生态产生版本冲突。

### 3.5 插件版本锁定

根 POM 的 `<pluginManagement>` 将 `spring-boot-maven-plugin` 固定为 `${spring-boot.version}`，并在注释中说明“避免 Maven 解析到要求 JDK 17+ 的新版本”，从而保证 JDK 1.8 环境下的构建稳定性。

### 3.6 前端依赖管理

`ruoyi-ui/package.json` 使用 npm 脚本（`dev`、`build:prod`、`build:stage`、`preview`、`lint`），依赖包括 Vue 2.6.12、Element UI 2.15.8、Axios 0.24.0、ECharts 4.9.0、Vuex 3.6.0、Vue Router 3.4.9 等，并声明 `engines.node >= 8.9, npm >= 3.0.0`。未检出 `yarn.lock` 或 `pnpm-lock.yaml`，也没有 `.npmrc` 自定义 registry。

## 4. 约定与约束

- **版本单一来源**：所有第三方库版本集中在根 `pom.xml` 的 `<properties>` 与 `<dependencyManagement>` 中，子模块不得硬编码第三方依赖版本（除个别业务模块自行声明的 `drools`、`hive-*`、`hadoop-client`、`lombok` 等例外）。
- **BOM 优先**：Spring Cloud、Spring Cloud Alibaba、Spring Boot 均通过 import BOM 引入，子模块无需指定这些生态内的依赖版本。
- **自研模块版本统一**：`com.ruoyi:*` 模块统一使用 `${ruoyi.version}`（当前 3.5.0）。
- **JDK 约束**：根 POM 强制 `source/target=1.8`，且显式锁定 `spring-boot-maven-plugin` 版本以避免 JDK 17+ 要求。
- **无私有仓库配置**：整个仓库未在任意 `pom.xml` 中发现 `<repositories>`、`<mirror>` 或 `<pluginRepositories>` 段，依赖拉取走 Maven 默认中央仓库。
- **无依赖锁定机制**：没有 Maven dependency lock、`dependency-graph` 校验或 CI 中的 `mvn dependency:tree` 检查脚本可见于仓库。
- **前端无锁文件**：`ruoyi-ui/package.json` 存在但未见 `yarn.lock`/`pnpm-lock.yaml`，依赖安装结果取决于 npm 缓存与网络可达性。
- **部分模块独立包装器**：`ruoyi-modules/ruoyi-dqservice/` 自带 `.mvn/wrapper/` 与 `mvnw`，其余模块依赖全局 Maven。
- **Docker 镜像复用已构建 Jar**：`docker/ruoyi/*/jar/` 下存放各微服务的 Jar 包，由 `docker/deploy.sh` 与 `docker-compose.yml` 编排启动，Jar 本身是 Maven 构建产物而非运行时依赖管理器。