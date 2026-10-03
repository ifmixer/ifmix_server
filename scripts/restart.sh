#!/bin/bash
# start.sh — 启动 ifmix_server (local profile)
# 自动 kill 占用 3001 端口的进程后再启动

PORT=3001

# 本机 JVM 代理：Firebase/FCM 等 Google 服务需走代理（JVM 不读 shell 的 http_proxy，必须用系统属性）。
# SOCKS5 代理（8119）。注意：JVM socksProxyHost 在本地解析 DNS，若域名被污染可能异常——此为对照实验配置。
# 不需要代理时：PROXY_HOST= ./scripts/restart.sh （留空即禁用）。
PROXY_HOST="${PROXY_HOST:-127.0.0.1}"
PROXY_PORT="${PROXY_PORT:-10808}"
# 本机 DB/Redis + Agnes AI（国内可直连）绕过代理。SOCKS 只认 socksNonProxyHosts；http.nonProxyHosts 对 SOCKS 无效。
NON_PROXY="localhost|127.0.0.1|apihub.agnes-ai.com|apihub.agnes-ai.cn"
PROXY_OPTS=""
if [ -n "$PROXY_HOST" ]; then
  PROXY_OPTS="-DsocksProxyHost=$PROXY_HOST -DsocksProxyPort=$PROXY_PORT -DsocksNonProxyHosts=$NON_PROXY"
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
