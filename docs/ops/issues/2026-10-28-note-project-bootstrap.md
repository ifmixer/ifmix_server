# Banknote Scanner(projectId=note)接入 —— 项目数据初始化

状态:⏳ **未执行**(本地库与生产库均待执行;代码分支 `feature/note-prompt` 施工中)
日期:2026-10-28
相关:客户端设计 `/Users/jason/ai/myprojects/ai_scan/note/note_workspace/docs/noteapp-design.md` §5;
先例 [2026-10-27-coin-project-bootstrap](2026-10-27-coin-project-bootstrap.md)(本单结构沿用其对照)

## 背景

第五个 app(纸币扫描器)接入同一套后端。代码侧在 worktree `.worktrees/2026-10-28-note-prompt`
(分支 `feature/note-prompt`,基于 `rc/tcg-coin-rock-toy`),改动为**纯增量**:

| 文件 | 状态 |
| --- | --- |
| `application.yml` 的 `app.ai.prompt.projects.note` | ✅ 已加(`NOTE_SCAN_PROMPT_VERSION:v1`,`scan-uses-full-template: true`) |
| `prompts/note-scan-system-basic_v1.md` | ✅ 已写(606 行,basic-only 截断降级模板) |
| `prompts/note-scan-system-premium_v1.md` | ⏳ **待写**(v1 主用:一次产出 basic+premium) |

服务端代码零改动:prompt 按 projectId 查表(ScanPromptRegistry)、单次产出 premium、
截断降级、max-output-tokens、推送取图 MAIN→FRONT 回退均已在主线。

## 执行清单(本地开发库 core_api_local,后续生产 app_us1/core_api)

### 1. `core_project_info`

```sql
INSERT INTO core_project_info (id, name, description)
VALUES ('note', 'Banknote Scanner', 'AI banknote identification, grading and valuation')
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, description=EXCLUDED.description, updated_at=now();
```

`id='note'` 与客户端 `EXPO_PUBLIC_PROJECT_ID=note`、服务端 `app.ai.prompt.projects.note` 三处一致。

### 2. `core_project_serverconfig`(FCM)

**暂缓:Firebase 项目 `ifmix-note` 建好前无凭据可写。** 建好后按 coin 施工单同样方式写入
(存整个 service account JSON,`psql -c` 不支持 `:'var'` 变量替换,必须走 stdin heredoc):

```bash
SA=$(cat /path/to/ifmix-note-firebase-adminsdk-xxx.json)
psql "$DB_URL" -v sa="$SA" <<'SQL'
INSERT INTO core_project_serverconfig (id, project_id, fcm_config, created_at, updated_at)
VALUES (gen_random_uuid(), 'note', :'sa'::jsonb, now(), now())
ON CONFLICT (project_id) DO UPDATE SET fcm_config=EXCLUDED.fcm_config, updated_at=now();
SQL
```

Firebase 侧前置:iOS App bundleId `com.ifmix.note` + Android App + APNs key。
**不写此行时推送静默关闭,不影响扫描链路**(install/鉴权/上传/识别均正常)。

### 3. `core_project_configrevision`(Apple IAP)

**不建,v1 不需要**(Superwall entitlement 走客户端 `subscriptionStatus`,同 coin)。
缺这行时 `m_pay_iap_verify` 抛 `APP_CONFIG_MISSING`,v1 不可达。

### 4. attest

**不配置**(`app_attest_config` 保持 null)。服务端 `app.attest.global-enabled: false`,
客户端 noteapp 的 `DEFAULT_ATTEST_ENABLED` 保持 coin 复制来的 `false`,两侧都关,无无效请求。

## 前置依赖(执行前确认)

1. **`feature/note-prompt` 已部署**(两个 prompt 文件齐了再上线;只有 yml 没有 prompt 文件时,
   note 项目的首次扫描会在懒加载 prompt 时失败)。
2. 客户端 `google-services/` 已从占位换成 ifmix-note 的正式配置(bundleId 与 Firebase 注册一致)。

## 回滚

```sql
DELETE FROM core_project_serverconfig WHERE project_id = 'note';
DELETE FROM core_project_info WHERE id = 'note';
```

历史扫描记录按 `project_id='note'` 隔离,不删不影响其他 app。

## 排障要点

| 症状 | 可能原因 |
| --- | --- |
| 扫描返回古董结构 | 服务端 `app.ai.prompt.projects.note` 未部署或拼错 → 回落 antique(**静默**,不报错) |
| `Classpath resource not found: prompts/note-scan-system-premium_v1.md` | premium prompt 文件没写/没打进 jar |
| premium 一直为空 | premium prompt 尚未部署;或 `scan-uses-full-template: true` 漏配;或扫描 status 不是 SUCCESS/PARTIAL |
| 推送收不到 | `fcm_config` 未写或写错(本单 §2 暂缓中是预期状态);或客户端 bundleId 与 Firebase 不一致(应为 `com.ifmix.note`) |
| `invalid projectId format` | `core_project_info.id` 需匹配 `^[a-z][a-z0-9-]{2,29}$` |

## 客户端侧配套(状态)

- ✅ `bundleIdentifier` / `package` = `com.ifmix.note`、`scheme=ifmix-note`、`DEFAULT_PROJECT_ID='note'`
- ✅ 键前缀 `note.*`(kv/wipe)、`.env.example` `EXPO_PUBLIC_PROJECT_ID=note`
- ⏳ `google-services/` 为 coin 复制来的**占位**,上线前必须替换
- ⏳ 领域层组件迁移、i18n、图标 —— 见 `note_workspace/docs/noteapp-progress.md`
