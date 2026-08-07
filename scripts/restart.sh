#!/bin/bash
# start.sh — 启动 ifmix_server (local profile)
# 自动 kill 占用 3001 端口的进程后再启动

PORT=3001

# 查找并 kill 占用端口的进程
PID=$(lsof -ti :$PORT 2>/dev/null)
if [ -n "$PID" ]; then
  echo "Killing process $PID on port $PORT..."
  kill -9 $PID 2>/dev/null
  sleep 1
fi

echo "Starting ifmix_server on port $PORT..."
JAVA_TOOL_OPTIONS="-Xms128m -Xmx512m -XX:+HeapDumpOnOutOfMemoryError" SPRING_PROFILES_ACTIVE=local ./gradlew :core-api:bootRun
