---
title: 后端 Agent 指南
read_when:
  - 修改 backend/drone-backend 的代码、测试或构建配置
  - 判断接口契约、持久化、鉴权或 WebSocket 的当前约定
  - 运行后端门禁或排查 Windows 构建问题
---

# 后端 Agent 指南

本文件是 `backend/` 的唯一指令真源；`CLAUDE.md` 只是 `@AGENTS.md` 的导入 shim。跨端流程、需求/ADR/任务规则见 `../docs/AGENTS.md`。

## 技术栈与命令

- Java 21、Spring Boot 4.0.3、Maven Wrapper 3.9.12，源码在 `drone-backend/`，基础包 `com.uav`。
- 本机 Windows 必须用 `.cmd` 包装器：

```bash
cd /d/2024_code/drone-project/backend
sh tools/hooks/mvn21.sh -B test          # 单元层（Surefire，含 ArchUnit）
sh tools/hooks/mvn21.sh -B clean verify  # 单元 + IT/E2E + OpenAPI 契约 + CPD + JaCoCo
```

不要使用 PATH 上的 `mvn`（3.6.1 / Java 17），也不要依赖 `sh mvnw`（本机 `D:\Java\JDK\bin\java` 是 0 字节文件）。

## 包结构（`com.uav`）

`server`（JWT、异常、`Result<T>`、限流、WebSocket 路由、通用工具）、`admin`、`user`、`rider`、`task`、`order`、`uav`、`pay`、`upload`、`attachment`、`live`、`chat`、`log`。领域包内按 `controller/`、`service/`、`service/impl/`、`pojo/{dto,vo,entity,enums}`、`mapper/` 或 `repository/` 分层。

## 当前事实（不要沿用旧说法）

- **持久化**：全部领域实体（含 `chat`）使用 Spring Data JPA；没有 MyBatis-Plus 依赖。
- **Schema**：Flyway 是 DDL 单一真源（`src/main/resources/db/migration/`），`application.yml` 使用 `ddl-auto: validate`；未跟踪的 `application-dev.yml` 只在本地把 `ddl-auto` 设为 `update`，不得提交。
- **测试规模**：当前 10 个 `*Test`（纯单元）、35 个 `*IT`、8 个 `*E2EIT`；不存在“只有 contextLoads”的说法。
- **注入方式**：历史文件仍有一部分 `@Autowired` 字段注入（ArchUnit 冻结债务）；新代码必须用构造器注入（或 `@RequiredArgsConstructor`）。

## 约定

- 所有 HTTP 接口返回 `Result<T>`；异常由 `GlobalExceptionHandler` 统一映射。
- 鉴权：`JwtInterceptor` + `@SkipJwt` / `@RequireRole` / `@RequireDrone` / `@SkipDroneCheck`；请求上下文用 `UserContext`。
- 限流：`@RateLimiter` + `RateLimiterAspect`。
- WebSocket：`/ws/drone`（设备）、`/ws/web`（监管）、`/ws/{sid}`（IM）；消息按 `type` + `name` 路由，协议真源在 `spec/ws/`。
- 新代码单个类不超过 400 行；超过 800 行禁止。存量文件由 `tools/file-size-baseline.json` 冻结。

## 测试规则

- 纯单元测试命名为 `*Test`，不得加 `@SpringBootTest`（Surefire）。
- 需要 Spring 上下文的测试命名为 `*IT` 或 `*E2EIT`（Failsafe）；真实端口必须显式 `webEnvironment = RANDOM_PORT`。
- CI 断言实际执行数：`Tests run: 0` 或存在 skipped 即失败。
- `UserContext.clear()` 只能在 arrange 阶段或会话关闭时调用；禁止在 `@AfterEach` 里清理（由 `tools/check-thread-context-cleanup.sh` 校验）。
- 完整规则见 `../docs/quality/backend-test-strategy.md`。

## 契约

- OpenAPI SSOT：`spec/openapi/drone-backend.openapi.json`；只能通过 `bash tools/openapi-export.sh` 重新导出，导出后评审 diff。
- `bash tools/openapi-export.sh --check` 校验冻结规格与运行中实现一致；`OpenApiContractGateE2EIT` 兜底。
- 改变契约形状会让下游 frontend/app/djifly 的 sha 门禁失败；必须按 `../docs/agents/skills/contract-change.md` 执行跨仓库同步。
- WebSocket 协议：`spec/ws/` 为真源（manifest、schema、示例、`SHA256SUMS`），`WsMessageNames` 是 Java 常量唯一来源，`WsProtocolContractIT` 校验 handler 与清单一致；不得只改 Java 常量而不改清单。

## 质量门禁

- ArchUnit：`src/test/java/com/uav/architecture/ArchitectureTest.java`，冻结存量违例；新增违例必须失败。
- 覆盖率：`tools/check_coverage.py` 读取 JaCoCo 报告并与 `tools/coverage-baseline.json` 比较。
- 重复代码：`maven-pmd-plugin` CPD，基线在 `tools/cpd-baseline.txt`。
- 文件大小：`tools/check_file_size.py` + `tools/file-size-baseline.json`（存量不得增长，新文件 ≤400 行，硬上限 800）。
- 测试分层与 ThreadLocal 门禁：`tools/check-test-layers.sh`、`tools/check-thread-context-cleanup.sh`。

## 密钥与边界

- 不读取、不打印、不提交 `application-dev.yml`、`.env*`、`*.key`、`*.pem`；发现疑似密钥立即停止并报告。
- 本轮只允许修改 `backend/` 和 `docs/`；`frontend/`、`app/`、`djifly/` 只读。
- 不擅自 `git commit` / `git push`；用户明确说“提交”后才本地提交，push 前再次确认。

## 同步门禁

开工前先在工作区根执行 `python docs/tools/gate.py sync --all`。未同步时 `git` 包装器会拒绝读取命令并提示先同步；详细命令见 `../docs/agents/commands.md`。
