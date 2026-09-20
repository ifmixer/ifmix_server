#!/usr/bin/env bash
# 把本地 .env.prod 覆盖上传到线上 /data/app/core-api/common/.env.prod。
#
# 用法:
#   scripts/deploy/push-env.sh              # 上传并覆盖（远端先自动备份）
#   scripts/deploy/push-env.sh --restart    # 上传后重启 app-core-api 并检查健康
#
# 说明:
#   - 目标文件权限保持 640 root:app（含 DB 密码 / JWT 私钥，勿放宽）。
#   - 覆盖前在远端自动备份为 .env.prod.bak.<时间戳>。
#   - 依赖本地 ssh 别名 app_us1（~/.ssh/config 已配）。
set -euo pipefail

SSH_HOST="app_us1"
REMOTE_ENV="/data/app/core-api/common/.env.prod"
LOCAL_ENV="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/.env.prod"
RESTART=false
[ "${1:-}" = "--restart" ] && RESTART=true

[ -f "$LOCAL_ENV" ] || { echo "本地 $LOCAL_ENV 不存在" >&2; exit 1; }

echo "本地: $LOCAL_ENV"
echo "远端: $SSH_HOST:$REMOTE_ENV"
echo

# 1. 传到临时位置
TMP="/tmp/.env.prod.push.$$"
scp -q "$LOCAL_ENV" "$SSH_HOST:$TMP"

# 2. 远端：备份旧文件 → 覆盖 → 修正属主/权限 → 清理临时
ssh "$SSH_HOST" "
  set -e
  if sudo test -f '$REMOTE_ENV'; then
    ts=\$(date +%Y%m%d%H%M%S)
    sudo cp -a '$REMOTE_ENV' '${REMOTE_ENV}.bak.'\$ts
    echo \"已备份: ${REMOTE_ENV}.bak.\$ts\"
  fi
  sudo install -o root -g app -m 640 '$TMP' '$REMOTE_ENV'
  rm -f '$TMP'
  echo '已覆盖并设权限 640 root:app'
  echo '--- 远端 key 数 ---'; sudo grep -cE '^[A-Z_]+=' '$REMOTE_ENV'
"

# 3. 可选重启 + 健康检查
if $RESTART; then
  echo
  echo "重启 app-core-api ..."
  ssh "$SSH_HOST" "
    sudo systemctl reset-failed app-core-api 2>/dev/null || true
    sudo systemctl restart app-core-api
    for i in \$(seq 1 20); do
      if curl -fsS http://localhost:3001/actuator/health >/dev/null 2>&1; then
        echo \"健康检查通过: \$(curl -fsS http://localhost:3001/actuator/health)\"; exit 0
      fi
      sleep 3
    done
    echo '健康检查超时,查日志: sudo journalctl -u app-core-api -n 50' >&2; exit 1
  "
fi
echo
echo "完成。$($RESTART || echo '未重启（如需生效: sudo systemctl restart app-core-api，或加 --restart）')"
