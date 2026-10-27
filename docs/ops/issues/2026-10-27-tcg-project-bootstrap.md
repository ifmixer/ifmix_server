# TCG 扫描器（projectId=tcg）接入 —— 项目数据初始化

状态：⏳ **未执行**（等客户端/服务端联调时再做）
日期：2026-10-27
相关：[tcg-prompt-per-project](../design/ai/tcg-prompt-per-project.md)

## 现象 / 背景

第二个 app（TCG 卡牌扫描器）接入同一套后端。代码侧已由 `feature/tcgcard-prompt` 分支完成（提示词按 projectId 选择 + 内联 premium），但**项目数据还没写进数据库**，所以 tcg 的请求会因为找不到项目配置而失败或走不到正确分支。

本文档是这部分运维动作的施工单，项目短期搁置，恢复时照此执行。

## 需要写入的三张表

### 1. `core_project_info`

```sql
INSERT INTO core_project_info (id, name, description)
VALUES ('tcg', 'TCG Card Scanner', 'TCG card identification & valuation')
ON CONFLICT (id) DO NOTHING;
```

`id` 必须是 `tcg`（不是 `tcgcard`）—— 客户端 `EXPO_PUBLIC_PROJECT_ID=tcg`，服务端 `app.ai.prompt.projects.tcg` 也用它。格式需匹配 `^[a-z][a-z0-9-]{2,29}$`。

### 2. `core_project_serverconfig`（FCM）

`fcm_config` 存**整个 Firebase service account JSON**。

凭据来源：`/Users/jason/ai/myprojects/ai_scan/tcg/tcg_workspace/ifmix-tcg-firebase-adminsdk-fbsvc-5e90724914.json`
- `project_id` = `ifmix-tcg`
- `client_email` = `firebase-adminsdk-fbsvc@ifmix-tcg.iam.gserviceaccount.com`

```sql
INSERT INTO core_project_serverconfig (id, project_id, fcm_config, created_at, updated_at)
VALUES (gen_random_uuid(), 'tcg', '<service account JSON>'::jsonb, now(), now());
```

⚠️ **不要把私钥写进仓库**（含本文件）。执行时从本地文件读入：

```bash
SA=$(cat /path/to/ifmix-tcg-firebase-adminsdk-fbsvc-5e90724914.json)
psql "$DB_URL" -c "INSERT INTO core_project_serverconfig (id, project_id, fcm_config, created_at, updated_at)
  VALUES (gen_random_uuid(), 'tcg', \$json\$$SA\$json\$::jsonb, now(), now());"
```

**生效方式**：`FirebaseAppRegistry` 按 projectId 懒加载并缓存，**重启生效**（凭据变更后需重启，本期不做热更新）。

### 3. `core_project_configrevision`（IAP）

**v1 可以不建。** 理由：

- 客户端 entitlement 读的是 Superwall 的 `subscriptionStatus`（`useIsPro`），不依赖服务端验购。
- SDK 的 `iapVerify` 在 `useOpenPaywall` 里只是预留注入位，hook 本体**不调用**。
- 没有这行时 `m_pay_iap_verify` 会抛 `APP_CONFIG_MISSING`，但 v1 不会调它。

要做服务端验购时再补（`apple.appAppleId` / `issuerId` / `keyId` / `privateKey`）。

### 4. attest（App Attest）

**v1 不启用。** 服务端 `app.attest.global-enabled: false`，客户端 `DEFAULT_ATTEST_ENABLED=false`。

将来启用时 `core_project_serverconfig.app_attest_config` 填：

```json
{
  "ios": { "env": "production", "teamId": "Z2W6FKU98J", "bundleId": "com.ifmix.tcg" },
  "mode": "OBSERVE",
  "challengeSecret": "<生成一个随机值>"
}
```

⚠️ `bundleId` 是 **`com.ifmix.tcg`**（不是 `ifmix.tcgcard`）—— 与 `app.config.js` 和 Firebase 配置一致。

## 执行步骤

1. 确认分支 `feature/tcgcard-prompt` 已部署（否则代码里没有 `app.ai.prompt.projects.tcg`）。
2. 按上面的 SQL 写入 `core_project_info` 与 `core_project_serverconfig`。
3. 重启 core-api。
4. 冒烟：
   - 客户端用 `EXPO_PUBLIC_PROJECT_ID=tcg` 启动，扫一张卡；
   - 服务端日志应有 `projectId=tcg`；
   - 数据库 `core_ai_deepresearch` 应出现一行 `status=30` 且 `premium_result` 非空；
   - `scan_record.latest_deep_research_id` 指向该行。

## 回滚

```sql
DELETE FROM core_project_serverconfig WHERE project_id = 'tcg';
DELETE FROM core_project_info WHERE id = 'tcg';
```

历史扫描记录（`core_ai_scanrecord` / `core_ai_deepresearch`）按 `project_id='tcg'` 隔离，不删不影响其他 app。真要清：

```sql
DELETE FROM core_ai_deepresearch WHERE project_id = 'tcg';
DELETE FROM core_ai_scanrecord   WHERE project_id = 'tcg';
```

## 排障要点

| 症状 | 可能原因 |
| --- | --- |
| 扫描返回古董结构 | 服务端代码未部署，或 `app.ai.prompt.projects.tcg` 拼错 → 回落了 antique |
| `Classpath resource not found: prompts/tcgcard-scan-system-premium_v1.md` | 提示词文件没打进 jar（`resources/prompts/` 下应有两份） |
| premium 一直为空 | `scan_status.status` 不是 SUCCESS/PARTIAL（设计 §4.5 刻意拦掉）；或 prompt 没返回 `premium_result` |
| 推送收不到 | `fcm_config` 缺失 / 写错；或改完没重启（懒加载缓存） |
| `invalid projectId format` | `id` 不匹配 `^[a-z][a-z0-9-]{2,29}$` |
