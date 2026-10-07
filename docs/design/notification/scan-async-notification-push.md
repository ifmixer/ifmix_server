# Scan 异步化 + Notification 推送模块 设计文档

- 日期：2026-10-03
- 状态：**已实现（V12，2026-10-05 合入 main）**——scan 异步化（CAS + pending 预留台账）、`modules/notification`（Facade/DispatchService/PushChannel + FCM）均已上线
- 模块：`core-api` / `modules/ai`（scan 异步化）、`modules/notification`（新增通用通知模块）
- 相关前端：`/Users/jason/ai/myprojects/antique`（需同步改造，前端 agent review）
- 关联：复用 `../ai/deep-research-async.md` 的异步化基建；并对其做一处连带修正（§8）

## 1. 背景与目标

1. **scan 同步调用易超时**：`m_ai_scan_createOne` 当前同步跑 AI（事务外）再落库，AI 耗时可能触发网关超时。→ **异步化**：mutation 立即创建 IN_PROGRESS 记录返回 scanId，后台跑 AI，前端轮询（复用 DeepResearch 模式）。
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
| 6 | 配额语义 | 配额 = 扫描**次数**承诺。AI 正常返回即扣（INSUFFICIENT_IMAGE 也扣）。**用额度预留模型**（pending_scan_count）解决异步并发窗口，见 §4.5 |
| 7 | push 内容 | 模板+物品名（按 scan.locale，缺失回退纯模板）、主图 public URL（无 MAIN/构造失败则不带图）、深链 `/p/${projectId}/scan-result/${scanId}` |
| 8 | 何时发 push | CAS 成功到 SUCCESS && 开关=true && 未被 TIMEOUT/FAILED 抢先。INSUFFICIENT_IMAGE 也发，但文案不得暗示"识别成功" |
| 9 | 通知开关 + token 有效性 | Install 加 `scan_result_noti_enabled`（默认开；拒绝授权→前端改关）+ `fcm_token_valid`（默认 true；仅 UNREGISTERED 等永久失效才标 false，带 token 条件避免误伤轮换）。**不删除 fcm_token** |
| 10 | DeepResearch 连带修正 | 移除 isSuccess 业务判定 / AI_STATUS_REJECTED 码 / 业务 status 的 fail 分支（§8） |
| 11 | 僵死 pending 清理 | 无 sweeper。查询超时 + **下次 createScan 预留前清理本 customer 超 5min 的 IN_PROGRESS**（CAS→FAILED 释放 pending），再预留本次（§4.6） |

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
  → mutation m_ai_scan_createOne（立即返回, <1s）
      [同一事务]
      1. stale cleanup：清理本 customer 超 5min 的 IN_PROGRESS scan（CAS 20→40 TIMEOUT，
         CAS 成功才释放其 pending）——见 §4.6
      2. 条件预留：单条 UPDATE（WHERE scan_count+pending_scan_count<scanQuota）pending+1，
         affected=1 → 预留成功；affected=0 → QUOTA_EXCEEDED 回滚（见 §4.5，不先查后改）
      3. 创建 ScanRecord status=20(IN_PROGRESS)，带 images，basicResult=null，latestDeepResearchId=null
      → 事务提交后：经 executor 启动虚拟线程后台任务
      4. 返回 { scanId, status: 20, errorCode: null }
  → 后台任务 ScanTaskService.runScanTask（AI 调用，事务外）
      a. AI 调用抛异常 → [单短事务] CAS 20→40, error_code=AI_FAILED, pending -= 1（CAS 赢家才动）
      b. AI 正常返回（任何业务 status）→ [单短事务]:
           CAS 20→30 + 写 basicResult + pending -= 1 + scan_count += 1（额度转移，CAS 赢家才动）
         事务成功后（事务外）→ notificationFacade 发 push（异常只记 WARN）
  → 前端轮询 q_ai_scan_getStatus(scanId)
      - IN_PROGRESS：updated_at 超 5 min → [单短事务] CAS 20→40 TIMEOUT + pending -= 1；否则继续
      - FAILED：返回 status + errorCode
      - SUCCESS：→ q_ai_scan_getById(scanId) 取权威 ScanRecord（含 basicResult）→ SQLite 覆盖
```

### 4.3 复用 DeepResearch / scan 特有

**复用**：CAS 终态、惰性超时(5min)、submit 失败处理(TASK_SUBMISSION_FAILED)、后台虚拟线程 executor、TxRunner 事务边界、offline ActionContext 构造、兜底 catch(INTERNAL_ERROR)。

**scan 特有/简化**：
- **无 latest 指针、无 (created_at,id) 比较、无 FOR UPDATE**——scan 一次创建一条记录，无并发竞争 latest 的场景。
- 记录创建时就带 images；basicResult 在成功 CAS 时回写。
- **无 AI_STATUS_REJECTED**：AI 正常返回即任务 SUCCESS（决策 5/6）。
- **配额用预留模型**（见 §4.5），而非 DeepResearch 的"成功时封顶自增"——异步 scan 有并发窗口，必须预留。

### 4.4 数据模型变更

**`core_ai_scan_record`**：
- `status`：复用现列，码表重定义为 10/20/30/40（现无人读，安全）。创建写 20，CAS 终态改 30/40。
- 新增 `error_code: String?`、`error_details: jsonb?`。
- **status 历史回填**（前端 agent #1）：V12 迁移前先统计线上 `status` 分布；已知旧完成记录 `20→30`、旧失败码 `30→40`；旧 `10/11` 必须先确认实际含义和数量再决定（保留/回填失败/按 basic_result 是否为空判断），**不盲改**。迁移主体：
  ```sql
  UPDATE core_ai_scan_record SET status = CASE status
    WHEN 20 THEN 30  WHEN 30 THEN 40  ELSE status END
  WHERE status IN (20, 30);
  -- 旧 10/11 处理以线上预检为准
  ```

**`core_ai_customer_scan_metrics`**：新增 `pending_scan_count integer NOT NULL DEFAULT 0`（额度预留，§4.5）。

### 4.5 配额预留模型（前端 agent #2，核心修正）

**问题**：异步化后，仅"预检 + 成功时封顶自增"有并发窗口——剩 1 额度时两个 scan 都过预检并调用 AI，先完成者 scan_count +1 成功，后完成者 `tryIncrementScanCount` 返回 0。忽略返回值 → 免费 scan；标失败 → 丢弃已生成的 AI 结果。

**解法**：额度预留（reservation），`scan_count + pending_scan_count` 共同占额。

| 时机 | pending_scan_count | scan_count |
|------|------|------|
| 创建任务成功 | +1 | 不变 |
| AI 正常返回、CAS 20→30 成功 | −1 | +1 |
| AI 技术失败、CAS 20→40 成功 | −1 | 不变 |
| executor submit 失败、CAS 成功 | −1 | 不变 |
| 查询/cleanup 发现超时、CAS 成功 | −1 | 不变 |
| CAS 已被其它路径终结 | 不变 | 不变 |

**硬约束**：
- 创建记录 + 预留额度（+pending）**同一事务**。
- 成功回写：CAS 20→30 + 写 basicResult + `pending−1,scan_count+1` **同一短事务**。
- 失败：CAS 20→40 + `pending−1` **同一短事务**。
**硬约束（并发正确性，前端 agent #1/#2——必须是单条条件 UPDATE，不能"先查后改"）**：

1. **预留（创建端，同创建事务内）** —— `ensureRow` 后执行**单条条件 UPDATE**，不先 `findCounts` 再无条件 +1（READ COMMITTED 下先查后改仍有并发窗口）：
   ```sql
   UPDATE core_ai_customer_scan_metrics
   SET pending_scan_count = pending_scan_count + 1, updated_at = now()
   WHERE project_id = :projectId AND customer_id = :customerId
     AND scan_count + pending_scan_count < :scanQuota;
   ```
   - affected=1 → 预留成功，再 INSERT ScanRecord(IN_PROGRESS)；
   - affected=0 → `QUOTA_EXCEEDED`（回滚）；
   - INSERT 失败 → 整事务回滚，预留自动回滚。
   - 这样"最后 1 额度并发创建两个 scan"真正只成功一个。

2. **成功转移（CAS 20→30 赢家，同短事务）** —— 单条条件 UPDATE，`pending > 0` 显式约束（不用 GREATEST 掩盖）：
   ```sql
   UPDATE core_ai_customer_scan_metrics
   SET pending_scan_count = pending_scan_count - 1, scan_count = scan_count + 1, updated_at = now()
   WHERE project_id = :projectId AND customer_id = :customerId AND pending_scan_count > 0;
   ```
   - **必须影响 1 行**；affected=0（pending 账本异常）→ 抛 INTERNAL 回滚本次终态转换，不静默继续（否则 pending=0 还 scan_count+1 会破坏账本）。

3. **释放（CAS 20→40 赢家：失败/超时/submit 失败，同短事务）**：
   ```sql
   UPDATE core_ai_customer_scan_metrics
   SET pending_scan_count = pending_scan_count - 1, updated_at = now()
   WHERE project_id = :projectId AND customer_id = :customerId AND pending_scan_count > 0;
   ```
   - **必须影响 1 行**；affected=0 → 抛 INTERNAL 回滚本次终态转换。

4. **只有 CAS 赢得终态的一方调整 metrics**：CAS affected=1 才执行 2/3 的 metrics UPDATE；CAS=0（已被其它路径终结）**完全不触碰 metrics**（防重复释放/转移）。
5. 额度判断只存在于 1 的 WHERE 子句（`scan_count + pending_scan_count < scanQuota`），不额外先 `findCounts`。
6. `tryIncrementScanCount`（旧同步模型"检查+封顶自增"）**不再用于 scan 异步完成判定**；新增上述三个原子方法（预留/转移/释放）。**不用 `GREATEST(...,0)` 兜底**——用 `pending_scan_count > 0` 条件 + affected 校验显式暴露账本异常。

### 4.6 僵死 pending 清理（无 sweeper，前端 agent #2/#3）

无后台定时任务。pending 预留不能永久占额，用两条惰性路径收敛：
- **查询端**：`q_ai_scan_getStatus` 见 IN_PROGRESS 且 `updated_at < now−5min` → CAS 20→40(TIMEOUT) + 释放 pending，重读返回终态。
- **创建端**：`m_ai_scan_createOne` 预留前，先清理本 customer 超 5min 的 IN_PROGRESS（逐条 CAS 20→40 TIMEOUT，CAS 成功才释放对应 pending），再做本次额度判断 + 预留。

**5min 的语义**：不是强制取消正在跑的 AI，而是"查询/下次创建**观察到**超时后，把任务持久化为 FAILED 的终止期限"。后台 AI 即使超时后才返回，也因 **CAS 20→30 失败而放弃**：不覆盖 FAILED、不写 basicResult、不动配额、不发 push。

## 5. Notification 模块（新增，通用）

### 5.1 结构（分层约定，模块名 notification / 简称 noti）

| 层 | 文件 | 职责 |
|----|------|------|
| Facade | `modules/notification/NotificationFacade.kt` | 统一入口 `sendToInstall(request: NotificationRequest)`；未来并列 `sendSms`/`sendEmail` |
| Service | `modules/notification/NotificationDispatchService.kt` | **持有 TxRunner 的后台服务角色**（类比 DeepResearchTaskService）：resolve install target → `PushChannel.send`（**事务外**）→ 若永久 token 失效则 `TxRunner.withTx` 更新 `fcm_token_valid`。承担事务边界 |
| Handler | `modules/notification/handler/NotificationHandler.kt` | **纯业务处理**：查 install（经 install 模块公开能力）、开关判断、channel 选择、destination 解析。**不开事务、不调外部 FCM** |
| Channel | `modules/notification/channel/PushChannel.kt`（`@Component`） | **纯发送**：只接收已解析的 destination + NotificationContent，不查 Install、不碰事务。未来 `SmsChannel`/`EmailChannel` 并列 |
| Infra | `infra/push/FcmConfig.kt`（`@Configuration`） | Firebase Admin SDK 初始化（§9.1） |
| DTO | `dto/notification/NotificationContent.kt` + `NotificationRequest.kt` | `NotificationContent{title,body,imageUrl?,link}`；`NotificationRequest{projectId, installId, content}`（install 是 project-scoped，离线任务需显式 projectId） |

> **事务与外部调用边界（前端 agent #3）**：外部 FCM 调用**永远在事务外**；token-valid 更新由 **DispatchService** 在发送后另开短事务（`TxRunner.withTx`）。Handler 保持纯业务、Channel 保持纯发送，均不持事务——与现有 DeepResearchTaskService 后台服务模式一致。

### 5.2 发送语义（push channel）

- **开关前置**：先查 install 的 `scan_result_noti_enabled`，false → 直接跳过（不取 token、不发）。
  - ponytail: 本期只有一个开关，直接查该字段；未来多通知类型再抽象「按类型查偏好」。
- **寻址（三态区分，前端 agent #5）**：
  ```
  notiEnabled=false                              → 跳过
  fcm_token != null && fcm_token_valid=true      → direct token
  否则                                           → topic install_{installId}
  ```
  三个状态语义独立：`scan_result_noti_enabled`(用户是否允许) / `fcm_token_valid`(上次 direct 投递是否确认失效) / token 是否为空(当前有无 direct 地址)。
- **内容**：FCM `notification.title/body` + `notification.image`(主图 URL) + `data.link`(深链)。**FCM data 必须全是字符串键值**（link/scanId/projectId 均序列化为 String）。
- **失效 token 处理（不删除，前端 agent #5）**：
  - **不删 fcm_token**；新增 `fcm_token_valid`。
  - 仅**明确永久失效**（如 `UNREGISTERED`）才标 `fcm_token_valid=false`；**不要**把所有 `INVALID_ARGUMENT` 当失效（它也可能来自非法 payload/图片 URL）。
  - 标失效**必须带发送时 token 条件**，避免 token 轮换后误伤新 token：
    ```sql
    UPDATE core_auth_install SET fcm_token_valid = false
    WHERE project_id = :projectId AND id = :installId AND fcm_token = :sentToken;
    ```
  - 客户端每次成功上报非空 fcmToken → 重置 `fcm_token_valid=true`。
- **发送失败绝不影响主流程**：在后台任务成功回写**之后**、事务外调用，异常只记 WARN。日志含 `projectId/installId/scanId/channel/destinationKind(token|topic)/FCM error code`——**不得记录完整 token 或凭据**。
- **外部 FCM 调用永远不在 DB 事务中**；token-valid 更新在发送后另开短事务。

### 5.3 复用性

scan 和未来 DeepResearch 都只调 `notificationFacade.sendToInstall(NotificationRequest(projectId, installId, content))`，各自组装 content。notification 模块不含业务逻辑；install 读取/token 更新走 install 模块公开能力，不穿透到 `modules/install/repo`。

## 6. 通知内容与深链

### 6.1 scan 侧 NotificationContent 组装（降级规则，前端 agent #9）

- **title/body**：按 `scan.locale` 选模板 + 物品名（`basicResult.object_overview.name`）。name 缺失/空白/非字符串 → 纯模板。
  - AI 业务 status 为 INSUFFICIENT_IMAGE / NON_PHYSICAL_SUBJECT 时**仍发**，但文案不得暗示"识别成功"，如「扫描已完成，点击查看拍摄建议」。
- **imageUrl**：首个 category=MAIN 图片的 public URL。无 MAIN / URL 构造失败 → **不带 image**。
- **link**：`/p/${projectId}/scan-result/${scanId}`。
- **发送条件（全满足才发）**：CAS 成功到 SUCCESS && `scan_result_noti_enabled=true` && 任务未被 TIMEOUT/FAILED 抢先终结。

### 6.2 深链约定

- 格式 `/p/${projectId}/scan-result/${scanRecordId}`（服务端**逻辑**深链，非 Expo Router 文件路由）。
- 客户端（前端 agent #4）：messageHandlers 解析 `data.link` → 严格匹配 `/p/{projectId}/scan-result/{scanId}` → 校验 projectId==本地 → 映射到实际路由 `/result/{scanId}`。格式非法/ID 非法/project 不符 → 不导航。保留旧 `data.url` 兼容，新 scan push 统一用 `data.link`。

## 7. 通知开关与授权（决策 9，前端 agent #6）

- **Install 加字段**：`scan_result_noti_enabled: Boolean` 默认 `true`；`fcm_token_valid: Boolean` 默认 `true`（§5.2）。
- **updateInstall** 支持更新 `scan_result_noti_enabled`。
- **后台发 push 前**查开关（§5.2），false 跳过。
- **前端授权流程（不能只调系统权限 API，必须复用/拆出 push 注册流程）**：发起 scan 时请求授权，**授权成功后执行**：
  1. 获取 FCM token；2. 上报 fcmToken + Firebase Installation ID；3. 订阅 `install_${installId}`；4. `updateInstall(scanResultNotiEnabled=true)`。
  - 用户**拒绝授权** → `updateInstall(scanResultNotiEnabled=false)`。
  - 设置页重开开关且系统授权已允许 → 重新注册 token/topic 并设 true；系统仍拒绝 → UI 保持/回退为关。
- 客户端成功上报非空 fcmToken 时，服务端重置 `fcm_token_valid=true`（§5.2）。

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
| Schema | `schema/customer/ai.graphqls` | `m_ai_scan_createOne` 返回改 `{ scanId, status, errorCode }`；加 `q_ai_scan_getStatus(scanId): ScanStatus`；updateInstall input 加 `scanResultNotiEnabled` |
| DataFetcher | `bff/graphql/customer/ai/AiFetcher.kt` | `newScan` 改为建记录+预检+提交后启动后台任务+submit 失败处理；加 `getScanStatus` |
| Service | **新增** `modules/ai/ScanTaskService.kt` | scan 后台任务编排（类比 DeepResearchTaskService）：AI→CAS 回写→扣配额→发 push |
| Handler | `modules/ai/handler/ScanAggHandler.kt` | 建 IN_PROGRESS 记录（预检）；成功回写 CAS+basicResult+配额；惰性超时 CAS；组装 scan NotificationContent |
| Repository | `modules/ai/repo/ScanRecordRepository.kt` | 新增 `casSuccess`(写 basicResult)、`casFailed`、`insert`(IN_PROGRESS) |
| Facade | `modules/ai/AiFacade.kt` | 转发 createScanTask / getScanStatus |
| **新增模块** | `modules/notification/**` + `infra/push/FcmConfig.kt` + `dto/notification/**` | §5 整个 notification 模块 |
| Entity | `entity/install/Install.kt` | 加 `scanResultNotiEnabled: Boolean`、`fcmTokenValid: Boolean` |
| Install 侧 | updateInstall handler/facade/schema | 支持更新 `scan_result_noti_enabled`；成功上报非空 fcmToken 时重置 `fcm_token_valid=true` |
| Metrics | `entity/ai/CustomerScanMetrics.kt` + repo | 加 `pendingScanCount`；新增原子方法：条件预留(+pending)、条件转移(pending−1, scan_count+1)、条件释放(pending−1)；仅 CAS 赢家调用，均要求 affected=1，异常则回滚；**不用 GREATEST 兜底**（§4.5） |
| Migration | `db/migration/V12__...sql` | §11 |
| 依赖 | `core-api/build.gradle.kts` | 加 `com.google.firebase:firebase-admin`（**pin 固定版本**，不接受浮动） |

### 9.1 Firebase Admin 初始化与配置（前端 agent #8）

```yaml
app:
  noti:
    fcm:
      enabled: false                 # local/test 默认 disabled → 用 no-op PushChannel
      # credential-source: 明确选 文件路径 / 受控环境变量 / 工作负载身份 之一
```
- **local/test 默认 `enabled=false`**，装配 **no-op PushChannel**（无凭据也能启动、跑测试）。
- **production `enabled=true` 且缺凭据 → fail-fast**（启动即报错，不静默降级）。
- `FirebaseApp` 初始化**幂等**（Spring 测试/热重启不重复 init）。
- 凭据不得提交仓库/日志/GraphQL。

## 10. GraphQL 交付清单（Trusted Documents）

**服务端**：
- `schema/customer/ai.graphqls`：`m_ai_scan_createOne` 返回 `{scanId status errorCode}`；加 `q_ai_scan_getStatus` + `ScanStatus` type。
- updateInstall schema 加 `scanResultNotiEnabled`。
- `customer.json`：更新 `m_ai_scan_createOne`（回包选 `scanId status errorCode`）、加 `q_ai_scan_getStatus`、更新 updateInstall；DGS codegen。

**前端（antique）**：
- `_API_ENTRIES`：更新 `m_ai_scan_createOne`（返回值变）、加 `q_ai_scan_getStatus`、更新 updateInstall。
- 重新生成 schema 类型 + persisted-query，与服务端 1:1。

## 11. 前端改造要点（供前端 agent review）

- `runScanFlow`：从「createScan 直接拿 ScanRecord」改为「createScan 拿 scanId → 轮询 q_ai_scan_getStatus → SUCCESS 后 q_ai_scan_getById 取权威记录 → SQLite 覆盖」。复用已有 DeepResearch 轮询/恢复基建（pending 列 + resume + 回前台恢复），scan 加对应 `pendingScanId`。
- mutation 返回 status=40(TASK_SUBMISSION_FAILED) 的早检查（同 DeepResearch）。
- **push 接收 + 深链（§6.2）**：解析 `data.link` → 严格匹配 `/p/{projectId}/scan-result/{scanId}` → 校验 projectId → 映射到 Expo Router 实际路由 `/result/{scanId}`；非法/不符不导航。勿直接 `router.push` 逻辑深链。
- **授权 + 开关（§7）**：发起 scan 提示 + 请求授权；授权成功执行"取 token→上报→订阅 topic→开关 true"完整注册；拒绝 → 开关 false；设置页重开按系统授权状态处理。
- **DeepResearch 连带（§8）**：INSUFFICIENT_IMAGE 等判定改从权威 basicResult.scan_status 读；更新 `runDeepResearchFlow.test.ts`/恢复流程/rejection UI 中对 AI_STATUS_REJECTED 的旧断言。

## 12. 错误处理与边界

| 场景 | 处理 |
|------|------|
| AI 调用抛异常 | CAS 20→40，AI_FAILED，释放 pending |
| AI 正常返回（任何业务 status） | CAS 20→30 + basicResult + pending→scan_count 转移 + 发 push |
| 查询惰性超时 | IN_PROGRESS 且 updated_at 超 5 min → CAS 20→40，TIMEOUT，释放 pending |
| 创建端 stale cleanup | 预留前清理本 customer 超 5min IN_PROGRESS，CAS 成功才释放其 pending |
| executor 提交失败 | fetcher catch → CAS 20→40，TASK_SUBMISSION_FAILED，释放 pending，返回终态 |
| 后台未预期 crash | 兜底 catch → CAS 20→40，INTERNAL_ERROR，释放 pending |
| late success（超时后 AI 才返回） | CAS 20→30 失败 → 放弃：不覆盖 FAILED、不写结果、不动配额、不发 push |
| CAS 已被终结 | 不动 metrics（防重复释放/转移） |
| push 发送失败 | 只记 WARN，不影响已落库 scan |
| 通知开关关 | 后台发前查 install，false 跳过（不取 token、不发） |
| 永久失效 token | 仅 UNREGISTERED 等 → `fcm_token_valid=false`（带 token 条件），**不删 fcm_token** |
| owner-scoped | 创建、查询校验 projectId+customerId；非本人 NOT_FOUND |

## 13. 测试计划（非框架、最小可运行校验）

**配额/并发**：
1. 最后 1 额度并发创建两 scan → 只一个预留成功（`scan_count+pending_scan_count<quota`）。
2. AI 成功：IN_PROGRESS→SUCCESS、写结果、pending−1、scan_count+1。
3. AI 技术异常/submit 失败/TIMEOUT：IN_PROGRESS→FAILED、pending−1、scan_count 不变。
4. 查询超时 vs 后台成功竞争：单一终态；超时胜出时无结果、无扣额、无 push。
5. 两条超时/失败路径竞争：pending 只释放一次（CAS 赢家才动）。
6. INSUFFICIENT_IMAGE：任务 SUCCESS、配额完成转移、basicResult.scan_status 完整保留。

**通知**：
7. 开关 false → 不查 token、不发。
8. 寻址：valid token→direct；无 token/invalid→topic。
9. UNREGISTERED 仅将匹配旧 token 的 `fcm_token_valid` 标 false（带 token 条件）。
10. 新 token 上报重置 valid=true。
11. PushChannel 异常 → scan 仍 SUCCESS（仅 WARN）。
12. local/test 无凭据可启动 + 跑测试（no-op channel）。
13. NotificationContent 组装：name 有→用，缺失→纯模板；无 MAIN→不带 image。

**其它**：
14. 非 owner getScanStatus → NOT_FOUND。
15. DeepResearch 修正回归：AI 业务 status 差时任务仍 SUCCESS、扣配额（原 AI_STATUS_REJECTED 用例改断言）。

## 14. 实现期验证点

1. Firebase Admin SDK 初始化凭证的注入方式（文件路径 / 受控环境变量 / 工作负载身份三选一）与各环境配置；local/test 的 no-op 装配。
2. ~~FCM topic 订阅前提~~ **已由前端 agent #6 确认满足**：Antique 已上报 fcm_token、Firebase Installation ID、订阅 `install_${installId}`、具备前台/冷启动点击处理。topic 回退有效。
3. updateInstall 现有结构支持新增 `scan_result_noti_enabled` 字段的部分更新。
4. 后台任务 offline ActionContext 的 scanRunner 调用（同 DeepResearch 验证点）。
5. **V12 迁移前必须线上预检** `core_ai_scan_record.status` 分布（§4.4）：确认旧 10/11 的实际含义与数量后再定回填策略，不盲改。
6. CustomerScanMetrics 的 pending/scan_count 原子方法用 Jimmer native `sql(...)` 表达（参考现有 `tryIncrementScanCount` 的 `%e/%v` 写法）。
