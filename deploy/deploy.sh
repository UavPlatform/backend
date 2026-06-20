#!/bin/bash
set -e

# 确保 XDG_RUNTIME_DIR 存在
export XDG_RUNTIME_DIR=/run/user/0
mkdir -p "$XDG_RUNTIME_DIR/containers" 2>/dev/null || true

# 确保 podman auth 可用
if [ ! -f "$XDG_RUNTIME_DIR/containers/auth.json" ] && [ -f /etc/containers/auth.json ]; then
    cp /etc/containers/auth.json "$XDG_RUNTIME_DIR/containers/auth.json"
fi

cd /opt/drone
echo "=== Pulling latest images ==="
podman pull crpi-6p1nxczl55pe0ag2-vpc.cn-shenzhen.personal.cr.aliyuncs.com/uav_fuwaki/backend:latest
echo "=== Restarting services ==="
docker compose up -d
echo "=== Done ==="
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
