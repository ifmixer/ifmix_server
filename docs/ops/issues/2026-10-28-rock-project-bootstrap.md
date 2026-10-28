# 岩石扫描器（projectId=rock）接入 —— 项目数据初始化

状态：✅ **本地库已执行**（2026-10-28）；生产环境待执行
日期：2026-10-28
相关：代码分支 `rc/tcg-coin-rock-toy`（提示词按 projectId 选择 + premium 直写列，与 tcg/coin/toy 同构）

## 现象 / 背景

第四个 app（岩石识别）接入同一套后端。代码与提示词已在 rc 分支就绪
（`rockid-scan-system-{basic,premium}_v1.md` + `app.ai.prompt.projects.rock`），
本文档补齐项目数据初始化。命名决策（2026-10-28）：**projectId 用短名 `rock`**，
产品/仓库/目录名仍叫 rockid（`rock_rn/apps/rockid`），提示词文件前缀沿用 `rockid-scan-system`。

## 命名对照

| 项 | 值 |
| --- | --- |
| `projectId` | `rock`（客户端 `EXPO_PUBLIC_PROJECT_ID`、服务端 `app.ai.prompt.projects.rock`、DB 三处一致） |
| bundleId / package | `com.ifmix.rock` |
| Firebase 项目 | `ifmix-rock` |
| 提示词文件 | `rockid-scan-system-{basic,premium}_v1.md`（前缀未随 projectId 改） |
| `basic_result` 根键 | `object_overview`（同 tcg/toy/coin） |

> ⚠️ projectId 是 `rock` 不是 `rockid`；yml key 拼错会**静默回落 antique 提示词**（不报错）。

## 需要写入的两张表

### 1. `core_project_info`

```sql
INSERT INTO core_project_info (id, name, description)
VALUES ('rock', 'Rock Identifier', 'AI rock & mineral identification and valuation')
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, description=EXCLUDED.description, updated_at=now();
```

`id` 必须匹配 `^[a-z][a-z0-9-]{2,29}$`（`rock` 合法）。

### 2. `core_project_serverconfig`（FCM）

`fcm_config` 存**整个 Firebase service account JSON**。

凭据来源：`/Users/jason/ai/myprojects/ai_scan/rock/rock_workspace/ifmix-rock-firebase-adminsdk-fbsvc-274ecc4bc8.json`
- `project_id` = `ifmix-rock`
- `client_email` = `firebase-adminsdk-fbsvc@ifmix-rock.iam.gserviceaccount.com`

⚠️ **不要把私钥写进仓库**。执行时从本地文件读入（`psql -c` 不支持 `:'var'` 变量替换，必须走 stdin）：

```bash
SA=$(cat /Users/jason/ai/myprojects/ai_scan/rock/rock_workspace/ifmix-rock-firebase-adminsdk-fbsvc-274ecc4bc8.json)
psql "$DB_URL" -v sa="$SA" <<'SQL'
INSERT INTO core_project_serverconfig (id, project_id, fcm_config, created_at, updated_at)
VALUES (gen_random_uuid(), 'rock', :'sa'::jsonb, now(), now())
ON CONFLICT (project_id) DO UPDATE SET fcm_config=EXCLUDED.fcm_config, updated_at=now();
SQL
```

**生效方式**：`FirebaseAppRegistry` 按 projectId 懒加载并缓存，**重启生效**。

### 3. `core_project_configrevision`（IAP）

**未建，v1 不需要。** 同 coin/toy：客户端 entitlement 读 Superwall 的 `subscriptionStatus`。

### 4. attest

**未配置**（`app_attest_config` 保持 null）。服务端 `app.attest.global-enabled: false`，
客户端两侧全关。将来启用时照 toyscan 施工单格式填（`bundleId: com.ifmix.rock`，teamId 待填）。

## 本地执行记录（core_api_local，2026-10-28）

- `core_project_info` 插入 `rock` / `Rock Identifier` / `AI rock & mineral identification and valuation`
- `core_project_serverconfig` 插入 `rock` 的 `fcm_config`（`ifmix-rock` service account，
  `ON CONFLICT (project_id)` 幂等写法）

验证查询：

```sql
select project_id, fcm_config->>'project_id', fcm_config->>'client_email',
       (fcm_config ? 'private_key') as has_key
from core_project_serverconfig where project_id = 'rock';
```

## 执行步骤（生产环境）

0. **先跑 Flyway 迁移**（若尚未跑）：`V2__scan_record_premium_result.sql`。四个 app 共用，跑过一次即可。
1. 确认含 `app.ai.prompt.projects.rock` 的版本已部署（否则请求会静默回落 antique 提示词）。
2. 按上面的 SQL 写入两张表。
3. 重启 core-api。
4. 冒烟：
   - 客户端用 `EXPO_PUBLIC_PROJECT_ID=rock` 启动，扫一块岩石；
   - 服务端日志应有 `projectId=rock`；
   - 返回结构里 `basic_result.object_overview` 有值（不是古董的字段）；
   - `core_ai_scanrecord.premium_result` 非空（会员/成功路径）；
   - `core_ai_deepresearch` **不应**新增行；
   - 推送到达且有图。

## 回滚

```sql
DELETE FROM core_project_serverconfig WHERE project_id = 'rock';
DELETE FROM core_project_info WHERE id = 'rock';
```

历史记录按 `project_id='rock'` 隔离，不删不影响其他 app。

## 排障要点

| 症状 | 可能原因 |
| --- | --- |
| 扫描返回古董结构 | 服务端未部署含 `projects.rock` 的版本，或 yml key 拼成 `rockid` → 静默回落 antique |
| `Classpath resource not found: prompts/rockid-scan-system-premium_v1.md` | 提示词没打进 jar（`resources/prompts/` 下应有两份）；文件前缀是 `rockid-scan-system` |
| premium 一直为空 | `scan_status.status` 不是 SUCCESS/PARTIAL（刻意拦掉）；或迁移 V2 没跑 |
| 推送收不到 | `fcm_config` 缺失/写错；或改完没重启（懒加载缓存）；或客户端 bundleId 与 Firebase 不一致 |
| iOS 推送静默失败 | `GoogleService-Info.plist` 的 `BUNDLE_ID` 必须是 `com.ifmix.rock` |
| `invalid projectId format` | `id` 不匹配 `^[a-z][a-z0-9-]{2,29}$` |
