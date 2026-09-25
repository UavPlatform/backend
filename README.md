# backend

Web backend code for the drone platform.

## 测试分层与执行层

测试约定见 [docs/quality/backend-test-strategy.md](../docs/quality/backend-test-strategy.md)（§6 命名、§7 执行、§9 验证）。
按类名分层：`*Test` = 单元层（Surefire，不启动 Spring）；`*IT`/`*E2EIT` = 集成/真实协议层（Failsafe，启动 Spring）。

| 目的 | 命令（backend 仓根） |
|---|---|
| 跑单元层（仅 `*Test`，不启动 Spring） | `cd drone-backend && sh mvnw -B test` |
| 跑全量测试（`*Test` + `*IT`/`*E2EIT`，含契约门禁） | `cd drone-backend && sh mvnw -B verify` |
| 检查测试分层与执行层命名（只读） | `bash tools/check-test-layers.sh`（自证：`bash tools/check-test-layers.sh --selftest`） |

## OpenAPI 契约（结构单一事实源）

结构事实源：`spec/openapi/drone-backend.openapi.json`（归一化后入库，消费端据此生成类型）。

| 目的 | 命令（backend 仓根） |
|---|---|
| 导出契约（评审 diff 后提交） | `bash tools/openapi-export.sh` |
| 只校验漂移（CI / 本地，只读） | `bash tools/openapi-export.sh --check` |
| 跑全量测试（含契约门禁） | `cd drone-backend && sh mvnw -B verify` |

约定：

1. 契约由 `OpenApiContractGateE2EIT` 从运行中的 `/v3/api-docs` 归一化导出，**随机端口与示例里的本机地址会被擦洗**，因此同一份实现多次导出逐字节一致；
2. 契约门禁挂在 **Failsafe（`*E2EIT`）** 上，因此它随 `sh mvnw -B verify` 执行；`sh mvnw -B test` 只跑单元层，**不包含**契约门禁。门禁默认**只读**：把运行中实现与冻结规格做**整文档深比较**，端点/参数/字段/枚举任一漂移即红；只有显式 `-Dopenapi.export=true` 才写 `spec/openapi/`（由 `tools/openapi-export.sh` 封装）。脚本会先删除上一次的报告与 `failsafe-summary.xml`，并断言本次报告为 `Tests run: 1, Failures: 0, Errors: 0`，避免"0 用例/陈旧报告"被当成通过；
3. 控制器注解纪律：**不要**用「不带 `implementation` 的 `type = "object"` 示例型 `content`」覆盖真实返回类型——那会让契约在消费端退化成 `Record<string, never>`。要写示例请写在 VO 的 `@Schema(example = …)` 上；
4. 新增/修改端点后：导出 → 评审 → 提交 → 通知消费端用同一 sha256 对账（`frontend`：`pnpm api`）。
