#!/bin/bash
# start.sh — 启动 ifmix_server (local profile)
# 自动 kill 占用 3001 端口的进程后再启动

PORT=3001

# 本机 JVM 代理：Firebase/FCM 等 Google 服务需走代理（JVM 不读 shell 的 http_proxy，必须用系统属性）。
# nonProxyHosts 用 JVM 格式（| 分隔、* 通配、不支持 CIDR）：排除本机 DB/Redis 与 Agnes AI（直连）。
# 不需要代理时：PROXY_HOST= ./scripts/restart.sh （留空即禁用）。
PROXY_HOST="${PROXY_HOST:-127.0.0.1}"
PROXY_PORT="${PROXY_PORT:-10808}"
PROXY_OPTS=""
if [ -n "$PROXY_HOST" ]; then
  PROXY_OPTS="-Dhttps.proxyHost=$PROXY_HOST -Dhttps.proxyPort=$PROXY_PORT -Dhttp.proxyHost=$PROXY_HOST -Dhttp.proxyPort=$PROXY_PORT -Dhttp.nonProxyHosts=localhost|127.0.0.1|*.agnes-ai.com"
fi

# 查找并 kill 占用端口的进程
PID=$(lsof -ti :$PORT 2>/dev/null)
if [ -n "$PID" ]; then
  echo "Killing process $PID on port $PORT..."
  kill -9 $PID 2>/dev/null
  sleep 1
fi

PROXY_LABEL="none"
if [ -n "$PROXY_HOST" ]; then
  PROXY_LABEL="$PROXY_HOST:$PROXY_PORT"
fi

echo "Starting ifmix_server on port $PORT... (proxy: $PROXY_LABEL)"
JAVA_TOOL_OPTIONS="-Xms128m -Xmx512m -XX:+HeapDumpOnOutOfMemoryError $PROXY_OPTS" SPRING_PROFILES_ACTIVE=local ./gradlew :core-api:bootRun
