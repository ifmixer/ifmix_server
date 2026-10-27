# Coin Scanner（projectId=coin）接入 —— 项目数据初始化

状态：✅ **本地库已执行**（2026-10-27）；生产环境待执行
日期：2026-10-27
相关：设计 `coin_workspace/docs/superpowers/specs/2026-10-27-coinapp-design.md` §8.4；代码分支 `feature/coin-prompt-v2`

## 背景

第三个 app（硬币扫描器）接入同一套后端。代码侧在 `feature/coin-prompt-v2`（基于 `rc/tcg-coin-rock-toy`），
本次在**本地开发库** `core_api_local` 写入了项目数据。

## 执行记录（本地库 core_api_local）

### 1. `core_project_info`

```sql
INSERT INTO core_project_info (id, name, description)
VALUES ('coin', 'Coin Scanner', 'AI coin identification, grading and valuation')
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, description=EXCLUDED.description, updated_at=now();
```

`id='coin'` 与客户端 `EXPO_PUBLIC_PROJECT_ID=coin`、服务端 `app.ai.prompt.projects.coin` 三处一致。

### 2. `core_project_serverconfig`（FCM）

`fcm_config` 存**整个 Firebase service account JSON**，不是客户端那两份配置文件。

凭据来源：`/Users/jason/ai/myprojects/ai_scan/coin/coin_workspace/ifmix-coin-firebase-adminsdk-fbsvc-f777aee32d.json`

- `project_id` = `ifmix-coin`
- `client_email` = `firebase-adminsdk-fbsvc@ifmix-coin.iam.gserviceaccount.com`
- 私钥长度 1704 字符（写入后校验一致）

```bash
SA=$(cat /path/to/ifmix-coin-firebase-adminsdk-fbsvc-f777aee32d.json)
psql "$DB_URL" -v sa="$SA" <<'SQL'
INSERT INTO core_project_serverconfig (id, project_id, fcm_config, created_at, updated_at)
VALUES (gen_random_uuid(), 'coin', :'sa'::jsonb, now(), now())
ON CONFLICT (project_id) DO UPDATE SET fcm_config=EXCLUDED.fcm_config, updated_at=now();
SQL
```

> `psql -c` **不支持** `:'var'` 变量替换，必须走 stdin（heredoc）。踩过一次。

**生效方式**：`FirebaseAppRegistry` 按 projectId 懒加载并缓存，**重启生效**。

### 3. `core_project_configrevision`（Apple IAP）

**未建，v1 不需要。** 客户端 entitlement 读 Superwall 的 `subscriptionStatus`，
SDK 的 `iapVerify` 在 `useOpenPaywall` 里只是预留注入位、hook 本体不调用。
缺这行时 `m_pay_iap_verify` 抛 `APP_CONFIG_MISSING`，v1 不可达。

### 4. attest

**未配置**（`app_attest_config` 保持 null）。服务端 `app.attest.global-enabled: false`，
客户端 coinapp 的 `DEFAULT_ATTEST_ENABLED` 已改为 `false`，两侧都关。

### 5. Flyway

`V2__scan_record_premium_result.sql` 已执行（`flyway_schema_history` version=2 success=t），
`core_ai_scanrecord.premium_result` 列存在。

## 验证结果

```
   id    |     name     |  fcm_project  | has_key | key_len
---------+--------------+---------------+---------+---------
 antique | antique      | ifmix-antique | t       |    1704
 coin    | Coin Scanner | ifmix-coin    | t       |    1704
 toyscan | Toy Scanner  | ifmix-toy     | t       |    1704
```

## 生产环境待执行

按上述 SQL 在 `app_us1` 的生产库 `core_api` 执行（连库方式见
[生产接入手册](../PRODUCTION_ACCESS.md)），然后重启 `app-core-api.service`。

⚠️ 生产执行前确认 `feature/coin-prompt-v2` 已部署，否则代码里没有
`app.ai.prompt.projects.coin`，请求会静默回落 antique prompt（返回古董结构，不报错）。

## 回滚

```sql
DELETE FROM core_project_serverconfig WHERE project_id = 'coin';
DELETE FROM core_project_info WHERE id = 'coin';
```

历史扫描记录按 `project_id='coin'` 隔离，不删不影响其他 app。

## 排障要点

| 症状 | 可能原因 |
| --- | --- |
| 扫描返回古董结构 | 服务端代码未部署，或 `app.ai.prompt.projects.coin` 拼错 → 回落 antique（**静默**，不报错） |
| `Classpath resource not found: prompts/coin-scan-system-premium_v1.md` | prompt 文件没打进 jar |
| premium 一直为空 | `scan_status.status` 不是 SUCCESS/PARTIAL；或 prompt 没返回 `premium_result`；或迁移 V2 没跑 |
| 推送收不到 | `fcm_config` 缺失/写错；改完没重启（懒加载缓存）；或客户端 bundleId 与 Firebase 不一致（应为 `com.ifmix.coin`） |
| 推送无图 | coin 只有 FRONT/BACK 分类，推送取图需 MAIN→FRONT 回退（本分支已实现） |
| `invalid projectId format` | `id` 需匹配 `^[a-z][a-z0-9-]{2,29}$` |

## 客户端侧配套（已完成）

- `apps/coinapp/google-services/` 已替换为 ifmix-coin 配置（原为 antique 副本）
- `bundleIdentifier` / `package` = `com.ifmix.coin`（与 Firebase 注册一致）
- `.env.example` 的 `EXPO_PUBLIC_PROJECT_ID=coin`
