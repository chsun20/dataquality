---
kind: external_dependency
name: Nacos 2.1.0 注册与配置中心
slug: nacos
category: external_dependency
category_hints:
    - migration_status
    - client_constraint
scope:
    - '**'
source_files:
    - docker/docker-compose.yml
    - sql/ry_config_20220424.sql
---

### ...
- 首次启动需先起 MySQL+Redis，再起 Nacos，否则 config_info/config_data 表不存在会失败。
- 业务配置通过 Nacos Data ID 管理，包括 application-dev.yml、ruoyi-gateway-dev.yml、ruoyi-dqservice-dev.yml 等；dqservice 的配置是补齐 dqservice 的关键阻塞项。