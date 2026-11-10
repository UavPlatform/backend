#!/usr/bin/env bash
# 启动全新 IT 栈：MySQL + LocalStack S3 + backend(profile=it)。
# 仓库根：backend/；在任意 cwd 执行均可。
set -euo pipefail

# 与本地 e2e 一致：优先使用仓库旁的 docker CLI / sock（CI 用系统 docker）。
if [[ -d /workspace/e2e/tools/docker ]]; then
  export PATH="/workspace/e2e/tools/docker:${PATH}"
  export DOCKER_HOST="${DOCKER_HOST:-unix:///tmp/docker-run/docker.sock}"
fi
if ! command -v docker >/dev/null 2>&1; then
  echo "[it] docker not found on PATH" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
IT_DIR="$ROOT/tools/it"
COMPOSE=(docker compose -f "$IT_DIR/docker-compose.yml" --project-directory "$IT_DIR")
if [[ -f "$IT_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$IT_DIR/.env"
  set +a
fi

MYSQL_PORT="${MYSQL_PORT:-13307}"
S3_PORT="${S3_PORT:-4567}"
APP_PORT="${APP_PORT:-8082}"
MYSQL_DATABASE="${MYSQL_DATABASE:-drone_it}"
MYSQL_USER="${MYSQL_USER:-drone}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-it-only-pass}"
JWT_SECRET="${JWT_SECRET:-it-local-only-jwt-secret-0123456789abcdef}"
MINIO_BUCKET="${MINIO_BUCKET:-drone-it}"
PID_FILE="${IT_DIR}/.backend.pid"
LOG_FILE="${IT_DIR}/backend.log"
JAR="${JAR:-}"

echo "[it] starting mysql + s3 …"
"${COMPOSE[@]}" up -d mysql s3

echo "[it] waiting for mysql on :${MYSQL_PORT} …"
for i in $(seq 1 60); do
  if docker exec "$( "${COMPOSE[@]}" ps -q mysql )" mysqladmin ping -h 127.0.0.1 -uroot -p"${MYSQL_ROOT_PASSWORD:-it-only-root}" --silent 2>/dev/null; then
    break
  fi
  sleep 2
  if [[ "$i" -eq 60 ]]; then
    echo "[it] mysql not healthy" >&2
    exit 1
  fi
done

echo "[it] ensuring S3 bucket ${MINIO_BUCKET} …"
# LocalStack 接受 path-style 建桶
curl -sf -X PUT "http://127.0.0.1:${S3_PORT}/${MINIO_BUCKET}" >/dev/null || true

if [[ -z "$JAR" ]]; then
  echo "[it] building backend jar …"
  (
    cd "$ROOT/drone-backend"
    export JAVA_HOME="${JAVA_HOME:-${JDK_HOME:-}}"
    if [[ -z "${JAVA_HOME:-}" && -x /home/box/jdk21/bin/java ]]; then
      export JAVA_HOME=/home/box/jdk21
    fi
    sh mvnw -B -DskipTests package
  )
  JAR="$(ls -1t "$ROOT"/drone-backend/target/drone-backend-*.jar 2>/dev/null | head -1 || true)"
  if [[ -z "$JAR" ]]; then
    JAR="$(ls -1t "$ROOT"/drone-backend/target/*.jar 2>/dev/null | grep -v '\.original$' | head -1 || true)"
  fi
fi
if [[ -z "$JAR" || ! -f "$JAR" ]]; then
  echo "[it] backend jar not found" >&2
  exit 1
fi

if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "[it] backend already running pid=$(cat "$PID_FILE")"
else
  echo "[it] starting backend profile=it on :${APP_PORT} …"
  export SPRING_PROFILES_ACTIVE=it
  export WECHAT_PAY_MOCK_ENABLED=true
  export DISPATCH_DEMO_ENABLED=true
  export DRONE_DATASOURCE_DRIVER_CLASS_NAME=com.mysql.cj.jdbc.Driver
  export DRONE_DATASOURCE_HOST=127.0.0.1
  export DRONE_DATASOURCE_PORT="$MYSQL_PORT"
  export DRONE_DATASOURCE_DATABASE="$MYSQL_DATABASE"
  export DRONE_DATASOURCE_USERNAME="$MYSQL_USER"
  export DRONE_DATASOURCE_PASSWORD="$MYSQL_PASSWORD"
  export DRONE_JWT_SECRET="$JWT_SECRET"
  export DRONE_JWT_EXPIRATION=3600000
  export DRONE_JWT_REFRESH_EXPIRATION=604800000
  export DRONE_TRTC_SDK_APP_ID=1400000001
  export DRONE_TRTC_SECRET_KEY=it-placeholder-trtc-secret-key
  export DRONE_TRTC_ROOM_EXPIRE=3600
  export DRONE_AMAP_KEY=it-placeholder-amap-key
  export DRONE_AMAP_SECURITY_KEY=it-placeholder-amap-security-key
  export MINIO_ENDPOINT="http://127.0.0.1:${S3_PORT}"
  export MINIO_ACCESS_KEY=test
  export MINIO_SECRET_KEY=test
  export MINIO_BUCKET
  export MINIO_REGION=us-east-1
  export SERVER_PORT="$APP_PORT"
  nohup java ${JAVA_TOOL_OPTIONS:--Xms256m -Xmx768m} -jar "$JAR" \
    >"$LOG_FILE" 2>&1 &
  echo $! >"$PID_FILE"
fi

echo "[it] waiting for http://127.0.0.1:${APP_PORT}/v3/api-docs …"
for i in $(seq 1 90); do
  if curl -sf "http://127.0.0.1:${APP_PORT}/v3/api-docs" >/dev/null; then
    echo "[it] ready. base=http://127.0.0.1:${APP_PORT}"
    echo "[it] accounts: it-admin / it-pilot / it-customer  password=It@123456"
    exit 0
  fi
  sleep 2
done
echo "[it] backend failed to become ready; see $LOG_FILE" >&2
exit 1
