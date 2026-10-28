---
kind: external_dependency
name: Drools 7.5.0 规则引擎
slug: drools
category: external_dependency
category_hints:
    - sdk_real_api
    - client_constraint
scope:
    - '**'
source_files:
    - ruoyi-modules/ruoyi-dqservice/src/main/java/com/ruoyi/dqservice/connecter/DroolsManager.java
    - ruoyi-modules/ruoyi-dqservice/src/test/java/com/sch/test/TestDroolsLocal.java
---

### ...
- 只有学习用测试类 `TestDrools` / `DroolsApplicationWorkBenchTests` 才连接远程 Drools Workbench（jboss/drools-workbench-showcase，localhost:8080/drools-wb/maven2），该容器不是系统架构的一部分。
- 当前规则的 then 分支为空，未产出违规记录；后续质量评估报告需在此处收集 QcViolation 并落库。