# Install↔Customer 强关系与客户端容错修改清单

> **2026-10-06 legacy 删除**：v1.0.6 未发布，`x-install-id` 回退、legacy 限流层、DateTime epoch millis 兼容已全部删除；`install_id` 只写 token 可信 iid。下文涉及 legacy/兼容的表述为历史决策记录。


> **状态**：已实现并完成本地 V6、单元测试、真实 persisted-query 与 shared client 联调；未来 V7 NOT NULL 锁定延期
> **主实现仓库**：`ifmix_server / core-api`
> **客户端仓库**：`antique / apps/shared + apps/antique`
> **关联决策**：
> - `docs/design/install/install-tracking.md`
> - `docs/design/infra/idempotency.md`
> - `antique/docs/install-tracking-frontend-api.md`
> - `antique/docs/idempotency-frontend-api.md`

---

## 1. 目标

在不建设通用创建幂等的前提下，建立以下目标不变量：

```text
core_auth_install.id
    = API installId
    = install token iid
    = customer token iid
    = core_auth_install2customer.install_id
    = Customer 业务记录的可信 install_id
```

并保证：

1. 新匿名 Customer 必须携带有效 iid（token 类型不限——installToken 是主路径，含 iid 的 customer token 亦可）创建，并在同一事务内绑定 Install。
2. login 后维护正确 Install↔Customer 关系；refresh 续期并保留 iid/anonymous；若 refresh token 属于某 customer actor 且与 iid 未绑定，则补绑（幂等）。
3. 已有有效 customer session 不因本地 installStore 读取、createInstall 或 updateInstall 失败而无法调用普通业务。
4. App 启动不阻塞 UI、不等待凭证创建；但 install 作为设备级凭证尽早在后台创建——有网即建(幂等),没网则挂网络监听、恢复联网后再建。匿名 Customer 仍惰性,按需创建。
5. 临时网络/服务错误不能清除已有登录身份或静默切换 Customer。
6. 允许 createInstall/createAnonymous 重试产生孤立记录，以可用性换取实现简单；重复由限流、清理和监控处理。
7. 所有 “My” 业务查询和修改必须按 customer owner 隔离。

---

## 2. 非目标

本次不实现：

- 通用 `idempotencyKey`；
- 客户端指定服务端业务主键；
- `ALREADY_EXISTS` 创建恢复协议；
- response/token replay；
- pending-operation journal；
- AI Exactly Once；
- 业务 mutation 的基础设施透明重试；
- App Attest / Play Integrity（只保留未来接入点）。

---

## 3. 当前实现与目标差距

### 3.1 服务端

| 范围 | 当前实现 | 目标 |
|------|----------|------|
| Install ID | `core_auth_install.id` 与 `install_id` 分别生成 | 只保留一个 ID：PK `id` 即 API installId/JWT iid |
| createAnonymous | `installToken`/iid 可空 | 新客户端必须携带有效 iid（token 类型不限），iid 有效 |
| customer token | `signAccess.installId` 可空 | 新 customer token 必须含 iid；nullable 仅用于 manager/legacy 解析 |
| refresh | 将请求 iid 写入新 token，保留 anonymous，未调用 bind | 续期并保留 iid/anonymous；customer refresh token 与 iid 未绑定时补绑（bind 幂等） |
| login | token 可选；当前前端总发 installToken | 有当前 Customer 时发 customer token以保留匿名 actor/迁移；无 Customer 时发 installToken |
| 业务 install_id | `String?`，来自 `x-install-id` header | `UUID`，来自已验签 token iid |
| Scan by-id | 仅按 project+id | 按 project+customer+id；跨用户返回 NOT_FOUND |
| Scan 修改 | 部分 update/delete/deep-research 仅按 project+id | 所有 Customer 私有路径增加 owner 条件 |
| cleanup | 当前脚本行为与长期清理目标尚未重新设计 | 本期完全延期：不改脚本、不增加 RESOURCE_GUARD_TABLES、不恢复删除循环 |
| Install 清理 | 无 | 本期不设计、不实现；后续另起 cleanup 方案 |

### 3.2 前端

| 范围 | 当前实现 | 目标 |
|------|----------|------|
| `guarded()` | 所有业务先 `ensureInstall()` | 已有 CustomerSession 时直接使用；只有签发/刷新身份才要求 installToken |
| install 持久化 | SecureStore 写成功后才写内存 cache | 服务端成功后先缓存，SecureStore best-effort + 后台重试 |
| customer 持久化 | 内存赋值后 SecureStore 异常继续向上抛 | 当前会话保留内存 token，记录并重试持久化 |
| refresh 异常 | catch all 后重建匿名身份 | 只有明确 session 失效才重建；网络/429/5xx 保留原身份 |
| login token | 总是 installToken | 当前有 customer session 时用 customer token；否则用 installToken |
| 凭证创建重试 | 无统一有限重试 | 仅 createInstall/createAnonymous/refresh 做受控重试 |
| App 启动 | 已不联网创建凭证 | install 尽早后台创建:有网即建、没网等网络恢复再建;不阻塞 UI。匿名 Customer 仍惰性 |
| 业务 mutation 重试 | 无自动重试 | 保持不变；用户显式重试，接受重复 |

---

## 4. 服务端修改清单

### 4.1 数据库迁移：Install ID 收敛

**文件：**

- 新增 `core-api/src/main/resources/db/migration/V6__install_id_unification.sql`
- 修改 `core-api/src/main/kotlin/com/ifmix/core/api/entity/install/Install.kt`
- 修改 `core-api/src/main/kotlin/com/ifmix/core/api/modules/install/repo/InstallRepository.kt`
- 修改 `core-api/src/main/kotlin/com/ifmix/core/api/modules/install/handler/InstallAggHandler.kt`

#### 目标结构

```sql
CREATE TABLE core_auth_install (
    id uuid PRIMARY KEY,
    project_id text NOT NULL,
    -- 不再有独立 install_id
    ...
);
```

新记录：

```kotlin
val installId = UuidV7.generate()
Install {
    id = installId
}
jwt.signInstall(installId.toString(), projectId)
```

`CreateInstallResult.installId` 返回 `Install.id`；`InstallRepository.findByInstallId` 删除或改为 `findById`，`updateInstall` 直接按主键查。

#### V6 迁移建议

当前旧 token iid 指向 `core_auth_install.install_id`，而非内部 PK。由于 relation 的 `install_id` 也保存该业务值，可将主键收敛到旧业务 ID：

1. 预检查 `install_id` 是否全局重复；当前唯一约束只覆盖 `(project_id, install_id)`，不能直接假设全局唯一。预检不通过必须让 migration 明确 abort，不得跳过冲突行或部分更新。
2. 预检查是否存在 `某行 id == 另一行 install_id` 的交叉冲突。
3. 无冲突时执行 `UPDATE core_auth_install SET id = install_id`。
4. 删除 `core_auth_install_project_install_uq`。
5. 删除 `core_auth_install.install_id`。
6. relation 表无需改值，其 `install_id` 已是旧 token iid，迁移后自然指向新 PK。

> 若尚未部署任何共享环境，可直接修正 V5 并重建本地数据库；若 V5 已在共享/线上环境执行，必须新增 V6，不能修改历史 migration。

### 4.2 业务表可信 install_id

**文件：**

- `core-api/src/main/resources/db/migration/V6__install_id_unification.sql`：收敛 core_auth_install PK，并把业务 install_id 转为 nullable UUID
- 未来文件（本轮不得创建/执行）`V7__require_trusted_install_id.sql`：仅在可信回填完成后才另建，用于 `SET NOT NULL`；置于 Flyway 目录会紧随 V6 自动执行、无法延期，故本轮禁止提前创建
- 修改 `entity/common/InstallIdProps.kt`
- 修改：
  - `modules/ai/handler/ScanAggHandler.kt`
  - `modules/ai/handler/ScanCollectionAggHandler.kt`
  - `modules/cs/handler/FeedbackAggHandler.kt`
  - `modules/cs/handler/SupportRequestAggHandler.kt`

当前业务列为 `varchar(...)/String?`，且写入：

```kotlin
this.installId = action.installId // x-install-id，不可信
```

目标写入：

```kotlin
this.installId = action.mustGetTokenInstallId()
```

目标实体：

```kotlin
interface InstallIdProps {
    val installId: UUID
}
```

涉及表至少包括：

- `core_ai_scanrecord`
- `core_ai_scancollection`
- `core_cs_feedback`
- `core_cs_supportrequest`

#### NOT NULL 迁移

- **无线上旧数据：** 一次性把列改为 `uuid NOT NULL`。
- **有 legacy 数据：** 先改代码只写 token iid，列暂时 nullable；仅对可回填出可信来源（token iid）的 legacy null 行做可信回填，无法可信回填的行保持 nullable 并延期，绝不删除 resource、绝不静默清理；待全部可信回填完成后，才另建未来 V7（本轮不得创建/执行）用 `SET NOT NULL` 收尾。
- 不拆 `client_install_id`：业务表只保留单一 `install_id` 列，直接存 token iid。旧 `x-install-id` 不再单独落表；线上已有的历史 `install_id` 数据可直接信任、无需与可信 iid 区分或分列。

### 4.3 ActionContext 与 token 类型校验

**文件：**

- `infra/auth/RequestParser.kt`
- `infra/http/ActionContext.kt`
- `infra/graphql/ActionContextProvider.kt`
- 对应 `RequestParserTest.kt`

新增或收敛：

```kotlin
val tokenType: Int?
fun mustGetTokenInstallId(): UUID
```

要求：

- install token：`type=5`、无 actor、iid 必填；
- customer token：`type=10`、actor 必填，新 token iid 必填；
- manager/legacy 的解析模型可继续 nullable，但 Customer 业务入口必须显式 require iid；
- `x-install-id` 不参与 token/owner 校验。

createAnonymous / refresh 只要求携带**有效可信 iid**（`mustGetTokenInstallId()`），token 类型不限：installToken 是首装主路径，含 iid 的 customer token 亦可 bootstrap/续期。不再强制 type=5，也不再拒绝 customer token。

### 4.4 createAnonymous 要求有效 iid

**文件：**

- `bff/graphql/customer/customer/CustomerFetcher.kt`
- `modules/auth/handler/AuthAggHandler.kt`
- 对应单元/集成测试

Fetcher 在进入事务前要求（`mustGetTokenInstallId()`）：

```text
installId != null   // 有效可信 iid
```

token 类型不限（installToken 或含 iid 的 customer token 皆可）；无有效 iid 返回 `UNAUTHORIZED`；本期不新增专用错误码。

Handler 把 installId 改为非空局部变量，并在一个 `GlobalTxRunner` 事务内完成：

1. 创建 Customer；
2. 创建 refresh token；
3. bind Install→Customer；
4. 签发含 iid 的 customer access token。

任一步失败，整个事务回滚。

### 4.5 login 的 token 选择与关系维护

**服务端文件：**

- `bff/graphql/customer/auth/AuthFetcher.kt`
- `modules/auth/handler/AuthAggHandler.kt`

服务端 login 保持 actor 可选，但强制 iid：

```text
有当前 CustomerSession：
  Authorization = customer access token
  → actorId = 当前匿名/登录 Customer
  → iid = customer token iid
  → 保留 promote/merge 上下文

没有当前 CustomerSession：
  Authorization = installToken
  → actorId = null
  → iid = install token iid
  → 创建/绑定最终 Customer
```

login 成功后必须 bind `iid → ownerId`，并签发含同一 iid 的 customer token。

> 当前客户端总是发送 installToken，会让服务端看不到当前匿名 actor，从而绕过原有 promote/merge 上下文；必须和前端同时修正。

### 4.6 refresh 续期并补绑 actor↔install

**文件：**

- `modules/auth/handler/AuthAggHandler.kt`
- `core-api/src/test/.../AuthRefreshInstallTest.kt`

refresh 请求：

```text
Authorization: Bearer <accessToken>   // customerToken 或 installToken 均可
body: { refreshToken }
```

Authorization 携带的 accessToken 提供可信 iid，可能是 **customerToken（type=10，含 actor）**，也可能是 **installToken（type=5，无 actor）**，两者都支持——只要求 iid 有效（`mustGetTokenInstallId()`）。installToken 当前无过期时间，实际很少走 refresh 提供 iid；此处兼容两类来源。

校验后得到：

- token iid（customerToken/installToken 皆可提供）；
- refreshToken → actorId/actorType。

refresh 负责：

```text
轮换 refresh token
签发新 access token
保留 iid
保留 Customer.anonymous
```

**关系维护：** 若 refresh token 属于某 customer actor（`actorType == CUSTOMER`），检查该 actor 与 iid 的关系——未绑定则补绑（`installFacade.bind`，幂等：已绑定 NoOp、软删则复活）。这修复 legacy/漏绑场景，使 refresh 也收敛到「一 install 一 customer」不变量。install refresh token 无 actor，不触发绑定。

### 4.7 Customer 业务入口强制 iid

**文件：**

- 各 Customer GraphQL Fetcher 或统一的 `ActionContextProvider` require 档位

建议增加明确档位，而不是每个 Handler 手写：

```kotlin
fromDfe(
    requireActorType = CUSTOMER,
    requireInstall = true,
)
```

适用于 scan、collection、feedback、support、IAP 等 Customer 业务。本轮选择部署方案 A：直接强制可信 iid；未实现按 project/appVersion 只记录缺失的 feature flag。若未来确认存在活跃旧版本，必须另起兼容任务，不能假设当前代码已支持灰度。

### 4.8 Scan owner 安全修复

**文件：**

- `modules/ai/repo/ScanRecordRepository.kt`
- `modules/ai/handler/ScanAggHandler.kt`
- `bff/graphql/customer/ai/AiFetcher.kt`
- 新增 owner/security 测试

新增：

```kotlin
findByIdOwned(mc, projectId, customerId, id)
```

条件必须包含：

```text
project_id = projectId
customer_id = actorId
id = scanId
```

至少修复 `q_ai_scan_getById`。同一轮必须审计并补齐：

- `m_ai_scan_updateMyOne`
- `m_ai_scan_deleteMyOne`
- Deep Research 图片更新、读取和结果写回
- collection add/remove 对 scan ownership 的验证

跨用户访问统一返回 `NOT_FOUND`，不要泄露记录是否存在。公开 Scan 如有需求，另建显式 public query。

### 4.9 Cleanup 完全延期

本期不修改：

- `core-job/src/main/kotlin/com/ifmix/core/job/customer/AnonymousCleanupCleaner.kt`；
- `RESOURCE_TABLES`；
- 任何 resource existence guard；
- Customer/Install 清理 SQL；
- 清理调度、阈值和测试。

不要在本任务中引入 `RESOURCE_GUARD_TABLES`，也不要恢复当前被注释的资源删除循环。cleanup 的候选条件、资源保护和身份元数据删除顺序后续另起设计评审；本清单不对未来实现作推断。

### 4.10 限流与观测

保留：

- createInstall IP 限流；
- createAnonymous IP 限流；
- Customer 扫描配额；
- mutation 不做透明自动重试。

新增指标/日志：

- credential bootstrap 失败率和错误码；
- 每 Install 创建匿名 Customer 的频率；
- 无 iid customer token 的请求数；
- 孤立 Install/Customer 数量；
- SecureStore 持久化失败由客户端 Crashlytics 上报。

---

## 5. 前端修改清单

### 5.1 `guarded()` 不阻塞已有 CustomerSession

**文件：** `apps/shared/src/api/client.ts`

当前：

```ts
await ensureInstall();
await session.ensure();
```

目标：

```ts
await session.ensure();
```

`session.ensure()` 在已有有效 CustomerSession 时直接返回；只有没有 session、需要签发匿名身份时，`issueAnonymous()` 才调用 `ensureInstall()`。

这样本地 installStore 读取失败、createInstall 故障或 updateInstall 失败不会阻塞已有 customer token 的普通业务。

### 5.2 install credentials 内存优先

**文件：**

- `apps/shared/src/api/client.ts`
- `apps/antique/src/lib/installStore.ts`
- 对应 `credentialStores.test.ts` / `client.install.test.ts`

createInstall 成功后：

```ts
cachedInstall = credentials;
try {
  await installStore.save(credentials);
} catch (error) {
  onCredentialPersistenceError?.(error);
  schedulePersistRetry(credentials);
}
return credentials;
```

要求：

- SecureStore save 失败不丢掉本次服务端响应；
- 当前进程继续使用内存 installToken；
- 后台重试必须有次数上限，不能无限 timer；
- 不回退明文 AsyncStorage；
- App 被杀后重新创建 Install，接受孤立记录。

SecureStore **读取失败不能等同于“key 不存在”**：读取异常时保留存储，不清 key、不覆盖旧值，当前网络操作报错并允许下次重试。

### 5.3 customer token 持久化容错

**文件：**

- `apps/shared/src/api/session.ts`
- `apps/antique/src/lib/tokenStore.ts`

`write(tokens)` 已先赋内存 cache，但当前 `store.save()` 异常仍会让签发流程失败。目标：

- 当前内存 session 保持可用；
- 通过 callback/telemetry 报告持久化错误；
- 有限后台重试 SecureStore；
- 不清除原有 SecureStore token，除非明确 logout/session invalid；
- legacy AsyncStorage → SecureStore 仍保持“先写成功、后删除旧值”。

### 5.4 refresh 只在明确失效时重建身份

**文件：** `apps/shared/src/api/session.ts`

当前 `catch { issueAnonymous() }` 会把网络错误、429、500、503 都视为身份失效。

目标分类：

```text
401000 / 401003：
  refresh token/session 明确失效
  → 通知登录失效（若非匿名）
  → 重新签发匿名身份

网络异常 / 429 / 500 / 503：
  → 保留当前 session
  → 向上传递错误
  → 下次操作继续尝试
```

若 access token 只是进入 3 分钟提前刷新窗口但尚未过期，refresh 遇到临时错误时可继续尝试原业务请求；真正 401 后再让当前操作失败。

### 5.5 login token 选择

**文件：** `apps/shared/src/api/client.ts`

当前所有 login 都发送 installToken。目标：

```text
有当前 CustomerSession：
  先确保 access token 可用
  Authorization = customer access token
  → 服务端获得 cur actor + iid，可执行匿名转正/合并

没有 CustomerSession：
  ensureInstall()
  Authorization = installToken
```

如果 customer token 在 login 时返回 TOKEN_EXPIRED，先使用 installToken+refreshToken 刷新，再重试 login；不能直接丢掉当前匿名 actor，否则匿名数据迁移会丢失上下文。

### 5.6 createAnonymous 携带 install 并容错重建

**文件：** `apps/shared/src/api/client.ts`

保持：

```text
ensureInstall()
→ createAnonymous(makeInstallGqlOpts())
```

不增加 authless fallback。创建失败只影响当前需要网络的操作；App 根布局和本地页面保持可用。

**install token invalid 恢复：** 若 createAnonymous 报「install token invalid」（`isSessionInvalid` = 401000/401003），说明本地 install 凭证已失效（如服务端 install 记录被清）。客户端应**删除本地 install 信息（id + token）**（`clearInstall()`：清内存 cache + `installStore.clear()`），然后**重新走 createInstall 流程**并重试一次 createAnonymous；重建有次数上限（最多一次），不无限循环。已在 `raw.anonymous()` 循环实现：

```text
for attempt in 0..1:
  ensureInstall()          // 无本地 install 时惰性 createInstall
  try createAnonymous()
  catch isSessionInvalid:
    clearInstall()         // 删本地 id + token
    if attempt < 1: continue   // 重建一次
  throw
```

响应丢失时允许再次创建，服务端关系逻辑会把当前 Install 绑定到新 Customer；只有没有任何业务 resource 的孤立匿名空壳才可由清理任务回收。

### 5.7 有限凭证重试

仅以下操作允许有限内部重试：

- createInstall；
- createAnonymous；
- refresh（仅临时错误）。

建议：

```text
第 1 次：立即
第 2 次：300 ms + jitter
第 3 次：1 s + jitter
随后向上抛错
```

规则：

- 网络错误、502/503 可重试；
- 429 尊重 `Retry-After`，不在前台长时间等待；
- 400/403 不重试；
- 401 installToken invalid：清 install 后最多重建一次；
- 普通 createScan/support/feedback 不自动重试。

### 5.8 不增加 boundInstallId 本地元信息

本轮未在 `AuthTokens` 增加 `boundInstallId`。强制方案下，新 createAnonymous/login token 均含 iid；旧 token 通过 refresh 升级。客户端不解析 JWT，也不维护第二份关系真相。

### 5.9 UI 错误恢复

- App 启动不显示全局 credential loading；
- scan/support/payment 的 loading 只覆盖当前操作；
- credential 创建失败后必须复位 `scanningRef/submitting`；
- 展示可重试错误，不把重复风险暴露为阻断确认框；
- 本地 history/settings 始终可访问。

---

## 6. GraphQL 与 persisted query

本轮原则上不新增 GraphQL input，也不增加幂等字段。

鉴权传输：

| 操作 | Authorization |
|------|---------------|
| createInstall | 无 |
| createAnonymous | 含有效 iid 的 token（installToken 或 customer token） |
| login（有当前 Customer） | customer access token |
| login（无当前 Customer） | installToken |
| refresh | 含有效 iid 的 accessToken（customerToken 或 installToken）；refreshToken 仍在 body |
| 普通 Customer 业务 | customer access token |
| updateInstall | installToken 或含 iid 的 customer token |

线上 persisted query 以服务端 `customer.json` 为准。若 operation 文本未变，只调整鉴权行为，不需要更新 allowlist；双方仍应运行现有一致性检查。

---

## 7. 部署与兼容顺序

### 方案 A：无必须兼容的线上旧版本（本轮已选择）

1. 执行 Install ID/业务 install_id 的 V6 migration（仅 V6：收敛 PK + 转 nullable uuid，不含 NOT NULL）。
2. 服务端切换 token iid、可信业务写入和 owner 校验。
3. 服务端强制 createAnonymous/login/refresh 的 install 要求。
4. 发布客户端 session/login/persistence/retry 修改。
5. 验证后仅对可回填出可信来源的 legacy null 行做可信回填（无法回填则保持 nullable、延期，不删除 resource）；全部可信回填完成后才另建未来 V7 执行 `SET NOT NULL`。

### 方案 B：存在活跃旧版本（未实现，仅供未来参考）

1. 服务端先支持新 token/新关系写入，但暂不拒绝 legacy 无 iid。
2. 发布新客户端，所有新匿名创建和 refresh 都携带 installToken。
3. 监控无 iid 请求比例，按 `x-app-version` 或 project feature flag 灰度拒绝。
4. 比例降到可接受阈值后，服务端强制 iid。
5. 对可回填出可信来源的 legacy null 行做可信回填（无法回填则保持 nullable、延期，绝不删除 resource、绝不静默清理）；全部可信回填完成后，才另建未来 V7 执行 `SET NOT NULL`。

> 禁止先强制服务端再发布客户端，否则旧客户端会无法创建匿名 Customer。

---

## 8. 实施顺序

建议按以下顺序拆任务：

1. **安全先行**：修复 Scan owner 查询/修改范围并补测试。
2. **数据库模型**：Install ID 收敛、业务 install_id UUID 化；暂不急于 NOT NULL。
3. **服务端 token/auth**：tokenType、mustGetTokenInstallId、createAnonymous/login 建关系，refresh 续期并补绑 customer actor↔install。
4. **服务端可信写入**：Scan/Collection/Feedback/Support 改用 token iid。
5. **客户端 session 修复**：refresh 错误分类、guarded 去掉无条件 ensureInstall。
6. **客户端持久化容错**：内存优先、SecureStore 有限后台重试。
7. **客户端 login 选择**：customer token 优先保留 actor，无 session 才用 installToken。
8. **凭证有限重试与 UI 恢复**。
9. **监控**（cleanup 延期，不改脚本）。
10. **灰度强制 iid / NOT NULL 收尾**。

每一阶段独立发布、独立验证；不要把数据库强制、服务端拒绝和客户端升级压成一个不可回滚的大版本。

---

## 9. 测试清单

### 9.1 服务端

- install token：type=5、iid=`core_auth_install.id`、无 sub、无 exp；
- createAnonymous 无 token/无 iid 拒绝；携带有效 iid 的 token（installToken 或含 iid 的 customer token）成功；
- createAnonymous 事务失败时 Customer/relation/refresh token 全回滚；
- login 有 customer token 时保留 cur actor，promote/merge 方向正确；无 session 时 installToken 路径成功；
- refresh 写 iid、保留 anonymous；customer refresh token 与 iid 未绑定时补绑（bind 幂等），install refresh token 不触发绑定；
- Customer 业务缺 iid 在强制模式下拒绝；
- Scan find/update/delete/deep-research 跨 customer 返回 NOT_FOUND；
- Scan/Feedback/Support/Collection 新记录 install_id 等于 token iid，不受 `x-install-id` 伪造影响；
- V6 迁移后旧 installToken iid 能按新 PK 找到 Install；
- V6 遇到 install_id 全局重复或 id/install_id 交叉冲突时明确 abort，不能部分迁移；
- createInstall/createAnonymous 限流保持有效。

### 9.2 前端 shared

- 已有 customer session 时业务请求不调用 createInstall；
- 无 session 时严格按 createInstall→createAnonymous→业务顺序；
- install/customer SecureStore save 失败后当前内存凭证仍可用；
- 持久化后台重试有上限；
- refresh 网络/429/503 不清 session、不触发 `onLoggedOutByServer`；
- refresh 401000/401003 才重建匿名；
- login 有 session 用 customer token，无 session 用 installToken；
- login customer token 过期时先 refresh 再保留 actor 重试；
- installToken invalid 清 install 并最多重建一次；
- 普通业务 mutation 不自动重试。

### 9.3 前端 App

- 离线启动可进入 history/settings；
- 首次 scan 凭证创建失败后 loading 和防重复锁复位；
- 重试后可完成 scan；
- SecureStore 临时写失败时当前会话仍可完成一次网络业务；
- logout 清 CustomerSession、保留 InstallCredentials；
- 不新增任何 idempotencyKey/客户端业务主键。

### 9.4 联调

真实 persisted-query 链路至少验证：

```text
createInstall
→ createAnonymous（installToken）
→ customer token iid == install.id
→ Scan/Feedback/Support install_id == iid
→ refresh（installToken + refreshToken）
→ 新 token iid/ano 正确，既有 relation 不变
→ login promote/merge 保留匿名数据
→ logout 解绑、InstallCredentials 仍可复用
```

并使用伪造 `x-install-id` 验证数据库仍写 token iid。

---

## 10. 验收标准

- 新创建的 customer access token 全部有 iid；
- 新 Customer 业务记录 install_id 全部非空且来自 token；
- `core_auth_install.id` 与 API/JWT iid 一致；
- 一个 Install 同时只有一个有效 Customer 关系；
- 已有 customer session 的业务不依赖 install 创建成功；
- 临时 refresh 错误不改变 Customer 身份；
- createInstall/createAnonymous 可重试恢复，允许重复但不永久卡死；
- Scan 私有读写不存在跨 customer 越权；
- 服务端 `customer.json` 仍是线上 trusted-document 真相源；
- 未引入通用幂等表、客户端业务主键或透明业务 mutation 重试。
