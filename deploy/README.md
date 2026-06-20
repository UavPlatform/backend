# 服务器部署配置

本目录包含生产服务器的部署配置文件备份。

## 服务器信息

- **主机**: `drone.fuwaki.icu`
- **系统**: Ubuntu 26.04 + Podman 5.7.0（兼容 Docker CLI）
- **部署目录**: `/opt/drone/`
- **部署用户**: `github-runner`（受限用户，仅可执行 deploy.sh）

## 文件说明

| 文件 | 说明 | 服务器路径 |
|------|------|-----------|
| `docker-compose.yml` | Docker Compose 编排文件 | `/opt/drone/docker-compose.yml` |
| `env.example` | 环境变量模板（真实值见服务器 `.env`） | `/opt/drone/.env` |
| `deploy.sh` | 部署脚本（拉镜像 + 重启服务） | `/opt/drone/deploy.sh` |
| `restricted-shell.sh` | github-runner 的受限 shell | `/opt/drone/restricted-shell.sh` |
| `sudoers.github-runner` | sudo 权限配置 | `/etc/sudoers.d/github-runner` |
| `authorized_keys.example` | SSH 公钥模板 | `/opt/drone/.ssh/authorized_keys` |
| `Caddyfile` | Caddy 反向代理配置 | `/etc/caddy/Caddyfile` |

## 初始部署步骤

```bash
# 1. 创建部署目录
ssh root@drone.fuwaki.icu "mkdir -p /opt/drone"

# 2. 上传配置文件
scp docker-compose.yml root@drone.fuwaki.icu:/opt/drone/
scp env.example root@drone.fuwaki.icu:/opt/drone/.env
scp deploy.sh root@drone.fuwaki.icu:/opt/drone/
scp restricted-shell.sh root@drone.fuwaki.icu:/opt/drone/

# 3. 设置权限
ssh root@drone.fuwaki.icu "chmod +x /opt/drone/deploy.sh /opt/drone/restricted-shell.sh && chmod 600 /opt/drone/.env"

# 4. 创建 github-runner 用户
ssh root@drone.fuwaki.icu "useradd -r -s /opt/drone/restricted-shell.sh -d /opt/drone -M github-runner"

# 5. 配置 sudo
scp sudoers.github-runner root@drone.fuwaki.icu:/etc/sudoers.d/github-runner
ssh root@drone.fuwaki.icu "chmod 440 /etc/sudoers.d/github-runner && visudo -c"

# 6. 生成 SSH 密钥
ssh root@drone.fuwaki.icu "mkdir -p /opt/drone/.ssh && ssh-keygen -t ed25519 -f /opt/drone/.ssh/deploy_key -N '' -C 'github-actions-deploy'"

# 7. 配置 authorized_keys（将公钥加入，带 command 限制）
# 参考 authorized_keys.example 格式

# 8. 共享 podman 认证
ssh root@drone.fuwaki.icu "mkdir -p /etc/containers && cp /run/user/0/containers/auth.json /etc/containers/auth.json && chmod 644 /etc/containers/auth.json"

# 9. 配置 Caddy 反向代理
scp Caddyfile root@drone.fuwaki.icu:/etc/caddy/Caddyfile
ssh root@drone.fuwaki.icu "systemctl restart caddy"

# 10. 启动服务
ssh root@drone.fuwaki.icu "cd /opt/drone && docker compose up -d"
```

## GitHub Actions 自动部署

CI 构建完成后自动触发部署，需在 GitHub 仓库 Secret 中配置：

- `DEPLOY_SSH_KEY`: deploy_key 私钥内容

## 安全说明

- `github-runner` 用户无法交互登录（受限 shell）
- 只能执行 `sudo /opt/drone/deploy.sh`
- 无法读取 `.env`、`docker-compose.yml` 等敏感文件
- 无法修改 `deploy.sh`（root 所有，755 权限）
