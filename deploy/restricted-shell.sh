#!/bin/bash
# 受限 shell - 仅允许 deploy.sh
# SSH command= 通过 "shell -c \"cmd\"" 调用，$1=-c, $2=命令
if [ "$1" = "-c" ]; then
    case "$2" in
        "sudo /opt/drone/deploy.sh")
            exec sudo /opt/drone/deploy.sh
            ;;
        *)
            echo "ERROR: Command not allowed"
            exit 1
            ;;
    esac
elif [ -n "$SSH_ORIGINAL_COMMAND" ]; then
    # 直接传入的命令
    case "$SSH_ORIGINAL_COMMAND" in
        "sudo /opt/drone/deploy.sh")
            exec sudo /opt/drone/deploy.sh
            ;;
        *)
            echo "ERROR: Command not allowed"
            exit 1
            ;;
    esac
else
    echo "ERROR: Interactive login not allowed"
    exit 1
fi
