---
kind: logging_system
name: 基于 Logback + AOP 注解的分布式操作日志系统
category: logging_system
scope:
    - '**'
source_files:
    - ruoyi-common/ruoyi-common-log/src/main/java/com/ruoyi/common/log/annotation/Log.java
    - ruoyi-common/ruoyi-common-log/src/main/java/com/ruoyi/common/log/aspect/LogAspect.java
    - ruoyi-common/ruoyi-common-log/src/main/java/com/ruoyi/common/log/enums/BusinessStatus.java
    - ruoyi-common/ruoyi-common-log/src/main/java/com/ruoyi/common/log/enums/BusinessType.java
    - ruoyi-common/ruoyi-common-log/src/main/java/com/ruoyi/common/log/enums/OperatorType.java
    - ruoyi-common/ruoyi-common-log/src/main/java/com/ruoyi/common/log/service/AsyncLogService.java
    - ruoyi-auth/src/main/resources/logback.xml
    - ruoyi-gateway/src/main/resources/logback.xml
    - ruoyi-modules/ruoyi-system/src/main/resources/logback.xml
    - ruoyi-modules/ruoyi-dqservice/src/main/resources/logback.xml
---

## 1. 框架与总体方案

本仓库采用 **Logback**（`ch.qos.logback`）作为底层日志实现，配合 **SLF4J**（`org.slf4j.Logger`）作为门面。每个 Spring Boot 子模块在 `src/main/resources/logback.xml` 中独立声明自己的 Appender、RollingPolicy 与 Logger 级别。

业务操作日志通过自研的 `ruoyi-common-log` 模块提供：以 `@Log` 注解 + AspectJ AOP 切面拦截 Controller 方法，将结构化操作记录异步写入远端 system 模块的 `sys_oper_log` 表；同时应用自身的运行时日志输出到本地文件。

## 2. 关键文件与包

- `ruoyi-common/ruoyi-common-log/src/main/java/com/ruoyi/common/log/annotation/Log.java` — 自定义操作日志注解，定义 `title`、`businessType`、`operatorType`、`isSaveRequestData`、`isSaveResponseData` 五个属性。
- `ruoyi-common/ruoyi-common-log/src/main/java/com/ruoyi/common/log/aspect/LogAspect.java` — AOP 切面，使用 `@AfterReturning` 和 `@AfterThrowing` 捕获成功/异常结果，组装 `SysOperLog` 并调用 `AsyncLogService.saveSysLog`。
- `ruoyi-common/ruoyi-common-log/src/main/java/com/ruoyi/common/log/enums/BusinessStatus.java` — 操作状态枚举（`SUCCESS` / `FAIL`）。
- `ruoyi-common/ruoyi-common-log/src/main/java/com/ruoyi/common/log/enums/BusinessType.java` — 业务类型枚举（`OTHER`/`INSERT`/`UPDATE`/`DELETE`/`GRANT`/`EXPORT`/`IMPORT`/`FORCE`/`GENCODE`/`CLEAN`）。
- `ruoyi-common/ruoyi-common-log/src/main/java/com/ruoyi/common/log/enums/OperatorType.java` — 操作人类别枚举（`OTHER`/`MANAGE`/`MOBILE`）。
- `ruoyi-common/ruoyi-common-log/src/main/java/com/ruoyi/common/log/service/AsyncLogService.java` — 使用 `@Async` 异步调用 `RemoteLogService.saveLog`，通过 OpenFeign 跨服务落库。
- 各模块 `logback.xml`：`ruoyi-auth`、`ruoyi-gateway`、`ruoyi-modules/ruoyi-system`、`ruoyi-modules/ruoyi-dqservice` 等。

## 3. 架构与约定

### 3.1 运行时日志（Logback）

每个微服务的 `logback.xml` 结构完全一致，仅 `log.path` 不同：

- 路径变量：`logs/ruoyi-{module}`（如 `logs/ruoyi-auth`、`logs/ruoyi-gateway`、`logs/ruoyi-system`、`logs/ruoyi-dqservice`）。
- 输出格式：`%d{HH:mm:ss.SSS} [%thread] %-5level %logger{20} - [%method,%line] - %msg%n`。
- Appender：
  - `console` → `ConsoleAppender`
  - `file_info` → `RollingFileAppender`，按日滚动 `info.%d{yyyy-MM-dd}.log`，保留 60 天
  - `file_error` → `RollingFileAppender`，按日滚动 `error.%d{yyyy-MM-dd}.log`，保留 60 天
- Filter：两个文件 Appender 分别用 `LevelFilter` 精确匹配 `INFO` 与 `ERROR`。
- Logger 级别：`com.ruoyi` 为 `info`，`org.springframework` 为 `warn`。
- Root：控制台 + info/error 两个文件 Appender。

根目录 `logs/` 下还可见进程化启动脚本产出的 `auth.out.log`、`auth.err.log`、`gateway.out.log`、`system.out.log`、`dqservice.out.log` 等扁平文件，由外部脚本重定向产生，与 Logback 配置无关。

### 3.2 业务操作日志（AOP + 远程落库）

- 开发者在 Controller 方法上标注 `@Log(title = "…", businessType = BusinessType.XXX)`。
- `LogAspect` 通过 `JoinPoint` 提取类名+方法名、HTTP 方法、URI、IP（`IpUtils.getIpAddr`）、用户名（`SecurityUtils.getUsername`），并根据注解填充 `title`、`businessType`、`operatorType`。
- 请求参数仅在 `POST`/`PUT` 时序列化（FastJSON），截断至 2000 字符；响应体同样截断 2000 字符。
- `MultipartFile`、`HttpServletRequest`、`HttpServletResponse`、`BindingResult` 被过滤掉，不进入日志。
- 最终通过 `AsyncLogService.saveSysLog` 异步调用 `RemoteLogService.saveLog(sysOperLog, SecurityConstants.INNER)`，把 `SysOperLog` 持久化到 system 模块的数据库表。

该模式已在多个业务模块中被广泛使用，例如 `ruoyi-dqservice` 的 `DataSourceController`、`RuleController`、`RuleExecuteController`，以及 `ruoyi-gen`、`ruoyi-job` 下的各类 Controller。

## 4. 约定与约束

- **日志框架统一为 Logback + SLF4J**：所有模块的 `logback.xml` 均使用 `ch.qos.logback.core.RollingFileAppender` + `TimeBasedRollingPolicy`，未引入其他日志实现。
- **每个服务一份独立的 logback.xml**：日志路径按服务名隔离（`logs/ruoyi-{module}`），没有共享的父级配置文件。
- **日志滚动策略固定**：按天滚动，文件名后缀 `.log`，历史保留 60 天，信息/错误分文件。
- **日志级别策略固定**：`com.ruoyi` 包默认 `info`，Spring 框架包默认 `warn`。
- **操作日志必须通过 `@Log` 注解声明**：`LogAspect` 只拦截带 `@Log` 的方法；业务方法若需记录审计日志，必须在 Controller 层加注解，而非在 Service 层直接写日志。
- **操作日志字段来源有明确约定**：
  - `operName` 来自 `SecurityUtils.getUsername()`；
  - `operIp` 来自 `IpUtils.getIpAddr(request)`；
  - `requestMethod` 来自 `ServletUtils.getRequest().getMethod()`；
  - `operParam` 仅对 `POST`/`PUT` 序列化入参；
  - `jsonResult` 仅当 `isSaveResponseData=true` 且返回值非空时保存。
- **参数与响应体长度上限为 2000 字符**：`StringUtils.substring(... , 0, 2000)` 在 `errorMsg`、`operParam`、`jsonResult` 三处统一截断。
- **操作日志落库是异步的**：`AsyncLogService.saveSysLog` 使用 `@Async`，避免切面同步阻塞主流程。
- **远程调用走内部通道**：`saveSysLog` 始终传入 `SecurityConstants.INNER`，表明这是服务间内部调用。
- **前端无独立日志子系统**：前端 `ruoyi-ui` 未包含日志框架或日志收集代码，日志集中在后端微服务。