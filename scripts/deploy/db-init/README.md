# 线上初始 DB（schema + 种子数据）

来源：本地 `core_api_local`（v1.0.6，V1–V20 全部应用后的最终 schema），2026-10-07 导出。

## 文件

- `schema.sql` — 纯表结构（26 张业务表，**不含** `flyway_schema_history`）
- `data_seed.sql` — 保留数据的 3 张表：
  - `core_ai_apikey`（AI key 池，真实 key，3169 行）
  - `core_project_info`（app 定义，1 行：antique）
  - `core_project_serverconfig`（per-project 服务端配置，1 行）

其余表为运行时数据（install/customer/scan/…），不随初始版本导入。

## 导入（空库，按顺序）

```bash
createdb <目标库>
psql -d <目标库> -f schema.sql
psql -d <目标库> -f data_seed.sql   # COPY 格式，必须在 psql 里执行
```

## ⚠️ 导入后必须处理的本地开发值（core_project_serverconfig）

| 字段 | 本地值 | 线上动作 |
|---|---|---|
| `app_attest_config.challengeSecret` | 本地生成的 dev secret | **替换**：`openssl rand -base64 32` 生成线上独立值，勿复用 |
| `app_attest_config.ios.env` | `development` | 按构建类型改：TestFlight/App Store=`production` |
| `fcm_config` | 本机 Firebase 凭据 | 替换为线上项目的 FCM 凭据 |

## Flyway 后续迁移

线上库 schema 已是 V20 终态、无 `flyway_schema_history`。后续发布用 `flywayMigrate` 前先 baseline：

```
spring.flyway.baseline-on-migrate=true（或手动 baseline-version=20）
```

否则 Flyway 会尝试从 V1 重放导致冲突。
