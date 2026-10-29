# dataquality

基于若依微服务（RuoYi-Cloud 3.5.0）扩展的数据质量检测系统后端，在平台基础服务之上增加 `ruoyi-dqservice` 质控模块。

- 运行时：Java 8 / Spring Boot 2.6.7 / Spring Cloud Alibaba 2021.0.1.0 / Nacos 2.0.4
- 业务前端：同目录仓库 `dataquality-ui-main`（`ruoyi-ui/` 为若依自带前端）

## 仓库结构

| 目录 | 说明 |
| --- | --- |
| `ruoyi-gateway` | 统一入口（8089），校验码、令牌校验与路由转发 |
| `ruoyi-auth` | 认证服务（9200） |
| `ruoyi-modules/ruoyi-system` | 系统管理（9201） |
| `ruoyi-modules/ruoyi-dqservice` | 质控模块（9205）：数据源、规则、执行、报告、质控任务 |
| `ruoyi-modules/ruoyi-{file,gen,job}` | 若依自带文件、代码生成、定时任务模块 |
| `ruoyi-visual/ruoyi-monitor` | 若依监控模块 |
| `sql/` | 平台库与质控模块初始化脚本 |
| `docker/cloud/` | 本地独立演示环境（1 Gateway + 2 dqservice）的 Compose 编排 |
| `bin/` | 本地开发与演示启动脚本 |
| `docs/` | 系统说明、后续工作清单与验收记录 |

## 本地开发环境

沿用本机已有基础设施（Nacos 8848、MySQL 3306、Redis 6379，均为独立容器），不随仓库启动：

```powershell
powershell -ExecutionPolicy Bypass -File .\bin\start-backend.ps1            # 启动 auth/system/dqservice/gateway
powershell -ExecutionPolicy Bypass -File .\bin\start-backend.ps1 -Stop      # 停止
powershell -ExecutionPolicy Bypass -File .\bin\start-ui.ps1 -Port 8081      # 启动业务前端
```

## 本地独立演示环境：1 Gateway + 2 dqservice

用 Docker Compose 在本机复现「1 个网关 + 2 个质控服务副本」的部署形态，用于演示请求分流、单实例故障后继续服务、实例恢复后重新加入。**这不是云端部署**，边界见下文。

### 与开发环境的隔离

| 项目 | 开发环境 | 演示环境 |
| --- | --- | --- |
| Docker 项目 / 容器名 | 现有 `ruoyi-*` 容器 | `dq-demo`，容器前缀 `dq-demo-` |
| Web 入口 | 8081（前端）+ 8089（网关） | 仅 `127.0.0.1:18080`（容器内 nginx） |
| 数据库 / 缓存 / 注册中心 | `ruoyi-mysql` 3306、`ruoyi-redis` 6379、`ruoyi-nacos` 8848 | 容器内独立 MySQL、Redis；Nacos 用内置 derby，不对外暴露端口 |
| 数据卷 | 无 | `dq-demo-*` 独立卷，`docker compose down -v` 可整体清除 |
| 演示业务库 | 本机参考库（3307 等） | 独立网络 `172.30.51.0/24` 内的 `172.30.51.10:3306`，只读账号 `dq_reader` |
| 构建产物 | `target/`（被运行中的进程占用） | `docker/cloud/build/`，不覆盖 `target/` |

### 前置条件

Docker Desktop（Compose v2+）、JDK 8、Maven、Node（前端 `build:prod` 需 `--openssl-legacy-provider`，脚本已处理）。

### 构建

```powershell
powershell -ExecutionPolicy Bypass -File .\bin\build-cloud.ps1
```

依次执行：复制源码到 `docker/cloud/build/src`（排除 `target` 与运行时目录）→ 打包 gateway/auth/system/dqservice 四个 JAR → 前端 `build:prod` 并收集 `dist` → 生成 `docker/cloud/.env`（随机 Redis 口令、AES 密钥，已被 `.gitignore` 忽略）→ `docker compose build`。

可选参数：`-SkipBackend`、`-SkipFrontend`、`-SkipImages`。

### 启动、停止、登录

```powershell
cd docker/cloud
docker compose up -d          # 启动 10 个容器
docker compose ps             # 全部应为 healthy；config-publisher 为 Exited(0) 属正常
docker compose down           # 停止，保留数据卷
docker compose down -v        # 停止并清空演示数据
```

浏览器访问 `http://127.0.0.1:18080`，账号 `admin / admin123`，**验证码保持开启**。

### 演示脚本

```powershell
powershell -ExecutionPolicy Bypass -File .\bin\demo-ha.ps1 -Action status
powershell -ExecutionPolicy Bypass -File .\bin\demo-ha.ps1 -Action login      # 走真实登录接口，令牌写入 docker/cloud/build/token.txt
powershell -ExecutionPolicy Bypass -File .\bin\demo-ha.ps1 -Action seed       # 创建演示数据源与规则（幂等）
powershell -ExecutionPolicy Bypass -File .\bin\demo-ha.ps1 -Action execute -RuleId 1
powershell -ExecutionPolicy Bypass -File .\bin\demo-ha.ps1 -Action probe -Count 20
powershell -ExecutionPolicy Bypass -File .\bin\demo-ha.ps1 -Action failover   # 分流 → 停 A → 恢复 A → 再分流
powershell -ExecutionPolicy Bypass -File .\bin\demo-ha.ps1 -Action failover -HardKill
```

`login` 会从演示 Redis 读取本次验证码答案，仅为自动化验证；验证码本身未关闭。演示只用已鉴权的只读接口 `GET /prod-api/dqservice/rule/list`，通过响应头 `X-DQ-Instance`（取值 `dq-a` / `dq-b`）判断本次请求由哪个副本处理。

### 实测记录（2026-10-30，本机）

| 场景 | 结果 |
| --- | --- |
| 正常运行 20 次只读请求 | 成功 20，`dq-a` 10 次 / `dq-b` 10 次 |
| 优雅停止 dq-a | 首个 dq-b 成功响应 0 秒；连续 10 次成功 4.8 秒达成；失败 0 次 |
| 强制终止（`-HardKill`）dq-a | 首个 dq-b 成功响应 0.1 秒；连续 10 次成功 4.9 秒达成；失败 0 次 |
| 恢复 dq-a | 优雅停止后 10.2 秒、强杀后 11.5 秒首次重新承接请求（含 JVM 启动与 Nacos 注册） |
| 恢复后再 20 次请求 | 成功 20，`dq-a` 10 次 / `dq-b` 10 次 |
| 规则执行 | `totalRows=10`、`violationRows=4`、`passRate=60.0`，与演示库中 4 行空年龄一致 |
| 跨实例读取 | dq-a 执行写入的报告，由 dq-b 在 `report/list` 中读取到 |

### 一个容易踩的坑：Nacos 注册地址

dqservice 同时接入 `platform` 与 `source` 两个网络，Nacos 客户端自动选择本机 IP 不稳定，实测曾把 dq-a 注册成 source 网段的 `172.30.51.3`，网关无法路由，一半请求 15 秒超时。现在 compose 已为两个副本固定 platform 网段 IP（`172.31.60.11` / `172.31.60.12`）并通过 `SPRING_CLOUD_NACOS_DISCOVERY_IP` 显式声明注册地址。云端采用多网卡时同样需要显式指定，不要依赖自动探测。

### 明确边界

1. **在途检测不会自动续跑。** 检测在请求线程内一次执行完成，承载实例被停止时该请求直接失败或结果未知，不会由另一副本接手；报告是否已落库需到「质量报告」回查后再决定是否重跑。写接口未配置自动重试（nginx 也未配置 `proxy_next_upstream`），避免重复落库。
2. **切换延迟取决于实例如何退出。** 优雅停止时服务先向 Nacos 注销，命令返回时流量已切走；进程被强杀时 Nacos 2.x 因 gRPC 连接断开立即注销实例，实测切换同样接近 0。只有在连接未断的场景（如网络黑洞、容器被 `pause`）才需要等约 15 秒心跳超时摘除，这段时间的请求会失败。
3. **只覆盖 dqservice 进程级故障。** gateway、MySQL、Redis、Nacos 与宿主机仍是单点，不能据此宣称整套系统高可用。
4. **演示配置关闭了定时调度**（`dq.task.scheduler.enabled=false`），避免两个副本重复触发 Cron；因此演示环境不演示定时任务。
5. 未做：真实云服务器部署、多网关、数据库主从、TLS 终止、监控告警与压测。

### 与云端部署计划的偏差

`docs/` 下的云部署准备只完成了「本地可验证的部署材料」部分：Compose、Dockerfile、nginx 配置、配置发布脚本、演示脚本均已就绪；真实云端部署、域名与 HTTPS、Nacos 集群、MySQL 高可用等仍未实施，工作清单第 8 项保持未勾选。
