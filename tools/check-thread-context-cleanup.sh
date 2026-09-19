#!/usr/bin/env bash
# check-thread-context-cleanup.sh —— R8 门禁：teardown 清理必须集中在基类，不得逐类手工重复
#
# 用法：
#   bash tools/check-thread-context-cleanup.sh                  # 检查真树（backend/drone-backend/src/test/java）
#   bash tools/check-thread-context-cleanup.sh --root DIR       # 检查指定测试源根
#   bash tools/check-thread-context-cleanup.sh --variant grep   # 回放坏实现（全文 grep：不剥注释、不分作用域）
#   bash tools/check-thread-context-cleanup.sh --selftest       # 自证：8 条锚点（见下方锚点表）
#
# 判据（docs/quality/backend-test-strategy.md §9.1 R8）：
#   * 达标 = **该类本地 teardown 的手工清理数 = 0**（清理集中在基类）。
#   * 用例体内 **arrange 阶段的 `clear()` 属原语义、必须保留**，**不算**违例。
#   * 某类 `clear()` 数 = 0 **不等于**达标：若该类的清理逻辑仍在本地 teardown 里手写，才是违例。
#   ⇒ 因此本检查**只看 teardown 方法体**（`@AfterEach`/`@AfterAll`），不看用例体内调用。
#
# 基类白名单（允许在 teardown 内清理）：com/uav/support 下的类（IntegrationTestBase、RealProtocolTestBase）。
# 口径：先剥注释**与字符串内容**（字符串内容置空、只留引号占位），避免注释/字符串里的 `clear()`
#       被计入（假阳性）或被洗白。
#
# 自证锚点表（--selftest；判据 → 语料 → 期望）：
#   R8-teardown(@AfterEach)     neg/LocalTeardownIT.java      期望=违例
#   R8-teardown(@AfterAll)      neg/AfterAllCleanupIT.java    期望=违例
#   R8-teardown(@Nested)        neg/NestedTeardownIT.java     期望=违例
#   R8-teardown(真继承)          neg/InheritedTeardownIT.java  期望=违例（基类+子类同文件，须遍历全部类）
#   R8-allow(arrange 期)        pos/ArrangeOnlyIT.java        期望=不报（**反向负例**）
#   R8-allow(注释里提及)         pos/CommentedTeardownIT.java  期望=不报
#   R8-allow(字符串里提及)       pos/StringMentionIT.java      期望=不报（F2 回归）
#   R8-allow(会话关闭)           pos/SessionTeardownE2EIT.java 期望=不报
#   R8-allow(无本地 teardown)    pos/NoTeardownIT.java         期望=不报
#   真树                         ../drone-backend/src/test/java 期望=0 违例
#   分母断言                     不存在的根 / 空目录            期望=exit 1（禁止真空通过）
#   坏实现 --variant grep         pos/（反向负例所在语料）        期望=**报**（证明好坏实现可区分）
#
# 依赖：bash、python3。
set -uo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_ROOT="$(cd "$SELF_DIR/../drone-backend/src/test/java" 2>/dev/null && pwd || true)"
ROOT="$DEFAULT_ROOT"
SELFTEST=0
QUIET=0
VARIANT="default"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --root) ROOT="${2:-}"; shift 2 ;;
    --variant) VARIANT="${2:-}"; shift 2 ;;
    --selftest) SELFTEST=1; shift ;;
    --quiet) QUIET=1; shift ;;
    -h|--help) sed -n '2,32p' "$0"; exit 0 ;;
    *) echo "[threadctx] 未知参数：$1" >&2; exit 2 ;;
  esac
done

run_checks() {
  python3 - "$1" "$VARIANT" <<'PY'
import os, re, sys

ROOT = sys.argv[1]
VARIANT = sys.argv[2] if len(sys.argv) > 2 else 'default'
CLEAR = re.compile(r'(?<![\w.])(?:(?:UserContext|MDC)\s*\.\s*)?clear\s*\(\s*\)')
TEARDOWN = re.compile(r'@After(Each|All)\b')
RAW_CLEAR = re.compile(r'clear\s*\(\s*\)')
# 方法声明：从 @AfterEach 之后找到方法体（简单的花括号配平扫描）


def strip(s: str) -> str:
    out = []; i = 0; n = len(s); st = None
    while i < n:
        c = s[i]; x = s[i + 1] if i + 1 < n else ''
        if st is None:
            if c == '/' and x == '/': st = 'line'; i += 2; continue
            if c == '/' and x == '*': st = 'block'; i += 2; continue
            if c == '"': st = 'str'; out.append(c); i += 1; continue
            out.append(c); i += 1
        elif st == 'line':
            if c == '\n': st = None; out.append(c)
            i += 1
        elif st == 'block':
            if c == '*' and x == '/': st = None; i += 2
            else: out.append('\n' if c == '\n' else ' '); i += 1
        else:  # str：丢弃字符串内容（含转义），只保留结构 —— 字符串里的 clear() 不算真实清理
            if c == '\n': out.append('\n')
            if c == '\\': i += 2; continue
            if c == '"': st = None; out.append(c)
            i += 1
    return ''.join(out)


violations = []
errors = []
files_scanned = 0
checked = 0
teardown_methods = 0
for dirpath, _, files in os.walk(ROOT):
    for f in sorted(files):
        if not f.endswith('.java'):
            continue
        files_scanned += 1
        path = os.path.join(dirpath, f)
        if '/support/' in path.replace(os.sep, '/'):
            continue
        raw = open(path, encoding='utf-8').read()
        checked += 1
        if VARIANT == 'grep':
            # 坏实现：全文搜索、不剥注释、不区分作用域 —— 会把 arrange 期清理与注释一并误报
            if RAW_CLEAR.search(raw):
                line = 1 + raw[:RAW_CLEAR.search(raw).start()].count('\n')
                violations.append((path, line, '坏实现（全文 grep，不剥注释、不分作用域）'))
            continue
        code = strip(raw)
        for m in TEARDOWN.finditer(code):
            j = m.end()
            brace = code.find('{', j)
            if brace < 0:
                continue
            depth = 0; k = brace
            while k < len(code):
                if code[k] == '{': depth += 1
                elif code[k] == '}':
                    depth -= 1
                    if depth == 0: break
                k += 1
            body = code[brace:k + 1]
            teardown_methods += 1
            if CLEAR.search(body):
                line = code[:m.start()].count('\n') + 1
                violations.append((path, line, '@After' + (m.group(1) or '') + ' 的 teardown 体内手工清理（应集中到基类）'))

for path, line, why in violations:
    print(f"  ✗ {path}:{line}: {why}")
# ---- 分母断言：禁止"检了 0 个类"通过（§9.2 门禁必须断言实际执行数）----
if files_scanned == 0:
    errors.append(f'未扫描到任何 .java 文件（ROOT={ROOT} 不存在或为空）—— 门禁拒绝真空通过')
if checked == 0:
    errors.append('未检到任何非 support 类 —— 门禁拒绝真空通过')
for e in errors:
    print(f"  ✗ [分母断言] {e}")
print(f"[threadctx] 文件={files_scanned} 检查类数={checked} teardown 方法={teardown_methods} 违例={len(violations)}"
      + (f"（variant={VARIANT}）" if VARIANT != 'default' else ''))
sys.exit(1 if (violations or errors) else 0)
PY
}

make_corpus() {
  local d="$1"; mkdir -p "$d/pos" "$d/neg"
  # ---- 负例：teardown 作用域内的手工清理，都必须判违例 ----
  cat > "$d/neg/LocalTeardownIT.java" <<'JAVA'
package com.uav.selftest;

import org.junit.jupiter.api.AfterEach;

/** 负例：本地 @AfterEach 里手写清理（应集中到基类）⇒ 必须判违例。 */
class LocalTeardownIT extends IntegrationTestBase {
    @AfterEach
    void cleanup() {
        UserContext.clear();
    }
}
JAVA
  cat > "$d/neg/AfterAllCleanupIT.java" <<'JAVA'
package com.uav.selftest;

import org.junit.jupiter.api.AfterAll;
import org.slf4j.MDC;

/** 负例：@AfterAll 里手写 MDC 清理 ⇒ 必须判违例。 */
class AfterAllCleanupIT extends IntegrationTestBase {
    @AfterAll
    static void cleanupAll() {
        MDC.clear();
    }
}
JAVA
  cat > "$d/neg/NestedTeardownIT.java" <<'JAVA'
package com.uav.selftest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;

/** 负例：清理写在 @Nested 内层类的 @AfterEach 里 ⇒ 必须判违例（同一文件内也要被发现）。 */
class NestedTeardownIT extends IntegrationTestBase {
    void scenario() {
        UserContext.set(new UserContext.Principal(1L, "rider"));
    }

    @Nested
    class Inner {
        @AfterEach
        void cleanup() {
            UserContext.clear();
        }
    }
}
JAVA
  cat > "$d/neg/InheritedTeardownIT.java" <<'JAVA'
package com.uav.selftest;

import org.junit.jupiter.api.AfterEach;

/**
 * 负例（F6 回归）：**真正的继承**场景 —— 清理写在本地基类的 @AfterEach 里，子类 `extends` 它并继承该 teardown
 * ⇒ 必须判违例（只有 com/uav/support 下的基类豁免手写清理；继承来的清理同样不得逐类手写）。
 * 基类与子类同文件 ⇒ 门禁必须遍历**全部**类声明/teardown 才能发现（与层门禁 F5 同源）。
 */
class InheritedTeardownBase extends IntegrationTestBase {
    @AfterEach
    void restoreContext() {
        UserContext.clear();
    }
}

class InheritedTeardownIT extends InheritedTeardownBase {
    void scenario() {
        UserContext.set(new UserContext.Principal(1L, "rider"));
    }
}
JAVA
  # ---- 正例：这些都**不得**误报 ----
  cat > "$d/pos/ArrangeOnlyIT.java" <<'JAVA'
package com.uav.selftest;

/**
 * **反向负例（关键）**：只在**用例体内 arrange 阶段**清理（原语义，必须保留）⇒ **必须不报**。
 * 任何"全文 grep / 只看是否有 clear()"的坏实现都会在此例上假阳性。
 */
class ArrangeOnlyIT extends IntegrationTestBase {
    void scenario() {
        UserContext.clear();
        UserContext.set(new UserContext.Principal(1L, "rider"));
    }
}
JAVA
  cat > "$d/pos/CommentedTeardownIT.java" <<'JAVA'
package com.uav.selftest;

import org.junit.jupiter.api.AfterEach;

/** 正例：teardown 里只有**注释**提到清理（已由基类承担）⇒ 不得误报（剥注释口径）。 */
class CommentedTeardownIT extends IntegrationTestBase {
    @AfterEach
    void cleanup() {
        // UserContext.clear(); 由基类统一清理
    }
}
JAVA
  cat > "$d/pos/StringMentionIT.java" <<'JAVA'
package com.uav.selftest;

import org.junit.jupiter.api.AfterEach;

/**
 * 正例（F2 回归，反向）：teardown 体内**字符串**里出现 `UserContext.clear()` 字样（日志文案），
 * 但没有任何真实清理调用 ⇒ 不得误报（剥字符串口径；只剥注释的实现会在此例上假阳性）。
 */
class StringMentionIT extends IntegrationTestBase {
    @AfterEach
    void cleanup() {
        log("UserContext.clear() 由基类统一执行，无需逐类手写");
    }

    private void log(String msg) {
        this.toString();
    }
}
JAVA
  cat > "$d/pos/SessionTeardownE2EIT.java" <<'JAVA'
package com.uav.selftest;

import org.junit.jupiter.api.AfterEach;
import java.util.List;
import java.util.ArrayList;

/**
 * 正例：teardown 里关闭**会话**（sessions.clear()），不是线程上下文清理 ⇒ 不得误报。
 * 此例是首版把 `sessions.clear()` 误判为线程上下文清理的回归语料。
 */
class SessionTeardownE2EIT extends RealProtocolTestBase {
    private final List<Object> sessions = new ArrayList<>();

    @AfterEach
    void closeSessions() {
        for (Object s : sessions) {
            s.hashCode();
        }
        sessions.clear();
    }
}
JAVA
  cat > "$d/pos/NoTeardownIT.java" <<'JAVA'
package com.uav.selftest;

/** 正例：完全无本地 teardown ⇒ 不得误报。 */
class NoTeardownIT extends IntegrationTestBase {
}
JAVA
}

anchor_table() {
  cat <<'TBL'
[selftest] 锚点表（判据 → 语料 → 期望）
    #1 R8-真树                  ../drone-backend/src/test/java   期望=0 违例
    #2 R8-allow(arrange 期)     pos/ArrangeOnlyIT.java           期望=不报（反向负例）
       R8-allow(注释里提及)      pos/CommentedTeardownIT.java     期望=不报
       R8-allow(会话关闭)        pos/SessionTeardownE2EIT.java    期望=不报
       R8-allow(无本地 teardown) pos/NoTeardownIT.java            期望=不报
    #3 R8-teardown(@AfterEach)  neg/LocalTeardownIT.java         期望=违例
    #4 R8-teardown(@AfterAll)   neg/AfterAllCleanupIT.java       期望=违例
    #5 R8-teardown(@Nested)     neg/NestedTeardownIT.java        期望=违例
       R8-allow(字符串里提及)    pos/StringMentionIT.java         期望=不报（F2 回归）
    #6 R8-teardown(真继承)       neg/InheritedTeardownIT.java     期望=违例（基类+子类同文件）
    #7 分母断言                 不存在的根 / 空目录                期望=exit 1（禁止真空通过）
    #8 坏实现 --variant grep     pos/（含反向负例的语料）          期望=报（证明好坏实现可区分）
TBL
}

# 期望违例：在独立临时根下只放该文件，检查必须**因规则**非零退出。
# 注意：分母断言也会让空根 exit 1，故必须先确认语料确实拷进去了，且失败原因不是分母断言
# （否则"语料缺失/拷失败"会被误读成"违例已被检出" —— 自证自身假通过）。
expect_violation() {
  local f="$1" label="$2" fail_ref="$3" tmp out rc
  if [[ ! -f "$f" ]]; then
    echo "  ✗ 语料缺失：$f" >&2; eval "$fail_ref=1"; return
  fi
  tmp="$(mktemp -d)"; mkdir -p "$tmp/com/uav/selftest"
  if ! cp "$f" "$tmp/com/uav/selftest/"; then
    echo "  ✗ 语料复制失败：$f" >&2; eval "$fail_ref=1"; rm -rf "$tmp"; return
  fi
  out="$(run_checks "$tmp" 2>&1)"; rc=$?
  if [[ "$rc" == "0" ]]; then
    echo "  ✗ 期望违例但未报：$label" >&2; eval "$fail_ref=1"
  elif echo "$out" | grep -q '\[分母断言\]'; then
    echo "  ✗ 负例只命中分母断言（语料未真正触发规则）：$label" >&2; eval "$fail_ref=1"
  else
    echo "  ✓ $label → 判违例"
  fi
  rm -rf "$tmp"
}

selftest() {
  local fail=0 corpus; corpus="$(mktemp -d)"; make_corpus "$corpus"
  anchor_table

  local out rc
  echo "[selftest] #1 正例：真树必须 0 违例"
  if [[ -z "$ROOT" || ! -d "$ROOT" ]]; then echo "  ✗ 真树根不存在：$ROOT" >&2; fail=1
  else
    out="$(run_checks "$ROOT")"; rc=$?
    echo "$out" | tail -1
    if [[ "$rc" == "0" ]]; then echo "  ✓ 真树通过（0 违例）"; else echo "  ✗ 真树存在违例" >&2; fail=1; fi
  fi

  echo "[selftest] #2 正例／反向负例：语料必须 0 违例（arrange 期清理 / 注释内提及 / 字符串内提及 / 会话关闭 / 无 teardown）"
  out="$(run_checks "$corpus/pos")"; rc=$?
  echo "$out" | tail -1
  if [[ "$rc" == "0" ]]; then echo "  ✓ 正例语料通过（含反向负例 ArrangeOnlyIT 未被误报）"; else echo "  ✗ 正例语料被误报" >&2; fail=1; fi

  echo "[selftest] #3 负例：@AfterEach 体内清理必须判违例"
  expect_violation "$corpus/neg/LocalTeardownIT.java" "@AfterEach 体内 UserContext.clear()" fail

  echo "[selftest] #4 负例：@AfterAll 体内清理必须判违例"
  expect_violation "$corpus/neg/AfterAllCleanupIT.java" "@AfterAll 体内 MDC.clear()" fail

  echo "[selftest] #5 负例：@Nested 内层 teardown 清理必须判违例"
  expect_violation "$corpus/neg/NestedTeardownIT.java" "@Nested 内层 @AfterEach 清理" fail

  echo "[selftest] #6 负例：真实继承场景（本地基类含 teardown 清理 + 子类 extends 它）必须判违例"
  expect_violation "$corpus/neg/InheritedTeardownIT.java" "被继承的本地基类 @AfterEach 清理（基类与子类同文件）" fail

  echo "[selftest] #7 分母断言：不存在的根 / 空目录必须 exit 1（禁止真空通过）"
  mkdir -p "$corpus/emptydir"
  local bad
  for bad in "$corpus/nonexist-root-xyz" "$corpus/emptydir"; do
    if run_checks "$bad" >/dev/null 2>&1; then
      echo "  ✗ 真空通过（exit 0）：$bad" >&2; fail=1
    else
      echo "  ✓ 已拒绝真空通过：$(basename "$bad")"
    fi
  done

  echo "[selftest] #8 坏实现回放：--variant grep（全文、不剥注释、不分作用域）必须在反向负例上被判违例"
  local save_variant="$VARIANT" bad_rc=0
  VARIANT="grep"
  if run_checks "$corpus/pos" >/dev/null 2>&1; then
    echo "  ✗ 坏实现（全文 grep）通过了正例语料 ⇒ 好坏实现不可区分" >&2; fail=1
  else
    echo "  ✓ 坏实现（全文 grep）在正例语料上被判违例（arrange 期清理 / 会话关闭被误报）⇒ 与真实现可区分"
    bad_rc=1
  fi
  VARIANT="$save_variant"
  [[ "$bad_rc" == "1" ]] || fail=1

  rm -rf "$corpus"
  if [[ "$fail" == "0" ]]; then echo "[selftest] OK：8 条锚点全部符合期望（真树+5 正例 0 违例；4 负例全违例；真空通过被拒；坏实现被反向负例打红）"
  else echo "[selftest] FAILED" >&2; fi
  return "$fail"
}

if [[ "$SELFTEST" == "1" ]]; then
  selftest
else
  if [[ -z "$ROOT" || ! -d "$ROOT" ]]; then
    echo "[threadctx] 根目录不存在或不是目录：$ROOT —— 门禁拒绝真空通过" >&2
    exit 1
  fi
  [[ "$QUIET" == "0" ]] && echo "[threadctx] 根=$ROOT${VARIANT:+（variant=$VARIANT）}"
  run_checks "$ROOT"
fi
