# DeepResearch 完成 Push 通知 设计文档

- 日期：2026-10-03
- 状态：**已实现（V13，2026-10-05 合入 main）**
- 模块：`core-api` / `modules/ai`（DeepResearch）、复用 `modules/notification`
- 相关前端：`/Users/jason/ai/myprojects/antique`
- 依赖：`../notification/scan-async-notification-push.md`（notification 模块、FCM、授权流程、深链协议均在此定义，本文档只描述 DeepResearch 的增量差异，不重述）

## 1. 背景与目标

DeepResearch 已异步化（创建 IN_PROGRESS → 后台跑 AI → CAS 回写），但完成后无主动通知。本次给它加 push，模式**完全对齐 scan result**：发起时检查通知授权（无则弹窗提示、拒绝改设置）、设置页开关、后台完成发 push（文本+图片，点击打开 scan 结果页）。

**复用 scan push 的全部机制**（见依赖文档）：notification 模块（NotificationFacade/DispatchService/PushChannel）、FCM 寻址（fcm_token 优先 / topic 回退）、fcm_token_valid 失效标记、授权注册流程、深链协议、发送失败不影响主流程。本文档只列 DeepResearch 的**差异点**。

## 2. 关键设计决策（已确认）

| # | 决策 | 选择 |
|---|------|------|
| 1 | 通知开关 | **独立开关** `deep_research_noti_enabled`（与 `scan_result_noti_enabled` 并列，用户分别控制） |
| 2 | 深链目标 | **复用 scan 结果页** `/p/${projectId}/scan-result/${scanRecordId}`（premium 结果展示在该页） |
| 3 | push 文案 | "深度研究完成"主题模板 + 物品名（按 locale，缺失回退纯模板） |
| 4 | 发送目标 | 发给发起 DeepResearch 的 install（需给 `DeepResearchTaskContext` 补 `installId`） |
| 5 | 何时发 | **仅 `finalizeDeepResearchSuccess` 返回 finalized=true（CAS 成功且成为 latest）**时发；旧任务晚完成（SUCCESS 但非 latest）不发（见 §3.3 理由）。事务提交后、事务外发送，失败只 WARN |

## 3. 增量改动

### 3.1 必须补的字段：`DeepResearchTaskContext.installId`

- 当前 `DeepResearchTaskContext` 无 installId；push 需发给发起方 install。
- `createDeepResearchTask` 构造 ctx 时，从**本次请求的** ActionContext.tokenInstallId 快照取（可信 installId）。**不能从 scan 的原始 installId 推断**——通知目标必须是发起本次 DeepResearch 的设备（可能与创建 scan 的设备不同）。
- 若 installId 为 null（理论上 customer 操作必有 iid）：§3.3 的 guard 跳过 push（DEBUG），不构造伪 UUID、不调 facade，不影响主流程。

### 3.2 Install 新增开关

- `entity/install/Install.kt`：加 `deepResearchNotiEnabled: Boolean`（默认 `true`，与 `scanResultNotiEnabled` 并列）。
- updateInstall（handler/facade/schema）：支持更新 `deep_research_noti_enabled`。
- Migration：`ALTER TABLE core_install ADD COLUMN deep_research_noti_enabled boolean NOT NULL DEFAULT true;`（版本号接续 scan 那次迁移之后）。

### 3.3 后台发 push（`DeepResearchTaskService.doRun`）

成功回写 `finalizeDeepResearchSuccess` 返回后（事务已提交、事务外）：
```kotlin
val finalized = txRunner.withTx(mc) { scanAggHandler.finalizeDeepResearchSuccess(it, ctx, result) }
// 仅成为 latest 才通知（见下方理由）
if (finalized) {
    val installId = ctx.installId ?: run {
        log.debug("Skip DeepResearch notification: no installId. deepResearchId={}", ctx.deepResearchId)
        return
    }
    val content = buildDeepResearchNotificationContent(ctx, result)  // §3.4
    runCatching {
        notificationFacade.sendToInstall(
            NotificationRequest(
                projectId = ctx.projectId,
                installId = installId,
                notiType = NotiType.DEEP_RESEARCH,
                content = content,
            )
        )
    }.onFailure { log.warn("DeepResearch push failed. deepResearchId={}", ctx.deepResearchId, it) }
}
```

**为什么仅 finalized=true 发（前端 agent #1，消除原稿矛盾）**：
- `finalizeDeepResearchSuccess` 的返回值语义是 **isLatest**（本次是否成为 scan 的权威 latest 指针），**不是** "CAS 是否成功"。
- 深链固定为 `/p/{projectId}/scan-result/{scanRecordId}`，只能展示该 scan **当前 latest** 的 DeepResearch。
- 若旧任务 A 晚于较新任务 B 完成：A 技术上 SUCCESS 但未成为 latest，链接打开的是 B 的结果。向 A 的发起设备发"深度研究完成"会**误导**用户。
- 故**旧任务晚完成**：历史记录为 SUCCESS，但**不发 push、不移动权威指针、不再扣配额**（后两者本就是 finalizeDeepResearchSuccess 既有行为）。
- 若将来产品坚持"每个成功任务都通知"，必须先新增 task-specific DeepResearch 详情路由 + API（当前无此展示入口），不在本期。

### 3.4 NotificationContent 组装（DeepResearch 侧，文案降级同 scan，前端 agent #2）

- **title/body**（按 locale 模板）：
  - `basicResult.scan_status ∈ {INSUFFICIENT_IMAGE, NON_PHYSICAL_SUBJECT}` → title「深度研究已完成」/ body「点击查看拍摄建议」（不暗示报告已就绪）。
  - 否则 → 「深度研究已完成」+ 物品名（`basicResult.object_overview.name`）/「查看报告」；物品名缺失/空白/非字符串 → 纯模板。
- **imageUrl**：scan 主图 public URL（category=MAIN；无 MAIN / URL 构造失败 → 不带图），同 scan。
- **link**：`/p/${projectId}/scan-result/${scanRecordId}`。

### 3.5 notification 模块：开关参数化（对 scan 设计的小扩展）

scan 设计里 `sendToInstall` 查死 `scan_result_noti_enabled`。为支持 DeepResearch 的独立开关，`NotificationRequest` 增加通知类型标识，由 NotificationHandler 据此查对应开关：
- `NotificationRequest` 加 `notiType: NotiType`（枚举 `SCAN_RESULT` / `DEEP_RESEARCH`）。
- NotificationHandler 开关前置判断按 notiType 映射到 install 字段：`SCAN_RESULT→scan_result_noti_enabled`、`DEEP_RESEARCH→deep_research_noti_enabled`。
- scan 侧调用传 `SCAN_RESULT`，DeepResearch 侧传 `DEEP_RESEARCH`。
- ponytail: 用枚举 + when 映射，不引入通用偏好表（YAGNI，两个开关而已）。

## 4. 前端改造要点（供前端 agent review）

对齐 scan 的授权/开关交互，针对 DeepResearch：
- **发起 DeepResearch 时检查通知授权**：未授权 → 弹窗提示「深度研究进行中，完成后通知你」+ 请求授权；授权成功执行完整注册（取 token→上报→订阅 topic→`updateInstall(deepResearchNotiEnabled=true)`）；拒绝 → `updateInstall(deepResearchNotiEnabled=false)`。
  - 注：系统通知授权是**设备级**，scan 已请求过则无需重复弹系统权限；但 `deep_research_noti_enabled` 开关是独立的，按本功能首次发起时设置。
- **设置页**加「深度研究结果通知」开关（与「扫描结果通知」并列），改动调 updateInstall；系统授权被拒时保持/回退为关。
- **深链**：复用 scan push 前端改造提供的 `data.link` 解析能力（解析 `/p/{projectId}/scan-result/{scanId}` → 校验 projectId → 映射 `/result/{scanId}`）。**此能力由 scan push 前端计划实现，尚未落地**（当前 `features/push/messageHandlers.ts` 仍只读 `data.url`）。在该能力发布前，DeepResearch push 只展示、点击路由不保证。DeepResearch 不新增路由、不复制解析逻辑。
- updateInstall 的 `_API_ENTRIES` / schema 类型 / persisted-query 增加 `deepResearchNotiEnabled` 字段。

## 5. 分层落点

| 层 | 文件 | 改动 |
|----|------|------|
| DTO | `dto/ai/DeepResearchTaskContext.kt` | 加 `installId: UUID?` |
| Handler | `modules/ai/handler/ScanAggHandler.kt` | `createDeepResearchTask` 填 installId；新增 DeepResearch NotificationContent 组装 |
| Service | `modules/ai/DeepResearchTaskService.kt` | 成功回写后调 notificationFacade 发 push（事务外，WARN 兜底） |
| DTO | `dto/notification/NotificationRequest.kt` | 加 `notiType: NotiType` |
| 枚举 | `dto/notification/NotiType.kt`（新增） | `SCAN_RESULT` / `DEEP_RESEARCH` |
| Handler | `modules/notification/handler/NotificationHandler.kt` | 开关前置按 notiType 映射到对应 install 字段 |
| Entity | `entity/install/Install.kt` | 加 `deepResearchNotiEnabled: Boolean` |
| Install 侧 | updateInstall handler/facade/schema | 支持更新 `deep_research_noti_enabled` |
| Migration | `db/migration/V13__...sql` | install 加 `deep_research_noti_enabled default true` |
| scan 侧 | ScanTaskService 发 push 处 | NotificationRequest 补 `notiType=SCAN_RESULT`（配合 §3.5 扩展） |

## 6. GraphQL 交付清单

- 服务端：updateInstall schema/customer.json 加 `deepResearchNotiEnabled`；DGS codegen。
- 前端：`_API_ENTRIES` 的 updateInstall 加字段；重新生成 schema 类型 + persisted-query，1:1 对齐。

## 7. 错误处理与边界（增量，其余同 scan push）

| 场景 | 处理 |
|------|------|
| installId 为 null | 跳过 push（DEBUG），不影响 DeepResearch 落库 |
| deep_research_noti_enabled=false | 后台发前查开关跳过（不取 token、不发） |
| push 发送失败 | 只记 WARN，不影响已落库 DeepResearch |
| late success（超时后 AI 返回）| CAS 失败 → 不发 push（同 scan，无结果则无通知） |

## 8. 测试计划（增量）

1. **开关路由**：notiType=DEEP_RESEARCH 查 `deep_research_noti_enabled`、SCAN_RESULT 查 `scan_result_noti_enabled`（不串）。
2. **DeepResearch 完成发 push**：成功且 finalized=true → 调 notificationFacade，content 的 link 指向 scan 结果页、文案为深度研究主题。
3. **旧任务晚完成不发**：finalized=false（未成为 latest）→ 任务 SUCCESS 但不发 push、不动指针、不扣配额。
4. **文案降级**：basicResult.scan_status=INSUFFICIENT_IMAGE → 文案「查看拍摄建议」，不暗示报告就绪。
5. **开关关**：deep_research_noti_enabled=false → 不发。
6. **installId 缺失**：ctx.installId=null → 跳过 push，DeepResearch 仍 SUCCESS。
7. **push 失败不影响主流程**：PushChannel 抛异常 → DeepResearch 仍 SUCCESS，仅 WARN。
8. **开关独立性回归**：关 scan 开关不影响 DeepResearch push，反之亦然。

## 9. 实现期验证点

1. `DeepResearchTaskContext.installId` 来源确认（createDeepResearchTask 的本次 ActionContext.tokenInstallId）。
2. §3.5 notification 开关参数化：本文档依赖 scan push 的 notification 模块落地；若 scan push 尚在实现中，两者协调 `NotificationRequest.notiType` 扩展的落地顺序。
