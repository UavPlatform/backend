#!/usr/bin/env bash
# check-test-layers.sh —— 测试分层与执行层门禁（只读检查，不改任何文件）
#
# 用法：
#   bash tools/check-test-layers.sh                    # 检查真树（backend/drone-backend/src/test/java）
#   bash tools/check-test-layers.sh --root DIR         # 检查指定测试源根
#   bash tools/check-test-layers.sh --selftest         # 门禁自证（五步，见下）
#   bash tools/check-test-layers.sh --variant v1|v2    # 仅回归取证用：回放已知坏实现的盲区
#
# 判据见 docs/quality/backend-test-strategy.md §9；口径：**先剥注释与字符串**（字符串内容置空、只留引号占位），
# 且**遍历文件内全部 class 声明**，逐个取紧邻其 `class` 关键字的类级注解块。
# 基类比较前先**规范化**（取末段），故 `extends com.uav.support.RealProtocolTestBase` 与简单名等价（G1）。
# `package-info.java`／`module-info.java` 属合法的无类型声明文件，**豁免**"每文件必须有类型声明"的分母要求（G2）。
#
# 检查项：
#   C1 单元层纯净：被 Surefire 匹配的 *Test.java 不得在类级注解块里出现 @SpringBootTest。
#   C2 执行层归属：出现 @SpringBootTest 的类必须命名为 *IT/*E2EIT。
#   C3 共享包纯净：com/uav/support/ 下不得出现 @Test（工具包只放基类与工厂）。
#   C4 真实端口（正向）：extends RealProtocolTestBase 且类级注解块含 @SpringBootTest
#      ⇒ 同一注解块内必须有 webEnvironment = (SpringBootTest.)?(WebEnvironment.)?RANDOM_PORT。
#      原因：子类自声明 @SpringBootTest **不合并**超类属性，漏写即回落 MOCK；缺 T4_DB_* 时会被
#      类级 @EnabledIfEnvironmentVariable 掩盖为 skipped，报告上看不出问题。
#      **字符串不算数**：端口字样只出现在 `properties = {"x=...RANDOM_PORT"}` 里仍是漏写。
#   C5 端口语义（反向）：extends IntegrationTestBase（MOCK 语义）⇒ 注解块内 webEnvironment
#      **若出现则必须为 MOCK**；RANDOM_PORT / DEFINED_PORT / NONE 均为冲突 ⇒ 违例。
#      注意判据是"**冲突才算违例**"，不是"不得出现 @SpringBootTest"。
#
# 分母断言（禁止"检了 0 个类"通过）：ROOT 必须存在且为目录；扫描到的 .java 数 ≥ 1；
#   类型声明数（class/interface/enum/record）≥ 扫描文件数；class 声明数 ≥ 1。
#   任一不成立即 exit 1 并打印原因 —— 与 §9.2「门禁必须断言实际执行数」一致。
#
# 自证（--selftest；五步全过才 exit 0，语料逐个单放以便各只命中其目标规则）：
#   1) 真树 0 违例（含 MOCK 家族合规自声明与完全继承的 E2EIT）
#   2) 正例语料 0 违例（纯继承 / 显式 RANDOM_PORT / 显式 MOCK / 仅 Javadoc 提及 / 仅字符串提及）
#   3) 负例语料逐个判违例（8 个；含 G1 回归的 FQN 基类负例）：
#        RPTB 漏写端口（多行实参含 `}`）、Javadoc 假端口、MOCK 家族声明 RANDOM_PORT、
#        MOCK 家族声明 DEFINED_PORT、**无 extends 的 *Test + @SpringBootTest**、
#        **同文件多类且首个 class 在前**、**端口只出现在字符串里**、
#        **基类写成完全限定名（FQN）→ 必须能命中 C4**
#   4) 分母断言负例：不存在的根 / 空目录必须 exit 1（禁止真空通过）
#   5) 已知坏实现 v1/v2 必须"假通过"（证明语料有区分力：坏实现下的 0 违例与好实现下的 0 违例不可区分）
#
# 依赖：bash、python3（CI ubuntu-latest 自带）。
set -uo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_ROOT="$(cd "$SELF_DIR/../drone-backend/src/test/java" 2>/dev/null && pwd || true)"
ROOT="$DEFAULT_ROOT"
SELFTEST=0
VARIANT="good"
QUIET=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --root) ROOT="${2:-}"; shift 2 ;;
    --selftest) SELFTEST=1; shift ;;
    --variant) VARIANT="${2:-good}"; shift 2 ;;
    --quiet) QUIET=1; shift ;;
    -h|--help) sed -n '2,45p' "$0"; exit 0 ;;
    *) echo "[layers] 未知参数：$1" >&2; exit 2 ;;
  esac
done

run_checks() { # $1=根目录 $2=variant
  python3 - "$1" "${2:-good}" <<'PY'
import os, re, sys

ROOT, VARIANT = sys.argv[1], sys.argv[2]


def strip(s: str) -> str:
    """剥掉注释与**字符串内容**（保留引号占位与结构），避免注释/字符串污染判定。

    字符串内容一律丢弃：否则 `properties = {"x=webEnvironment=RANDOM_PORT"}` 这类
    纯字符串即可满足 C4（假绿），teardown 里的 "UserContext.clear()" 也会造成假阳性。
    """
    out = []; i = 0; n = len(s); st = None
    while i < n:
        c = s[i]; x = s[i + 1] if i + 1 < n else ''
        if st is None:
            if c == '/' and x == '/': st = 'line'; i += 2; continue
            if c == '/' and x == '*': st = 'block'; i += 2; continue
            if c == '"': st = 'str'; out.append(c); out.append(c); i += 1; continue
            out.append(c); i += 1
        elif st == 'line':
            if c == '\n': st = None; out.append(c)
            i += 1
        elif st == 'block':
            if c == '*' and x == '/': st = None; i += 2
            else: out.append('\n' if c == '\n' else ' '); i += 1
        else:  # str：丢弃内容（含转义），只保留结构性的换行以维持行号近似
            if c == '\n': out.append('\n')
            if c == '\\': i += 2; continue
            if c == '"': st = None; out.append(c)
            i += 1
    return ''.join(out)


CLASS = re.compile(r'(?<![\w.])class\s+(\w+)(?:\s+extends\s+([\w.]+))?')
TYPE = re.compile(r'(?<![\w.])(?:class|interface|enum|record)\s+(\w+)')
ANN_RUN = re.compile(r'((?:@[\w.]+(?:\s*\((?:[^()]|\([^()]*\))*\))?\s*)+)$')
WE_ANY = re.compile(r'webEnvironment\s*=\s*([\w.]+)')
WE_RANDOM = re.compile(r'webEnvironment\s*=\s*(?:SpringBootTest\.)?(?:WebEnvironment\.)?RANDOM_PORT')


def anno_block(code: str, pos: int, variant: str) -> str:
    """取紧邻 class 声明之前的类级注解块。variant 用于回放已知坏实现（仅回归取证）。"""
    if variant == 'v1':          # 坏实现 1：按最后一个 `}`/`;` 切分 → 被多行注解实参里的 `}` 截断
        pre = code[:pos]
        cut = max(pre.rfind('\n}'), pre.rfind('\n;'), -1)
        return pre[cut + 1:]
    if variant == 'v2':          # 坏实现 2：向上逐行收集，遇空行/非注解行即 break
        lines = code[:pos].split('\n'); i = len(lines) - 1; coll = []; depth = 0
        while i >= 0:
            t = lines[i].strip()
            if depth > 0:
                coll.append(t); depth += t.count('(') - t.count(')'); i -= 1; continue
            if t == '' or not t.startswith('@'):
                break
            coll.append(t); depth += t.count('(') - t.count(')'); i -= 1
        return '\n'.join(reversed(coll))
    m = ANN_RUN.search(code[:pos])
    return m.group(1) if m else ''


violations = []
errors = []
files_scanned = 0
classes_checked = 0
types_seen = 0
exempt_files = 0
for dirpath, _, files in os.walk(ROOT):
    for f in sorted(files):
        if not f.endswith('.java'):
            continue
        files_scanned += 1
        if f in ('package-info.java', 'module-info.java'):
            exempt_files += 1      # G2：合法的无类型声明文件，豁免分母要求
            continue
        path = os.path.join(dirpath, f)
        raw = open(path, encoding='utf-8').read()
        code = strip(raw)
        types_seen += len(TYPE.findall(code))
        decls = list(CLASS.finditer(code))
        classes_checked += len(decls)
        if '/support/' in path.replace(os.sep, '/'):
            if re.search(r'@Test\b', code):
                violations.append((path, '', 'C3 com/uav/support 下出现 @Test（共享工具包不得含用例）'))
            continue
        for m in decls:
            name = m.group(1)
            base = (m.group(2) or '').split('.')[-1]   # G1：完全限定名基类先规范化再比较
            block = anno_block(code, m.start(), VARIANT)
            sbt = '@SpringBootTest' in block
            we = WE_ANY.search(block)
            we_value = (we.group(1).split('.')[-1] if we else '')
            if f.endswith('Test.java') and sbt:
                violations.append((path, name, 'C1 单元层（*Test）出现类级 @SpringBootTest（应改名 *IT/*E2EIT 并下沉到 failsafe）'))
            if sbt and not (f.endswith('IT.java') or f.endswith('E2EIT.java')):
                violations.append((path, name, 'C2 含 @SpringBootTest 的类未按 *IT/*E2EIT 命名（执行层归属不清）'))
            if base == 'RealProtocolTestBase' and sbt and not WE_RANDOM.search(block):
                violations.append((path, name, 'C4 继承 RealProtocolTestBase 的子类自声明 @SpringBootTest，但注解块内缺少 webEnvironment=RANDOM_PORT（漏写即回落 MOCK；缺 T4_DB_* 时被门控掩盖为 skipped）'))
            if base == 'IntegrationTestBase' and we and we_value != 'MOCK':
                violations.append((path, name, f'C5 继承 IntegrationTestBase（MOCK）的类声明了冲突的 webEnvironment={we_value}（只允许 MOCK）'))

# ---- 分母断言：禁止"检了 0 个类"或"静默跳过文件"通过 ----
if files_scanned == 0:
    errors.append(f'未扫描到任何 .java 文件（ROOT={ROOT} 不存在或为空）—— 门禁拒绝真空通过')
if classes_checked == 0:
    errors.append('未检到任何 class 声明 —— 门禁拒绝真空通过')
elif types_seen < (files_scanned - exempt_files):
    errors.append(f'有 {files_scanned - exempt_files - types_seen} 个 .java 文件未检出任何类型声明（class/interface/enum/record）—— 文件被静默跳过')

for path, name, why in violations:
    print(f"  ✗ {path}{(':' + name) if name else ''}: {why}")
for e in errors:
    print(f"  ✗ [分母断言] {e}")
print(f"[layers] 文件={files_scanned} 检查类数={classes_checked} 类型声明={types_seen} 豁免={exempt_files} 违例={len(violations)}（variant={VARIANT}）")
sys.exit(1 if (violations or errors) else 0)
PY
}

# ---- 自证语料（内联生成，落库后无需额外文件）----
make_corpus() {
  local d="$1"
  mkdir -p "$d/pos" "$d/neg"
  # ===== 负例（逐个必须判违例）=====
  cat > "$d/neg/ChatStyleE2EIT.java" <<'JAVA'
package com.uav.selftest;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * 负例：继承 RealProtocolTestBase 却自声明 @SpringBootTest(properties=...) 而漏写 webEnvironment
 * ⇒ 真实回落 MOCK；缺 T4_DB_* 时被类级门控掩盖成 skipped。多行实参含 `}`，是坏实现 v1 的盲区。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:selftest-n1"
})
class ChatStyleE2EIT extends RealProtocolTestBase {
}
JAVA
  cat > "$d/neg/OrderStyleE2EIT.java" <<'JAVA'
package com.uav.selftest;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * 负例：注解块已无 webEnvironment（真实回落 MOCK），但 Javadoc 仍写着
 * webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT —— 全文 grep 会假通过。
 */
@SpringBootTest(
        properties = {"spring.datasource.url=jdbc:h2:mem:selftest-n2"}
)
class OrderStyleE2EIT extends RealProtocolTestBase {
}
JAVA
  cat > "$d/neg/MockStyleIT.java" <<'JAVA'
package com.uav.selftest;

import org.springframework.boot.test.context.SpringBootTest;

/** 负例：继承 IntegrationTestBase（MOCK）却声明 RANDOM_PORT，与基类端口语义冲突。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MockStyleIT extends IntegrationTestBase {
}
JAVA
  cat > "$d/neg/DefinedPortIT.java" <<'JAVA'
package com.uav.selftest;

import org.springframework.boot.test.context.SpringBootTest;

/** 负例：继承 IntegrationTestBase（MOCK）却声明 DEFINED_PORT —— 非 MOCK 即冲突（判据是冲突，不是有无 @SpringBootTest）。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class DefinedPortIT extends IntegrationTestBase {
}
JAVA
  cat > "$d/neg/UnitNoExtendsTest.java" <<'JAVA'
package com.uav.selftest;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * 负例（F1 回归）：*Test 类带类级 @SpringBootTest 但**没有 extends**。
 * 旧实现用 `class\s+(\w+)\s+extends\s+(\w+)` 发现类 ⇒ 此文件被静默跳过、检查类数不增 ⇒ C1 假绿。
 * 真树里被同一路径漏掉的正是两个纯单元类 pay/MockPayGuardTest、pay/PayNotifyAmountTest。
 */
@SpringBootTest
class UnitNoExtendsTest {
}
JAVA
  cat > "$d/neg/MultiClassOrderTest.java" <<'JAVA'
package com.uav.selftest;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * 负例（F5 回归）：同一文件多个 class，**首个 class 带 extends 在前**、带 @SpringBootTest 的类在后。
 * 旧实现 `CLASS.search` 只取首个匹配 ⇒ 后一个类完全不参与 C1/C2/C4/C5 ⇒ 假绿。
 */
class HelperFirst extends Object {
}

@SpringBootTest
class MultiClassOrderTest extends IntegrationTestBase {
}
JAVA
  cat > "$d/neg/StringPortE2EIT.java" <<'JAVA'
package com.uav.selftest;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * 负例（F2 回归）：端口字样**只出现在字符串里**（注解块内没有真实 webEnvironment）。
 * 只剥注释、不剥字符串的实现会把它当成 C4 已满足 ⇒ 假绿。
 */
@SpringBootTest(properties = {"x=webEnvironment=RANDOM_PORT"})
class StringPortE2EIT extends RealProtocolTestBase {
}
JAVA
  cat > "$d/neg/FqBaseE2EIT.java" <<'JAVA'
package com.uav.selftest;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * 负例（G1 回归）：基类写成**完全限定名**（不 import），且自声明 @SpringBootTest 漏 webEnvironment。
 * 旧实现把捕获的 `com.uav.support.RealProtocolTestBase` 与简单名精确比较 ⇒ C4 不命中 ⇒ **假绿 exit 0**。
 */
@SpringBootTest(properties = {"a=b"})
class FqBaseE2EIT extends com.uav.support.RealProtocolTestBase {
}
JAVA
  # ===== 正例（都不得误报）=====
  cat > "$d/pos/P2InheritedE2EIT.java" <<'JAVA'
package com.uav.selftest;

/** 正例：完全继承 RealProtocolTestBase 的 RANDOM_PORT，无类级注解 ⇒ 不得误报。 */
class P2InheritedE2EIT extends RealProtocolTestBase {
}
JAVA
  cat > "$d/pos/P3ExplicitPortE2EIT.java" <<'JAVA'
package com.uav.selftest;

import org.springframework.boot.test.context.SpringBootTest;

/** 正例：真实协议子类自声明 @SpringBootTest 并**同块重复** RANDOM_PORT ⇒ C4 满足。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.url=jdbc:h2:mem:selftest-p3"}
)
class P3ExplicitPortE2EIT extends RealProtocolTestBase {
}
JAVA
  cat > "$d/pos/P4JavadocMentionIT.java" <<'JAVA'
package com.uav.selftest;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * 正例：Javadoc 提及 webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT，但注解块未声明
 * ⇒ 不得误报（剥注释口径；对照真实类 DroneBackendApplicationIT 的纯 Javadoc 命中）。
 */
@SpringBootTest
class P4JavadocMentionIT extends IntegrationTestBase {
}
JAVA
  cat > "$d/pos/P5ExplicitMockIT.java" <<'JAVA'
package com.uav.selftest;

import org.springframework.boot.test.context.SpringBootTest;

/** 正例：MOCK 家族显式声明 webEnvironment = MOCK（与基类端口语义一致）⇒ 不得误报。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class P5ExplicitMockIT extends IntegrationTestBase {
}
JAVA
  cat > "$d/pos/package-info.java" <<'JAVA'
package com.uav.selftest;
JAVA
  cat > "$d/pos/P6StringMentionIT.java" <<'JAVA'
package com.uav.selftest;

/**
 * 正例（F2 反向）：字符串里出现 @SpringBootTest 与 webEnvironment=RANDOM_PORT 字样，但注解块为空
 * ⇒ 不得误报（剥字符串后这些字样不参与判定）。
 */
class P6StringMentionIT extends IntegrationTestBase {
    private final String note = "@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)";
}
JAVA
}

selftest() {
  local fail=0 corpus
  corpus="$(mktemp -d)"; make_corpus "$corpus"

  echo "[selftest] 1/5 正例：真树必须 0 违例"
  if [[ -z "$ROOT" || ! -d "$ROOT" ]]; then
    echo "  ✗ 真树根不存在：$ROOT" >&2; fail=1
  elif run_checks "$ROOT" good; then
    echo "  ✓ 真树通过（0 违例）"
  else
    echo "  ✗ 真树存在违例" >&2; fail=1
  fi

  echo "[selftest] 2/5 正例：语料必须 0 违例（纯继承 / 显式 RANDOM_PORT / 显式 MOCK / 仅 Javadoc 提及 / 仅字符串提及）"
  if run_checks "$corpus/pos" good; then echo "  ✓ 正例语料通过"; else echo "  ✗ 正例语料被误报" >&2; fail=1; fi

  echo "[selftest] 3/5 负例：8 个构造副本逐个都必须被判违例（文件名＝类名，故各只命中其目标规则）"
  local f tmp n=0 out rc
  for f in "$corpus"/neg/*.java; do
    tmp="$(mktemp -d)"; mkdir -p "$tmp/com/uav/selftest"
    if ! cp "$f" "$tmp/com/uav/selftest/"; then
      echo "  ✗ 语料复制失败：$(basename "$f")" >&2; fail=1; rm -rf "$tmp"; continue
    fi
    out="$(run_checks "$tmp" good 2>&1)"; rc=$?
    if [[ "$rc" == "0" ]]; then
      echo "  ✗ 负例未被判违例：$(basename "$f")" >&2; fail=1
    elif echo "$out" | grep -q '\[分母断言\]'; then
      echo "  ✗ 负例只命中分母断言（语料未真正触发规则）：$(basename "$f")" >&2; fail=1
    else
      echo "  ✓ 负例被判违例：$(basename "$f")"; n=$((n + 1))
    fi
    rm -rf "$tmp"
  done
  if [[ "$n" != "8" ]]; then echo "  ✗ 负例语料数不是 8（实为 $n）——语料缺失" >&2; fail=1; fi

  echo "[selftest] 4/5 分母断言：不存在的根 / 空目录必须 exit 1（禁止真空通过）"
  mkdir -p "$corpus/emptydir"
  local bad
  for bad in "$corpus/nonexist-root-xyz" "$corpus/emptydir"; do
    if run_checks "$bad" good >/dev/null 2>&1; then
      echo "  ✗ 真空通过（exit 0）：$bad" >&2; fail=1
    else
      echo "  ✓ 已拒绝真空通过：$(basename "$bad")"
    fi
  done

  echo "[selftest] 5/5 回归：已知坏实现 v1/v2 必须以\"假通过\"收场（证明语料有区分力）"
  expect_false_pass() { # $1=variant $2=负例文件名
    local d; d="$(mktemp -d)"; mkdir -p "$d/com/uav/selftest"; cp "$corpus/neg/$2" "$d/com/uav/selftest/"
    if run_checks "$d" "$1" >/dev/null 2>&1; then
      echo "  ✓ 坏实现 $1 对 $2 假通过（0 违例）"
    else
      echo "  ✗ 坏实现 $1 竟检出 $2：区分力结论需重测" >&2; fail=1
    fi
    rm -rf "$d"
  }
  expect_false_pass v1 ChatStyleE2EIT.java
  expect_false_pass v2 ChatStyleE2EIT.java
  expect_false_pass v2 OrderStyleE2EIT.java
  local v
  for v in v1 v2; do
    if run_checks "$ROOT" "$v" >/dev/null 2>&1; then
      echo "  ✓ 坏实现 $v 对真树同样 0 违例（真树正例不足以区分好坏实现）"
    else
      echo "  ✗ 坏实现 $v 在真树上报出违例：矩阵需重测" >&2; fail=1
    fi
  done

  rm -rf "$corpus"
  if [[ "$fail" == "0" ]]; then
    echo "[selftest] OK：真树+6 正例 0 违例；8 负例全违例；真空通过被拒；坏实现 v1/v2 不可区分"
  else
    echo "[selftest] FAILED" >&2
  fi
  return "$fail"
}

if [[ "$SELFTEST" == "1" ]]; then
  selftest
else
  if [[ -z "$ROOT" || ! -d "$ROOT" ]]; then
    echo "[layers] 根目录不存在或不是目录：$ROOT —— 门禁拒绝真空通过" >&2
    exit 1
  fi
  [[ "$QUIET" == "0" ]] && echo "[layers] 根=$ROOT variant=$VARIANT"
  run_checks "$ROOT" "$VARIANT"
fi
