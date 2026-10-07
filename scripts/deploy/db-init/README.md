# 线上 DB 初始化（Flyway V1 + 种子数据）

迁移已收敛为单一 `V1__init.sql`（历史 V1–V20 固化为初始 schema，见 release.md 决策 15）。
线上初始化**直接走 Flyway**，与本地同一份文件 → checksum 天然一致，无需 baseline。

## 步骤（空库）

```bash
createdb <目标库>
# 在仓库根执行（DB 连接指向线上库）：
./gradlew :core-api:flywayMigrate        # 应用 V1，建 26 张表 + flyway_schema_history
psql -d <目标库> -f data_seed.sql        # 种子数据（COPY 格式，必须用 psql）
```

## data_seed.sql（含敏感数据，已 gitignore，勿提交）

保留数据的 3 张表：
- `core_ai_apikey`（AI key 池，真实 key）
- `core_project_info`（app 定义）
- `core_project_serverconfig`（per-project 配置）

**已预置线上值**：`challengeSecret`（线上独立值，备份在 `scripts/.attest_secret_prod`）、
`ios.env=production`、`fcm_config`（ifmix-antique 服务账号）。
导入后核对这三项；后续变更直接改线上 DB（进程内缓存，重启生效）。

## 后续发布

正常追加 `V2__xxx.sql` 递增迁移，`flywayMigrate` 两边（本地/线上）应用同一文件，无需任何 baseline。
