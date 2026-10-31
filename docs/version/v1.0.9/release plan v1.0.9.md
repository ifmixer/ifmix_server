# Release

core-api v1.0.9 发布计划。代码基线：分支 `rc/tcg-coin-rock-toy`（相对 main 累计 16 commits）。
主题：**提示词按项目（projectId）选择 + premium 内联落库 + 一批多品类 app 接入**——本版一次性接入 4 个新 app：
**tcg（TCG 卡牌）/ toy（玩具）/ coin（硬币）/ rock（岩石）**。
projectId 统一为短名 `antique/coin/toy/rock/tcg`（2026-10-28 决策）；产品名与提示词文件前缀沿用
`toyscan-scan-system` / `rockid-scan-system` 不改。

| 版本 | tag commit | 日期 | 线上 | 说明 |
|------|-----------|------|:----:|------|
| v1.0.9 | `7cb1aa61`（tag `v1.0.9`） | 2026-10-31 | ✅ | 4 app 接入（tcg/toyscan/coin/rockid）：prompt per-project registry、`premium_result` 内联落 `scan_record`、输出截断降级重跑、推送取图 MAIN→FRONT→第一张 回退、Flyway V2 |
| v1.0.7 | `4644ba52` | 2026-10 | ✅ | 上一线上版本（见 Changelog） |

设计文档：[tcg-prompt-per-project](../../design/ai/tcg-prompt-per-project.md)（✅ 代码已实现，本分支即其载体）。
施工单：[tcg](../../ops/issues/2026-10-27-tcg-project-bootstrap.md) / [toyscan](../../ops/issues/2026-10-27-toyscan-project-bootstrap.md) / [coin](../../ops/issues/2026-10-27-coin-project-bootstrap.md)。

---

## 1. 本版内容（相对 main 的 diff 摘要）

### Added
- **提示词按 projectId 查表**：`app.ai.prompt.*`（`ScanPromptProperties` / `ScanPromptRegistry` / `ScanPrompt` 按项目实例化并缓存）。未登记的 projectId 回落 default（antique），老 app 行为不变。8 个新提示词文件：`{tcgcard,rockid,coin,toyscan}-scan-system-{basic,premium}_v1.md`。
- **premium 内联落库**（Flyway **V2**：`core_ai_scanrecord` 加 `premium_result jsonb`）：4 个新 app 一次 AI 调用同时产出 `basic_result` + `premium_result`，同一条 CAS 写入；仅 `basic_result.scan_status.status ∈ {SUCCESS, PARTIAL}` 才写。GraphQL `ScanRecord` 新增只读字段 `premiumResult`（纯增量，老客户端无感）。
- **输出截断降级重跑**：`app.ai.max-output-tokens`（默认 8192）显式上限 + 解析失败且 JSON 括号不平衡时，用 basic 模板降级重跑一次（产出 basic、premium 留空），不白烧整条重试。
- **推送取图回退**：`MAIN → FRONT → 第一张`（coin 只有 FRONT/BACK，原逻辑推送恒无图；对 antique 是纯增强）。

### Changed
- `ScanTaskService` / `ScanAggHandler` / `SpringAiScanRunner`：从单例 `ScanPrompt` 改为 `ScanPromptRegistry.forProject(projectId)`（5 处 `promptVersion` 取值点全部按项目化）。
- user prompt 对非 MAIN 图片补视角说明行（`Image 1 = front, Image 2 = back`）；全 MAIN 或分类缺失时逐字保持原措辞。

### 兼容性
- **无破坏性 API 变更**：GraphQL 只增字段；V2 只加列（nullable）；未登记项目静默回落 antique 提示词；`prompt-version` 旧配置键保留。
- 发布顺序自由：后端先上不影响 antique；客户端各 app 独立发版，后端不就绪则该 app 扫描报项目配置错误。

---

## 2. 2026-10-27 review 结论（rc/tcg-coin-rock-toy vs main）

编译 ✅；`com.ifmix.core.api.modules.ai` 全量 104 用例通过（8 跳过为 Docker 项）。设计文档与代码一致（premium 直写列、根键 `object_overview`、prompt 键名 snake_case 均已核对）。

### 发布前必须处理（阻塞）

- [x] ~~**P0 rockid 无施工单、项目数据未写**~~ → **已解决（2026-10-28）**：补写
  [2026-10-28-rock-project-bootstrap](../../ops/issues/2026-10-28-rock-project-bootstrap.md)，
  本地库已写入 `rock`（info + FCM `ifmix-rock`）。
- [x] ~~**P0 tcg 项目数据未写**~~ → **已解决（2026-10-28）**：本地库已写入 `tcg`（info + FCM `ifmix-tcg`），
  施工单状态已更新。
- [ ] **P0 projectId 统一为短名 `antique/coin/toy/rock/tcg`（2026-10-28 决策）的代码侧收尾**：
  - [x] `application.yml` registry key：`toyscan`→`toy`、`rockid`→`rock`（文件名前缀不变），测试已改齐、已通过
  - [x] 本地库改名 `toyscan`→`toy`（info + serverconfig，无孤儿数据）+ 新增 `tcg`/`rock`
  - [x] 玩具/岩石施工单与 AGENTS.md 索引同步
  - [ ] **客户端 env 对齐**：`toy_rn/apps/toyscan/.env.example`（toyscan→toy）、
    `rock_rn/apps/rockid/prod.env` + `.env.example`（rockid→rock）——**改完前客户端联调会静默回落 antique**

### 建议随本版修复（P2，均小改动）

- [ ] **降级重跑不受总预算约束**（`SpringAiScanRunner` catch 块内）：预算检查只在 attempt 入口做，主调用 360s 跑满后降级重跑还能再 360s，最坏 ~960s > `scan-deadline-sec` 600s 的设计不变量（「最坏总耗时 = deadline」）。重跑前补一次剩余预算检查即可。
- [ ] **`ScanAggHandler` 注释残留旧方案**：`INLINE_PREMIUM_STATUSES` 注释仍写「落 core_ai_deepresearch 行」，实际写 `scan_record.premium_result`（7e336dce 改掉后注释没跟）。
- [x] ~~**AGENTS.md toyscan 状态与施工单不一致**~~ → 已同步（2026-10-28，随 projectId 改名一并更新）。
- [ ] **死代码同步路径不产 premium**：`ScanAggHandler.runAiScan`/`saveNewScan`（经 `AiFacade`，已无任何 fetcher 调用）未提取 `premium_result`，与异步路径行为分叉。建议删除；保留则补齐。

### 记入已知取舍（P3，不阻塞）

- `max-output-tokens=8192` **全局生效**，antique DeepResearch 也从 provider 默认上限变为 8192（实测已留 30% 余量）；截断识别只对 BASIC 生效，DR 路径截断 = 整单换 key 重试。后续若 antique premium 报告变长需按项目拆配置。
- `ScanPromptRegistry` 不在启动时校验 `default` 是否已注册于 `projects`：配置段整体缺失时静默回落 legacy v10 模板，配置错误是静默的（coin 施工单已提示；靠冒烟兜底）。
- 未登记 projectId 各自缓存一份回落实例（键来自 DB 校验过的 project，集合有界，无害）。

---

## 3. 发布 Runbook

### Phase 0 — 发布前（本地）

- [x] 全量 `./gradlew :core-api:test :core-job:test` 通过（2026-10-31 发布前复跑，BUILD SUCCESSFUL）。
  P0 仅剩客户端 env 对齐（属客户端仓库，不阻塞后端发布）；P2 两项（降级重跑预算检查、`ScanAggHandler` 注释）未随本版修，见 §2
- [ ] 本地从零库验证 Flyway：V1 + V2 依次应用成功（`flyway_schema_history` version=2 success=t）
  ——未单独做从零库演练；生产库在既有 V1 上应用 V2 成功（见 §5 执行记录）
- [x] 本地补写 4 个新 app 项目数据（2026-10-28 完成：`toy` 由 `toyscan` 改名而来，`tcg`/`rock` 新增，均含 FCM config；antique/coin 原有）
- [x] 确认 `.env.prod` 无需新增变量：新配置全部有默认值（`AI_MAX_OUTPUT_TOKENS` / `*_SCAN_PROMPT_VERSION` 均可不配；2026-10-31 核对 `application.yml`）

### Phase 1 — 生产 DB（SSH 隧道 + 本地 psql，流程同 v1.0.6 计划 Phase 2）

- [x] Flyway 应用 **V2**（`ALTER TABLE core_ai_scanrecord ADD COLUMN IF NOT EXISTS premium_result jsonb`，可重复执行）——2026-10-31 生产执行，`flyway_schema_history` v2 success=t
- [x] 4 个 app 的 `core_project_info` + `core_project_serverconfig` 写入（SQL 模板与凭据路径见各施工单）——2026-10-31 生产执行：

| projectId | bundleId / package | Firebase 项目 | 提示词文件（前缀不变） | 施工单状态 |
|---|---|---|---|---|
| `tcg` | `com.ifmix.tcg` | `ifmix-tcg` | `tcgcard-scan-system-{basic,premium}_v1` | ✅ 本地+生产已执行（2026-10-31） |
| `toy` | `com.ifmix.toy` | `ifmix-toy` | `toyscan-scan-system-{basic,premium}_v1` | ✅ 本地+生产已执行（2026-10-31） |
| `coin` | `com.ifmix.coin` | `ifmix-coin` | `coin-scan-system-{basic,premium}_v1` | ✅ 本地+生产已执行（2026-10-31） |
| `rock` | `com.ifmix.rock` | `ifmix-rock` | `rockid-scan-system-{basic,premium}_v1` | ✅ 本地+生产已执行（2026-10-31） |

> ⚠️ 生产库无历史 `toyscan`/`rockid` 行，四个 app 均直接 INSERT；**不要**把旧短名之外的
> `toyscan`/`rockid` 写进生产 DB 或 yml（yml key 已改为 `toy`/`rock`，拼旧名会静默回落 antique）。

- [x] 每行写入后核对（2026-10-31）：`core_project_info` 5 行齐全（antique + 4 新）；4 行 `fcm_config->>'project_id'` 与上表一致（`ifmix-tcg/toy/coin/rock`）、`client_email` 一致、`private_key` 1704 字符与源文件一致
- [ ] v1 各 app 均不需要：`core_project_configrevision`（IAP 走 Superwall）、attest 配置（global-enabled=false 两侧全关）

### Phase 2 — 后端部署

- [x] 部署 v1.0.9（2026-10-31：先 DB 后代码；`sync-core-api.sh` 增量发布，active 槽 a→b，健康 UP、NRestarts=0、error/warn 日志无新条目；部署产物瘦 jar 内 prompt 模板齐全）
- [ ] 冒烟（antique 回归）：老 app 扫一单 → 结果与升级前同构、prompt_version 仍 v10、推送有图（MAIN 命中第一分支）——**待真机**
- [ ] 冒烟（新项目回落保护）：用一个未登记 projectId 发请求 → 应回落 antique 提示词并正常出结果（不 500）——**待真机**

### Phase 3 — 客户端发布（4 个 app 独立发版，服务端就绪后随时可上）

每个 app 上线后执行同一份冒烟清单：

- [ ] createInstall → 拍照扫描 → `scan_status.status=SUCCESS` 且 `basic_result` 根键为 `object_overview`
- [ ] 查询返回含 `premiumResult`（非会员客户端应只读 basic；会员解锁 premium）
- [ ] 推送到达且**有图**（coin/toyscan 重点验 FRONT 回退路径）
- [ ] 废图路径（模糊/非目标物）：premium 为空、状态 INSUFFICIENT_IMAGE / NON_CARD_SUBJECT
- [ ] 观察点：`Scan attempt ok` 日志中 contentLength 与 premium 输出规模；出现 `likely truncated` WARN 即 8192 上限吃紧，回调大该值

### Phase 4 — 回滚

- 后端：回退二进制即可（V2 加列对旧版本无害，无需回滚 DB）
- 单 app 出问题：下架/暂停该客户端发版即可，其余 app 与 antique 不受影响（按项目隔离的提示词与 FCM 配置）
- 数据回滚：`core_project_info` / `core_project_serverconfig` 对应行 delete（无外键依赖）；`premium_result` 列保留不清理

---

## 4. 决策记录

1. **4 app 的 BASIC 扫描全部 `scan-uses-full-template: true`**：一次调用同时产出 basic + premium（各 app v1 设计）；antique 维持 DeepResearch 异步路径（false），premium 仍走深研表。
2. **premium 直写 `scan_record.premium_result`**，不复用 `core_ai_deepresearch` 表（原方案过度设计，见设计文档 §4.2）；客户端读取优先级：`premium_result ?? latestDeepResearch.premiumResult`。
3. **inline premium 不消耗深研配额**（设计文档 §3）——计费口径：只有用户主动发起的 DeepResearch 计入深研额度。
4. **差异一律走配置，不写 projectId 分支**（设计 §4 反目标）：服务端代码无 `if (projectId == ...)`；新 app 接入 = yml 一段 + 施工单。
5. **发布顺序自由、无停机要求**：全部变更向后兼容（加列/加字段/按项目回落），不设 v1.0.6 式「短暂停机」前置。
6. **8192 输出上限是本版兜底手段之一**：显式 maxTokens + 截断判定 + basic 降级重跑三件套，把「premium 拖垮 basic」从整单失败降为「缺 premium 的成功」。
7. **projectId 统一为短名 `antique/coin/toy/rock/tcg`**（2026-10-28 决策）：DB、yml registry key、客户端 `EXPO_PUBLIC_PROJECT_ID` 三处一致；提示词**文件名前缀**与产品/仓库/目录名不变（`toyscan-scan-system`、`rockid-scan-system`，app 目录 `apps/toyscan`、`apps/rockid`）。promptVersion 与扫描落库值不受影响。

---

## 5. 发布执行记录（2026-10-31）

| 步骤 | 结果 |
|---|---|
| 全量测试 | `:core-api:test :core-job:test` BUILD SUCCESSFUL |
| 生产 DB：Flyway V2 | ✅ `flyway_schema_history` v1+v2 均 success=t（在既有 V1 上应用） |
| 生产 DB：4 app 项目数据 | ✅ `core_project_info` 5 行（antique+tcg/toy/coin/rock）；`core_project_serverconfig` 4 行新增，`fcm_project=ifmix-tcg/toy/coin/rock`、`private_key` 1704 字符与源文件一致 |
| 部署 | ✅ `sync-core-api.sh` 增量发布，active 槽 `a→b`，健康 UP、NRestarts=0；error/warn 日志无重启后新条目 |
| 产物验证 | 瘦 jar（3.25MB）内 prompt 模板齐全：antique v10、coin/note/toy v1+v2、rockid/tcgcard v1 |
| tag | `v1.0.9` @ `7cb1aa61` |

**发布后待办**：

- 真机冒烟（Phase 2 两条 + Phase 3 各 app 清单）待客户端发版时执行。
- P2 两项（降级重跑预算检查、`ScanAggHandler` 注释残留）未随本版修，见 §2。
- note（纸币扫描器）prompt/代码已随本版上线，两张表 SQL 仍待执行（FCM 暂缓至 Firebase `ifmix-note` 建好）——见 [note 施工单](../../ops/issues/2026-10-28-note-project-bootstrap.md)。

**与 §1 描述的差异（计划定稿后代码又演进，以代码为准）**：tcg/rock/coin/toy/note 已改为与 antique 一致的
**两段式**（`scan-uses-full-template: false`——basic 扫描只出 basic_result，premium 由用户主动发起的
DeepResearch 产出），不再是「一次 AI 调用同时产出 basic+premium」；coin/toy/note prompt 升到 v2（2026-10-30/31，
防输出语言漂移）。`premium_result` 列与 GraphQL `premiumResult` 字段保留（读取优先级 `premium_result ?? latestDeepResearch.premiumResult` 不变）。
