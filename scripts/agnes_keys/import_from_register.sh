#!/usr/bin/env bash
# 从 agnes_register.accounts 导入 keys 到 core_api_local.ai_agnes_key。
#
# 规则：
#   personal: personal_api_key 非空 -> key=personal_api_key, email=reg_email,               type=10
#   ent:      ent_api_key 非空      -> key=ent_api_key,      email=COALESCE(ent_email,reg_email), type=20
#   两个 key 都空 -> 跳过。只看 key 有无，不按 status 过滤。
#
# 跨库：源/目标是同实例不同 database。用 psql \copy 导出 CSV 到临时文件，再导入目标库临时表。
# 目标 id 用 gen_random_uuid()；key 唯一 -> ON CONFLICT (key) DO NOTHING（幂等，可重跑）。
set -euo pipefail

PGHOST="${PGHOST:-localhost}"
PGUSER="${PGUSER:-postgres}"
export PGPASSWORD="${PGPASSWORD:-postgres}"
SRC_DB="${SRC_DB:-agnes_register}"
DST_DB="${DST_DB:-core_api_local}"

TMP_CSV="$(mktemp -t agnes_keys.XXXXXX.csv)"
trap 'rm -f "$TMP_CSV"' EXIT

echo "1/3 导出 $SRC_DB.accounts -> $TMP_CSV"
psql -h "$PGHOST" -U "$PGUSER" -d "$SRC_DB" -v ON_ERROR_STOP=1 -q -c "\copy (
  SELECT personal_api_key AS key, reg_email AS email, 10 AS type
  FROM accounts WHERE personal_api_key IS NOT NULL AND personal_api_key <> ''
  UNION ALL
  SELECT ent_api_key AS key, COALESCE(NULLIF(ent_email,''), reg_email) AS email, 20 AS type
  FROM accounts WHERE ent_api_key IS NOT NULL AND ent_api_key <> ''
) TO '$TMP_CSV' WITH (FORMAT csv)"

echo "2/3 源行数: $(wc -l < "$TMP_CSV" | tr -d ' ')"

echo "3/3 导入 -> $DST_DB.ai_agnes_key"
psql -h "$PGHOST" -U "$PGUSER" -d "$DST_DB" -v ON_ERROR_STOP=1 -q --single-transaction <<SQL
CREATE TEMP TABLE _agnes_import (key text, email text, type smallint) ON COMMIT DROP;
\copy _agnes_import (key, email, type) FROM '$TMP_CSV' WITH (FORMAT csv)
INSERT INTO ai_agnes_key (id, key, email, type)
SELECT gen_random_uuid(), key, email, type
FROM (
  -- 源内同一 key 可能重复(如 ent key 挂两个账号)，去重取任意一行
  SELECT DISTINCT ON (key) key, email, type FROM _agnes_import ORDER BY key
) d
ON CONFLICT (key) DO NOTHING;
SQL

echo "完成。核对："
psql -h "$PGHOST" -U "$PGUSER" -d "$DST_DB" -q -c "SELECT type, count(*) FROM ai_agnes_key GROUP BY type ORDER BY type;"
