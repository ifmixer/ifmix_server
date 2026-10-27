# 玩具扫描器（projectId=toyscan）接入 —— 项目数据初始化

状态：✅ **本地（core_api_local）已执行** 2026-10-27；生产环境待部署时执行
日期：2026-10-27
相关：[toyscan 设计](../../../ai_scan/toy/toy_workspace/docs/superpowers/specs/2026-10-27-toyscan-design.md)

## 现象 / 背景

第三个 app（玩具扫描器）接入同一套后端。代码侧已由 `feature/toyscan-prompt` 分支完成
（基于 `rc/tcg-coin-rock-toy`，复用 tcg 的提示词按 projectId 选择 + premium 直写列），
但**项目数据还没写进数据库**，所以 toyscan 的请求会因为找不到项目配置而失败。

## 与 tcg 施工单的差异

| 项 | tcg | toyscan |
| --- | --- | --- |
| `projectId` | `tcg` | `toyscan` |
| bundleId / package | `com.ifmix.tcg` | `com.ifmix.toy` |
| Firebase 项目 | `ifmix-tcg` | `ifmix-toy` |
| 提示词文件 | `tcgcard-scan-system-{basic,premium}_v1.md` | `toyscan-scan-system-{basic,premium}_v1.md` |
| `basic_result` 根键 | `object_overview` | `object_overview`（同） |

> ⚠️ **projectId 是 `toyscan`，bundleId 是 `com.ifmix.toy`** —— 两者不同，不要互相套用。
> projectId 由客户端 `EXPO_PUBLIC_PROJECT_ID` 与服务端 `app.ai.prompt.projects` 决定；
> bundleId 由 Firebase 里注册的 app 决定。

## 需要写入的两张表

### 1. `core_project_info`

```sql
INSERT INTO core_project_info (id, name, description)
VALUES ('toyscan', 'Toy Scanner', 'Toy identification & valuation')
ON CONFLICT (id) DO NOTHING;
```

`id` 必须匹配 `^[a-z][a-z0-9-]{2,29}$`。

### 2. `core_project_serverconfig`（FCM）

`fcm_config` 存**整个 Firebase service account JSON**。

凭据来源：`/Users/jason/ai/myprojects/ai_scan/toy/toy_workspace/ifmix-toy-firebase-adminsdk-fbsvc-fad73aabd6.json`
- `type` = `service_account`
- `project_id` = `ifmix-toy`
- `client_email` = `firebase-adminsdk-fbsvc@ifmix-toy.iam.gserviceaccount.com`

⚠️ **不要把私钥写进仓库**。执行时从本地文件读入：

```bash
SA=$(cat /Users/jason/ai/myprojects/ai_scan/toy/toy_workspace/ifmix-toy-firebase-adminsdk-fbsvc-fad73aabd6.json)
psql "$DB_URL" -c "INSERT INTO core_project_serverconfig (id, project_id, fcm_config, created_at, updated_at)
  VALUES (gen_random_uuid(), 'toyscan', \$json\$$SA\$json\$::jsonb, now(), now());"
```

**生效方式**：`FirebaseAppRegistry` 按 projectId 懒加载并缓存，**重启生效**。

### 3. `core_project_configrevision`（IAP）

**v1 不建。** 同 tcg：客户端 entitlement 读 Superwall 的 `subscriptionStatus`，
服务端 `iapVerify` 在 v1 不调用。

### 4. attest（App Attest）

**v1 不启用。** 服务端 `app.attest.global-enabled: false`，客户端 `DEFAULT_ATTEST_ENABLED=false`。

将来启用时填：

```json
{
  "ios": { "env": "production", "teamId": "Z2W6FKU98J", "bundleId": "com.ifmix.toy" },
  "mode": "OBSERVE",
  "challengeSecret": "<生成一个随机值>"
}
```

## 本地执行记录（2026-10-27）

已在 `core_api_local` 完成：

- `flywayMigrate`：v1 → v2（`core_ai_scanrecord.premium_result` jsonb 已加）
- `core_project_info` 插入 `toyscan` / `Toy Scanner`
- `core_project_serverconfig` 插入 `toyscan` 的 `fcm_config`（`ifmix-toy` service account，私钥 1704 字符已写入）

验证查询：

```sql
select project_id, fcm_config->>'project_id', fcm_config->>'client_email',
       (fcm_config ? 'private_key') as has_key
from core_project_serverconfig where project_id = 'toyscan';
```

## 执行步骤（生产环境）

0. **先跑 Flyway 迁移**（若尚未跑）：`V2__scan_record_premium_result.sql` 给
   `core_ai_scanrecord` 加 `premium_result` 列。这条与 tcg 共用，跑过一次即可。
   ```bash
   DB_URL=jdbc:postgresql://localhost:15432/core_api DB_USER=app DB_PASSWORD=<密码> \
     ./gradlew :core-api:flywayMigrate
   ```
1. 确认 `feature/toyscan-prompt` 已部署（否则 `app.ai.prompt.projects.toyscan` 不存在，
   请求会回落到 antique 提示词）。
2. 按上面的 SQL 写入两张表。
3. 重启 core-api。
4. 冒烟：
   - 客户端用 `EXPO_PUBLIC_PROJECT_ID=toyscan` 启动，扫一件玩具；
   - 服务端日志应有 `projectId=toyscan`；
   - 返回结构里 `basic_result.object_overview` 有值（不是古董的字段）；
   - `core_ai_scanrecord.premium_result` 非空；
   - `core_ai_deepresearch` **不应**新增行。

## 回滚

```sql
DELETE FROM core_project_serverconfig WHERE project_id = 'toyscan';
DELETE FROM core_project_info WHERE id = 'toyscan';
```

历史记录按 `project_id='toyscan'` 隔离，不删不影响其他 app。真要清：

```sql
DELETE FROM core_ai_scanrecord WHERE project_id = 'toyscan';
```

## 排障要点

| 症状 | 可能原因 |
| --- | --- |
| 扫描返回古董结构 | 服务端未部署 toyscan 分支，或 `app.ai.prompt.projects.toyscan` 拼错 → 回落 antique |
| `Classpath resource not found: prompts/toyscan-scan-system-premium_v1.md` | 提示词没打进 jar（`resources/prompts/` 下应有两份） |
| premium 一直为空 | `scan_status.status` 不是 SUCCESS/PARTIAL（刻意拦掉）；或 prompt 没返回 `premium_result`；或迁移 V2 没跑 |
| 推送收不到 | `fcm_config` 缺失/写错；或改完没重启（懒加载缓存）；或客户端 bundleId 与 Firebase 不一致 |
| iOS 推送静默失败 | `GoogleService-Info.plist` 的 `BUNDLE_ID` 必须是 `com.ifmix.toy`（与 `app.config.js` 一致） |
| `invalid projectId format` | `id` 不匹配 `^[a-z][a-z0-9-]{2,29}$` |
