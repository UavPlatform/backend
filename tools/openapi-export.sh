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

# 门禁测试的 failsafe 报告路径，供 require_gate_executed 断言"确实执行了 1 例"。
GATE_REPORT="$MODULE/target/failsafe-reports/com.uav.contract.OpenApiContractGateE2EIT.txt"
# 0 用例的运行不会重写 summary，若留着上一次失败结果（result="255"），verify 会报误导性的
# "There are test failures"——同一输入会因残留历史状态得到不同判定。运行前一并清除 summary，
# 目的是【消除状态依赖】，使"0 用例"与"失败"两种情形在任何历史状态下判定一致
# （成功时该 summary 的值为 result="null"，不是 "0"）。
# 两条分支都会先删这两个文件：
#   target/failsafe-reports/com.uav.contract.OpenApiContractGateE2EIT.txt（门禁报告）
#   target/failsafe-reports/failsafe-summary.xml（failsafe:verify 的判定依据）
FAILSAFE_SUMMARY="$MODULE/target/failsafe-reports/failsafe-summary.xml"

# 门禁必须"真的跑起来"才算一致：
#   - failsafe 的 failIfNoSpecifiedTests 只在 scan 完全无匹配【类】时非零（类别名/删除可被阻断）；
#   - 但 `-Dit.test='类#方法'` 中【方法名】失效时类文件仍命中 scan，provider 只报 0 用例，
#     且 0 用例的运行不写 failsafe-summary.xml，failsafe:verify 无内容可判 → 静默 exit 0。
#   实测 -DfailIfNoTests=true 与 -Dfailsafe.failIfNoTests=true 都不覆盖该情形（仍 exit 0）。
# 因此运行前删除旧报告、运行后要求报告恰好 1 例通过，堵住"选择器失效 = 门禁静默放行"。
# 两处细节不可省：
#   1) 断言必须以【本次运行前删除旧报告】为前提——0 用例的运行不重写该 .txt，
#      上一次成功运行留下的同名报告会继续满足 grep，门禁将"假绿"（本仓库见过同构幽灵：
#      com.uav.DroneBackendApplicationTests.txt 在类被 surefire 静默排除后仍长期存在）；
#   2) 只定向删除本门禁自己的报告与 summary，不用 `mvn clean`：脚本在 CI 里跑，
#      不应为一条断言清空整个 target/（也避免丢掉其它诊断产物）；
#   3) `Tests run: 1,` 的逗号使 `Tests run: 11,` 不会误匹配。
require_gate_executed() {
  if ! grep -q 'Tests run: 1, Failures: 0, Errors: 0' "$GATE_REPORT" 2>/dev/null; then
    echo "[openapi] 门禁未实际执行（0 用例或报告缺失）" >&2
    return 1
  fi
}

MODE="${1:-export}"
cd "$MODULE"

case "$MODE" in
  --check|check)
    echo "[openapi] 校验模式：冻结规格 vs 运行中实现（不写 spec/）"
    rm -f "$GATE_REPORT" "$FAILSAFE_SUMMARY"
    mvn -B -q test-compile failsafe:integration-test failsafe:verify \
      -Dfailsafe.failIfNoSpecifiedTests=true \
      -Dit.test='OpenApiContractGateE2EIT#frozenSpecMatchesRunningImplementation'
    require_gate_executed
    echo "[openapi] OK：实现与 $SPEC_REL 一致"
    ;;
  export|--export)
    echo "[openapi] 导出模式：profile=test(H2) 启动上下文，拉取 /v3/api-docs 并归一化"
    rm -f "$GATE_REPORT" "$FAILSAFE_SUMMARY"
    mvn -B -q test-compile failsafe:integration-test failsafe:verify \
      -Dfailsafe.failIfNoSpecifiedTests=true \
      -Dit.test='OpenApiContractGateE2EIT#exportContract' \
      -Dopenapi.export=true
    require_gate_executed
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
