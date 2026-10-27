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

`ScanTaskService.doRun` 从 AI 响应里同时取 `basic_result` 和 `premium_result`，传给 `finalizeScanSuccess`。同一事务内：

1. 若 `premium_result != null` 且 `basic_result.scan_status.status ∈ {SUCCESS, PARTIAL}`：
   - 插入一条 `core_ai_deepresearch` 行（`status=30 SUCCESS`，含 `premium_result` 与 `basic_result` 快照）；
   - 把 `scan_record.latest_deep_research_id` 指过去，`has_deep_search=true`。

2. 否则只更新 scan（antique 走这条）。

### 4.2 为什么复用 `core_ai_deepresearch` 表

客户端读 premium 的代码是 `premiumOf()` → `record.deepResearch.premiumResult`，源头是 GraphQL 的 `ScanRecord.latestDeepResearch.premiumResult`（已有字段 + DataLoader）。

复用后：
- **GraphQL schema 零变更**
- **客户端读取代码零改动**
- 不需要给 `scan_record` 加列、不需要迁移

### 4.3 为什么不消耗深研配额

`tryIncrementDeepResearchCount` **不调用**。这不是用户主动发起的深研，是普通扫描的附带产出，计费口径应是扫描配额（已有）。

### 4.4 为什么直接插 SUCCESS 而不走 CAS

DeepResearch 的 `IN_PROGRESS → SUCCESS` CAS 是为了处理「异步任务晚完成 / 查询侧惰性超时抢先」的竞态。内联 premium 与 basic 是**同一次 AI 调用、同一个事务**产出，不存在这个竞态，直接插终态行。

### 4.5 为什么只在 SUCCESS / PARTIAL 落行

`INSUFFICIENT_IMAGE` / `NON_CARD_SUBJECT` 的扫描，模型可能仍返回 `premium_result`（prompt 没强制禁止），但内容没有意义。不拦的话每张废图都在深研表留垃圾行，而 `core_ai_deepresearch` 的行是不可回收的。

### 4.6 已知副作用

每条 tcg 扫描都会在 `core_ai_deepresearch` 里留一行 `status=30`。将来若有按该表做的深研统计 / 历史，**需要按 `project_id` 过滤**。v1 接受这个代价。

## 5. 正反面告知模型

`ScanMediaItem` 新增 `category: ImageCategory?`，`ScanAggHandler.buildScanInput` 从 `ctx.images[].category` 透传。

`ScanPrompt.userPrompt` 在所有图片都带可识别分类码时，追加一行：

```
Image 1 = front, Image 2 = back.
```

**码表故意不含 `MAIN(0)` / `OTHER(1000)`**：这两个没有视角语义。antique 的图片恒为 MAIN → `buildViewLine` 返回 null → 走原来的通用措辞，**行为逐字不变**。

为什么必须明说：卡牌背面承载版本 / 版权 / 标志，靠模型自己猜哪张是背面会直接影响 `variant.finish`、`is_first_edition`、真伪判断。

## 6. 推送标题读名

`buildScanNotificationContent` / `buildDeepResearchNotificationContent` 原先直接读 `basicResult["object_overview"]["name"]`。tcg 的卡名在 `card_identity` 下。

新增 `extractSubjectName(basicResult)`：**先读 `card_identity.name`，再回退 `object_overview.name`**。两个 app 共用这一处，antique 不受影响。

## 7. 改动清单

| 文件 | 改动 |
| --- | --- |
| `modules/ai/service/ScanPromptProperties.kt` | 新增。`@ConfigurationProperties(prefix="app.ai.prompt")` |
| `modules/ai/service/ScanPromptRegistry.kt` | 新增。按 projectId 懒加载 + 缓存，未登记回落 default |
| `modules/ai/service/ScanPrompt.kt` | 按项目实例化；新增 `systemPromptFor()` / `fullSystemPrompt()`；`userPrompt` 加视角行 |
| `modules/ai/service/SpringAiScanRunner.kt` | 用 `registry.forProject(input.projectId).systemPromptFor(input)` |
| `dto/ai/ScanDto.kt` | `ScanInput.projectId`、`ScanMediaItem.category` |
| `modules/ai/ScanTaskService.kt` | 解析 `premium_result` 并传给 finalize |
| `modules/ai/handler/ScanAggHandler.kt` | `attachInlinePremium` / `inlinePremiumEligible` / `extractSubjectName`；5 处 promptVersion 改按项目取 |
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
