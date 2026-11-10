#!/usr/bin/env bash
# 拆除 IT 栈（backend 进程 + compose 卷）。
set -euo pipefail
if [[ -d /workspace/e2e/tools/docker ]]; then
  export PATH="/workspace/e2e/tools/docker:${PATH}"
  export DOCKER_HOST="${DOCKER_HOST:-unix:///tmp/docker-run/docker.sock}"
fi
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
IT_DIR="$ROOT/tools/it"
COMPOSE=(docker compose -f "$IT_DIR/docker-compose.yml" --project-directory "$IT_DIR")
PID_FILE="${IT_DIR}/.backend.pid"

if [[ -f "$PID_FILE" ]]; then
  pid="$(cat "$PID_FILE")"
  if kill -0 "$pid" 2>/dev/null; then
    echo "[it] stopping backend pid=$pid"
    kill "$pid" || true
    for i in $(seq 1 20); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 0.5
    done
    kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$PID_FILE"
fi

echo "[it] docker compose down -v …"
"${COMPOSE[@]}" down -v --remove-orphans
echo "[it] torn down"
