---
kind: error_handling
name: RuoYi-Cloud 统一异常体系与全局错误处理
category: error_handling
scope:
    - '**'
source_files:
    - ruoyi-common/ruoyi-common-core/src/main/java/com/ruoyi/common/core/exception/base/BaseException.java
    - ruoyi-common/ruoyi-common-core/src/main/java/com/ruoyi/common/core/exception/ServiceException.java
    - ruoyi-common/ruoyi-common-security/src/main/java/com/ruoyi/common/security/handler/GlobalExceptionHandler.java
    - ruoyi-gateway/src/main/java/com/ruoyi/gateway/handler/GatewayExceptionHandler.java
    - ruoyi-gateway/src/main/java/com/ruoyi/gateway/handler/SentinelFallbackHandler.java
    - ruoyi-common/ruoyi-common-core/src/main/java/com/ruoyi/common/core/web/domain/AjaxResult.java
    - ruoyi-common/ruoyi-common-core/src/main/java/com/ruoyi/common/core/web/controller/BaseController.java
    - ruoyi-common/ruoyi-common-core/src/main/java/com/ruoyi/common/core/utils/ExceptionUtil.java
---

## 1. 采用的方案

RuoYi-Cloud 在后端采用「自定义业务异常类 + Spring `@RestControllerAdvice` 全局异常处理器 + 网关层 `ErrorWebExceptionHandler`」的分层错误处理架构：

- 业务层（各 Spring Boot 微服务）通过 `ruoyi-common-security` 提供的 `GlobalExceptionHandler` 将异常统一转换为 `AjaxResult`。
- 网关层（Spring Cloud Gateway，响应式）通过实现 `ErrorWebExceptionHandler` 的 `GatewayExceptionHandler` 以及 Sentinel 的 `SentinelFallbackHandler` 统一处理路由/限流异常。
- 前端 Vue 侧通过 `src/utils/errorCode.js`、`src/utils/request.js` 解析后端返回的 `code/msg/data` 结构并弹出提示或跳转。

## 2. 核心文件与包

- 异常类型定义：`ruoyi-common/ruoyi-common-core/src/main/java/com/ruoyi/common/core/exception/`
  - 根基类：`base/BaseException`（带 `module/code/args/defaultMessage` 字段）
  - 通用业务异常：`ServiceException`（带 `Integer code / String message / detailMessage`）
  - 认证相关：`auth/NotLoginException`、`auth/NotPermissionException`、`auth/NotRoleException`
  - 用户域：`user/UserException`、`user/CaptchaExpireException`、`user/UserPasswordNotMatchException`
  - 文件域：`file/FileException`、`file/FileNameLengthLimitExceededException`、`file/FileSizeLimitExceededException`、`file/InvalidExtensionException`（含静态内部类 `InvalidImageExtensionException` 等）
  - 其他领域：`CaptchaException`、`CheckedException`、`DemoModeException`、`GlobalException`、`InnerAuthException`、`PreAuthorizeException`、`UtilException`、`job/TaskException`
- 全局异常处理器：`ruoyi-common/ruoyi-common-security/src/main/java/com/ruoyi/common/security/handler/GlobalExceptionHandler.java`
- 网关异常处理器：`ruoyi-gateway/src/main/java/com/ruoyi/gateway/handler/GatewayExceptionHandler.java`、`SentinelFallbackHandler.java`
- 统一响应体：`ruoyi-common/ruoyi-common-core/src/main/java/com/ruoyi/common/core/web/domain/AjaxResult.java`（继承 `HashMap<String,Object>`，常量 `CODE_TAG="code"`、`MSG_TAG="msg"`、`DATA_TAG="data"`）
- HTTP 状态码常量：`com.ruoyi.common.core.constant.HttpStatus`（被 `BaseController`、`AjaxResult` 引用）
- 工具类：`ruoyi-common/ruoyi-common-core/src/main/java/com/ruoyi/common/core/utils/ExceptionUtil.java`（封装堆栈字符串与根异常提取）

## 3. 架构与约定

### 3.1 异常分类层次

```text
RuntimeException
├── BaseException (module/code/args/defaultMessage)
│   ├── UserException
│   │   ├── CaptchaExpireException
│   │   └── UserPasswordNotMatchException
│   └── FileException
│       ├── FileNameLengthLimitExceededException
│       ├── FileSizeLimitExceededException
│       └── InvalidExtensionException
│           ├── InvalidImageExtensionException
│           ├── InvalidFlashExtensionException
│           ├── InvalidMediaExtensionException
│           └── InvalidVideoExtensionException
├── ServiceException (code/message/detailMessage) — 独立于 BaseException
├── CaptchaException / CheckedException / DemoModeException / GlobalException / InnerAuthException / PreAuthorizeException / UtilException
└── job/TaskException extends Exception (受检异常)
```

业务代码通常直接抛 `ServiceException`（携带业务码），或在需要模块/参数化消息时抛 `BaseException` 子类。`TaskException` 是唯一显式声明为受检异常的领域类型。

### 3.2 控制器层统一响应

所有 Controller 继承 `BaseController`，通过 `success()` / `error()` / `toAjax(boolean)` / `getDataTable(...)` 等方法返回 `AjaxResult`，保证成功/失败响应结构一致（`code`、`msg`、`data`）。分页数据通过 `TableDataInfo` 包装，也使用 `HttpStatus.SUCCESS` 作为默认状态码。

### 3.3 全局异常处理器（Servlet 容器）

`GlobalExceptionHandler` 使用 `@RestControllerAdvice` 集中捕获以下异常并按优先级映射为 `AjaxResult`：

| 异常类型 | 处理方式 |
|---|---|
| `NotPermissionException` | 403，日志记录请求 URI 与消息 |
| `NotRoleException` | 同上 |
| `HttpRequestMethodNotSupportedException` | 返回 `e.getMessage()` |
| `ServiceException` | 优先使用 `e.getCode()`，否则用默认错误码 |
| `BindException` / `MethodArgumentNotValidException` | 取第一个校验错误消息 |
| `InnerAuthException` | 透传消息 |
| `DemoModeException` | 固定消息 "演示模式，不允许操作" |
| `RuntimeException` | 兜底，记录未知异常日志 |
| `Exception` | 最外层兜底，记录系统异常日志 |

每个 handler 均通过 SLF4J 记录请求 URI 和异常信息，再返回 `AjaxResult.error(...)`。

### 3.4 网关层异常处理（响应式）

`GatewayExceptionHandler` 实现 `ErrorWebExceptionHandler`，按 `@Order(-1)` 最高优先级拦截：

- `NotFoundException` → "服务未找到"
- `ResponseStatusException` → 透传其 message
- 其他 → "内部服务器错误"

并通过 `ServletUtils.webFluxResponseWriter` 写回响应。

`SentinelFallbackHandler` 实现 `WebExceptionHandler`，仅对 `BlockException.isBlockException(ex)` 生效，返回 "请求超过最大数，请稍候再试"；非限流异常直接 `Mono.error(ex)` 交给后续处理器。

### 3.5 前端错误处理

前端通过 `src/utils/request.js` 统一拦截 HTTP 响应，根据后端 `code` 判断是否成功；`src/utils/errorCode.js` 维护前端本地错误码映射，用于展示友好提示。

## 4. 约定与约束

- **业务异常统一抛出 `ServiceException`**：该类的注释明确其为「业务异常」，且 `GlobalExceptionHandler` 对其有专门的 `@ExceptionHandler` 分支，支持可选的 `Integer code` 覆盖默认错误码。
- **模块级可诊断异常使用 `BaseException` 及其子类**：`BaseException` 提供 `module`、`code`、`args`、`defaultMessage` 四个字段，适合需要定位来源模块与参数化消息的场景。
- **所有 Web 层返回值统一为 `AjaxResult`**：由 `BaseController` 暴露 `success()/error()` 方法，禁止 Controller 直接返回裸对象或 Map。
- **网关层不向上抛出原始异常**：`GatewayExceptionHandler` 在 response 已 committed 时直接 `Mono.error(ex)`，否则一律转为中文提示文本，避免下游服务堆栈泄露到客户端。
- **限流异常与普通异常分离**：`SentinelFallbackHandler` 通过 `BlockException.isBlockException(ex)` 判断，非限流异常直接上抛，确保限流策略与其他异常处理互不干扰。
- **校验异常统一走 Bean Validation**：`BindException` 与 `MethodArgumentNotValidException` 分别处理表单绑定与 `@Valid` 注解校验，取第一条错误消息返回。
- **演示模式不可变**：`DemoModeException` 被 `GlobalExceptionHandler` 固定返回 "演示模式，不允许操作"，不允许业务代码自行改写提示信息。
- **受检异常极少**：除 `job/TaskException extends Exception` 外，其余异常均为 `RuntimeException` 派生，调用方无需强制 try-catch，符合 RuoYi 框架一贯风格。
- **异常日志统一格式**：每个 `@ExceptionHandler` 都先 `log.error(...)` 记录请求 URI 与异常详情，再返回 `AjaxResult`，便于运维排查。

## 5. 观察到的不足

- `GlobalExceptionHandler` 中 `RuntimeException` 与 `Exception` 两个兜底分支几乎相同，仅在日志文案上略有差异，存在重复逻辑。
- `ServiceException` 同时拥有 `message` 与 `detailMessage` 两个字段，但 `handleServiceException` 只使用 `message`，`detailMessage` 未被消费，属于未使用的冗余字段。
- 部分异常类（如 `CaptchaException`、`CheckedException`、`GlobalException`、`UtilException`）仅有空构造函数，未在 `GlobalExceptionHandler` 中被显式捕获，依赖 `RuntimeException`/`Exception` 兜底，语义不够精确。
- 前端 `errorCode.js` 与后端 `HttpStatus` 的状态码之间没有编译期关联，容易漂移。

总体而言，RuoYi-Cloud 的错误处理以 `ServiceException` + `BaseException` 为异常模型，以 `GlobalExceptionHandler` 为 Servlet 容器的统一出口，以 `GatewayExceptionHandler` + `SentinelFallbackHandler` 为网关层的统一出口，形成前后端一致的 `code/msg/data` 契约。