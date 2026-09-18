#!/usr/bin/env bash
# 导出后端 OpenAPI 结构契约（SSOT）。
#
#   - 事实源文件：backend/spec/openapi/drone-backend.openapi.json（本仓库持有并提交）
#   - 导出物必须归一化：擦洗随机端口 + servers 占位为相对基址 + 全树排序
#   - 「脚本不得静默改写 spec/」：本脚本只在人工显式执行时落盘，且落盘后必须评审 diff
#
# 用法：
#   bash tools/openapi-export.sh          # 导出并打印 sha256
#   bash tools/openapi-export.sh --check  # 只做漂移校验（不写 spec/），CI 可用
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE="$ROOT/drone-backend"
SPEC_REL="spec/openapi/drone-backend.openapi.json"
SPEC="$ROOT/$SPEC_REL"

MODE="${1:-export}"
cd "$MODULE"

case "$MODE" in
  --check|check)
    echo "[openapi] 校验模式：冻结规格 vs 运行中实现（不写 spec/）"
    mvn -B -q -Dtest=OpenApiContractGateTest#frozenSpecMatchesRunningImplementation test
    echo "[openapi] OK：实现与 $SPEC_REL 一致"
    ;;
  export|--export)
    echo "[openapi] 导出模式：profile=test(H2) 启动上下文，拉取 /v3/api-docs 并归一化"
    mvn -B -q -Dtest=OpenApiContractGateTest#exportContract -Dopenapi.export=true test
    if [[ ! -f "$SPEC" ]]; then
      echo "[openapi] 失败：未生成 $SPEC_REL" >&2
      exit 1
    fi
    SHA="$(sha256sum "$SPEC" | awk '{print $1}')"
    SIZE="$(wc -c < "$SPEC")"
    # Jackson 的 pretty printer 输出形如 "get" : {，冒号前有空格，故模式里留空匹配
    OPS="$(grep -cE '^ +"(get|post|put|delete|patch)" *: \{' "$SPEC")"
    echo
    echo "[openapi] 已更新 $SPEC_REL"
    echo "          bytes=$SIZE  operations=$OPS"
    echo "          sha256=$SHA"
    echo
    echo "[openapi] 下一步：评审 diff → 提交 → 下游仓库用同一 sha256 对账"
    ;;
  *)
    echo "用法：bash tools/openapi-export.sh [export|--check]" >&2
    exit 2
    ;;
esac
