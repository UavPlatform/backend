#!/bin/sh
# Resolve a working Python 3 interpreter, then exec the given script.
# Needed on Windows because `python3` may be the Microsoft Store stub (exit 49).
set -u

for candidate in "${PYTHON:-}" python3 python; do
  [ -n "$candidate" ] || continue
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'import sys' >/dev/null 2>&1; then
    exec "$candidate" "$@"
  fi
done

echo "[py] no working Python 3 interpreter (tried \$PYTHON, python3, python)" >&2
exit 127
