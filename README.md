# backend

Web backend code for the drone platform.

## OpenAPI 契约（结构单一事实源）

结构事实源：`spec/openapi/drone-backend.openapi.json`（归一化后入库，消费端据此生成类型）。
决策与门禁分层见 `docs/adr/0004-OpenAPI契约流水线与消费端接入.md`。

| 目的 | 命令（backend 仓根） |
|---|---|
| 导出契约（评审 diff 后提交） | `bash tools/openapi-export.sh` |
| 只校验漂移（CI / 本地，只读） | `bash tools/openapi-export.sh --check` |
| 跑全量测试（含契约门禁） | `cd drone-backend && mvn -B test` |

约定：

1. 契约由 `OpenApiContractGateTest` 从运行中的 `/v3/api-docs` 归一化导出，**随机端口与示例里的本机地址会被擦洗**，因此同一份实现多次导出逐字节一致；
2. `mvn test` 默认**只读**：把运行中实现与冻结规格做**整文档深比较**，端点/参数/字段/枚举任一漂移即红；只有显式 `-Dopenapi.export=true` 才写 `spec/openapi/`；
3. 控制器注解纪律：**不要**用「不带 `implementation` 的 `type = "object"` 示例型 `content`」覆盖真实返回类型——那会让契约在消费端退化成 `Record<string, never>`。要写示例请写在 VO 的 `@Schema(example = …)` 上；
4. 新增/修改端点后：导出 → 评审 → 提交 → 通知消费端用同一 sha256 对账（`frontend`：`pnpm api`）。
