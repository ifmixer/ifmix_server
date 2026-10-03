# Scan 异步化 + Notification 推送模块 设计文档

- 日期：2026-10-03
- 模块：`core-api` / `modules/ai`（scan 异步化）、`modules/notification`（新增通用通知模块）
- 相关前端：`/Users/jason/ai/myprojects/antique`（需同步改造，前端 agent review）
- 关联：复用 `2026-10-03-deep-research-async-pg-versioning-design.md` 的异步化基建；并对其做一处连带修正（§8）

## 1. 背景与目标

1. **scan 同步调用易超时**：`m_ai_createScan` 当前同步跑 AI（事务外）再落库，AI 耗时可能触发网关超时。→ **异步化**：mutation 立即创建 IN_PROGRESS 记录返回 scanId，后台跑 AI，前端轮询（复用 DeepResearch 模式）。
2. **完成后主动通知用户**：scan 完成发 push（文本+图片），点击打开 scan 结果页。
3. **通知能力通用化**：push 以后 DeepResearch 也要用，SMS/email 未来也要——做成独立 `notification` 模块（简称 noti），push 为首个 channel。

## 2. 关键设计决策（已确认）

| # | 决策 | 选择 |
|---|------|------|
| 1 | scan 完成感知 | **轮询为主 + push 为辅**（push 不可靠，网络/权限/token 失效时轮询兜底） |
| 2 | 通知模块 | 独立 `modules/notification`（简称 **noti**），push 为首个 channel，未来 sms/email 并列 |
| 3 | FCM 发送 | **Firebase Admin SDK**（`com.google.firebase:firebase-admin`） |
| 4 | 寻址 | install 的 `fcm_token` 优先；无 → FCM topic `install_${installId}`。发给**发起 scan 的 install**（scan.installId） |
| 5 | 两个 status 维度 | **彻底拆分**：任务 status（record 级 Int 10/20/30/40）vs AI 业务 status（basicResult.scan_status 枚举）。AI 正常返回即任务 SUCCESS |
| 6 | 配额语义 | 配额 = 扫描**次数**承诺。AI 正常返回即扣（不看业务结论质量；INSUFFICIENT_IMAGE 也扣） |
| 7 | push 内容 | 模板+物品名（按 scan.locale，缺失回退纯模板）、主图 public URL、深链 `/p/${projectId}/scan-result/${scanId}` |
| 8 | 何时发 push | 任务 SUCCESS 就发（文案做好兜底） |
| 9 | 通知开关 | Install 加 `scan_result_noti_enabled`（默认**开**；用户拒绝系统授权→前端改关）。后台发前查开关 |
| 10 | DeepResearch 连带修正 | 移除 isSuccess 业务判定 / AI_STATUS_REJECTED 码 / 业务 status 的 fail 分支（§8） |

## 3. 两个 status 维度（核心概念）

本设计最重要的概念澄清——**两个完全不同维度的 status，分开字段表达，不可混淆**：

| 维度 | 载体 | 取值 | 含义 |
|------|------|------|------|
| **任务 status** | `ScanRecord.status`（Int 列） | 10=CREATED/20=IN_PROGRESS/30=SUCCESS/40=FAILED | **异步任务的技术执行状态**。AI 正常返回（无论业务结论）= 任务 SUCCESS；只有 AI 调用异常/超时/submit 失败 = 任务 FAILED |
| **AI 业务 status** | `basicResult.scan_status.status`（JSONB 内） | SUCCESS/PARTIAL/INSUFFICIENT_IMAGE/NON_PHYSICAL_SUBJECT | **AI 对图片的业务判定**。照存，前端据此展示（如 INSUFFICIENT_IMAGE 提示补拍） |

- **任务 FAILED 只剩技术失败**：AI_FAILED（调用抛异常）、TIMEOUT、TASK_SUBMISSION_FAILED、INTERNAL_ERROR。
- AI 返回 INSUFFICIENT_IMAGE 是**任务成功 + 业务结论"图片不足"**，任务 status=SUCCESS、照扣配额、basicResult 照存，前端读 scan_status 展示补拍提示。
- 记录级 `ScanRecord.status` 现无人读（后端只写不读、前端只读 basicResult.scan_status），重定义为四态安全。

## 4. Scan 异步化

### 4.1 状态机

```
CREATED(10) → IN_PROGRESS(20) → SUCCESS(30)
                              └→ FAILED(40)
```
实际落库直接 20。复用 DeepResearch 四态与错误码（去掉 AI_STATUS_REJECTED）。

### 4.2 流程

```
前端 createScan
  → mutation m_ai_createScan（立即返回, <1s）
      1. 事务内：配额预检（used >= scanQuota → QUOTA_EXCEEDED 回滚）
      2. 事务内：创建 ScanRecord status=20(IN_PROGRESS)，带 images，basicResult=null，
                 latestDeepResearchId=null
      3. 事务提交后：经 executor 启动虚拟线程后台任务
      4. 返回 { scanId, status: 20, errorCode: null }
  → 后台任务 ScanTaskService.runScanTask（AI 调用，事务外）
      a. AI 调用抛异常 → CAS 20→40, error_code=AI_FAILED
      b. AI 正常返回（任何业务 status）→ 单短事务:
           CAS 20→30 + 写 basicResult + 扣配额（tryIncrementScanCount 封顶）
         事务成功后（事务外）→ notificationFacade.sendToInstall(installId, content)
                              异常只记 WARN（不影响已落库的 scan）
  → 前端轮询 q_ai_getScanStatus(scanId) 每隔几秒
      - IN_PROGRESS：updated_at 超 5 min → CAS 置 FAILED(TIMEOUT)；否则继续
      - FAILED：返回 status + errorCode
      - SUCCESS：→ q_ai_findMyScanById(scanId) 取权威 ScanRecord（含 basicResult）→ SQLite 覆盖
```

### 4.3 复用 DeepResearch / scan 特有

**复用**：CAS 终态、成功才扣配额、惰性超时(5min)、submit 失败处理(TASK_SUBMISSION_FAILED)、后台虚拟线程 executor、TxRunner 事务边界、offline ActionContext 构造、兜底 catch(INTERNAL_ERROR)。

**scan 特有/简化**：
- **无 latest 指针、无 (created_at,id) 比较、无 FOR UPDATE**——scan 一次创建一条记录，无并发竞争 latest 的场景，一条记录自己 CAS 终态即可。
- 记录创建时就带 images（AI 要分析）；basicResult 在成功 CAS 时回写。
- **无 AI_STATUS_REJECTED**：AI 正常返回即任务 SUCCESS（决策 5/6）。

### 4.4 数据模型变更（`core_ai_scan_record`）

- `status`：复用现列，码表重定义为 10/20/30/40（现无人读，安全）。创建写 20，CAS 终态改 30/40。
- 新增 `error_code: String?`、`error_details: jsonb?`（任务失败时写，同 DeepResearch）。
- 无需新增 latest 相关列（已有 latest_deep_research_id 是 DeepResearch 用，与此无关）。
- Flyway 新增迁移（版本号接续，当前最大 V11 → **V12**）。

## 5. Notification 模块（新增，通用）

### 5.1 结构（分层约定，模块名 notification / 简称 noti）

| 层 | 文件 | 职责 |
|----|------|------|
| Facade | `modules/notification/NotificationFacade.kt` | 统一入口。本期 `sendToInstall(installId, NotificationContent)`；未来并列 `sendSms`/`sendEmail` |
| Handler | `modules/notification/handler/NotificationHandler.kt` | 查 install（寻址 + 开关）、选 channel、组装、发送、失效 token 清理 |
| Channel | `modules/notification/channel/PushChannel.kt`（`@Component`） | FCM 推送渠道。未来 `SmsChannel`/`EmailChannel` 并列 |
| Infra | `infra/push/FcmConfig.kt`（`@Configuration`） | Firebase Admin SDK 初始化（service account 凭证走 `app.noti.fcm.*`） |
| DTO | `dto/notification/NotificationContent.kt` | 渠道无关内容：`{ title, body, imageUrl?, link }` |

### 5.2 发送语义（push channel）

- **开关前置**：`sendToInstall` 先查 install 的 `scan_result_noti_enabled`，false → 直接跳过（连 fcm_token 都不取）。
  - ponytail: 本期只有一个开关，直接查该字段；未来多通知类型再抽象「按类型查偏好」。
- **寻址**：`fcm_token` 优先；无 → FCM topic `install_${installId}`。
- **内容**：FCM `notification.title/body` + `notification.image`(主图 public URL) + `data.link`(深链)。
- **失效 token 清理**：SDK 返回 `UNREGISTERED`/`INVALID_ARGUMENT` → 清空该 install 的 `fcm_token`（下次自然回退 topic）。
- **发送失败绝不影响主流程**：scan 已落库，push 只是通知。在后台任务成功回写**之后**、事务外调用，异常只记 WARN。

### 5.3 复用性

scan 和未来 DeepResearch 都只调 `notificationFacade.sendToInstall(installId, content)`，各自组装 NotificationContent。notification 模块不含任何 scan/业务逻辑。

## 6. 通知内容与深链

### 6.1 scan 侧 NotificationContent 组装

- **title/body**：按 `scan.locale` 选模板 + 物品名（`basicResult.object_overview.name`）。物品名缺失/业务结论差 → 回退纯模板（如「扫描完成，点击查看结果」）。多语言由服务端按 locale 出模板。
- **imageUrl**：主图（`images` 中 category=MAIN 的 key，`objectStorage.getPublicUrl("ugc", key)`）。
- **link**：`/p/${projectId}/scan-result/${scanId}`。

### 6.2 深链约定

- 格式 `/p/${projectId}/scan-result/${scanRecordId}`。
- 客户端收到 push 的 link：**校验 path 中的 projectId == 本地 projectId**，不符则跳过（防多 project 环境串台）；一致则路由到 scan 结果页。

## 7. 通知开关与授权（决策 9）

- **Install 加字段** `scan_result_noti_enabled: Boolean`，默认 `true`（开）。
- **updateInstall** 支持更新该字段（设置页开关改动 → 同步 install）。
- **后台发 push 前**查该字段（§5.2），false 跳过。
- **前端**（供前端 agent）：
  - 发起 scan 时提示「扫描进行中，完成后通知你」并请求系统通知授权。
  - 设置页「扫描结果通知」开关，改动调 updateInstall 同步。
  - **用户拒绝系统授权 → 前端把开关改成关**（updateInstall 同步 false）——保持授权状态与开关一致，避免向无授权设备发无效推送。
  - FCM token 上报（Install 已有 fcm_token 列，确认前端在上报；无 token 时后台走 topic）。

## 8. DeepResearch 连带修正（本次一并，决策 10）

现有 DeepResearch 把 AI 业务 status 非 SUCCESS/PARTIAL 当作任务 FAILED(AI_STATUS_REJECTED)，混淆了两个 status 维度。按决策 5/6 修正：

- `DeepResearchResult`：移除 `isSuccess`（基于业务 status 的判定）。
- `DeepResearchErrorCodes`：移除 `AI_STATUS_REJECTED`。
- `DeepResearchTaskService.doRun`：移除「`!result.isSuccess` → fail(AI_STATUS_REJECTED)」分支——AI 正常返回即走成功回写 + 扣配额。
- `error_details`：不再塞 scan_status（它在 basicResult.scan_status 里，前端从结果读）。
- 任务 FAILED 只剩技术失败：AI_FAILED / TIMEOUT / TASK_SUBMISSION_FAILED / INTERNAL_ERROR。
- 前端 `deepResearchRejectionReason`：INSUFFICIENT_IMAGE/NON_PHYSICAL_SUBJECT 的判定改为从**权威 ScanRecord 的 basicResult.scan_status** 读（任务已 SUCCESS），不再从任务 errorCode 读。**需前端 review 确认**。

## 9. 分层落点

| 层 | 文件 | 改动 |
|----|------|------|
| Schema | `schema/customer/ai.graphqls` | `m_ai_createScan` 返回改 `{ scanId, status, errorCode }`；加 `q_ai_getScanStatus(scanId): ScanStatus`；updateInstall input 加 `scanResultNotiEnabled` |
| DataFetcher | `bff/graphql/customer/ai/AiFetcher.kt` | `newScan` 改为建记录+预检+提交后启动后台任务+submit 失败处理；加 `getScanStatus` |
| Service | **新增** `modules/ai/ScanTaskService.kt` | scan 后台任务编排（类比 DeepResearchTaskService）：AI→CAS 回写→扣配额→发 push |
| Handler | `modules/ai/handler/ScanAggHandler.kt` | 建 IN_PROGRESS 记录（预检）；成功回写 CAS+basicResult+配额；惰性超时 CAS；组装 scan NotificationContent |
| Repository | `modules/ai/repo/ScanRecordRepository.kt` | 新增 `casSuccess`(写 basicResult)、`casFailed`、`insert`(IN_PROGRESS) |
| Facade | `modules/ai/AiFacade.kt` | 转发 createScanTask / getScanStatus |
| **新增模块** | `modules/notification/**` + `infra/push/FcmConfig.kt` + `dto/notification/**` | §5 整个 notification 模块 |
| Entity | `entity/install/Install.kt` | 加 `scanResultNotiEnabled: Boolean` |
| Install 侧 | updateInstall handler/facade/schema | 支持更新 `scan_result_noti_enabled` |
| Migration | `db/migration/V12__...sql` | scan_record 加 error_code/error_details；install 加 scan_result_noti_enabled default true |
| 依赖 | `core-api/build.gradle.kts` | 加 `com.google.firebase:firebase-admin`（pinned 版本） |

## 10. GraphQL 交付清单（Trusted Documents）

**服务端**：
- `schema/customer/ai.graphqls`：`m_ai_createScan` 返回 `{scanId status errorCode}`；加 `q_ai_getScanStatus` + `ScanStatus` type。
- updateInstall schema 加 `scanResultNotiEnabled`。
- `customer.json`：更新 `m_ai_createScan`、加 `q_ai_getScanStatus`、更新 updateInstall；DGS codegen。

**前端（antique）**：
- `_API_ENTRIES`：更新 `m_ai_createScan`（返回值变）、加 `q_ai_getScanStatus`、更新 updateInstall。
- 重新生成 schema 类型 + persisted-query，与服务端 1:1。

## 11. 前端改造要点（供前端 agent review）

- `runScanFlow`：从「createScan 直接拿 ScanRecord」改为「createScan 拿 scanId → 轮询 q_ai_getScanStatus → SUCCESS 后 q_ai_findMyScanById 取权威记录 → SQLite 覆盖」。复用已有 DeepResearch 轮询/恢复基建（pending 列 + resume + 回前台恢复），scan 加对应 `pendingScanId`。
- mutation 返回 status=40(TASK_SUBMISSION_FAILED) 的早检查（同 DeepResearch）。
- **push 接收 + 深链**：收 push 的 `link`，校验 `/p/{projectId}/` 的 projectId == 本地 → 路由 scan-result 页；不符跳过。
- **授权 + 开关**：发起 scan 提示 + 请求通知授权；设置页「扫描结果通知」开关 → updateInstall；拒绝授权 → 开关改关。
- FCM token 上报（确认已在上报 Install.fcm_token）。
- **DeepResearch 连带（§8）**：INSUFFICIENT_IMAGE 等判定改从权威 basicResult.scan_status 读（任务已 SUCCESS）。

## 12. 错误处理与边界

| 场景 | 处理 |
|------|------|
| AI 调用抛异常 | CAS 20→40，error_code=AI_FAILED |
| AI 正常返回（任何业务 status） | CAS 20→30 + basicResult + 扣配额 + 发 push |
| 查询惰性超时 | IN_PROGRESS 且 updated_at 超 5 min → CAS 20→40，TIMEOUT |
| executor 提交失败 | fetcher catch → CAS 20→40，TASK_SUBMISSION_FAILED，返回终态 |
| 后台未预期 crash | 兜底 catch → CAS 20→40，INTERNAL_ERROR |
| push 发送失败 | 只记 WARN，不影响已落库 scan |
| 通知开关关 | 后台发前查 install，false 跳过（不取 token、不发） |
| 失效 fcm_token | SDK 返回 UNREGISTERED/INVALID_ARGUMENT → 清空 install.fcm_token |
| owner-scoped | 创建、查询校验 projectId+customerId；非本人 NOT_FOUND |

## 13. 测试计划（非框架、最小可运行校验）

1. **scan CAS 终态幂等**：超时 FAILED 与后台成功竞争 → 单一终态；配额只变一次。
2. **两 status 维度**：AI 返回 INSUFFICIENT_IMAGE → 任务 status=SUCCESS、配额已扣、basicResult.scan_status=INSUFFICIENT_IMAGE 照存。
3. **配额成功才扣**：AI 异常/超时不扣；AI 正常返回扣；预检 used>=limit 拒绝。
4. **NotificationContent 组装纯函数**：物品名有→用，缺失→回退模板；link 格式；主图 URL。
5. **开关前置**：scan_result_noti_enabled=false → 不发（PushChannel 假实现验证未被调用）。
6. **寻址回退**：有 fcm_token 用 token；无 → topic install_{id}。
7. **push 失败不影响主流程**：PushChannel 抛异常 → scan 仍 SUCCESS，仅 WARN。
8. **DeepResearch 修正回归**：AI 业务 status 差时任务仍 SUCCESS、扣配额（原 AI_STATUS_REJECTED 用例改断言）。
9. **owner-scope**：跨 customer getScanStatus NOT_FOUND。

## 14. 实现期验证点

1. Firebase Admin SDK 初始化凭证的注入方式（service account JSON 路径 vs 内联）与各环境配置。
2. FCM topic 订阅：客户端是否已订阅 `install_${installId}`（topic 回退依赖它）——需与前端确认，若未订阅则 topic 回退无效，仅 fcm_token 路径有效。
3. updateInstall 现有结构支持新增 `scan_result_noti_enabled` 字段的部分更新。
4. 后台任务 offline ActionContext 的 scanRunner 调用（同 DeepResearch 验证点）。
