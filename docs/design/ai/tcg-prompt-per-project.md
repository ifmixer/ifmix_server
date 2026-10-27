# 提示词按项目选择 + 单次扫描内联 Premium

状态：✅ 代码已实现（分支 `feature/tcgcard-prompt`，未合 main）
日期：2026-10-27
相关：客户端设计 `tcg/tcg_workspace/docs/superpowers/specs/2026-10-27-tcgcard-design.md` §8

## 1. 为什么需要这个改动

第二个 app（TCG 卡牌扫描器，`projectId=tcg`）接入同一套后端时，撞上两个硬编码：

1. **`ScanPrompt` 在构造时硬编码加载 `antique-scan-system-basic_v10.md`**，与 `projectId` 无关。不改成按项目选择，tcg 的所有扫描都会跑古董提示词。
2. **`premium_result` 只由 DeepResearch 异步任务产出**。tcg v1 不做 DeepResearch（客户端设计 §3），但 Premium 会员仍然需要解锁内容 —— 没有产出路径。

本设计解决这两点，且**保证 antique 行为逐字不变**。

## 2. 反目标

- **不写 `if (projectId == "tcg")` 分支**。差异一律走配置（`app.ai.prompt.*`）。
- **不动 GraphQL schema**。客户端 `premiumOf()` 读的是 `latestDeepResearch.premiumResult`，复用既有字段即可。
- **不让 antique 需要同步改代码**。antique 的两条路径（BASIC / DEEP_RESEARCH）选到哪份模板必须与改造前完全一致。

## 3. 提示词按项目选择

### 3.1 配置

```yaml
app:
  ai:
    prompt:
      # 未登记的 projectId 一律回落这一组（保持老 app 行为）
      default: antique
      projects:
        antique:
          name: antique-scan-system
          version: ${SCAN_PROMPT_VERSION:v10}
        tcg:
          name: tcgcard-scan-system
          # full 组文件名覆盖：tcg 的 basic+premium 模板叫 -premium
          full: tcgcard-scan-system-premium
          version: ${TCG_SCAN_PROMPT_VERSION:v1}
          # 普通扫描直接走 full 组（一次调用同时产出 basic + premium）
          scan-uses-full-template: true
```

**一个项目两组模板**：

| 组 | 文件名 | 返回 |
| --- | --- | --- |
| basic | `${name}-basic_${version}.md` | 只 `basic_result` |
| full | `${full ?: name + "-deep-research"}_${version}.md` | `basic_result` + `premium_result` |

antique 的 full 组就是原来的 `antique-scan-system-deep-research_v10.md`（默认后缀 `-deep-research`，无需配置）；tcg 的 full 组叫 `-premium`，用 `full:` 覆盖。

### 3.2 选择逻辑

`ScanPromptRegistry.forProject(projectId)` 按 `projectId` 取（懒加载 + 缓存），`ScanPrompt.systemPromptFor(input)` 再按 `ScanType` 决定用哪组：

```
DEEP_RESEARCH → 永远 full 组
BASIC         → scanUsesFullTemplate ? full 组 : basic 组
```

- **antique**：`scanUsesFullTemplate=false` → BASIC 走 basic 组、DEEP_RESEARCH 走 full 组。**与改造前一致。**
- **tcg**：`scanUsesFullTemplate=true` → 普通扫描就走 full 组，一次拿到 basic + premium。

**未登记的 projectId 回落 `default`**，所以老 app 或新接入但还没配提示词的项目不会炸。

**懒加载而非启动时加载**：某项目提示词文件缺失只在**该项目首次扫描**时报错，不会拖垮整个 Spring 上下文。

### 3.3 promptVersion 落库

`scan_record.prompt_version` 与 `core_ai_deepresearch.prompt_version` 都按 `projectId` 取对应配置的版本号（`ScanAggHandler` 里 5 处 `scanPrompt.promptVersion` 已全部改掉）。

**已知限制**：版本号是单列。tcg v1 的 basic 与 premium 来自同一次调用、同一份模板，单版本号是准确的；将来若拆成两份独立模板，需要各自记录版本。

## 4. 单次扫描内联 Premium

### 4.1 做法

`ScanTaskService.doRun` 从 AI 响应里同时取 `basic_result` 和 `premium_result`，传给 `finalizeScanSuccess`。

`finalizeScanSuccess` 在 `casSuccess` 里一次写完两者：

1. 若 `premium_result != null` 且 `basic_result.scan_status.status ∈ {SUCCESS, PARTIAL}` → `scan_record.premium_result` 写入；
2. 否则该列写 null（antique 走这条）。

没有额外建行、没有指针挪动、没有第二个事务。

### 4.2 为什么直接加 `scan_record.premium_result` 列

最初的方案是复用 `core_ai_deepresearch` 表（插一行 + 挪 `latest_deep_research_id` 指针），理由是客户端和 GraphQL schema 零改动。**但这是过度设计**：

- 这不是用户发起的深研，没有异步任务语义 —— 插深研行纯粹是为了复用已有的读取路径；
- 多一条 INSERT + 一次指针 CAS 比较，还留下「每条扫描在深研表留一行」的副作用；
- 代价只是加一个 JSONB 列 + schema 加一个字段，一次迁移的事。

现在的做法：`scan_record` 新增 `premium_result` 列（`V2__scan_record_premium_result.sql`），`casSuccess` 一次写 basic + premium。

**读取优先级（客户端 `premiumOf()`）**：`record.premiumResult` ?? `record.latestDeepResearch.premiumResult`。后者是 antique 的 DeepResearch 路径，保留兜底是为了同一套读取代码服务两种来源。

### 4.3 为什么不消耗深研配额

`tryIncrementDeepResearchCount` **不调用**。这不是用户主动发起的深研，是普通扫描的附带产出，计费口径应是扫描配额（已有）。

### 4.4 与 DeepResearch 竞态无关

DeepResearch 的 `IN_PROGRESS → SUCCESS` CAS 处理的是「异步任务晚完成 / 查询侧惰性超时抢先」。内联 premium 与 basic 是**同一次 AI 调用、同一个事务**产出，`casSuccess` 本身已在做这个 CAS，premium 跟着一起写，不引入新的竞态面。

### 4.5 为什么只在 SUCCESS / PARTIAL 落库

`INSUFFICIENT_IMAGE` / `NON_CARD_SUBJECT` 的扫描，模型可能仍返回 `premium_result`（prompt 没强制禁止），但内容没有意义。不拦的话每张废图都带一份垃圾 premium。

## 5. 正反面告知模型

`ScanMediaItem` 新增 `category: ImageCategory?`，`ScanAggHandler.buildScanInput` 从 `ctx.images[].category` 透传。

`ScanPrompt.userPrompt` 在所有图片都带可识别分类码时，追加一行：

```
Image 1 = front, Image 2 = back.
```

**码表故意不含 `MAIN(0)` / `OTHER(1000)`**：这两个没有视角语义。antique 的图片恒为 MAIN → `buildViewLine` 返回 null → 走原来的通用措辞，**行为逐字不变**。

为什么必须明说：卡牌背面承载版本 / 版权 / 标志，靠模型自己猜哪张是背面会直接影响 `variant.finish`、`is_first_edition`、真伪判断。

## 6. 根键保持 `object_overview`

卡牌的 `basic_result` 根键**沿用 antique 的 `object_overview`**，只是内部字段按卡牌语义重建（`game` / `set_name` / `card_number` / `rarity_tier` / `card_language` …），并新增 `variant` 与 `condition` 两个板块。

改名（`card_identity`）曾出现在初版实现里，已撤销：这是内部 JSON 键，不展示给用户，改名要牵动服务端推送读名回退 + 客户端 17 个文件，纯增复杂度。

因此 `buildScanNotificationContent` / `buildDeepResearchNotificationContent` **保持原样**直接读 `basicResult["object_overview"]["name"]`。

## 7. 改动清单

| 文件 | 改动 |
| --- | --- |
| `modules/ai/service/ScanPromptProperties.kt` | 新增。`@ConfigurationProperties(prefix="app.ai.prompt")` |
| `modules/ai/service/ScanPromptRegistry.kt` | 新增。按 projectId 懒加载 + 缓存，未登记回落 default |
| `modules/ai/service/ScanPrompt.kt` | 按项目实例化；新增 `systemPromptFor()` / `fullSystemPrompt()`；`userPrompt` 加视角行 |
| `modules/ai/service/SpringAiScanRunner.kt` | 用 `registry.forProject(ctx.projectId).systemPromptFor(input)`（projectId 取自 ActionContext，不进 ScanInput） |
| `dto/ai/ScanDto.kt` | `ScanMediaItem.category`（`ScanInput` 不需要 projectId，`run(ctx, input)` 里已有） |
| `modules/ai/ScanTaskService.kt` | 解析 `premium_result` 并传给 finalize |
| `entity/ai/ScanRecord.kt` + `repo/ScanRecordRepository.kt` | 新增 `premiumResult` 列；`casSuccess` 多一个可选参数 |
| `db/migration/V2__scan_record_premium_result.sql` | `ALTER TABLE core_ai_scanrecord ADD COLUMN premium_result jsonb` |
| `schema/customer/ai.graphqls` | `ScanRecord.premiumResult: JSON` |
| `modules/ai/handler/ScanAggHandler.kt` | `finalizeScanSuccess` 接收 premiumResult 并交给 `casSuccess`；`inlinePremiumEligible`；5 处 promptVersion 改按项目取 |
| `infra/ratelimit/RateLimitBeans.kt` | 登记 `ScanPromptProperties` |
| `resources/application.yml` | `app.ai.prompt.*` 配置 |
| `resources/prompts/tcgcard-scan-system-basic_v1.md` | 新增（占位，内容与 premium 一致） |
| `resources/prompts/tcgcard-scan-system-premium_v1.md` | 新增（basic + premium 一次产出） |

## 8. 测试

`./gradlew :core-api:test`：**448 tests / 0 failures / 11 skipped**。

新增用例（`ScanAsyncQuotaTest`）：
- `inline premium skipped when scan status is not SUCCESS or PARTIAL`
- `inline premium attached when scan status is SUCCESS`

其余是 runner fixture 补 `registry.forProject` stub（`anyOrNull` 匹配 null projectId）。

## 9. 未完成 / 后续

- [ ] **项目数据入库**（见 `docs/ops/issues/2026-10-27-tcg-project-bootstrap.md`）
- [ ] 合入 main（等用户确认）
- [ ] 真机联调：确认 tcg 扫描返回的 premium 能被客户端 `premiumOf()` 读到
- [ ] 压测单次扫描耗时（premium 输出让 token 翻倍；客户端轮询预算已提到 150×5s=750s）
