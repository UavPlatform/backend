# backend — Agent 入口

权威的工作流程、规则、ADR、需求与完整 `AGENTS.md` 都在 UavPlatform/docs 仓库：
https://github.com/UavPlatform/docs

开始工作前，先在线阅读 docs，或把它克隆到单独的本地目录（例如与本仓库并列的 `docs/`）后再读。本仓库不维护规则副本；如有冲突，以 docs 为准。

## 运行测试

在 `drone-backend/` 下执行（JDK 21；Windows 用 `mvnw.cmd`）：

```bash
sh mvnw -B test          # 单元测试
sh mvnw -B clean verify  # 全量：单元 + 集成/E2E + 契约与质量门禁
```

## 硬规则

- 不读取、不打印、不提交密钥（`application-dev.yml`、`.env*`、`*.key`、`*.pem` 等）。
- 未经用户明确同意，不 `git commit` / `git push`。
