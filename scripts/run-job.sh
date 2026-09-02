#!/usr/bin/env bash
#
# core-job 单次跑批启动脚本（Unix cron 外部触发）。
#
# 用法：
#   scripts/run-job.sh <jobName>
# 例：
#   scripts/run-job.sh anonymousCleanup
#
# 退出码（来自 JobDispatcher）：
#   0 = Job COMPLETED
#   1 = Job 运行但未成功（BatchStatus != COMPLETED）
#   2 = 入参错误（缺 job 名 / 未知 job 名）
#
# flock -n 防重叠：同名 job 上一次未跑完时，本次直接跳过（不排队），避免叠跑同一清理任务。
# 每个 job 名一把独立锁，不同 job 互不阻塞。

set -euo pipefail

JOB_NAME="${1:-}"
if [[ -z "$JOB_NAME" ]]; then
  echo "usage: $0 <jobName>" >&2
  exit 2
fi

# 可按部署环境覆盖：JAR 路径与 JVM 参数。
JAR="${CORE_JOB_JAR:-/opt/ifmix/core-job.jar}"
JAVA_BIN="${JAVA_BIN:-java}"
LOCK_DIR="${CORE_JOB_LOCK_DIR:-/tmp}"
LOCK_FILE="${LOCK_DIR}/ifmix-core-job-${JOB_NAME}.lock"

# flock -n：拿不到锁立即退出（上一轮同名 job 还在跑）。用 -E 75 让「锁被占用而跳过」返回专用码 75，
# 与 Job 自身失败（JobDispatcher 的 1）区分开，避免 cron 告警把「正常跳过」误判成「Job 失败」。
exec flock -n -E 75 "$LOCK_FILE" \
  "$JAVA_BIN" -jar "$JAR" "--job.name=${JOB_NAME}" || {
    rc=$?
    if [[ $rc -eq 75 ]]; then
      echo "core-job[$JOB_NAME]: 上一轮仍在运行，本轮被 flock 跳过（不告警）" >&2
      exit 0   # 跳过视为正常，不惊动 cron 告警
    fi
    echo "core-job[$JOB_NAME]: 失败 rc=$rc（1=Job 未 COMPLETED，2=入参错误）" >&2
    exit $rc
  }

# ---------------------------------------------------------------------------
# crontab 示例（crontab -e）：
#
#   # 每天 03:30 跑匿名清理；输出追加到日志，flock 已防重叠。
#   30 3 * * * /opt/ifmix/scripts/run-job.sh anonymousCleanup >> /var/log/ifmix/core-job.log 2>&1
#
# 说明：
#   - cron 负责调度周期；进程跑完即退出，非常驻（保留 Spring Batch 执行历史/断点在 core_job_local）。
#   - flock -n 保证同名 job 不叠跑；单实例部署下即天然单副本。
#   - 退出码：0=成功或本轮被跳过；1=Job 失败（需告警）；2=入参错误（部署/脚本 bug）。
# ---------------------------------------------------------------------------
