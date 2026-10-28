---
kind: external_dependency
name: RuoYi-Cloud 3.5.0 微服务脚手架
slug: ruoyi-cloud
category: external_dependency
category_hints:
    - framework_behavior
scope:
    - '**'
source_files:
    - pom.xml
    - docker/docker-compose.yml
    - sql/ry_config_20220424.sql
    - bin/start-backend.ps1
---

### ...
- 中间件通过 docker-compose.yml 编排 MySQL、Redis、Nacos；业务服务通过 bootstrap.yml 从 Nacos 拉取 application-dev.yml、ruoyi-gateway-dev.yml 等配置。
- 前端有两个入口：仓库自带的 `ruoyi-ui`（原始若依前端，代理 8080）和独立业务前端 `dataquality-ui-main`（Vue 2.6 + Element UI，代理 8089，包含 rules/dataAssets 页面）。注意不要把两者混淆。
- 构建使用 Maven 多模块，Java 版本要求 JDK 8；Qoder IDE 的 Java 扩展需要额外 JDK 17 作为语言服务器引擎，与项目运行 JDK 8 并存。