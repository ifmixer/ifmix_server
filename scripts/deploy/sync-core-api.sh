#!/usr/bin/env bash
#
# core-api 增量部署（Spring Boot layered jar + 双目录 + 软链原子切换）。
#
# 收益：日常发布只 rsync 变化的 application 层（~2.3MB），140MB 依赖层在依赖没升级时
# 自动跳过（--checksum 按内容比对）。相比整包 scp（142MB）省 ~98% 传输。
#
# 机制：服务器上 core-api-a / core-api-b 两个目录，core-api-current 软链指向 active。
#   发布 = rsync 到 idle 目录 → 校验完整 → 原子切软链 → 重启 → 健康检查（失败自动回切）。
#   回滚 = scripts/deploy/rollback-core-api.sh（软链切回另一个目录）。
#
# 用法：
#   scripts/deploy/sync-core-api.sh            # 增量发布（默认）
#   scripts/deploy/sync-core-api.sh --full     # 兜底：整包 scp fat jar（老方式，依赖大变/想稳时用）
#   scripts/deploy/sync-core-api.sh --no-build # 跳过 gradle 构建，用现有产物
#
set -euo pipefail

# ── 配置 ──
SSH_HOST="${DEPLOY_SSH_HOST:-app_us1}"
REMOTE_BASE="/data/app/core-api"
APP="core-api"
CURRENT_LINK="$REMOTE_BASE/current"               # 软链：指向 a 或 b
DIR_A="$REMOTE_BASE/a"
DIR_B="$REMOTE_BASE/b"
SERVICE="app-core-api"
HEALTH_URL="http://localhost:3001/actuator/health"
STAGING="/tmp/$APP-sync"                           # ubuntu 可写的中转区（rsync 落点）
APP_USER="app"
APP_GROUP="app"

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOCAL_EXTRACT="$PROJECT_ROOT/core-api/build/deploy-extract/$APP"

MODE="incremental"
DO_BUILD=1
for arg in "$@"; do
  case "$arg" in
    --full) MODE="full" ;;
    --no-build) DO_BUILD=0 ;;
    *) echo "unknown arg: $arg" >&2; exit 2 ;;
  esac
done

log() { printf '\033[1;34m▶ %s\033[0m\n' "$*"; }
die() { printf '\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

# ── 1. 构建 ──
if [ "$DO_BUILD" -eq 1 ]; then
  log "本地构建 bootJar"
  (cd "$PROJECT_ROOT" && ./gradlew :core-api:bootJar -q)
fi
JAR="$(ls "$PROJECT_ROOT"/core-api/build/libs/core-api-*.jar 2>/dev/null | grep -v plain | head -1)" \
  || die "找不到 fat jar，先构建"
[ -n "$JAR" ] || die "找不到 fat jar（非 -plain）"
log "产物：$JAR ($(du -h "$JAR" | cut -f1))"

# ── 兜底：整包 scp（老方式，原子 install） ──
if [ "$MODE" = "full" ]; then
  log "FULL 模式：整包 scp（原子替换单 jar）"
  scp "$JAR" "$SSH_HOST:/tmp/$APP.jar"
  ssh "$SSH_HOST" "
    set -e
    sudo install -o $APP_USER -g $APP_GROUP -m 640 /tmp/$APP.jar $REMOTE_BASE/$APP.jar
    sudo systemctl restart $SERVICE
  "
  log "FULL 发布完成（注意：full 模式跑单 jar，与增量的解压目录方式互斥，"
  log "  仅应急用；日常请用默认增量模式以维持双目录/软链状态）"
  exit 0
fi

# ── 2. 本地解压（Boot 4 tools extract：lib/ 依赖散成独立文件 + 一个瘦业务 jar） ──
# 产物：$LOCAL_EXTRACT/lib/*.jar（140MB，依赖，rsync 按文件跳过）
#       $LOCAL_EXTRACT/core-api-*.jar（~2.4MB 瘦 jar，manifest Class-Path 指向 lib/，每次变）
# 运行：java -jar core-api-*.jar（瘦 jar 自动加载 lib/）。
log "本地解压 → ${LOCAL_EXTRACT}（lib/ 依赖 + 瘦业务 jar）"
rm -rf "$LOCAL_EXTRACT"
mkdir -p "$LOCAL_EXTRACT"
( cd "$LOCAL_EXTRACT" && java -Djarmode=tools -jar "$JAR" extract --destination . >/dev/null )
THIN_JAR_NAME="$(cd "$LOCAL_EXTRACT" && ls core-api-*.jar 2>/dev/null | head -1)"
[ -n "$THIN_JAR_NAME" ] || die "解压异常：缺瘦业务 jar"
[ -d "$LOCAL_EXTRACT/lib" ] || die "解压异常：缺 lib/ 依赖目录"

# ── 3. 确定 active / idle 目录（自举：首次无软链 → 用 -a） ──
log "读取当前 active 目录"
ACTIVE_DIR="$(ssh "$SSH_HOST" "readlink -f $CURRENT_LINK 2>/dev/null || true")"
if [ "$ACTIVE_DIR" = "$DIR_A" ]; then
  IDLE_DIR="$DIR_B"
elif [ "$ACTIVE_DIR" = "$DIR_B" ]; then
  IDLE_DIR="$DIR_A"
else
  # 首次发布：无软链或指向未知，初始化到 -a
  ACTIVE_DIR=""
  IDLE_DIR="$DIR_A"
fi
log "active=${ACTIVE_DIR:-<none>}  →  写入 idle=$IDLE_DIR"

# ── 4. rsync 到中转区（ubuntu 可写），再服务器内 sudo rsync 就位到 idle ──
# 说明：rsync 直接写 app 属主目录需要 sudo rsync + 远端 sudo，麻烦；
#   改为两跳：本地 → /tmp 中转（ubuntu 属主）→ 服务器内 sudo rsync 到 idle（app 属主）。
# --checksum：按内容哈希比对，避免 gradle 重建导致 mtime 抖动而误传依赖层。
# --delete：idle 目录里上一版残留（如已删除的旧依赖）被清掉，保持与本地一致。
log "rsync 增量同步（--checksum，仅传变化文件）"
ssh "$SSH_HOST" "mkdir -p $STAGING"
# 注意：不用 --info=...（macOS 自带 openrsync 不支持，会导致命令失败）。用 -v 提供基本反馈。
rsync -avz --checksum --delete \
  -e "ssh" \
  "$LOCAL_EXTRACT/" "$SSH_HOST:$STAGING/"

log "服务器内就位到 ${IDLE_DIR}（属主 ${APP_USER}）"
ssh "$SSH_HOST" "
  set -e
  sudo mkdir -p $IDLE_DIR
  sudo rsync -a --checksum --delete $STAGING/ $IDLE_DIR/
  sudo chown -R $APP_USER:$APP_GROUP $IDLE_DIR
  # 校验就位完整
  [ -n \"\$(ls $IDLE_DIR/core-api-*.jar 2>/dev/null | head -1)\" ] || { echo 'MISSING thin jar'; exit 1; }
  [ -d $IDLE_DIR/lib ] || { echo 'MISSING lib/'; exit 1; }
"

# ── 5. 原子切软链 + 重启 ──
log "原子切换软链 ${CURRENT_LINK} → ${IDLE_DIR}，并重启"
ssh "$SSH_HOST" "
  set -e
  # ln -sfn 是原子替换软链；先建临时软链再 mv 确保原子
  sudo ln -sfn $IDLE_DIR ${CURRENT_LINK}.new
  sudo mv -Tf ${CURRENT_LINK}.new $CURRENT_LINK
  sudo systemctl restart $SERVICE
"

# ── 6. 健康检查，失败自动回切 ──
log "健康检查（最多等 40s）"
OK=0
for i in $(seq 1 20); do
  if ssh "$SSH_HOST" "curl -fsS $HEALTH_URL >/dev/null 2>&1"; then OK=1; break; fi
  sleep 2
done

if [ "$OK" -eq 1 ]; then
  log "✓ 发布成功，active=${IDLE_DIR}，健康 UP"
else
  printf '\033[1;31m✗ 健康检查失败\033[0m\n' >&2
  if [ -n "$ACTIVE_DIR" ]; then
    log "自动回切到上一版 $ACTIVE_DIR"
    ssh "$SSH_HOST" "
      sudo ln -sfn $ACTIVE_DIR ${CURRENT_LINK}.new
      sudo mv -Tf ${CURRENT_LINK}.new $CURRENT_LINK
      sudo systemctl restart $SERVICE
    "
    die "已回切到 ${ACTIVE_DIR}，请查 journalctl -u $SERVICE"
  else
    die "首次发布即失败，无可回切版本，请查 journalctl -u $SERVICE"
  fi
fi
