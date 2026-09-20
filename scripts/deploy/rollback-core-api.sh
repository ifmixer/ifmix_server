#!/usr/bin/env bash
#
# core-api 回滚：把 current 软链切回**另一个**目录（上一版仍完整保留在 idle 目录里），重启。
# 双目录蓝绿：a/b 各存一版，回滚 = 切软链，秒级、无需重传。
#
# 用法：
#   scripts/deploy/rollback-core-api.sh        # 切回另一个目录（上一版）
#   scripts/deploy/rollback-core-api.sh --status  # 只看当前指向，不动
#
set -euo pipefail

SSH_HOST="${DEPLOY_SSH_HOST:-app_us1}"
REMOTE_BASE="/opt/app"
APP="core-api"
CURRENT_LINK="$REMOTE_BASE/$APP-current"
DIR_A="$REMOTE_BASE/$APP-a"
DIR_B="$REMOTE_BASE/$APP-b"
SERVICE="app-core-api"
HEALTH_URL="http://localhost:3001/actuator/health"

log() { printf '\033[1;34m▶ %s\033[0m\n' "$*"; }
die() { printf '\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

ACTIVE_DIR="$(ssh "$SSH_HOST" "readlink -f $CURRENT_LINK 2>/dev/null || true")"
[ -n "$ACTIVE_DIR" ] || die "无 current 软链，尚未用增量方式发布过"

if [ "$ACTIVE_DIR" = "$DIR_A" ]; then
  TARGET="$DIR_B"
elif [ "$ACTIVE_DIR" = "$DIR_B" ]; then
  TARGET="$DIR_A"
else
  die "current 指向未知目录：$ACTIVE_DIR"
fi

if [ "${1:-}" = "--status" ]; then
  log "当前 active=$ACTIVE_DIR；回滚目标=$TARGET"
  exit 0
fi

# 目标目录必须完整（有过两次以上发布才成立）
ssh "$SSH_HOST" "[ -n \"\$(ls $TARGET/core-api-*.jar 2>/dev/null | head -1)\" ] && [ -d $TARGET/lib ]" \
  || die "回滚目标 $TARGET 不完整（可能只发布过一次，无上一版可回滚）"

log "回滚：$CURRENT_LINK → $TARGET，重启"
ssh "$SSH_HOST" "
  set -e
  sudo ln -sfn $TARGET ${CURRENT_LINK}.new
  sudo mv -Tf ${CURRENT_LINK}.new $CURRENT_LINK
  sudo systemctl restart $SERVICE
"

log "健康检查"
OK=0
for i in $(seq 1 20); do
  if ssh "$SSH_HOST" "curl -fsS $HEALTH_URL >/dev/null 2>&1"; then OK=1; break; fi
  sleep 2
done
[ "$OK" -eq 1 ] && log "✓ 已回滚到 $TARGET，健康 UP" || die "回滚后健康检查失败，查 journalctl -u $SERVICE"
